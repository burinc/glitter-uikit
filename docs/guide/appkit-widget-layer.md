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

`create!` builds a view end-to-end: construct via the widget spec's
`:ctor`, then apply props via its `:apply` closure — `:apply` *is* the
prop applier, not a separate step that runs after props are applied.
`create!` does no signal wiring at all: unlike `glimmer-uikit`'s original,
event lifecycle belongs entirely to `glitter-uikit.appkit`, which calls
`IRender/set-event-handler` whenever handler *data* changes between
renders — not on every render, and not for `glitter.gtk` either, which
calls its equivalent under the same "data changed" condition — see "Where
AppKit is simpler" below.

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
`programmatic-active-does-not-dispatch` test, which sets a checkbutton's
state programmatically and verifies no action callback fires.

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
for `NSNotFound`/negative results and returns **`nil`** for "absent" —
it throws nothing. Every caller (`insert-child-after!`, `replace-child!`)
is written to handle that `nil`, falling through to a safe branch
(append) instead of ever reaching the arithmetic that would produce a
process-aborting index. Verified live — an index access that misses
silently would cascade into worse bugs downstream, so this is
load-bearing.

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
`:changes`, `:alignments`) keyed by view pointer. Views passed to
`remove-child!` or replaced must be explicitly cleaned up via
`forget-view!`, or the
registries will grow unbounded and accumulate stale handlers that, because
AppKit reuses freed addresses, can be inherited by new views landing on
dead views' addresses.

**Solution:** `remove-child!` calls `forget-view!` before removing the
view. If cleanup is skipped, a re-created view can silently inherit
handlers from a previous view that occupied the same memory.

## Model adaptations vs. real defect fixes

The port made three changes that are **deliberate model adaptations**, not
fixes for bugs in glimmer-uikit — each is required because glitter's
architecture differs from glimmer's own, not because the original code was
wrong for glimmer:

1. **Event lifecycle ownership split.** Upstream (glimmer-uikit) connects
   target/action once at mount and lets handlers close over a reactive
   cell — correct for glimmer's own Reagent-style model. glitter calls
   `IRender/set-event-handler` whenever handler *data* changes between
   renders, not just on mount/unmount, and two writers of
   `setTarget:`/`setAction:` would fight — so `connect-signals!` was
   removed rather than adapted, and event wiring moved entirely to
   `glitter-uikit.appkit`'s `IRender/set-event-handler`.
2. **`GlitterTarget` class registration.** Renamed from upstream's
   `"GlimmerTarget"` (registered process-wide via `objc_allocateClassPair`)
   so the two classes are distinguishable if glitter and glitter-uikit ever
   ran in the same process — not a bug fix, just a name collision avoided.
3. **Prop filtering on `some?`.** Upstream's `apply-props!` filters on
   truthiness; here it filters on `some?` so an explicit `false`
   (`:active false`, `:sensitive false`) still reaches the view. Needed
   because glitter's own `apply-props!` makes the same `some?` choice for
   the same reason (deviation #3 from Replicant) — matching the caller's
   contract, not repairing upstream.

Three further changes ARE real defect fixes — bugs that would misbehave
regardless of which reconciler drives them:

1. **`replace-child!` captures position before removing.** Upstream did
   `removeArrangedSubview:` then `addArrangedSubview:`, which always
   inserts at the END of the stack — replacing a non-final child silently
   relocated it there. This is the identical defect glitter fixed on the
   GTK side (see glitter's NOTICE.md). Fix: capture the old child's index
   before removing it, then insert the new child at that same position.
2. **`insert-child-after!` added, then its forward-move index fixed.**
   Absent upstream entirely — glimmer's reconciler never needs it, but
   glitter.core's `insert-before` does. The first version carried a real
   bug of its own: `insertArrangedSubview:atIndex:` is remove-then-insert
   internally with a POST-removal index, and the added code incremented
   the sibling's PRE-removal index unconditionally, so a forward keyed
   move (the moved child currently sits before its target sibling) landed
   one slot too far right. Fixed during this arc's final review — see
   "insert-before is single-branch" above and `insert-child-after!`'s
   docstring in `widget.clj`.
3. **`arranged-index` added, and every index read routed through it.**
   Upstream called `stack-index-of!` directly and did `(inc i)` on the
   result — a process abort waiting to happen whenever the sibling was
   absent (`NSNotFound` is `NSIntegerMax`, and feeding `NSNotFound + 1` to
   `insertArrangedSubview:atIndex:` aborts the process uncatchably). Fix:
   `arranged-index` returns `nil` for "absent" instead, and every caller
   handles it.
4. **`forget-view!` added, and `remove-child!` calls it.** Upstream's
   `actions`/`changes`/`alignments` registries were never cleaned — an
   unbounded leak, and a stale-handler hazard: because AppKit reuses freed
   addresses, a newly allocated view could land on a dead view's address
   and inherit its handler.

---

For full provenance (which file ported from where, every documented
deviation), see `NOTICE.md`.
