# Known v1 limitations

`NOTICE.md`'s "Known gaps" section is the authoritative list this page
expands on. Most of these are AppKit-native constraints (there is no
AppKit equivalent of a thing GTK has); a couple are cross-renderer
defaults that happen to differ from `glitter.gtk` and were only found
during this port's final whole-branch review. None are unnoticed rough
edges — each has a reason the fix was deferred rather than a reason it's
impossible.

## GTK4 must be installed, even though this renderer never touches it

`deps.edn` pulls in `glitter` via `:local/root "../glitter"`, and
glitter's own `deps.edn` declares GTK4/GLib/GObject/GIO under
`:jolt/native`. Jolt inherits a dependency's natives transitively and
hard-fails in `load-natives!` before any namespace loads if one is
missing — so a `glitter-uikit` app needs GTK4 installed even though it
renders exclusively through AppKit and `glitter-uikit.ffi` never calls a
GTK function. `deps.edn`'s own comment records this, and it isn't a
guess: an `:aliases`-scoped `:jolt/native` was verified live to be
silently ignored, so it cannot be scoped away from this side.

**The real fix** is extracting a natives-free `glitter-core` — the
toolkit-agnostic half of glitter (`core`, `protocols`, `hiccup`, `vdom`,
`alias`, `assert`, `asserts`, `errors`, `console-logger`, `env`,
`nexus/*`) — exactly the split upstream glimmer made at its own v0.1.0,
with glitter (GTK4) and glitter-uikit (AppKit) both depending on it.
Recorded in `README.md`'s Status section as deferred out of this arc
because it touches `glitter` and `glitter-gl`, not because it's hard to
see how to do.

## The no-op `IRender` methods: no CSS, no inline styling

AppKit has no CSS-class system and no inline-style property — there is
no counterpart to `gtk_widget_add_css_class` or DOM's
`element.style.color = ...`. `glitter-uikit.appkit`'s renderer still
implements all four `IRender` methods glitter.core calls for `:style`/
`:class` diffing, but each is a genuine no-op:

```clojure
(set-style [_ _el _k _v] nil)
(remove-style [_ _el _k] nil)
(add-class [_ _el _cn] nil)
(remove-class [_ _el _cn] nil)
```

Hiccup `:style`/`:class` props are still accepted and diffed by
`glitter.core` (which is what calls these methods at all) — they're just
inert once they arrive here. This is a deliberate v1 boundary, not an
unfinished method: building a real equivalent would mean designing a
per-widget-type style/attribute system AppKit has no native primitive
for, not wiring an existing one the way `glitter.gtk`'s `:class` support
wires GTK's own CSS provider.

`on-transition-end` is the same story for animation: `(f)` runs
immediately and synchronously rather than after a real transition, since
there is no animated mount/unmount support in v1 either.

### `remove-attribute` is a no-op for a different reason

Unlike the four methods above, `remove-attribute` *is* wired to real
AppKit setters — it just never reaches them, because of how
`apply-props!` filters its input:

```clojure
(remove-attribute [_ el a]
  (w/apply-props! (:tag @el) (ptr el) {(keyword a) nil})
  nil)
```

`w/apply-props!` filters on `some?`, not truthiness, and drops any key
whose value is `nil` before it ever reaches a spec's `:apply` closure —
that's what makes an explicit `false` (`:sensitive false`, `:active
false`) reach the view correctly while `nil` never does. So
`{(keyword a) nil}` reduces to `{}` and the underlying AppKit property is
left completely untouched. Setting an attribute to a *new* value always
works; removing it so it reverts to some type default does not — AppKit
has no generic "unset this property" call the way DOM's
`removeAttribute` does, so there's no default to revert *to* even if the
plumbing reached the widget.

## A bare `[:box …]` renders HORIZONTAL here, VERTICAL under `glitter.gtk`

`glitter-uikit.widget/box-spec` constructs its view via a bare
`(u/stack-new)` and only calls `stack-orientation!` when the caller's
props actually contain `:orientation`:

```clojure
(defn- box-spec []
  {:ctor  (fn [_] (u/stack-new))
   :apply (fn [w p]
            ...
            (when (contains? p :orientation)
              (u/stack-orientation! w (if (= :vertical (:orientation p))
                                        u/ORIENTATION-VERTICAL
                                        u/ORIENTATION-HORIZONTAL)))
            ...)
   :container :box})
