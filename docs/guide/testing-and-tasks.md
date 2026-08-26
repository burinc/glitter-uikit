# Testing and tasks

glitter-uikit has two layers of verification: a headless unit suite that
runs in a plain `jolt -M:test`, and eight live-AppKit smokes that need a
real GUI session. Both matter for different reasons — the unit suite is
what CI can actually run, and the live smokes are what catches the class
of bug this port shipped several of during its own review: code that
looks correct against `glitter-uikit.appkit`'s own `:children`
bookkeeping and is only wrong once it's checked against the real,
live AppKit tree.

## Unit suite: `jolt -M:test` / `bb test`

`test/glitter_uikit/test_runner.clj` is the entry point (`deps.edn`'s
`:test` alias points `-m` at it). `-main` requires five namespaces and
runs `clojure.test` against all of them:

```clojure
[glitter-uikit.scaffold-test
 glitter-uikit.ffi-test
 glitter-uikit.container-test
 glitter-uikit.widget-test
 glitter-uikit.appkit-test]
```

Run live: `jolt -M:test` (or `bb test`) currently reports **23 tests,
77 assertions, 0 failures, 0 errors**.

What each namespace covers:

- **`scaffold-test`** (1 test) — proves the project resolves at all: the
  `:local/root "../glitter"` dependency is on the classpath and its
  `IRender`/`IMemory` protocol maps have the expected shape (19
  `IRender` methods, 2 `IMemory` methods). Its own docstring puts it
  plainly: *"If this fails, nothing else in the repo can work."*
- **`ffi-test`** (2 tests) — pure constant checks against the raw
  Objective-C/AppKit values (`NSWindowStyleMask`, `NSUserInterfaceLayoutOrientation`,
  `NSControlStateValue`, `NSLayoutPriority` ordering, the
  `NSAttributedString` attribute-name strings). Deliberately avoids any
  `objc_msgSend` call.
- **`container-test`** (8 tests) — the child-management fixes carried in
  from the `glimmer-uikit` port (replace-child position, insert-after
  fresh/move/forward-move, `NSNotFound`-safety, handler cleanup on
  removal) plus the entry-text-setter's only-when-different guard — see
  [`appkit-widget-layer.md`](appkit-widget-layer.md) for the mechanics
  behind each fix. Its own ns docstring is the reason this namespace is
  safe in a headless `jolt -M:test` at all: it "**construct[s] real
  `NSStackView`s and `NSButton`s but never run[s] an event loop**" — real
  AppKit objects exist and can be manipulated directly through their
  Objective-C API without `[NSApp run]` ever starting, so there's no
  main-loop requirement to fake.
