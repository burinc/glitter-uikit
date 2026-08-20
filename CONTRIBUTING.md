# Agent Instructions — glitter-uikit

This file is the canonical, tool-agnostic agent context for this project.
`CLAUDE.md` imports it.

## What this is

An AppKit (native macOS) renderer for
[glitter](https://github.com/burinc/glitter) (a Replicant-style Clojure UI
library on [Jolt](https://github.com/jolt-lang/jolt)), ported from
[glimmer-uikit](https://github.com/jolt-lang/glimmer-uikit) — the same
renderer for [glimmer](https://github.com/jolt-lang/glimmer), glitter's
Reagent-style sibling. A data-driven registry maps hiccup tags to AppKit
views (`NSWindow`, `NSStackView`, `NSButton`, `NSTextField`, etc.) and
prop/event wiring is driven through glitter's `IRender`/`IMemory`
protocols.

Repo: `git@github.com:burinc/glitter-uikit.git`. Own copyright (2026,
Burin Choomnuan) — vendors ported code under `NOTICE.md`'s file-by-file
attribution, not a fork of glimmer-uikit or jolt-lang.

## Build & run

Requirements: [Jolt](https://github.com/jolt-lang/jolt), macOS 10.13+
with Xcode Command Line Tools, and GTK4 + GLib (via `../glitter`'s
`deps.edn` — see README's Status section for the limitation and its fix).

```
jolt -M:test                        # 19 tests, 72 assertions
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

**In CI, invoke the `-M:<alias>` form, not the task form** —
`jolt -M:test`, `jolt -M:counter`, and so on — same non-propagating-exit-code
caveat glitter itself documents (verified against jolt v0.6.3). `bb.edn`'s
tasks already do this: `bb test`, `bb counter`, `bb smoke`, etc. are CI-safe.
Run `bb info` for the full grouped task list.

**Quality tooling** (`bb lint`/`lint:strict`, `bb lsp:format`/`format-check`,
`bb lsp:clean-ns`/`clean-ns-check`, `bb lsp:diagnostics`/`check`/`fix`, `bb
verify`, `bb hooks:install`/`:install:full`/`:uninstall`, `bb nrepl`): needs
`clj-kondo` and `clojure-lsp` on `PATH`. `.clj-kondo/hooks/jolt_ffi.clj`
(adapted from glitter-gl's own, which in turn credits glitter and b12n-rljlt —
see `docs/guide/porting-and-attribution.md`) rewrites `jolt.ffi/defcfn` so
clj-kondo/clojure-lsp can see through the FFI macro. `bb verify` is a manual
pre-commit gate: lint (report only) + `jolt -M:test` (must pass) — it does NOT
check formatting. The installed git hook (`bb hooks:install`) is a separate,
automatic gate that runs on every `git commit`: lint errors-only +
`clojure-lsp format --dry` + `clojure-lsp clean-ns --dry` (FAST), or the same
plus `jolt -M:test` (`hooks:install:full`). Running `bb verify` clean does not
guarantee the git hook will also pass — format drift is only caught by the
hook, not by `verify`.

## Architecture

```
glitter-uikit.ffi (AppKit/Foundation FFI)
    │
    ▼
glitter-uikit.widget (hiccup -> NSView, prop appliers, containers)
    │
    ▼
glitter-uikit.appkit (IRender/IMemory + glitter.core integration)
    │
    ▼
glitter-uikit.app (NSApplication loop + app lifecycle)
```

- `glitter-uikit.ffi` contains all Objective-C FFI bindings and low-level
  AppKit wrappers.
- `glitter-uikit.widget` maps tags (`:window`, `:box`/`:hbox`/`:vbox`,
  `:button`, `:label`, `:entry`, `:checkbutton`, `:separator`, `:frame`,
  `:scrolled`) to their AppKit views and implements the prop/container
  lifecycle. Unlike glitter.gtk, this layer carries NO event-signal
  wrapping (glitter.core calls `glitter-uikit.appkit`'s `IRender`
  directly) and NO suppression set (AppKit setters are silent).
- `glitter-uikit.appkit` implements `IRender` and `IMemory`, driving
  widget construction/update through the widget spec registry and routing
  events through glitter's dispatch.
- `glitter-uikit.app` handles the `NSApplication` event loop and window
  lifecycle.

Full topic breakdown: `docs/guide/index.md`.

## File map

| File | Role |
|---|---|
| `src/glitter_uikit/ffi.clj` | AppKit/Foundation FFI bindings (`jolt.ffi/defcfn` forms) |
| `src/glitter_uikit/widget.clj` | Hiccup tag → NSView mapping; widget spec registry; prop appliers; container strategies (ordered box vs. single-child window/frame/scrolled) |
| `src/glitter_uikit/appkit.clj` | `IRender` protocol (create/update/remove/insert-before); `IMemory` protocol (ref management); glitter.core integration |
| `src/glitter_uikit/app.clj` | `NSApplication` event loop and `-main`; `run` function for mounting hiccup against a window |
| `examples/glitter_uikit/counter.clj` | Counter demo — state atom + view function + action dispatch |
| `examples/glitter_uikit/todo.clj` | Task-board demo — derived counts, entry/checkbox list |
| `examples/glitter_uikit/smoke.clj` | Basic smoke: mount a tree and run the loop without exception |
| `examples/glitter_uikit/keyed_smoke.clj` | Keyed reorder — verifies live AppKit widget order via `-firstChild`/`-nextSibling` |
| `examples/glitter_uikit/replace_child_smoke.clj` | Replaced child stays at its position, not the end |
| `examples/glitter_uikit/insert_before_smoke.clj` | Child insertion — live AppKit order |
| `examples/glitter_uikit/handler_cleanup_smoke.clj` | Event handler lifecycle — wiring/unwiring on mount/update/unmount |
| `examples/glitter_uikit/main_thread_smoke.clj` | Off-thread state changes render on the NSApplication main thread |
| `examples/glitter_uikit/reactivity_smoke.clj` | Live state-atom reactivity — view recomputes on atom changes |
| `examples/glitter_uikit/repl_live_smoke.clj` | nREPL-driven live editing — redefine functions and hot-reload |
| `test/glitter_uikit/*_test.clj` | Unit suite mirroring glimmer-uikit's structure, covering the four source namespaces plus helper utilities |
| `test/glitter_uikit/test_runner.clj` | Test-suite entry point (`jolt -M:test`) |

Full provenance (which file ported from where, every documented deviation):
`NOTICE.md`.

## Conventions & gotchas (do not regress these)

1. **Events are DATA, never closures.** All handlers are vectors of action
   tuples dispatched through a single global `glitter.core/set-dispatch!`
   function, never lambda closures. Contrast glimmer's Reagent-style
   component-local closures: glitter uses Replicant's state-atom + pure-view
   + data-driven-dispatch model. Example: `[:button {:label "Click"
   :on {:click [[:action/do-something arg]]}}]`, NOT `[:button {:label
   "Click" :on {:click (fn [] ...)}}]`. glitter.hiccup's `hiccup?` check
   rejects a non-keyword tag and renders it as a stringified literal object
   instead of throwing — the same silent bug class this codebase hit
   historically (see glitter's `AGENTS.md` convention #10 for the full
   story).

2. **`:ctor` never sees props.** `glitter.core`'s reconciler calls a widget
   spec's `:ctor` with only an `{:ns ns-hint}` map (see `glitter.core:646`,
   line: `(when ns {:ns ns})`), and passes real props through a separate
   path later via `:apply`. Every prop, including those needed at
   construction time (a label's `:label` text, a button's `:label`), must be
   handled in `:apply` instead. Verified against the actual reconciler code
   path in glitter.core.

3. **Never add a suppression set.** glitter.gtk carries a `suppressing` atom
   because GTK's programmatic setters (like `gtk_editable_set_text`)
   synchronously re-emit their own signal, which would feed a re-render back
   into app dispatch. AppKit does NOT fire action or delegate callbacks for
   programmatic `setState:`/`setStringValue:`/etc., so there is nothing to
   suppress and no such set exists here. This absence is intentional — do not
   add one. The property is asserted live by
   `examples/glitter_uikit/keyed_smoke.clj`.

4. **`insert-before` is single-branch on purpose.** Unlike glitter.gtk, which
   branches on whether a child is already tracked in the parent's children
   list (reorder vs. insert), glitter-uikit's `insert-before` is a single
   code path — AppKit's `insertArrangedSubview:atIndex:` automatically moves
   an already-parented subview instead of asserting it's unparented like GTK
   does. See `docs/guide/appkit-widget-layer.md`'s "Where AppKit is simpler"
   section for the measured behavior and evidence.

5. **Every index read goes through `arranged-index`.** NSStackView's children
   are accessed via `arrangedSubviews` array indexing, which can raise an
   uncatchable `NSException` if the index is out of bounds or equals
   `NSNotFound` (which is `NSIntegerMax`, not `-1`). A raw index access that
   silently misses can cascade into worse bugs downstream. `arranged-index`
   guards every read.

6. **Never `(resolve 'System/exit)`.** It is always nil in Jolt/ClojureDart.
   Call `System/exit` directly or the smoke that needs an exit code will exit
   0. Verified live — a smoke that catches exceptions and tries to exit with
   a non-zero code via `(when-let [exit (resolve 'System/exit)] (exit 1))`
   silently exits 0.

7. **macOS `sed` is BSD and has no `\b` word boundary.** Use literal tokens or
   GNU `sed` (installed via Homebrew as `gsed`). E.g., replace `sed
   's/\bfoo\b/bar/g'` with `gsed 's/\bfoo\b/bar/g'` or `sed 's/foo/bar/g'` if
   the context is already specific enough.

8. **Prefer running the live smokes over reasoning about AppKit view-tree
   behavior in the abstract.** AppKit is a live, stateful system with a
   blocking main loop. Several of this port's most important facts —
   `insertArrangedSubview:atIndex:` auto-moving a subview,
   `removeArrangedSubview:` leaving a plain subview behind, every index read
   needing bounds-checking — were discovered and verified by actually running
   against live AppKit state, not by reading documentation or reasoning from
   GTK analogies. GTK and AppKit have subtly different semantics in exactly
   these areas, and the live smokes are the ground truth.

9. **The glitter-core split is the standing follow-up.** Extracting a
   natives-free `glitter-core` (cross-link README's Status section) is
   recorded in `AGENTS.md` at dispatch time as context for any future
   contributor picking up that arc.

## Scope (shipped)

The full AppKit renderer for glitter: nine widget tags (`:window`,
`:box`/`:hbox`/`:vbox`, `:button`, `:label`, `:entry`, `:checkbutton`,
`:separator`, `:frame`, `:scrolled`), mapped to their AppKit equivalents
(`NSWindow`, `NSStackView`, `NSButton`, `NSTextField` in various styles,
`NSBox`, `NSScrollView`). Eight live-AppKit smokes (keyed reorder, child
replacement/insertion, handler lifecycle, main-thread rendering,
state-atom reactivity, nREPL live editing, plus the basic smoke) plus the
full unit suite. Two interactive demos (counter, task board) mirroring
glitter's own examples.

Known v1 limitations, inherited unmodified from glimmer-uikit: `:class`
and `:style` props are silently accepted but do nothing (AppKit has no
CSS); no `hiccup?` validation on `:style` contents (unlike glitter.gtk,
which validates Pango markup); the `insert-before` single-branch design
(see gotcha #4) means keyed re-orders work but the single-branch shape
(no reorder API separate from insert) is less obvious than GTK's explicit
`gtk_box_reorder_child_after` call.
