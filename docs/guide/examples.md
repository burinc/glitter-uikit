# The examples

`examples/glitter_uikit/` holds **eleven runnable namespaces**, and every one
has a `deps.edn` alias and a `bb` task. They come in two kinds:

- **Three interactive demos** you open and click — the gallery below.
- **Eight live-AppKit smokes**, each a small, complete glitter program that
  mounts a real window, asserts against real AppKit state, and exits
  non-zero on failure.

Run any of them with `bb <name>`, or `jolt -M:<name>` without babashka.
`bb info` prints the whole list grouped, and `bb smokes` runs all eight
smokes in sequence, stopping at the first failure.

All ten need a GUI session — and GTK4 installed, even though this renderer
never calls a GTK function. See [Limitations](limitations.md) for why.

## Interactive demos

| preview | `bb` name | What it demonstrates |
|---|---|---|
| [<img src="../demos/counter.png" width="150">](../demos/counter.png) | `counter` | The canonical demo. One state atom, a pure `state -> hiccup` view, handlers as data. The whole model in a window you can click through in ten seconds. |
| [<img src="../demos/widgets.png" width="150">](../demos/widgets.png) | `widgets` | Every tag the renderer registers, in one window — `:vbox`/`:hbox`, `:label`, `:separator`, `:frame`, `:entry`, `:checkbutton`, `:button` (including a `:sensitive false` one) and a `:scrolled` list. `:separator` and `:scrolled` appear in no other example, so this is the only place either is shown running. Type a name and press Add, or tick the checkbutton first to add it in capitals. |
| [<img src="../demos/todo.png" width="150">](../demos/todo.png) | `todo` | A task board on `glitter.nexus`: derived counts (3 total / 1 done / 2 left) computed inline on every re-render — glitter has no reactive-derivation primitive — plus an entry with `:change`/`:activate`, checkbutton toggles, and list rendering in a frame. The counts row is the whole point: it's recomputed from `:tasks` on every render, not tracked as separate state. |

Every preview is a real screenshot of the demo running, not a mockup. They
are committed under `docs/demos/`, and each thumbnail links to the
full-size image.

## Live-AppKit smokes

These are examples in exactly the sense the demos are — each mounts a
window and drives a real glitter view. What makes them smokes is that they
then *assert*, against the live AppKit tree rather than against the
renderer's own bookkeeping (which would agree with itself and pass even if
no AppKit call ever landed).

Read them in this order; each is a good short read on one property of the
renderer.

| `bb` name | source | What it demonstrates |
|---|---|---|
| `smoke` | `smoke.clj` | The end-to-end seam: a view function renders into a real `NSWindow`, and a state-atom write re-renders it. Start here. |
| `reactivity-smoke` | `reactivity_smoke.clj` | A programmatic state write re-renders in place, **and** a real `-[NSControl performClick:]` dispatches an action through the target/action path the renderer actually wired — not a direct handler call, which would prove nothing about the wiring. |
| `replace-child-smoke` | `replace_child_smoke.clj` | The most ordinary update hiccup has — a text child whose string changes — puts the new view back at the replaced one's **exact index**, not at the end. (The `glimmer-uikit` original appended.) |
| `insert-before-smoke` | `insert_before_smoke.clj` | Three properties of `insert-before`: a genuinely new child lands mid-list; an existing child's keyed reorder **moves** rather than duplicates; and a *forward* move lands at the sibling's un-incremented index — this port's own final-review fix. |
| `keyed-smoke` | `keyed_smoke.clj` | A keyed reorder lands in the right live order **and reuses the same view pointers** rather than recreating them. Also pins the no-suppression property this renderer is built on: a programmatic `:active` write must fire zero `:toggled` events, while a real click fires exactly one. |
| `handler-cleanup-smoke` | `handler_cleanup_smoke.clj` | Unmounting a subtree drops every handler registration it held, **grandchildren included** — the registries would otherwise grow without bound, and AppKit reuses freed addresses, so a new view can land on a dead one's address and inherit its handler. |
| `main-thread-smoke` | `main_thread_smoke.clj` | A state change made from a **non-main thread** still renders on the AppKit main thread — the property the `CFRunLoopSource` scheduler exists for. |
| `repl-live-smoke` | `repl_live_smoke.clj` | The same marshalling, shaped like a live nREPL session: a worker thread mutates state while the app runs, standing in for an nREPL eval on its own thread. |

That table is the **index**. The full argument — the exact assertions each
one makes, and why each is shaped so it fails loudly instead of passing
vacuously — lives in
[Testing and tasks](testing-and-tasks.md#live-appkit-smokes). The two are
deliberately not duplicates: change a smoke's behaviour and that page is
the one to update.

## Why the demos are worth reading, not just running

`counter.clj` says it directly in its own docstring: in glimmer-uikit
(the Reagent-style sibling this project was ported from), local state
lives in a component-scoped ratom and a click closure calls `swap!`
itself. Here all state is in one top-level atom, the view is a pure
function of it, and click handlers are *data* dispatched through one
global fn, never closures. `todo.clj` makes the same contrast at larger
scale, and adds the derived-counts point: there is no memoized selector,
no reaction, no cache — the three numbers above the task list are just
arithmetic over `:tasks` re-run on every render, because re-running the
whole view is the model.

## Stills, not animations — a deliberate, revisitable choice

glitter's own gallery steers its demos with a scripted Tab/Space input
timeline to record short GIFs. That recipe is verified against GTK and
does not carry to AppKit: probed here, five Tab/Space presses left the
counter at 0 with no focus ring, because on macOS reaching a *button* by
keyboard needs "Full Keyboard Access" enabled in System Settings. So these
screenshots use `:driver :spawn` with no synthetic input — see
`scripts/demo_manifest.edn` for the full reasoning. Upgrading to GIFs
starts by probing what steering actually reaches an AppKit window.

## Adding an example

Two touchpoints, and skipping either leaves the example invisible to
something:

1. **The namespace** under `examples/glitter_uikit/`, plus a `deps.edn`
   alias so `jolt -M:<name>` works without babashka.
2. **A `bb.edn` task**, so `bb <name>` works and it shows up in `bb info`.

Then add its row here — to the demo gallery, or to the smoke index above.
A new smoke also belongs in `bb smokes` and in
[Testing and tasks](testing-and-tasks.md#live-appkit-smokes), which is
where its assertions get explained.

If the new example is a screenshot-worthy interactive demo, add it to
`scripts/demo_manifest.edn`'s `:examples` and regenerate with
`screen-grab shot --manifest scripts/demo_manifest.edn`.

One caveat if you touch `counter`: its committed screenshot shows `Count: 5`
because a person clicked the button five times. Synthetic input does not reach
an AppKit window here, so that frame cannot be regenerated by the tool. The
ledger hashes each item's `:src`, so an unchanged `counter.clj` reports
`up to date` and the frame is safe — but editing `counter.clj` (or passing
`--force`) recaptures it as an initial-state `Count: 0`. Re-steer it by hand
rather than committing that. `docs/demos/README.md` repeats this next to the
image.
