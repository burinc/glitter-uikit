# App loop and cross-thread marshalling

`glitter-uikit.app` is adapted from the non-reconciler half of
`glimmer-uikit.core` (its scheduler, `run*`, `run!`, `quit!`), reshaped to
`glitter.app`'s signature: `run` takes an `on-activate` callback of one
arg — the window pointer — so this namespace has no dependency on any
particular reconciler. `glitter.app` (the GTK sibling) is the same
reshaping applied to a different upstream source — `glimmer.core`, the
GTK original, rather than `glimmer-uikit.core` — for the same reason.
The two diverge sharply once the underlying platform APIs do, which is
most of what this page covers.

## Bootstrapping: `run` and `run*`

```clojure
(defn run
  [on-activate & {:as opts}]
  (let [start (fn [] (run* on-activate opts))]
    (if-let [hop (resolve 'jolt.host/call-on-main-thread-async)]
      (hop start)
      (start))))
```

AppKit requires its event loop on the process main thread. `run` hops
onto Jolt's main-thread pump asynchronously via
`jolt.host/call-on-main-thread-async` when that var resolves — true for
an nREPL session, whose primordial thread parks there, so the eval that
started the app returns and the session stays live — and runs inline
(blocking until the app quits) otherwise, which is what a plain `jolt run`
invocation gets.

`run*` does the actual bootstrap: gets `NSApplication`'s shared instance,
sets its activation policy to regular, sets `w/invoker` (the shared
`GlitterTarget` instance) as the app delegate, builds a window, then —
after setting `main-thread`/`gui-loop-running?`, covered below — calls
`on-activate` with the window pointer so the caller can mount its own
root content, centers and shows the window, activates the app, wires the
optional `:auto-quit-ms` timer (used by every automated live smoke to
quit the loop deterministically), and finally calls `[NSApp run]`, which
blocks running the AppKit main loop for the life of the app.

## `on-gui`'s three-way branch

```clojure
(defn on-gui
  [work]
  (cond
    (not @gui-loop-running?) (work)
    (= (Thread/currentThread) @main-thread) (work)
    :else (post-to-gui work)))
```

Every render in this project goes through `on-gui`, not called directly —
`mount!`'s state-atom watcher calls it, and `IRender/next-frame` is
implemented as `(app/on-gui f)`. Each of the three arms exists for a
distinct reason:

1. **No loop running → inline.** Unit tests never start `NSApplication`
   at all, so `on-gui` degrades to a plain synchronous call with no
   run-loop machinery involved.
2. **Already on the main thread → inline.** Without this branch, every
   call to `on-gui` would marshal — even one already safely on the main
   thread, like `appkit/mount!`'s initial `render!` call happening from
   inside `on-activate`, or a click handler's dispatch triggering a
   `swap!` whose watcher fires synchronously. That would break any
   caller expecting a synchronous read-back immediately after a state
   change. `examples/glitter_uikit/keyed_smoke.clj` is exactly that
   caller: it calls `(reset! state {:order ["c" "a" "b"]})` from inside
   `on-activate` (main thread) and reads the live `NSStackView`'s
   arranged subviews back on the very next line — that only observes
   the post-render state because this branch ran the render inline
   rather than deferring it to the next loop iteration.
3. **Any other thread → marshal via `post-to-gui`.** This is the actual
   safety net: AppKit rejects view mutation from a thread that isn't the
   main thread.