```

`NSStackView`'s own un-set orientation is horizontal — measured directly
against a headlessly-constructed view:

```
(u/stack-orientation (w/create! :box {}))  ;=> 0 (ORIENTATION-HORIZONTAL)
```

`glitter.gtk/box-spec`, by contrast, constructs explicitly *vertical*:

```clojure
(defn- box-spec []
  {:ctor (fn [p]
           ;; construct vertical by default; the real orientation is set in
           ;; :apply, by which point the box exists and GtkOrientation is
           ;; registered (the box installs the orientation property).
           (g/gtk-box-new 1 (or (:spacing p) 0)))
   ...})
```

— `1` is `GTK_ORIENTATION_VERTICAL`, and the comment states the choice is
deliberate.

**The consequence:** a glitter view written for GTK using a bare
`[:box ...]` (rather than `:hbox`/`:vbox`, which both inject an explicit
`:orientation` via `with-orientation` regardless of renderer) renders
rotated 90° under this renderer — silently, no error, no warning.
`:hbox`/`:vbox` are unaffected and portable either way; this project's
own `counter.clj` demo notes in its docstring that it deliberately uses
`:vbox`/`:hbox` rather than mirroring glitter's own `examples/glitter/counter.clj`
bare `:box` usage, for exactly this reason.

**Why left as-is:** matching `glitter.gtk`'s default here would itself be
a deviation from the `glimmer-uikit` source this file was ported from,
and would need its own review — not a free fix. Recorded as a
final-review finding in `NOTICE.md`'s Known gaps, not something this
port introduced and missed.

## `:halign`/`:valign` are stack-wide, not per-child

`NSStackView.alignment` is a property of the *stack*, not of an
individual arranged subview — there is no per-child alignment API to
bind to. `glitter-uikit.widget` records each child's `:halign`/`:valign`
in a `view -> [halign valign]` atom at prop-apply time, then derives the
*parent* stack's alignment from whichever child was appended or inserted
most recently:

```clojure
(defn- maybe-align!
  [parent child]
  (when-let [[halign valign] (get @alignments child)]
    (u/stack-alignment! parent (->stack-alignment halign valign (u/stack-orientation parent)))))
```

`append-child!`/`insert-child-after!`/`replace-child!` all call this
after placing a child. So the last child with a `:halign`/`:valign` prop
in a given stack wins for the *whole* stack — an earlier sibling asking
for a different alignment is silently overridden. Every bundled example
in this repo only ever sets alignment on the sibling that actually needs
it distinguished (e.g. `todo.clj`'s `:valign :center` on the checkbutton
and label of each task row, where all the row's children want the same
alignment anyway), so this has not caused a visible bug here — but it's
a real, unfixed constraint for any layout that wants two differently-
aligned children in the same stack.

## A vertical `:separator` renders as nothing

```clojure
(defn separator-new
  "An NSBox separator — a horizontal line. (AppKit has no vertical separator
  primitive; a :vertical :separator renders as nothing in v1.)"
  ...)
```

`NSBox`'s `boxType` separator style only draws a horizontal rule; AppKit
ships no vertical equivalent widget to fall back to. `separator-spec`'s
`:apply` is itself a no-op (`(fn [_ _] nil)`), so there is currently no
prop that would even let a caller ask for a vertical orientation — the
gap is in the constructor, not a missing branch in `:apply`.

## `:window`'s `:width`/`:height` are read once and never re-applied

```clojure
(defn- window-spec []
  {:ctor    (fn [p] (u/window-new (:title p) (or (:width p) 400) (or (:height p) 300)))
   :apply   (fn [w p]
              (when (:title p) (u/window-title! w (:title p)))
              (when (false? (:visible p)) (u/window-hide! w)))
   :container :window})
```

`:width`/`:height` are only read in `:ctor`; `:apply` never touches
them, so a re-render that changes either prop has no effect on an
already-created window. **Inert in practice, though**:
`glitter-uikit.app/run` builds the root `NSWindow` itself and
`glitter-uikit.appkit/mount!` wraps that pointer directly into the root
element atom —

```clojure
(let [root-el (atom {:tag :window :view window :children [] :handlers {}})
      ...])
