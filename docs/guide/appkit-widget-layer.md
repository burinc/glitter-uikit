# The AppKit widget layer

`glitter-uikit.widget` maps hiccup tags to AppKit view constructors and prop
appliers; `glitter-uikit.appkit` drives it from the `IRender`/`IMemory`
protocols. This page covers the mechanics and the specific AppKit API traps
this port hit — each is a real, live-verified behavior this codebase
measured rather than assumed.

## The widget registry

```clojure
(def specs
  (atom {:window      (window-spec)
         :box         (box-spec)
         :button      (button-spec)
         :label       (label-spec)
         :entry       (entry-spec)
         :checkbutton (checkbutton-spec)
         :separator   (separator-spec)
         :frame       (frame-spec)
         :scrolled    (scrolled-spec)}))
```

Nine tags, and `:hbox`/`:vbox` are not among them. They are sugar, resolved by a
separate two-step mechanism rather than by their own specs: `aliases` maps both
onto `:box`, `normalize-tag` applies that map, and `spec-for` looks the
normalized tag up — while `with-orientation` injects the implied `:orientation`
prop so a bare `[:hbox …]` lays out horizontally without the caller saying so.

```clojure
(def ^:private aliases {:hbox :box :vbox :box})
(defn- normalize-tag [tag] (get aliases tag tag))
(defn- spec-for [tag] (@specs (normalize-tag tag)))
```

Each spec is `{:ctor (fn [props] view) :apply (fn [view props]) :container kw}`.
`:container` determines how children attach: `:box` (ordered
append/remove/reorder via `NSStackView`), `:window`/`:frame`/`:scrolled`
(single child), or `:none` (leaf). Concrete view mapping:

| Tag | AppKit view | Container |
|---|---|---|
| `:window` | `NSWindow` | Single child (pinned to content view) |
| `:box`, `:hbox`, `:vbox` | `NSStackView` | Ordered (horizontal or vertical) |
| `:button` | `NSButton` (push style) | Leaf |
| `:label` | `NSTextField` (label style) | Leaf |
| `:entry` | `NSTextField` (editable, bordered) | Leaf |
| `:checkbutton` | `NSButton` (switch style) | Leaf |
| `:separator` | `NSBox` separator (horizontal only) | Leaf |
| `:frame` | `NSBox` (titled) | Single child |
| `:scrolled` | `NSScrollView` | Single child (document view) |

`create!` builds a view end-to-end: construct, apply props, then run the
widget spec's `:apply` closure for signal lifecycle. Unlike `glitter.gtk`,
which carries a suppress set and re-connects signals on every render,
`glitter-uikit` has neither — see "Where AppKit is simpler" below.

## Where AppKit is simpler than GTK

### `insert-before` is single-branch

glitter.gtk's `insert-before` branches on whether a child is already
tracked in the parent's children list, because `gtk_box_insert_child_after`
asserts its child is unparented (`gtk_widget_get_parent(child) == NULL`) and
throws a `GTK-CRITICAL` + silently no-ops when called on an already-parented
child. The real GTK API for repositioning is `gtk_box_reorder_child_after`.

AppKit's `insertArrangedSubview:atIndex:` handles both uniformly — it
automatically MOVES an already-arranged subview if you pass one of the
stack's own children, so no GTK-style parented/unparented branch is needed.
Measured live:

```clojure
;; Starting state: [A B C]
;; Insert C at index 0
;; Result: [C A B]
;; Count: unchanged
```

So `glitter-uikit.widget/insert-child-after!` is a single code path serving
both a fresh insert and a keyed move — **but it is not identical to DOM's
`insertBefore`**. `insertBefore` takes a reference *node*; AppKit's call
takes an *index*, and that index is remove-then-insert internally,
interpreted against the **post-removal** array:

```clojure
;; Starting state: [A B C D]
;; Insert A at index 3 (A is already arranged, at index 0)
;; Result: [B C D A]   -- NOT "insert before whatever is now at index 3"
```

A fresh insert or a *backward* move (the child already sits at or after the
target sibling) is unaffected, because nothing before the sibling shifted.
A *forward* move (the child currently sits before the sibling) needs the
un-incremented sibling index rather than `(inc i)`, or the child lands one
slot too far right — a real bug this port shipped and fixed during final
review; see `insert-child-after!`'s docstring in `widget.clj` for the full
measured detail.

### No suppression set needed

glitter.widget carries a `suppressing` set because GTK's programmatic
setters (like `gtk_editable_set_text`, `gtk_check_button_set_active`)
synchronously re-emit their own signal, which would feed a re-render back
into app dispatch.

