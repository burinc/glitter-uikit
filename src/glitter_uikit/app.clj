(ns glitter-uikit.app
  "NSApplication bootstrap and cross-thread marshalling for glitter-uikit.

  Adapted from the non-reconciler half of glimmer-uikit.core (its scheduler,
  run*, run! and quit!), reshaped to glitter.app's signature: `run` takes an
  `on-activate` callback of one arg — the window pointer — so this namespace has
  no dependency on any particular reconciler.

  The app loop is NSApplication; run builds a window, calls on-activate so the
  caller can mount into it, then calls [NSApp run], which blocks running the
  AppKit main loop. Every callback is a :collect-safe foreign-callable invoked
  from inside that loop.

  Off-thread work lands via a CFRunLoopSource registered on the main run loop —
  the AppKit analogue of GTK's g_idle_add, needing neither libdispatch nor ObjC
  blocks, because a source's perform callback is a plain C function pointer."
  (:require [glitter-uikit.ffi :as u]
            [glitter-uikit.widget :as w]
            [jolt.ffi :as ffi]))

(defonce ^:private gui-loop-running? (atom false))

;; The thread [NSApp run] actually runs on — captured when run* starts, so
;; on-gui can tell "already on the main thread" apart from "another thread,
;; must marshal". glitter.app carries the same pair for the same reason.
(defonce ^:private main-thread (atom nil))

;; A single CFRunLoopSource on the main run loop drains a queue of thunks;
;; posting is signal + wake-up, so no per-post allocation happens.
(defonce ^:private scheduler
  (let [queue   (atom [])
        perform (ffi/foreign-callable
                 (fn [_info]
                   ;; CORRECTION (P3.T2 review, 2026-08-20): capture-and-clear must be
                   ;; ONE atomic operation. glimmer-uikit's original did
                   ;; `(let [jobs @queue] (reset! queue []) ...)` — a deref followed
                   ;; by an unconditional reset. A worker thread's
                   ;; `(swap! queue conj work)` landing between those two steps is
                   ;; captured by neither the already-read `jobs` nor the
                   ;; just-clobbered queue: the thunk is lost permanently, with no
                   ;; error and no log line. swap-vals! is CAS-based, so a concurrent
                   ;; post either lands before this swap (and drains now) or after it
                   ;; (and survives for the next signal).
                   (let [[jobs _] (swap-vals! queue empty)]
                     (run! (fn [f]
                             (try (f)
                                  (catch :default e
                                    (println "glitter-uikit: scheduled work failed:" e))))
                           jobs)))
                 [:pointer] :void :collect-safe)
        ;; CFRunLoopSourceContext on arm64 (all 8-byte fields):
        ;; version@0 info@8 retain@16 release@24 copyDescription@32
        ;; equal@40 hash@48 schedule@56 cancel@64 perform@72
        ctx (ffi/alloc 80)]
    (doseq [off [0 8 16 24 32 40 48 56 64]] (ffi/write ctx :pointer 0 off))
    (ffi/write ctx :pointer perform 72)
    (let [src (u/cf-run-loop-source-create ffi/null 0 ctx)
          rl  (u/cf-run-loop-get-main)]
      (u/cf-run-loop-add-source rl src (u/default-mode))
      {:queue queue
       :source src
       :run-loop rl})))

(defn- post-to-gui
  "Schedule zero-arg `work` on the AppKit main loop."
  [work]
  (let [{:keys [queue source run-loop]} scheduler]
    (swap! queue conj work)
    (u/cf-run-loop-source-signal source)
    (u/cf-run-loop-wake-up run-loop))
  nil)

(defn on-gui
  "Run zero-arg `work` on the AppKit main thread. Runs `work` INLINE (not async)
  when there is no GUI loop running (headless/tests), or when the calling thread
  already IS the main thread — otherwise marshals via post-to-gui (async, next
  main-loop iteration).

  The inline-when-already-main-thread case matters and is NOT what glimmer-uikit
  did: without it, every render posts asynchronously even from code already
  safely on the main thread (a click handler, or mount!'s state-atom watcher
  when state changes from inside the app), breaking any caller that expects a
  synchronous read-back after render. glitter.app carries the identical branch,
  added there after a live finding during its final whole-branch review."
  [work]
  (cond
    (not @gui-loop-running?) (work)
    (= (Thread/currentThread) @main-thread) (work)
    :else (post-to-gui work)))

