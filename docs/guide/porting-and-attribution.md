# Porting and attribution

glitter-uikit's source falls into three buckets. `NOTICE.md` (repo root) is
the authoritative, maintained ledger — this page explains what the buckets
mean and summarizes the deviations; if the two ever disagree, `NOTICE.md`
wins.

## Bucket 1: ported from glimmer-uikit

Mechanical namespace-rename port from
[glimmer-uikit](https://github.com/jolt-lang/glimmer-uikit) commit `8f1c6a4`
(tag `v0.1.0`), Copyright 2026 Dmitri Sotnikov, published under the
`jolt-lang` organization.

**Upstream ships no LICENSE file.** Absent a license, default copyright
reserves all rights — no grant has been made. This section records accurate
provenance, not a claimed permission. The same author licenses the sibling
glimmer-gl under Apache-2.0, so this appears to be an upstream oversight
rather than a deliberate reservation; that is an observation, not a
substitute for a license.

Ported files:
- `src/glitter_uikit/ffi.clj` — `src/glimmer_uikit/ffi.clj`
- `src/glitter_uikit/widget.clj` — `src/glimmer_uikit/widget.clj`
- `src/glitter_uikit/app.clj` — adapted from `src/glimmer_uikit/core.clj`
  (the non-reconciler half: CFRunLoopSource scheduler, run/quit lifecycle)
- `test/glitter_uikit/widget_test.clj` — `test/glimmer_uikit/widget_test.clj`
- `examples/glitter_uikit/*.clj` — layouts/scenarios follow
  `examples/glimmer_uikit/*`

The port carries three **deliberate model adaptations** (required because
glitter's architecture differs from glimmer's, not because upstream was
wrong for glimmer), plus **real defect fixes** in `widget.clj` and
`app.clj`:

Model adaptations:
1. **Event lifecycle ownership split** — glitter calls `set-event-handler`
   whenever handler data changes, so `glitter-uikit.appkit` owns the
   lifecycle end to end. `connect-signals!` was removed rather than adapted.
2. **`GlitterTarget` class registration** — renamed from `GlimmerTarget` to
   avoid collision if both run in the same process.
3. **Prop filtering on `some?`** — allowing explicit `false` to reach views
   (e.g. `:active false`, `:sensitive false`), not treating it as "absent".

Real defect fixes:
4. **`replace-child!` position preservation** — captures index before
   removing and re-inserts at the same position (the identical defect glitter
   fixed on the GTK side).
5. **`insert-child-after!` added, then its own forward-move bug fixed** —
   absent upstream entirely; glitter.core's `insert-before` requires it. The
   first version incremented a moved child's target index unconditionally,
   which overshoots by one slot on a forward keyed move (the child currently
   sits before its target sibling), because AppKit's insert is
   remove-then-insert with a post-removal index. Fixed in this arc's final
   review.
6. **`arranged-index` added, guarding every index read** — upstream did
   `(inc i)` on a raw `stack-index-of!` result, which aborts the process
   (uncatchably) when the sibling is absent, since `NSNotFound` is
   `NSIntegerMax`. `arranged-index` returns `nil` for "absent" instead.
7. **`forget-view!` added, called from `remove-child!`** — upstream's
   `actions`/`changes`/`alignments` registries were never cleaned, an
   unbounded leak and a stale-handler hazard (AppKit reuses freed
   addresses).
8. **`app.clj` fixes** — (a) thunk queue drain made atomic (CAS-based
   `swap-vals!`), (b) `run*` flag ordering (set flags before calling
   `on-activate` so they are visible inside it), and (c) `on-gui`'s three-way
   branch (inline when headless, inline when already on-thread, marshal
   otherwise — not always-marshal like upstream).

Full detail for all deviations: `NOTICE.md`.

## Bucket 2: ported from glitter

`src/glitter_uikit/appkit.clj` is new code, but its structure follows
`glitter.gtk` closely (the `IRender`+`IMemory` single-`reify` form, the
tracking-atom `el` shape, `mount!`'s state-atom wiring). Same author as
glitter-uikit; listed for provenance.

`bb.edn`, `.clj-kondo/`, `.lsp/`, and `scripts/check_positional_args.clj`
are rename-only adaptations of glitter-gl's copies, which in turn credit
glitter and b12n-rljlt — see glitter-gl's own NOTICE.md.

## Bucket 3: new code (glitter-uikit-specific)

- `NOTICE.md`, `CONTRIBUTING.md` — this repository's documentation
- `docs/guide/` — architecture and design decision documentation
- `examples/glitter_uikit/counter.clj`, `todo.clj` — state models rewritten
  (one top-level state atom, plain derived values, action data instead of
  closures)
- Test suite enhancements — eight live-AppKit smokes (keyed reorder, child
  replacement/insertion, handler lifecycle, main-thread rendering,
  state-atom reactivity, nREPL live editing) plus two additional unit tests
  (markup `:color` alias validation, programmatic-active-does-not-dispatch)

## Licensing

`glitter-uikit` itself is **MIT-licensed** — see `LICENSE`, Copyright 2026 Burin
Choomnuan. That grant covers this project's own code: the AppKit FFI bindings,
the widget layer's reshaping, the renderer, the app loop, the examples and the
docs.

That is a separate question from the status of the code ported IN, and the two
must not be run together. Upstream `glimmer-uikit` ships **no LICENSE file**, so
absent one, default copyright reserves all rights and no grant has been made for
those files. `NOTICE.md` records that accurately and file by file. This
project's MIT grant does not extend to the upstream material it vendors, and
nothing here should be read as claiming otherwise.