```

— so `create!` (and therefore `window-spec`'s `:ctor`) is never invoked
for `:window` in a running app at all. The gap exists in the spec table
for completeness and for any future caller that constructs a `:window`
node directly, not on any path this project's own examples or smokes
exercise.

## `signal-name`/`signal-value-fn`/`retain-callable!`/`release-callable!` are deliberately absent

These four symbols exist in `glitter.widget` for two GTK-specific
reasons that have no AppKit counterpart, and their absence here is a
design decision recorded in `NOTICE.md`, not a dropped port:

- GTK connects a *new* foreign-callable per widget per signal, so it
  needs retain/release bookkeeping to keep each one from being collected
  while still connected — hence `retain-callable!`/`release-callable!`.
- GTK's connect/disconnect API is name-keyed
  (`g_signal_connect_data`/`g_signal_handler_disconnect`), so it needs
  the raw signal-name string — hence `signal-name`/`signal-value-fn`.

AppKit uses a target/action model instead (see
[`appkit-widget-layer.md`](appkit-widget-layer.md) for the pointer-keyed
registry mechanics this replaces it with): a handful of
permanently-retained `defonce` callbacks —

```clojure
(defonce ^:private fire-cb ...)
(defonce ^:private change-cb ...)
(defonce ^:private quit-cb ...)
(defonce ^:private terminate-cb ...)
```

— shared across every control, plus a pointer-keyed registry
(`glitter-uikit.widget/actions`/`changes`) that `glitter-uikit.appkit`
owns end to end. There is no per-widget-per-signal callable to retain,
no connect API to release from, and no name string to look up:
`glitter-uikit.appkit` selects handlers with a static `action-events`
set (`#{:click :toggled :activate}`) instead. A later reader comparing
the design spec's Architecture section (which lists these four as part
of the widget layer's surface) against this code should read this as an
intentional absence, not something to "restore."

## A Pango `:markup` attribute value containing a quote crashes

`markup->attributed`'s `parse-attrs` extracts `k='v'` pairs with a
regex that stops at the next literal quote character:

```clojure
(defn- parse-attrs [tag]
  (into {}
        (for [[_ k v] (re-seq #"([a-zA-Z_]+)=['\"]([^'\"]*)['\"]" tag)]
          [(keyword k) v])))
```

But hiccup escapes an embedded quote inside an attribute value to
`&quot;` rather than emitting a literal `"` — confirmed directly by this
project's own test suite:

```clojure
(is (= "<span foreground=\"a&quot;b\">x</span>"
       (w/markup [:span {:foreground "a\"b"} "x"])))
```

So `[:span {:foreground "a\"b"} "x"]` reaches `color-hex` as the literal
string `"a&quot;b"`, not `"a\"b"`. `color-hex` assumes a `#rrggbb`/`#rgb`
hex string and throws as soon as it hits a non-hex character:

```clojure
(defn- hex-digit [c]
  (let [n (int c)]
    (cond (<= 48 n 57) (- n 48)
          (<= 97 n 102) (- n 87)
          (<= 65 n 70) (- n 55)
          :else (throw (ex-info (str "glitter-uikit: bad hex digit " c) {})))))
```

— `&` is not a hex digit, so this throws `bad hex digit &`. Present
upstream and carried forward deliberately rather than fixed opportunistically:
the real fix is decoding entities *before* `parse-attrs` runs, which is
its own scoped task, not a one-line patch to `color-hex`.

## Sizing: `:width-chars` is not a width, `:width-request` is

`:width-chars` / `:max-width-chars` route to `setPreferredMaxLayoutWidth:`,
which is a text-**wrapping** hint. It does not stop a control from being
compressed. The measured consequences were not subtle: an `:entry` beside a
label in a stack was squeezed to **zero width** and the field vanished
entirely, and where it survived, a ten-character date rendered as `26.08.20`.

Four plausible-looking routes were each tried against a live window and each
did nothing:

| attempt | result |
|---|---|
| `:vexpand false` on the container | no effect |
| `:hexpand true` on the field | no effect |
| `:hexpand true` on the row | no effect |
| `:halign :fill` → `NSLayoutAttributeWidth` | no effect |

