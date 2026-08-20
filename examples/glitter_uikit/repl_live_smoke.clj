(ns glitter-uikit.repl-live-smoke
  "Regression smoke for live GUI development over nREPL.

  A label is bound to a plain state atom. A WORKER thread (standing in for an
  nREPL eval on its worker thread) mutates the atom while the app is running.
  The reactive re-render must hop onto the AppKit main loop via the
  CFRunLoopSource scheduler (gui-loop-running? marshalling in
  glitter-uikit.app), NOT reconcile inline on the worker thread — on macOS
  that off-main-thread AppKit mutation aborts.

  Marshalling is confirmed two ways:
    1. no crash (exit 0) — without marshalling, the worker-thread re-render
       touches AppKit off the main thread and aborts; and
    2. after the loop quits, the worker's mutation is reflected in a render
       (the deferred main-loop re-render applied it).

  CORRECTION (P4.T4): upstream's `fail` guards its exit with
  `(let [exit (resolve 'jolt.host/exit)] (when exit (exit 1)))`, but `resolve`
  on a static-method interop form is always nil, so upstream's smoke prints
  SMOKE FAIL and still exits 0 — unable to ever fail CI; this file calls
  System/exit directly instead.

  Auto-quits and exits 0 printing SMOKE OK; non-zero on failure."
  (:require [glitter-uikit.app :as app]
            [glitter-uikit.appkit :as appkit]
            [glitter.core :as core]
            [jolt.host :as host]))

(defonce state (atom "initial"))
(defonce observed (atom "initial"))           ; value seen by the most recent render

(defn view [v]
  (reset! observed v)
  [:label {:label v}])

(core/set-dispatch! (fn [_ _] nil))

(defn- fail [msg]
  (println "SMOKE FAIL (repl-live):" msg)
  (System/exit 1))

(defn -main [& _]
  (let [result (promise)]
    ;; Thread A — stand-in for the REPL thread that launched the app. app/run
    ;; marshals startup onto the main thread and blocks here until it quits.
    (future
      (try
        (app/run (fn [window] (appkit/mount! window view state))
                 :title "glitter-uikit repl-live smoke"
                 :width 260 :height 120
                 :auto-quit-ms 1800)
        (deliver result :ok)
        (catch :default e (deliver result [:crashed (.getMessage e)])
               (host/stop-main-pump))
        (finally (host/stop-main-pump))))
    ;; Thread B — stand-in for an nREPL eval mutating reactive state off the
    ;; main thread. Give the app time to mount + enter the AppKit run first.
    (future
      (Thread/sleep 600)
      (reset! state "updated-from-worker"))
    ;; Main thread owns the GUI loop via the pump.
    (host/run-main-pump)
    (let [r @result]
      (cond
        (not= r :ok)                (fail (str "app did not exit cleanly: " r))
        (not= "updated-from-worker" @observed)
        (fail (str "worker mutation never reached a render; observed=" @observed))
        :else                       (println "SMOKE OK (repl-live)")))))
