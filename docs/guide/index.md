# glitter-uikit — Guide

## Why this exists

`glitter-uikit` is an AppKit (native macOS) renderer for
[glitter](https://github.com/burinc/glitter). Its upstream source,
[glimmer-uikit](https://github.com/jolt-lang/glimmer-uikit), applies
Reagent's model (ratoms, automatic dependency tracking, component-local
state) to AppKit; this project deliberately applies a different model —
[Replicant](https://github.com/cjohansen/replicant)'s single
application-state atom, pure `state -> hiccup` view function, top-down
re-render, and data-driven action-dispatch handlers — the same model
`glitter` itself applies to GTK4. This guide covers how that model was
adapted to a native, retained-mode, Objective-C toolkit that has no DOM
underneath it.

`glitter-uikit` is a **whole alternative renderer**, not a widget added
to an existing registry: it implements `glitter.protocols/IRender` and
`IMemory` for real AppKit views, exactly as `glitter.gtk` implements the
same two protocols for GTK4. The two are siblings, chosen at `mount!`'s
call site by which library an app requires — `glitter.core`'s reconciler
itself knows nothing about either toolkit.

## What glitter-uikit is

A `.clj` (Jolt/Chez Scheme host, not JVM) library:

```clojure
(require '[glitter-uikit.app :as app]
         '[glitter-uikit.appkit :as appkit]
         '[glitter.core :as core])

(defonce state (atom {:count 0}))

(defn view [{:keys [count]}]
  [:vbox {:spacing 12}
   [:label {:label (str "Count: " count)}]
   [:hbox {:spacing 8}
    [:button {:label "+ 1" :on {:click [[:action/inc]]}}]]])

(defn execute-actions [_event actions]
  (doseq [[kind] actions]
    (case kind
      :action/inc (swap! state update :count inc)
      nil)))

(core/set-dispatch! execute-actions)

(defn -main [& _]
  (app/run (fn [window] (appkit/mount! window view state))))
```

Every subsequent `swap!` on `state` fires `mount!`'s watcher, which
routes the re-render through `glitter-uikit.app/on-gui` (marshalling
onto the AppKit main thread when the `swap!` came from elsewhere) and
calls `view` again; `glitter.core`'s reconciler diffs the new hiccup
against the previous vdom and issues the minimal set of `IRender`/
`IMemory` calls needed to bring the live AppKit view tree in sync.

## Pages

### Orientation
- [`examples.md`](examples.md) — the catalogue of all sixteen runnable
  namespaces: the eight interactive demos (`counter`, `widgets`,
  `temperature`, `flights`, `timer`, `crud`, `circles`, `todo`) with
  screenshots and what each shows about the model, plus an index of the
  eight live-AppKit smokes and the one property each one pins. Also why
  the screenshots are stills rather than animations for now.
- [`architecture.md`](architecture.md) — why `glitter-uikit` is a whole
  alternative renderer rather than a registered widget, the single
  `reify` implementing both `IRender` and `IMemory` (and the
  `:extend-via-metadata`-is-broken-under-Jolt finding behind that
  choice), the el atom that tracks a live view rather than handing the
  reconciler a raw pointer, `mount!`'s wiring, the data-driven event
  model this port adapted from glimmer-uikit's closure-based one, and
  the `:ctor`-always-gets-`{}` finding that shapes every widget spec.
- [`porting-and-attribution.md`](porting-and-attribution.md) — the two
  sourcing buckets (ported from glimmer-uikit / ported from glitter) and
  every documented deviation, model adaptation, and defect fix in the
  port — `NOTICE.md` is the authoritative ledger this page summarizes.

### AppKit integration
- [`appkit-widget-layer.md`](appkit-widget-layer.md) — the widget
  mapping layer, why it's shaped as it is, where AppKit is genuinely
  simpler than GTK (single-branch `insert-before`, no suppression set
  needed) and where it needs more care (`NSNotFound` raising uncaught,
  process-aborting exceptions; pointer-keyed registry cleanup) — kept
  deliberately separate from which changes are model adaptations versus
  which are fixes for real defects in glimmer-uikit v0.1.0.
- [`app-loop-and-threading.md`](app-loop-and-threading.md) — the
  `NSApplication` bootstrap, `on-gui`'s three-way thread branch, the
  single long-lived `CFRunLoopSource` + thunk-queue marshaller (and the
  atomic-drain fix that closed a dropped-callback race), and the
  flag-ordering defect `main_thread_smoke.clj` caught live.

### Verify
- [`testing-and-tasks.md`](testing-and-tasks.md) — the headless unit
  suite and what each namespace in it covers, the live-AppKit smokes and
  what each one actually pins (reading the real AppKit tree, never this
  renderer's own bookkeeping), and the full `jolt`/`bb` task surface
  that runs them.
- [`limitations.md`](limitations.md) — every known v1 gap and the
  reasoning behind leaving each one unfixed for now, including the two
  gaps that are about how confidently something is known rather than
  what the code does.

## See also

- [glimmer-uikit](https://github.com/jolt-lang/glimmer-uikit) — the
  Reagent-style sibling this project forked its AppKit FFI/widget layer
  from.
- [glitter](https://github.com/burinc/glitter) — the source of
  `glitter.core`'s reconciler and the GTK4 renderer this project mirrors
  the structure of.
- `README.md` (repo root) — feature overview, quick start, requirements,
  and the full `jolt`/`bb` command reference.
- `AGENTS.md` (repo root) — canonical agent context: conventions,
  gotchas, file map, and scope.
- `NOTICE.md` (repo root) — the authoritative file-by-file attribution
  ledger and the Known gaps list `limitations.md` expands on.