What works is `:width-request`, which installs a real `NSLayoutConstraint`
(`width == constant`) via `ffi.clj`'s `set-width!`. Use it whenever a control
must be a given size. It is applied once per view and guarded, because
constraints are cumulative — re-adding one on every re-render would stack
conflicting constraints on the same view.

`:halign :fill` is still mapped, since `NSLayoutAttributeWidth` is the correct
attribute for a vertical stack, but it did **not** fix the narrow-row case and
whatever governs that is unresolved. Treat it as available-but-unproven.

## Props accepted and ignored on the post-v1 controls

These exist so a glitter view ports across renderers unchanged, but AppKit has
no counterpart for them. They are listed rather than silently dropped:

| tag | ignored props | why |
|---|---|---|
| `:scale` | `:step`, `:digits`, `:draw-value` | `NSSlider` is continuous, draws no value label, and quantises only through tick marks. Use `:ticks` / `:ticks-only` instead. |
| `:progress-bar` | `:show-text`, `:text` | `NSProgressIndicator` draws no text. Pair it with a `:label`. |
| `:spin-button` | `:digits` | An `NSStepper` is only the arrows — unlike `GtkSpinButton` it has no built-in text field, so pair it with a `:label` or `:entry`. |
| `:password-entry` | `:show-peek-icon` | No AppKit counterpart. |
| `:search-entry` | `:search-delay` | `NSSearchField` sends its action as you type. |
| `:image` | `:pixel-size` | Size it with `:width-request` or the surrounding layout. |

## `:switch` needs macOS 10.15, unlike every other tag

`NSSwitch` is `API_AVAILABLE(macos(10.15))` — verified in the SDK header, not
assumed. Every other tag works on the project's 10.13 floor. The spec's `:ctor`
throws a named error when the class is absent rather than letting a null class
crash inside `objc_msgSend` with nothing pointing at the cause, so an older
system gets a clear message about that one tag instead of an opaque abort.

## Two gaps that are about verification, not behavior

The rest of this page is about what the code actually does. These two
are about how confidently that's known.

### The CI workflow has never been executed

`.github/workflows/tests.yml` is `on: [workflow_dispatch]` only — no
`push`/`pull_request` trigger — because the project has no GitHub
Actions credit budget and nothing should run automatically. That means
no run of this workflow has ever completed, and its own comments flag a
specific, plausible failure point rather than claiming a clean bill of
health: the job checks out this repo, then tries to check out `glitter`
(needed for `deps.edn`'s `:local/root "../glitter"`) using the job's
default `GITHUB_TOKEN`, which GitHub scopes to the triggering repository
only. If `burinc/glitter` is private, that second checkout has no
credentials to succeed with, and the workflow file says so directly:

> No GitHub Actions run has been performed for this project ... so this
> has NOT been verified on a real runner. The first manual run may fail
> at this exact step; that is a disclosed gap, not a surprise.

Nothing downstream of that checkout — installing jolt, installing GTK4,
running `jolt -M:test` — has ever executed in that environment either,
since the workflow would never get that far if the checkout itself
fails.

### The thunk-queue drain fix has no adversarial-concurrency test

`glitter-uikit.app`'s scheduler (see
[`app-loop-and-threading.md`](app-loop-and-threading.md) for the full
mechanics) fixed a real dropped-callback bug: the
original capture-and-clear was a non-atomic `(let [jobs @queue] (reset!
queue []) ...)`, so a worker thread's `swap!` landing between the deref
and the reset was silently lost. The fix replaced it with a single
CAS-based operation:

```clojure
(let [[jobs _] (swap-vals! queue empty)]
  (run! (fn [f] (try (f) (catch :default e ...))) jobs))
```

Two of this project's live smokes exercise this path under *real*
cross-thread concurrency — `main_thread_smoke.clj` posts from inside a
`future`, and `repl_live_smoke.clj` posts from a second, un-joined
`future` while the main AppKit pump is running — and both pass. Neither,
though, drives genuine *contention* on the CAS itself: each has exactly
one worker thread posting once, not several threads racing to post at
the same instant the main loop's `perform` callback is mid-drain, which
is the specific race `swap-vals!` was chosen to close. Testing that
properly needs multiple threads posting concurrently against a live
`CFRunLoop` actually pumping — not something the headless unit suite can
set up at all (there's no run loop in `jolt -M:test`), and not something
any current smoke was written to do either.