AppKit does NOT fire action or delegate callbacks for programmatic
`setState:`/`setStringValue:`/etc., so there is nothing to suppress. This
absence is intentional — do not add a suppression set. The property is
asserted live by `examples/glitter_uikit/keyed_smoke.clj`'s
`programmatic-active-does-not-dispatch` test, which sets a button's state
programmatically and verifies no action callback fires.

## Where AppKit needs more care than GTK

### `NSNotFound` is `NSIntegerMax`, not `-1`, and raises uncaught exceptions

`NSStackView`'s children are indexed via `arrangedSubviews` array access.
`indexOfObject:` returns `NSNotFound` (which is `NSIntegerMax` —
`9223372036854775807` — not `-1` or `NSUIntegerMax`) when the view is not
found. Feeding `NSNotFound + 1` to `insertArrangedSubview:atIndex:` raises
an uncatchable `NSException` that **aborts the process**. A Clojure `catch
:default` does not intercept it, because Objective-C exceptions do not
unwind into Scheme.

**Solution:** Every index read goes through `arranged-index`, which checks
bounds before use and throws a catchable error if the view is not found.
Verified live — an index access that misses silently would cascade into
worse bugs downstream, so this is load-bearing.

### `removeArrangedSubview:` leaves a plain subview

`NSStackView`'s `removeArrangedSubview:` removes a view from the stack's
arranged list but does NOT remove it from the view hierarchy — it becomes a
plain, unmounted subview still parented to the stack, consuming memory and
potentially interfering with sibling layout. A second call to
`removeFromSuperview` is needed to fully detach it.

**Solution:** `remove-child!` calls `removeFromSuperview` after
`removeArrangedSubview:` to complete the detach. Verified live against the
actual view hierarchy.

### Pointer-keyed registries and cleanup

`glitter-uikit.widget` maintains several registries (`:actions`,
`:changes`, `:alignments`) keyed by view pointer. Views passed to `remove!`
or replaced must be explicitly cleaned up via `forget-view!`, or the
registries will grow unbounded and accumulate stale handlers that, because
AppKit reuses freed addresses, can be inherited by new views landing on
dead views' addresses.

**Solution:** `remove-child!` calls `forget-view!` before removing the
view. If cleanup is skipped, a re-created view can silently inherit
handlers from a previous view that occupied the same memory.

## The four carried fixes

### 1. Event lifecycle ownership split

**Problem:** Upstream (glimmer-uikit) connects target/action once at mount
and lets handlers close over a reactive cell. Glitter calls
`IRender/set-event-handler` whenever handler *data* changes between
renders — not just on mount/unmount.

**Fix:** `glitter-uikit.appkit` owns signal lifecycle end to end. Two
writers of `setTarget:`/`setAction:` would fight if glitter-uikit tried to
adapt upstream's model, so `connect-signals!` was removed and event wiring
moved entirely to `glitter-uikit.appkit`'s `IRender/set-event-handler`.

### 2. `GlitterTarget` class registration avoids collision

**Problem:** Upstream registers an Objective-C class named
`"GlimmerTarget"` via `objc_allocateClassPair`. This is process-wide — if
glitter and glitter-uikit ran in the same process (e.g. in a mixed app),
the second caller would find the first's existing class and reuse it,
silently handing back glimmer's callbacks and handler registries.

**Fix:** Rename to `"GlitterTarget"`, making the two classes
distinguishable.

### 3. Prop filtering and falsy values

**Problem:** Upstream's `apply-props!` filtered on truthiness, treating
`false` the same as "absent". In AppKit, a checkbutton's `:active false` or
a widget's `:sensitive false` is a real, meaningful boolean value that must
reach the view, not get silently treated as "not set".

**Fix:** Filter on `some?` instead of truthiness, so explicit `false` still
reaches the view. Verified live against real AppKit state.

### 4. `replace-child!` captures position before removing

**Problem:** Upstream did `removeArrangedSubview:` then `addArrangedSubview:`,
which always inserts at the END of the stack. Replacing a non-final child
silently relocated it there, desyncing every caller's positional tracking.
This is the identical defect glitter fixed on the GTK side (see glitter's
NOTICE.md).

**Fix:** Capture the old child's index before removing it, then insert the
new child at that same position. With AppKit's auto-move semantics, a
single `insertArrangedSubview:atIndex:` call after the remove lands it
correctly.

---

For full provenance (which file ported from where, every documented
deviation), see `NOTICE.md`.
