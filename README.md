# glitter-uikit

An AppKit (native macOS) renderer for
[glitter](https://github.com/jlt-commons/glitter) — a Replicant-style Clojure UI
library on [Jolt](https://github.com/jolt-lang/jolt). Ported from
[glimmer-uikit](https://github.com/jolt-lang/glimmer-uikit), which does the
same for [glimmer](https://github.com/jolt-lang/glimmer) (glitter's
Reagent-style sibling). A data-driven registry maps hiccup tags to AppKit
views, and glitter's reconciler drives prop/event wiring through the
`IRender`/`IMemory` protocols.

**Documentation:** <https://jlt-commons.github.io/glitter-uikit/>

[<img src="docs/demos/counter.png" width="480">](docs/guide/examples.md)

*The counter demo, running as a real AppKit window. More screenshots
(and what each one demonstrates about the model) in the
[examples gallery](docs/guide/examples.md).*

## Requirements

**macOS 10.13+** with **Xcode Command Line Tools** (provides `clang`,
`ld`, and frameworks).

**GTK4 is required** — this is a known limitation inherited from
glitter, and it is real: this renderer never calls a single GTK function,
but jolt inherits a dependency's `:jolt/native` declarations transitively
and hard-fails in `load-natives!` before any namespace loads when one is
missing. glitter's own `deps.edn` declares GTK4 and GLib under
`:jolt/native`, so a glitter-uikit app needs GTK4 installed anyway.

Install GTK4 via Homebrew:

```sh
brew install gtk4 glib
```

The limitation cannot be scoped away from this side — verified live that an
`:aliases`-scoped `:jolt/native` is silently ignored. The tracked fix is to
extract a natives-free `glitter-core` — the toolkit-agnostic half of
glitter (`core`, `protocols`, `hiccup`, `vdom`, `alias`, `assert`,
`asserts`, `errors`, `console-logger`, `env`, `nexus/*`) — the same split
[upstream glimmer made at its own v0.1.0](https://github.com/jolt-lang/glimmer/tree/v0.1.0),
with glitter (GTK4) and glitter-uikit (AppKit) both depending on it. See
the Status section below for where that stands.

## Quick start

```clojure
(require '[glitter-uikit.app :as app]
         '[glitter-uikit.appkit :as appkit]
         '[glitter.core :as core])

(defonce state (atom {:count 0}))

(defn view [{:keys [count]}]
  [:vbox {:spacing 12}
   [:label {:label (str "Count: " count)}]
   [:hbox {:spacing 8}
    [:button {:label "− 1" :on {:click [[:action/dec]]}}]
    [:button {:label "+ 1" :on {:click [[:action/inc]]}}]
    [:button {:label "reset" :on {:click [[:action/reset]]}}]]])

(defn execute-actions [_event actions]
  (doseq [[kind] actions]
    (case kind
      :action/inc (swap! state update :count inc)
      :action/dec (swap! state update :count dec)
      :action/reset (swap! state assoc :count 0)
      nil)))

(core/set-dispatch! execute-actions)

(defn -main [& _]
  (app/run (fn [window] (appkit/mount! window view state))
           :title "glitter-uikit counter" :width 320 :height 160))
```

Run via `jolt -M:counter`.

**In CI, invoke the `-M:<alias>` form, not the task form** — `jolt -M:test`,
`jolt -M:counter`, and so on — same non-propagating-exit-code caveat
[glitter's own README](https://github.com/jlt-commons/glitter#quick-start)
documents (verified against jolt v0.6.3).

## Running

```sh
jolt -M:test                        # unit suite (headless; prints its own totals)
jolt -M:counter                     # interactive counter
jolt -M:todo                        # interactive task board
jolt -M:smoke                       # basic smoke test (live AppKit loop)
jolt -M:keyed-smoke                 # keyed reorder (live AppKit order)
jolt -M:replace-child-smoke         # child replacement (position preserved)
jolt -M:insert-before-smoke         # child insertion (live AppKit order)
jolt -M:handler-cleanup-smoke       # event handler lifecycle
jolt -M:main-thread-smoke           # off-thread state changes render on-thread
jolt -M:reactivity-smoke            # live state-atom reactivity
jolt -M:repl-live-smoke             # nREPL live editing
```

Or via `bb`:

```sh
bb test              # jolt -M:test
bb counter           # interactive counter
bb todo              # interactive task board
bb smoke             # basic smoke test
bb keyed-smoke       # keyed reorder
bb replace-child-smoke  # child replacement
bb insert-before-smoke  # child insertion
bb handler-cleanup-smoke  # event handler lifecycle
bb main-thread-smoke    # off-thread state changes
bb reactivity-smoke     # live state-atom reactivity
bb repl-live-smoke      # nREPL live editing
```

## Dependency modes

`deps.edn` declares glitter as a pinned git coordinate
(`io.github.jlt-commons/glitter` at a fixed `:git/sha`). jolt fetches and
builds against that exact commit, so a fresh clone of this repo builds
with no other setup — nothing needs to sit next to it on disk. This is
the default, and what CI and every command in Quick start/Running above
uses unless you say otherwise:

```sh
jolt -M:counter          # builds against the pinned glitter sha
```

For co-developing this renderer against an unreleased glitter change, a
`:dev` alias overrides the pin back to a sibling checkout at `../glitter`.
Combine it with any runnable alias:

```sh
jolt -M:dev:counter      # builds against ../glitter instead of the pin
```

`:dev` only helps if `../glitter` actually exists next to this checkout
— it is not something a first-time user needs or has.

## Hiccup reference

glitter-uikit speaks a hiccup dialect where **events are data, never
closures**. A button's click handler is a vector of action tuples to
dispatch, not a function:

```clojure
;; ✓ data-driven event, glitter-style
[:button {:label "Click me" :on {:click [[:action/do-something arg]]}}]

;; ✗ closure-based event, not supported
[:button {:label "Click me" :on {:click (fn [] ...)}}]
```

**Event key change: `:on-click` → `:on {:click ...}`.**  Adapt from
glimmer-uikit's docs by substituting `:on {:click ...}` for every instance
of `:on-click` and similar `:on-*` keys. All events go in a single `:on`
map.

**`:class` and `:style` are inert.** AppKit has no CSS — these keys are
accepted to keep hiccup trees portable, but they do nothing. There is no
AppKit equivalent of GTK's `gtk_widget_add_css_class`.

See `CONTRIBUTING.md` for the full event-dispatch architecture and `docs/guide/`
for widget-layer mechanics.

## Architecture

```mermaid
flowchart TD
    core["glitter.core<br/>the reconciler, toolkit-agnostic"]
    appkit["glitter-uikit.appkit<br/>IRender + IMemory"]
    widget["glitter-uikit.widget<br/>hiccup → NSView · prop appliers · containers"]
    ffi["glitter-uikit.ffi<br/>Objective-C runtime · AppKit · Foundation"]
    app["glitter-uikit.app<br/>NSApplication loop · cross-thread marshalling"]

    core -- "calls IRender/IMemory" --> appkit
    appkit -- "widget spec registry" --> widget
    widget -- "objc_msgSend" --> ffi
    app -- "owns the run loop, mounts into a window" --> appkit
    app --> ffi

    classDef ext fill:#2b2f3a,stroke:#8e939d,color:#e6e9ef;
    class core ext;
```

Four namespaces:

- `glitter-uikit.ffi` — AppKit/Foundation FFI layer via `jolt.ffi`.
- `glitter-uikit.widget` — hiccup tag → NSView mapping, prop appliers,
  container strategies (`:box` ordered, `:window`/`:frame`/`:scrolled`
  single-child), event handler lifecycle.
- `glitter-uikit.appkit` — `IRender`/`IMemory` protocols, glitter.core
  integration.
- `glitter-uikit.app` — macOS event loop and app lifecycle (`NSApplication`).

See `docs/guide/index.md` for the full breakdown.

## Documentation

- **[`docs/guide/index.md`](docs/guide/index.md)** — the full guide.
- **[`CONTRIBUTING.md`](CONTRIBUTING.md)** — conventions, gotchas, build
  commands and the file map. Read it before changing the widget or renderer
  layers.
- Design spec and implementation plan are kept in a private planning store and
  are not part of this repository.

## Licence

MIT for this project's own code — see [`LICENSE`](LICENSE).

**Read [`NOTICE.md`](NOTICE.md) before reusing any of it.** Parts of
`src/glitter_uikit/` are forked from
[glimmer-uikit](https://github.com/jolt-lang/glimmer-uikit), which ships no
LICENSE file at all — absent a license, default copyright reserves all rights,
so no grant has been made for that material. `NOTICE.md` records the
file-by-file provenance, pinned to the exact ref and SHA so the claim is
falsifiable.

## Status

Ported from glimmer-uikit v0.1.0 (2026-08-20 arc) — see `NOTICE.md` for the
full verbatim/adapted/new breakdown, including the defects fixed during the
port (carried from upstream, plus one the port's own new code introduced)
and the AppKit behaviors measured rather than assumed.

**Known limitation — GTK4 is required.** glitter's `deps.edn` declares GTK4 and
GLib under `:jolt/native`, and jolt inherits a dependency's natives transitively
and hard-fails before any namespace loads if one is missing. So a glitter-uikit
app needs GTK4 installed even though it renders through AppKit and never calls a
GTK function. Verified that an `:aliases`-scoped `:jolt/native` is ignored, so
there is no way to scope it away from this side. **The fix is to extract a
natives-free `glitter-core`** — the toolkit-agnostic half of glitter
(`core`, `protocols`, `hiccup`, `vdom`, `alias`, `assert`, `asserts`, `errors`,
`console-logger`, `env`, `nexus/*`) — exactly the split upstream glimmer made at
its own v0.1.0, with glitter (GTK4) and glitter-uikit (AppKit) both depending on
it. Deferred out of this arc because it touches glitter and glitter-gl.