`glitter.app` carries this identical branch — the code's own comment
says so directly ("glitter.app carries the identical branch, added there
after a live finding during its final whole-branch review"), and
always-marshalling regardless of caller thread was the specific bug that
finding caught there: it breaks exactly the synchronous-read-back case
above.

## The `CFRunLoopSource` + thunk-queue marshaller

Marshalling a worker-thread call onto the main loop needs a way to wake
the loop and run something on it. GTK's answer is `g_idle_add`, which
allocates and retains a fresh one-shot source per post. AppKit's
`CFRunLoop` offers a lower-level primitive that this project uses to
avoid that per-post cost: a single, long-lived `CFRunLoopSource`
installed once, whose `perform` callback drains a shared queue.

```clojure
(defonce ^:private scheduler
  (let [queue   (atom [])
        perform (ffi/foreign-callable
                 (fn [_info]
                   (let [[jobs _] (swap-vals! queue empty)]
                     (run! (fn [f]
                             (try (f)
                                  (catch :default e
                                    (println "glitter-uikit: scheduled work failed:" e))))
                           jobs)))
                 [:pointer] :void :collect-safe)
        ctx (ffi/alloc 80)]
    ;; ... CFRunLoopSourceContext fields written into ctx, perform at offset 72 ...
    (let [src (u/cf-run-loop-source-create ffi/null 0 ctx)
          rl  (u/cf-run-loop-get-main)]
      (u/cf-run-loop-add-source rl src (u/default-mode))
      {:queue queue :source src :run-loop rl})))

(defn- post-to-gui [work]
  (let [{:keys [queue source run-loop]} scheduler]
    (swap! queue conj work)
    (u/cf-run-loop-source-signal source)
    (u/cf-run-loop-wake-up run-loop))
  nil)
```

Posting is just `conj` onto the queue, signal, wake up — no allocation,
no retained callable, per post.

The drain step is the interesting part, and it carries a real, previously
shipped defect and its fix. The queue must be captured and cleared as
**one atomic operation**:

```clojure
(let [[jobs _] (swap-vals! queue empty)]
  (run! ... jobs))
```

The code's own correction comment explains exactly why a simpler
`(let [jobs @queue] (reset! queue []) ...)` — a deref followed by an
unconditional reset, which is what the upstream original did — is wrong:
a worker thread's `(swap! queue conj work)` landing between the deref and
the reset is captured by neither the already-read `jobs` nor the
just-clobbered queue. The thunk is lost permanently, with no error and no
log line. `swap-vals!` is CAS-based, so a concurrent post either lands
before this swap (and gets drained in this pass) or after it (and
survives intact for the next signal) — there is no window in between
where a post can vanish.

## The run-loop mode: a copy of the default mode, never the common modes

```clojure
;; ffi.clj
(def ^:private kCFRunLoopDefaultMode
  (cf-string-create-with-cstring ffi/null "kCFRunLoopDefaultMode" 134217984))

(defn default-mode [] kCFRunLoopDefaultMode)
```

`CFRunLoopAddSource` hashes the mode string it's given and doesn't accept
`NULL` — a value-equal `CFString` copy of an ordinary mode name works
fine as far as `CFRunLoopAddSource` is concerned. `kCFRunLoopCommonModes`
is the one exception: CoreFoundation recognizes that specific constant by
**pointer identity**, not by string value, so a value-equal copy of it —
constructed the same way as above, from the same characters — silently
fails to register as a common mode. The code sidesteps that trap
entirely by targeting `kCFRunLoopDefaultMode` instead, which is where
`[NSApp run]` actually pumps events, and which behaves like an ordinary
mode string under `CFRunLoopAddSource`.

## The flag-ordering defect

`run*` originally reset `gui-loop-running?` and `main-thread` **after**
calling `on-activate`. This repository's history is a single squashed
commit, so the original ordering isn't independently diffable — but the
fix's own correction comment, still in `app.clj` today, records exactly
what was wrong and why:

> These two resets MUST happen BEFORE `on-activate`, not after it.
> `glitter.app` sets them immediately before `g_application_run` and that
> is correct THERE, because its `on-activate` is a foreign-callable wired
> to GTK's `"activate"` signal — it fires from INSIDE the running loop, so
> the flags are already set by the time it runs. AppKit has no such
> signal indirection: `run*` calls `on-activate` eagerly and directly,
> before `[NSApp run]`. Copying glitter's textual ordering without
> accounting for that difference left both flags unset for the whole of
> `on-activate`, so EVERY `on-gui` call during it took branch 1 (inline)
> regardless of thread.

The fix moved both resets to **before** `on-activate` is called — the
current, corrected code is:

```clojure
(reset! main-thread (Thread/currentThread))
(reset! gui-loop-running? true)
(try
  (on-activate win)
  (u/window-center! win)
  (u/window-show! win)
  (u/activate! app)
  (when auto-quit-ms (w/auto-quit! app auto-quit-ms))
  (u/run-app! app)
  (finally (reset! gui-loop-running? false)))
```

With the flags reset *after* `on-activate` (the original ordering), every
`on-gui` call made during `on-activate` — which is exactly when
`appkit/mount!` runs its first render — saw `gui-loop-running?` still
`false` and took branch 1 (inline), unconditionally, regardless of which
thread actually made the call. For the mount's own synchronous render
that's harmless by accident. It stops being harmless the moment anything
else calls `on-gui` from a genuinely different thread while `on-activate`
is still running — an nREPL eval racing the mount, a background fetch
completing early — because inline means mutating AppKit views directly
from that worker thread, which is precisely the violation the three-way
branch in `on-gui` exists to prevent.

The reason this is subtle, not just a copy-paste slip, is that
`glitter.app`'s `run*` has the **identical textual ordering** — reset the
flags, then immediately call the blocking run function — and it is
*correct* there:

```clojure
;; glitter/app.clj — correct with this exact ordering
(try
  (reset! gui-loop-running? true)
  (reset! main-thread (Thread/currentThread))
  (g/g-application-run app 0 ffi/null)
  (finally (reset! gui-loop-running? false)))
```

`glitter.app`'s `on-activate` is not called directly by this code at all
— it's a foreign-callable wired to GTK's `"activate"` signal via
`g_signal_connect_data`, registered *before* this block runs. The
callback only actually fires once `g_application_run` starts pumping the
GTK main loop and GTK dispatches that signal — i.e., from **inside** the
already-running loop, after `gui-loop-running?`/`main-thread` were set
immediately before the call that starts it. The flags are simply already
true and correct by the time GTK's `activate` fires.

AppKit has no equivalent indirection. `run*` calls `on-activate` eagerly
and directly, as a plain function call, **before** `[NSApp run]`
(`u/run-app! app`) is ever invoked — there's no signal dispatch standing
between "the flags get set" and "the caller's mount code runs." Copying
`glitter.app`'s textual ordering without accounting for that difference
left both flags unset for the whole of `on-activate` on this platform. The
fix is the flag resets moved earlier, with the `try`/`finally` widened to
cover `on-activate` too, so `gui-loop-running?` still gets cleared if
`on-activate` throws.

## Caught by `main_thread_smoke.clj`

`examples/glitter_uikit/main_thread_smoke.clj` is what found this. It
mounts, then mutates `state` from inside a `future` — a genuinely
different thread — and schedules a read-back via `app/schedule!`, which
(unlike `on-gui`) always marshals regardless of caller thread, so the
read-back is guaranteed to run after the watcher's own posted render
(`CFRunLoopSource` thunks drain FIFO).

The load-bearing assertion is **which thread `view` actually ran on**,
not merely that the label's text updated:

```clojure
(defn view [{:keys [txt]}]
  (reset! render-thread (Thread/currentThread))
  [:vbox {:spacing 4} [:label {:label txt}]])
...
(record! (not= main-t @worker-t) "worker-really-was-another-thread")
(record! (= main-t @render-thread) "view-rendered-on-the-main-thread")
(record! (= ["from-worker"]
            (mapv u/control-string (w/stack-children (root-stack window))))
         "label-updated")
```

An unmarshalled watcher still updates the label — nothing stops a worker
thread from writing `NSTextField`'s `stringValue` under Jolt; it's an
AppKit violation, not something that throws or gets caught. So a
text-only check (did the label say `"from-worker"`?) would pass with the
bug fully present and prove nothing about which thread did the mutating.
`view` recording its own thread into `render-thread`, and the smoke
requiring that to equal the main thread captured by `run*`, is what
actually pins the regression. The smoke also separately asserts the
worker thread really was a different thread from the main one, so the
whole check can't pass vacuously if `future` were ever to run inline,
and it tracks whether the scheduled read-back callback ran at all
(checked after `app/run` returns) — so a scheduler regression that drops
the callback entirely can't report a silent, vacuous `:PASS` either.
