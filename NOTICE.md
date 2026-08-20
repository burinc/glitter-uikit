# Third-party code

## glimmer-uikit

The following files under `src/glitter_uikit/` are forked from
[glimmer-uikit](https://github.com/jolt-lang/glimmer-uikit) at tag `v0.1.0`
(commit `8f1c6a4`), Copyright 2026 Dmitri Sotnikov (`Yogthos`), published under
the `jolt-lang` organization.

**Upstream ships no LICENSE file.** Absent a license, default copyright reserves
all rights — no grant has been made. This section records accurate provenance,
not a claimed permission. The same author licenses the sibling
[glimmer-gl](https://github.com/jolt-lang/glimmer-gl) under Apache-2.0, so this
appears to be an upstream oversight rather than a deliberate reservation; that
is an observation, not a substitute for a license.

Authorship verified from git history, pinned to the exact ref and SHA counted so
the claim is falsifiable:

| repo | ref | SHA | dated | commits | authors |
|---|---|---|---|---|---|
| `glimmer-uikit` | `upstream/main` (= tag `v0.1.0`) | `8f1c6a4` | — | 1 | `Yogthos` 1 |
| `glimmer` | `upstream/main` | `55e38f0` | 2026-08-18 | 29 | `Yogthos` 25, `Dmitri Sotnikov` 4 |
| `glimmer-gl` | `origin/main` | `ae9a532` | 2026-08-13 | 20 | `Yogthos` 19, `Dmitri Sotnikov` 1 |

Zero commits by any other author in any of the three; the two identities are the
same person. Counts are pinned to the SHAs above because two checkouts of
`glimmer` and `glimmer-gl` exist locally and the stale pair yields plausible but
wrong totals.

- `src/glitter_uikit/ffi.clj` — `src/glimmer_uikit/ffi.clj`. Near-verbatim:
  namespace substitution only (4 lines), plus one added binding, `control-state`
  (the `NSControl -state` getter). Upstream only ever SET the state; glitter's
  renderer re-reads a value-bearing view's own property after its event fires,
  so `:checkbutton` needs a real getter.
- `src/glitter_uikit/widget.clj` — `src/glimmer_uikit/widget.clj`, with these
  deviations:
  1. **`connect-signals!` removed, not adapted.** glimmer connects target/action
     once at mount and lets handlers close over a reactive cell. glitter.core
     calls `IRender/set-event-handler` again whenever handler DATA changes, so
     `glitter-uikit.appkit` owns signal lifecycle end to end; two writers of
     `setTarget:`/`setAction:` would fight.
  2. **`GlimmerTarget` → `GlitterTarget`.** `objc_allocateClassPair` registers
     the class process-wide BY NAME, and the existing-class branch looks it up
     by that name — sharing it would silently hand back glimmer's class, with
     glimmer's IMPs and its handler registries.
  3. **`apply-props!` skips `:on`** (glitter's single event map) rather than
     `:on-*` keys, and filters on `some?` rather than truthiness so an explicit
     `false` (`:sensitive false`, `:active false`) still reaches the view.
  4. **`replace-child!` no longer relocates non-final children.** Upstream did
     remove + `addArrangedSubview:`, and add always lands at the END of the
     stack. Now captures the index before removing and inserts at it. This is
     the identical defect glitter fixed on the GTK side (see glitter's NOTICE,
     Bucket 2 deviation 1).
  5. **`insert-child-after!` added** — absent upstream; `glitter.core`'s
     `insert-before` requires it. One code path serves both a fresh insert and a
     move, because `-[NSStackView insertArrangedSubview:atIndex:]` MOVES an
     already-arranged subview (measured: `[A B C]` + insert C@0 → `[C A B]`,
     count unchanged). Notably this is where AppKit is SIMPLER than GTK:
     `glitter.gtk/insert-before` must branch on whether the child is already
     parented, because `gtk_box_insert_child_after` asserts an unparented child.
  6. **`arranged-index` added, and every index read routed through it.**
     Upstream called `stack-index-of!` and did `(inc i)` on the result.
     `indexOfObject:` returns `NSNotFound` for a non-member, which is
     `NSIntegerMax` (`9223372036854775807`) — not `NSUIntegerMax`/`-1` — and
     feeding `NSNotFound + 1` to `insertArrangedSubview:atIndex:` raises an
     uncaught `NSException` that ABORTS THE PROCESS; a Clojure `catch :default`
     does not intercept it, because ObjC exceptions do not unwind into Scheme.
     Measured, both facts.
  7. **`forget-view!` added, and `remove-child!` calls it.** Upstream's
     `actions`/`changes`/`alignments` registries were never cleaned: an
     unbounded leak, and — because AppKit reuses freed addresses — a
     stale-handler hazard where a new view lands on a dead view's address and
     inherits its handler.
  8. **`->bool` dropped** (defined and never called upstream).
  9. **No suppression set.** glitter.widget carries one because GTK's
     programmatic setters synchronously re-emit their own signal. AppKit does
     not fire action or delegate callbacks for programmatic setters, so there is
     nothing to guard. Asserted live by
     `examples/glitter_uikit/keyed_smoke.clj`'s
     `programmatic-active-does-not-dispatch`.
  10. **The Pango `:span` vocabulary keeps upstream's `:color` alias** for
      `:foreground`, which `glitter.widget`'s own table does not have. A
      deliberate superset, pinned by `widget-test/span-accepts-color-alias`.
  11. **Markup error prefixes** read `glitter-uikit/markup:` (upstream:
      `glimmer/markup:`; glitter.widget uses `glitter/markup:`) so the three are
      distinguishable in a stack trace.
- `src/glitter_uikit/app.clj` — adapted from the non-reconciler half of
  `src/glimmer_uikit/core.clj` (its `CFRunLoopSource` scheduler, `run*`, `run!`,
  `quit!`), reshaped to `glitter.app/run`'s `on-activate` signature. One
  behavioral addition: `on-gui` carries glitter.app's THREE-way branch (inline
  when headless, inline when already on the main thread, marshal otherwise)
  where glimmer-uikit always posted. Always-marshalling breaks any caller
  expecting a synchronous read-back after render — glitter found this live in
  its own final review. **A second, carried fix:** the scheduler's drain
  captured and cleared its thunk queue non-atomically
  (`(let [jobs @queue] (reset! queue []) …)`), so a `post-to-gui` from a worker
  thread landing between the deref and the reset was silently dropped — no
  error, no log, just a callback that never fires. Replaced with a single
  CAS-based `swap-vals!`. This is a fifth defect carried in from glimmer-uikit
  v0.1.0 alongside the four in `widget.clj`; found in this port's own P3.T2
  review.

  **A third fix in this file, and the only defect in the arc found by a running
  test rather than by review:** `run*` reset `gui-loop-running?` and
  `main-thread` AFTER calling `on-activate`, so both were unset for the whole of
  `on-activate` and every `on-gui` call inside it took the run-inline branch
  regardless of calling thread. Harmless for a main-thread write; for a
  worker-thread write (an nREPL eval, a background fetch completing during
  mount) it meant mutating AppKit off the main thread — the exact violation the
  three-way branch exists to prevent. Note this is NOT inherited from
  glimmer-uikit and NOT present in `glitter.app`: glitter sets the same two
  flags in the same textual position, but its `on-activate` is a
  foreign-callable wired to GTK's `"activate"` signal, so it fires from inside
  the running loop after the flags are set. AppKit has no signal indirection —
  `run*` calls `on-activate` eagerly, before `[NSApp run]`. The ordering was
  carried over from `glitter.app` without the callback-timing difference that
  made it safe there, so this is a defect the PORT introduced, not one it
  inherited. Record it as such. Found by `main_thread_smoke.clj`, which asserts
  which thread `view` ran on rather than merely that the label updated.
- `test/glitter_uikit/widget_test.clj` — `test/glimmer_uikit/widget_test.clj`,
  six deftests ported, two added.
- `examples/glitter_uikit/*.clj` — layouts and scenarios follow the
  `examples/glimmer_uikit/` originals; every state model is rewritten (one
  top-level state atom, plain derived values instead of reactions, action DATA
  instead of closures, `glitter.nexus` for `todo.clj`).

## glitter

`src/glitter_uikit/appkit.clj` is new code, but its structure follows
`glitter.gtk` closely (the `IRender`+`IMemory` single-`reify` form, the
tracking-atom `el` shape, `mount!`'s state-atom wiring). Same author as
glitter-uikit; listed for provenance.

`bb.edn`, `.clj-kondo/`, `.lsp/`, and `scripts/check_positional_args.clj` are
rename-only adaptations of glitter-gl's copies, which in turn credit glitter and
b12n-rljlt — see glitter-gl's own NOTICE.md.

## Known gaps

- **GTK4 must be installed to run anything here**, inherited transitively from
  glitter's own `:jolt/native`. Verified: jolt inherits a dependency's natives,
  hard-fails in `load-natives!` before any namespace loads, and ignores
  `:jolt/native` inside an `:aliases` entry — so it cannot be scoped away. The
  real fix is extracting a natives-free `glitter-core`, the way upstream glimmer
  split at v0.1.0.
- `add-class`/`remove-class` are no-ops — AppKit has no CSS-class system.
- `remove-attribute` is a no-op, inherited from glitter's own v1 gap.
- `:halign`/`:valign` are stack-wide, not per-child (`NSStackView.alignment` is
  a property of the stack; last child with an alignment wins).
- Vertical `:separator` renders as nothing — AppKit has no vertical separator
  primitive.
- `window-spec`'s `:width`/`:height` are read in `:ctor` and never re-applied.
  Inert in practice: `glitter-uikit.app/run` builds the root NSWindow and
  `mount!` wraps it directly, so `create!` is never called for `:window`.
- **`signal-name` / `signal-value-fn` / `retain-callable!` / `release-callable!`
  are deliberately ABSENT**, though the design spec's Architecture section lists
  them as part of this widget layer's surface. They exist in `glitter.widget`
  for two GTK-specific reasons that have no AppKit counterpart: GTK connects a
  *new* foreign-callable per widget per signal (so it needs retain/release
  bookkeeping to keep them from being collected), and it needs the raw signal
  *name string* to call `g_signal_connect_data` / `g_signal_handler_disconnect`.
  AppKit uses a target/action model with a handful of permanently-retained
  `defonce` shared callbacks (`fire-cb`, `change-cb`, `quit-cb`, `terminate-cb`)
  and no name-keyed connect API at all, so there is nothing to retain, release,
  or look up. `glitter-uikit.appkit` selects handlers with a static
  `action-events` set instead, and owns the `signal-value` table itself. This is
  an intentional absence — like `suppressing?` — recorded here so a later reader
  comparing spec to code does not conclude it was dropped by accident and
  "restore" dead machinery. (Pre-dispatch scan finding, 2026-08-20.)
- A Pango `:markup` attribute value containing a quote crashes. `parse-attrs`
  matches `([a-zA-Z_]+)=['"]([^'"]*)['"]`, but hiccup escapes an embedded quote
  to `&quot;`, so `[:span {:foreground "a\"b"} "x"]` reaches `color-hex` as
  `"a&quot;b"` and throws `bad hex digit &`. Present upstream (whose own test
  asserts the escaped form is produced) and carried forward deliberately — the
  fix is to decode entities before `parse-attrs`, which deserves its own task.