- **`widget-test`** (8 tests) — the pure, no-AppKit-needed half of the
  widget layer: `escape-markup`, hiccup-to-Pango `markup`/`markup-string`
  rendering (including the `:color`/`:foreground` alias glitter-uikit
  deliberately keeps as a superset of `glitter.widget`'s own vocabulary),
  `with-orientation`'s `:hbox`/`:vbox` injection, and that an explicit
  `false`/`nil`-valued prop survives tag normalization unmolested (the
  filtering itself happens downstream, in `apply-props!`).
- **`appkit-test`** (4 tests) — the renderer's pure parts: the
  `signal-value` table (that `:entry`'s `:change` reads back the field's
  live text, that `:checkbutton`'s `:toggled` reads back a real boolean
  via `control-state`, and that `register-signal-value!` lets an
  extension add a value-bearing event without editing this namespace),
  and the shape `create-element`/`create-text-node` produce. Its own
  docstring is explicit about the boundary: *"The end-to-end render is
  covered by the live smokes, which need a GUI session and so cannot
  guard CI."*

## Live-AppKit smokes

Eight examples under `examples/glitter_uikit/` each open a real AppKit
window, exercise one specific behavior, read back the *actual live
AppKit state* — never `glitter-uikit.appkit`'s own `:children`
tracking — and call `(System/exit 1)` directly on any mismatch. This
"read the real tree, not our bookkeeping" discipline is stated
explicitly in more than one smoke's own docstring, e.g. `smoke.clj`:
*"bookkeeping would agree with itself and pass even if no AppKit call
landed."*

| task | pins | how it verifies |
|---|---|---|
| `jolt smoke` | a view renders into a real `NSWindow`, and a state-atom write re-renders it | reads each label's `stringValue` back through `w/stack-children`/`u/control-string` before and after `(reset! state {:count 42})` |
| `jolt reactivity-smoke` | a programmatic state write re-renders in place; a REAL click through target/action reaches `glitter.core`'s dispatch | reads label text before/after a `reset!`; a real `-[NSControl performClick:]` call (not a direct handler invocation) drives the actual wiring `glitter-uikit.appkit` set up, asserting the count incremented, dispatch fired exactly once, and the event map carries `:glitter/node` |
| `jolt replace-child-smoke` | `IRender/replace-child` lands the new view at the OLD child's exact index, not appended at the end (the `glimmer-uikit` original's bug) | a stable `"anchor"` label placed AFTER the swapped text child is what makes a wrong position observable; reads texts back before/after `:txt` changes from `"first"` to `"second"` |
| `jolt insert-before-smoke` | a fresh keyed child inserts mid-list correctly; an existing child's keyed reorder MOVES rather than duplicates; a FORWARD keyed move lands at the sibling's un-incremented index (this port's own final-review fix, not carried from upstream) | reads the live stack's children back after each `reset!`; the forward-move case moves `"1"` to sit after `"3"` in `["1" "2" "3" "4"]`, expecting `["2" "3" "1" "4"]` — the buggy pre-fix code produced `["2" "3" "4" "1"]` |
| `jolt keyed-smoke` | a keyed reorder lands in the right live order AND reuses the same view pointers (not recreated); the "no suppression needed" claim this renderer is built on | captures each view's pointer before the reorder (keyed by its text) and confirms the same pointers show up in the new order; separately, a DIFFERENTIAL check — three programmatic `:active` writes on a checkbutton must fire zero dispatches, then a REAL `performClick:` on that same control must fire exactly one, so a broken/dead fire path can't make the "zero dispatches" result pass vacuously |
| `jolt handler-cleanup-smoke` | unmounting a subtree drops every handler registration it held, including grandchildren | the button lives ONE LEVEL DOWN inside a conditionally-rendered `:hbox`, not at the removed node itself, so a non-recursive `remove-child!` would leave its registration behind; counts `@w/actions` before and after `:show?` flips to `false` |
| `jolt main-thread-smoke` | a state write from a NON-main thread still renders ON the AppKit main thread — the property the `CFRunLoopSource` scheduler exists for (see [`app-loop-and-threading.md`](app-loop-and-threading.md)) | records WHICH thread `view` last ran on (not merely whether the label text changed — an unmarshalled render would still update the label, just from the wrong thread, so a text-only check would pass with the bug present); also asserts the worker thread really was a different thread, and that the scheduled read-back callback itself ran, so the smoke can't pass vacuously if `future` ran inline or if the scheduler silently never drained |
| `jolt repl-live-smoke` | the same cross-thread marshalling property as `main-thread-smoke`, shaped like a live nREPL dev session | one thread runs the AppKit app + pump (standing in for the nREPL session's primordial thread); a second, un-joined thread mutates state after a delay (standing in for an nREPL eval on its own worker thread); confirmed two ways — no crash (an unmarshalled render touching AppKit off-main-thread aborts the process outright) and the worker's mutation is actually reflected in a render once the loop quits |

## Interactive demos

`jolt counter`, `jolt widgets`, `jolt temperature`, `jolt flights`,
`jolt timer`, `jolt crud` and `jolt todo` are the seven demos meant to be run
and clicked, not asserted on — `counter.clj` is the canonical
Replicant-style counter (data-driven `:on {:click [[:action/dec]]}`
dispatch, all state in one top-level atom), and `todo.clj` is a larger
task-board demo built on `glitter.nexus` (action-expansions, an
`:entry`'s `:change`/`:activate` pair, checkbutton toggles inside a
`:frame`), while `widgets.clj` is the visual index of the widget layer —
the only example that exercises `:separator` and `:scrolled`, and
`temperature.clj` is the 7GUIs Temperature Converter ported from glitter.
All four block on their window until closed, which is exactly why
`bb smokes` (below) excludes them — an aggregate task that includes an
interactive demo would hang forever waiting for a window close that
never comes in an automated context.

## Exit discipline: direct `System/exit`, never a resolve-guard

Both `test_runner.clj` and every live smoke call `(System/exit 1)`
directly on failure, never through a resolve-guarded indirection.
`test_runner.clj`'s own comment states why:

```clojure
;; Call System/exit DIRECTLY. `System/exit` is a static-method interop FORM,
;; not a var, so `(resolve 'System/exit)` is ALWAYS nil — under Jolt and on
;; the JVM alike. A cond guarded on that resolve never fires and silently
;; falls through to nil, so the suite prints its failures and still exits 0.
```

`repl_live_smoke.clj`'s docstring makes the provenance explicit:
upstream's own smoke guards its exit with
`(let [exit (resolve 'jolt.host/exit)] (when exit (exit 1)))`, which
means upstream's smoke prints a failure message and **still exits 0** —
unable to ever fail CI. This file (and every smoke in this project) was
written to call `System/exit` directly instead, specifically to avoid
that trap.

## Running things: `jolt -M:<alias>` and `bb` task surfaces

Every runnable has its own `deps.edn` alias with `:main-opts`, and
`bb.edn` wraps each one as a babashka task shelling straight to
`jolt -M:<alias>` — never the `jolt <task>` shorthand, which (per the
sibling `glitter` project's own verified finding against jolt v0.6.3,
noted directly in `bb.edn`'s header comment) does not propagate its
child process's exit status. `bb info` is the discoverability
entry point — a grouped cheat-sheet, easier to scan than the flat
`bb tasks` listing:

```
bb info                    grouped task cheat-sheet (start here)
bb test                    jolt -M:test — the unit suite
bb verify                  lint (report) + test (must pass) — pre-commit gate
bb counter                 interactive demo (opens a real window)
bb todo                    interactive demo (opens a real window)
bb smoke                   live-AppKit smoke: mount, reconcile, quit
bb reactivity-smoke        live-AppKit smoke: state-atom watch + real click
bb replace-child-smoke     live-AppKit smoke: replace-child position
bb insert-before-smoke     live-AppKit smoke: insert/reorder ordering
bb keyed-smoke             live-AppKit smoke: keyed reorder + no-suppression
bb handler-cleanup-smoke   live-AppKit smoke: handler registry cleanup
bb main-thread-smoke       live-AppKit smoke: on-gui's 3-way thread branch
bb repl-live-smoke         live-AppKit smoke: nREPL-driven live mount/update
bb smokes                  all eight smokes above in sequence; stops at first failure
bb lint                    clj-kondo, report only
bb lint:strict              clj-kondo, propagates the real exit code
bb lsp:format               clojure-lsp reformat (mutating)
bb lsp:format-check         clojure-lsp reformat, dry run
bb lsp:clean-ns             clojure-lsp ns cleanup (mutating)
bb lsp:clean-ns-check       clojure-lsp ns cleanup, dry run
bb check:positional-args    fns with 3+ positional args, report only
bb check:positional-args:strict   same check, exits non-zero if any found
bb hooks:install            install the FAST git pre-commit hook
bb hooks:install:full       install the FULL git pre-commit hook (+ tests)
bb hooks:uninstall          remove the git pre-commit hook
```

Every task name above was cross-checked against a live `bb tasks` run —
none are aspirational. `bb smokes` chains all eight non-interactive
live smokes with a plain sequence of `shell` calls; babashka's task
runner aborts a `do` block at the first non-zero exit, so it naturally
stops at the first failure with no extra control flow needed. `counter`
and `todo` are deliberately absent from `bb smokes` for the reason given
above — they block on a window and would hang an automated run.

## Quality gates: lint, format, positional-args

```
bb lint            clj-kondo over src/test/examples (report only, always exits 0)
bb lint:strict      same lint, but propagates clj-kondo's real exit code
bb lsp:format / lsp:format-check   clojure-lsp reformat, or a dry-run check
bb lsp:clean-ns / lsp:clean-ns-check   clojure-lsp ns cleanup, or a dry-run check
bb verify           pre-commit gate: lint (report only) + jolt -M:test (must pass)
bb check:positional-args / :strict   fns with 3+ positional args (report | gate)
```

Live-run results as of this page: `bb lint` reports `errors: 0,
warnings: 0`, and `bb lsp:format-check` reports `Nothing to format!` —
both clean, unlike glitter's own project (which carries two accepted
warnings for style/dead-code reasons documented in its own guide).

**`bb verify` gates only on `jolt -M:test`.** Its `clj-kondo` step runs
under `{:continue true}`, so a lint finding never fails the gate — only
a test failure does. This mirrors `bb lint` vs `bb lint:strict`: the
plain form is always a report, the `:strict` form is what actually
propagates a non-zero exit.

**`check:positional-args` is report-only and wired into no gate at
all.** Running it finds 20 functions across `src/glitter_uikit/{app,
appkit,ffi,widget}.clj` with 3+ positional args — `app.clj` contributes
1 (`run`), `appkit.clj` 3 (`register-signal-value!`, `dispatcher`,
`mount!`), `ffi.clj` 8 (mostly raw AppKit-call wrappers like
`window-new`/`constraint`/`stack-edge-insets!`), and `widget.clj` 8
(container-management fns like `append-child!`/`insert-child-after!`/
`replace-child!`/`reorder-child!`, whose argument order mirrors
`glitter.widget`'s own equivalents). Confirmed live: `bb
check:positional-args` exits `0` regardless of how many findings it
reports (the `:strict` variant is what would actually fail on them, and
neither `bb verify` nor either git hook below calls even that variant).
The script's own `exceptions` set is deliberately left empty rather than
pre-populated with all 20 — its comment states this directly: curating
which of the 20 are "legitimately-positional-forever" (native-API
mirrors, protocol-shaped signatures) versus genuinely refactorable "is a
src/-level design judgment out of this tooling/CI task's scope — left
for a dedicated follow-up rather than decided here."

## Git hooks: `bb hooks:install` / `:install:full` / `:uninstall`

`bb hooks:install` writes an executable `.git/hooks/pre-commit` (via
`spit`, not itself tracked in the repo — each clone opts in with its own
run). The FAST hook runs three steps: `clj-kondo --lint src test
examples` gated on its exit code being exactly `3` (errors present,
not just warnings), then `clojure-lsp format --dry`, then `clojure-lsp
clean-ns --dry`. `bb hooks:install:full` adds a fourth step — the full
`jolt -M:test` suite, safe to run in a hook since the unit suite is
entirely headless. `bb hooks:uninstall` deletes the hook file
(idempotent — reports "no pre-commit hook found" on a second run rather
than erroring). `git commit --no-verify` skips the hook for one commit.
Neither hook calls `check:positional-args` in any form.
