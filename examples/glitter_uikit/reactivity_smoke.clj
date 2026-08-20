(ns glitter-uikit.reactivity-smoke
  "Non-interactive proof that a state-atom write re-renders through the live
  AppKit loop, and that a real button click dispatches an action.

  The click is synthesized with -[NSControl performClick:], which drives the
  actual target/action path glitter-uikit.appkit wired — not a direct call to
  the handler, which would prove nothing about the wiring.

  Run via `jolt reactivity-smoke`. Needs a GUI session. Exits non-zero on
  failure."
  (:require [glitter-uikit.app :as app]
            [glitter-uikit.appkit :as appkit]
            [glitter-uikit.ffi :as u]
            [glitter-uikit.widget :as w]
            [glitter.core :as core]
            [jolt.ffi :as ffi]))

(defonce state (atom {:count 0}))
(defonce dispatched (atom []))

(defn view [{:keys [count]}]
  [:vbox {:spacing 8}
   [:label {:label (str "Count: " count)}]
   [:button {:label "+ 1"
             :on {:click [[:action/inc]]}}]])

(core/set-dispatch!
 (fn [event actions]
   (swap! dispatched conj {:actions actions
                           :node? (some? (:glitter/node event))})
   (doseq [[kind] actions]
     (when (= :action/inc kind) (swap! state update :count inc)))))

(defn- root-stack [window]
  (u/array-get (u/objc-msg-send-0 (u/window-content window) (u/sel "subviews")) 0))

(defn -main [& _]
  (let [failures (atom [])
        record! (fn [ok? label] (when-not ok? (swap! failures conj label)))]
    (app/run
     (fn [window]
       (appkit/mount! window view state)
       (let [stack (root-stack window)
             [label button] (w/stack-children stack)]
         (record! (= "Count: 0" (u/control-string label)) "initial-label")

         ;; A programmatic state write must re-render in place.
         (reset! state {:count 7})
         (record! (= "Count: 7" (u/control-string (first (w/stack-children stack))))
                  "state-write-rerenders")

         ;; A REAL click through target/action must reach glitter's dispatch.
         (u/objc-msg-send-1pvoid button (u/sel "performClick:") ffi/null)
         (record! (= 8 (:count @state)) "click-dispatched-action")
         (record! (= 1 (count @dispatched)) "dispatch-called-once")
         (record! (:node? (first @dispatched)) "event-map-carries-glitter-node")))
     :title "reactivity smoke" :width 260 :height 140 :auto-quit-ms 800)
    (println :failures @failures)
    (when (seq @failures)
      (println :FAIL @failures)
      (System/exit 1))
    (println :PASS)))
