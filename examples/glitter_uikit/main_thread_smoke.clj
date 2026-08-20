(ns glitter-uikit.main-thread-smoke
  "Automated cross-thread smoke: does a state change made from a NON-main thread
  actually reach the views, ON THE MAIN THREAD?

  AppKit rejects off-main-thread view mutation outright, and an nREPL-driven dev
  session — a headline Jolt workflow, and the whole reason the CFRunLoopSource
  scheduler exists — hits this on the very first swap!.

  The load-bearing assertion is WHICH THREAD `view` ran on, not merely that the
  label updated. An unmarshalled watcher still updates the label; it just does
  it from the worker thread, which IS the violation — so a text-only assertion
  would pass with the bug present and prove nothing. `view` therefore records
  its own thread, and this requires it to equal the AppKit main thread. It also
  asserts the worker really was a different thread, so the whole thing cannot
  pass vacuously if `future` ever ran inline.

  Run via `jolt main-thread-smoke`. Needs a GUI session."
  (:require [glitter-uikit.app :as app]
            [glitter-uikit.appkit :as appkit]
            [glitter-uikit.ffi :as u]
            [glitter-uikit.widget :as w]
            [glitter.core :as core]))

(defonce state (atom {:txt "initial"}))

;; Which thread the reconciler last invoked `view` on — the property under test.
;; Reset to nil immediately before the cross-thread swap, so the initial mount's
;; (correctly main-thread) render cannot mask a bad one.
(defonce render-thread (atom nil))

(defn view [{:keys [txt]}]
  (reset! render-thread (Thread/currentThread))
  [:vbox {:spacing 4} [:label {:label txt}]])

(core/set-dispatch! (fn [_ _] nil))

(defn- root-stack [window]
  (u/array-get (u/objc-msg-send-0 (u/window-content window) (u/sel "subviews")) 0))

(defn -main [& _]
  (let [failures (atom [])
        ;; Whether the scheduled callback below ever actually ran. Every
        ;; other assertion lives INSIDE that callback, so if the
        ;; CFRunLoopSource never drains it (a regression in the scheduler
        ;; itself), `failures` stays empty and this smoke would print :PASS
        ;; vacuously — exactly the failure mode it exists to catch. Checked
        ;; after app/run returns, so it can't be skipped by an early quit.
        ran? (atom false)
        record! (fn [ok? label] (when-not ok? (swap! failures conj label)))]
    (app/run
     (fn [window]
       (appkit/mount! window view state)
       (let [main-t (Thread/currentThread)
             worker-t (atom nil)]
         (reset! render-thread nil)
         @(future
            (reset! worker-t (Thread/currentThread))
            (reset! state {:txt "from-worker"}))
         ;; The watcher posted a render onto the loop; queue the read-back
         ;; BEHIND it. CFRunLoopSource jobs drain FIFO, so by the time this
         ;; runs the render has happened.
         (app/schedule!
          (fn []
            (reset! ran? true)
            (record! (not= main-t @worker-t) "worker-really-was-another-thread")
            (record! (= main-t @render-thread) "view-rendered-on-the-main-thread")
            (record! (= ["from-worker"]
                        (mapv u/control-string (w/stack-children (root-stack window))))
                     "label-updated")))))
     :title "main-thread smoke" :width 260 :height 140 :auto-quit-ms 1200)
    (record! @ran? "schedule!-callback-ran")
    (println :failures @failures)
    (when (seq @failures) (println :FAIL @failures) (System/exit 1))
    (println :PASS)))