(defn schedule!
  "Schedule zero-arg `f` on the AppKit main loop, always asynchronously.
  Unlike on-gui this never runs inline, so a caller already on the main thread
  can queue work for the NEXT loop iteration — which is how the smokes step
  through a settled widget tree."
  [f]
  (post-to-gui f)
  nil)

(defn quit!
  "Stop the running NSApplication. Posts a wake-up event so the stop takes
  effect even when the loop is idle."
  []
  (let [app (u/shared-application)]
    (u/stop-app! app)
    (u/post-event-at-start! app (u/application-defined-event)))
  nil)

(defn- run*
  [on-activate opts]
  (let [{:keys [title width height auto-quit-ms]
         :or {title "glitter"
              width 400
              height 300}} opts
        _   (u/objc-autorelease-pool-push)
        app (u/shared-application)]
    (u/set-activation-policy! app u/ACTIVATION-REGULAR)
    (u/set-app-delegate! app w/invoker)
    (let [win (u/window-new title width height)]
      ;; CORRECTION (found by P4.T4's main-thread smoke, 2026-08-20): these two
      ;; resets MUST happen BEFORE on-activate, not after it.
      ;;
      ;; glitter.app sets them immediately before g_application_run and that is
      ;; correct THERE, because its on-activate is a foreign-callable wired to
      ;; GTK's "activate" signal — it fires from INSIDE the running loop, so the
      ;; flags are already set by the time it runs. AppKit has no such signal
      ;; indirection: run* calls on-activate eagerly and directly, before
      ;; [NSApp run]. Copying glitter's textual ordering without accounting for
      ;; that difference left both flags unset for the whole of on-activate, so
      ;; EVERY on-gui call during it took branch 1 (inline) regardless of thread.
      ;;
      ;; For a main-thread write that is harmless — branch 2 now covers it and
      ;; still runs inline, so synchronous read-back after render is unchanged.
      ;; For a WORKER-thread write — an nREPL eval, a background fetch
      ;; completing during mount — inline meant mutating AppKit off the main
      ;; thread, which is exactly the violation the three-way branch exists to
      ;; prevent. With the flags set first, that case takes branch 3 and is
      ;; marshalled onto the run loop, draining once [NSApp run] starts.
      ;;
      ;; The try now spans on-activate too, so the finally still clears the flag
      ;; if on-activate throws.
      (reset! main-thread (Thread/currentThread))
      (reset! gui-loop-running? true)
      (try
        (on-activate win)
        (u/window-center! win)
        (u/window-show! win)
        (u/activate! app)
        (when auto-quit-ms (w/auto-quit! app auto-quit-ms))
        (u/run-app! app)
        (finally (reset! gui-loop-running? false))))))

(defn run
  "Run an AppKit application. A window is created and `on-activate` (a fn of one
  arg — the NSWindow pointer) is called so the caller can mount its own root
  content into it. Blocks until the app quits.

  Options:
    :title         window title        (default \"glitter\")
    :width         window width in px  (default 400)
    :height        window height in px (default 300)
    :auto-quit-ms  if set, quit the loop after this many milliseconds
                   (smoke/automated tests).
    :app-id        accepted and IGNORED — AppKit has no GApplication id. Present
                   so example code can move between glitter and glitter-uikit
                   unchanged.

  AppKit requires its event loop on the process main thread. Hops onto jolt's
  main-thread pump asynchronously via jolt.host/call-on-main-thread-async when
  resolvable (an nREPL session's primordial thread parks there), so the eval
  that started the app returns and the session stays live; runs inline and
  blocks until the app quits otherwise (plain `jolt run`)."
  [on-activate & {:as opts}]
  (let [start (fn [] (run* on-activate opts))]
    (if-let [hop (resolve 'jolt.host/call-on-main-thread-async)]
      (hop start)
      (start))))
