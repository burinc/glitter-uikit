(ns glitter-uikit.handler-cleanup-smoke
  "Pins fix 4 at the reconciler level: unmounting a subtree must drop every
  handler registration it held, including GRANDCHILDREN.

  glitter.core never calls remove-event-handler for an unmounted node — the DOM
  lets GC handle it — so glitter-uikit.appkit's remove-child walks the removed
  subtree itself. Without that walk the registries grow without bound, and
  because AppKit reuses freed addresses a newly allocated view can land on a
  dead view's address and inherit its handler.

  Run via `jolt handler-cleanup-smoke`. Needs a GUI session."
  (:require [glitter-uikit.app :as app]
            [glitter-uikit.appkit :as appkit]
            [glitter-uikit.widget :as w]
            [glitter.core :as core]))

;; The button sits one level DOWN from the child being removed, so this
;; exercises the recursive walk rather than just the removed node itself.
(defonce state (atom {:show? true}))

(defn view [{:keys [show?]}]
  [:vbox {:spacing 4}
   [:label {:label "anchor"}]
   (when show?
     [:hbox {:spacing 4}
      [:button {:label "nested"
                :on {:click [[:action/noop]]}}]])])

(core/set-dispatch! (fn [_ _] nil))

(defn -main [& _]
  (let [failures (atom [])
        record! (fn [ok? label] (when-not ok? (swap! failures conj label)))]
    (app/run
     (fn [window]
       (appkit/mount! window view state)
       (let [registered (count @w/actions)]
         (record! (pos? registered) "nested-button-registered-a-handler")
         (reset! state {:show? false})
         (record! (zero? (count @w/actions))
                  (str "grandchild-registration-dropped (still "
                       (count @w/actions) " after unmount)"))))
     :title "handler cleanup smoke" :width 260 :height 140 :auto-quit-ms 700)
    (println :failures @failures)
    (when (seq @failures) (println :FAIL @failures) (System/exit 1))
    (println :PASS)))
