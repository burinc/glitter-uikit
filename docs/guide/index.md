# glitter-uikit guide

This guide covers the AppKit renderer for glitter, its architecture, and
specific design decisions.

## Pages

- **[README.md](../../README.md)** — feature overview, quick start, and
  requirements (including GTK4).
- **[AGENTS.md](../../AGENTS.md)** — canonical agent context: conventions,
  gotchas, file map, and scope.
- **[appkit-widget-layer.md](appkit-widget-layer.md)** — the widget mapping
  layer, why it's shaped as it is, where AppKit is genuinely simpler than GTK
  (single-branch `insert-before`, no suppression set) and where it needs more
  care (`NSNotFound` raising uncaught exceptions, pointer-keyed registry
  cleanup), and — kept deliberately separate — which changes are model
  adaptations versus which are fixes for real defects in glimmer-uikit v0.1.0.
- **[porting-and-attribution.md](porting-and-attribution.md)** — porting
  buckets and licensing.
