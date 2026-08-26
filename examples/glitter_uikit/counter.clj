(ns glitter-uikit.counter
  "A state-atom counter — the canonical Replicant-style demo over AppKit.

  Contrast with glimmer-uikit's examples/glimmer_uikit/counter.clj: there, local
  state lives in a component-scoped ratom and a click closure calls swap!
  directly. Here, ALL state lives in one top-level atom; the view is a pure
  function of it; and click handlers are DATA (:on {:click [[:action/dec]]})
  dispatched through a single global handler, never closures.

  This file mirrors glitter's own examples/glitter/counter.clj view shape
  and state model exactly — same actions, same dispatch — but is NOT
  byte-identical apart from requires: it uses :vbox/:hbox where glitter's
  original uses bare :box for both. That is not stylistic — a bare [:box …]
  defaults to VERTICAL under glitter.gtk but HORIZONTAL here (NSStackView's
  own default), so porting glitter's :box literally would render this
  counter rotated 90°. See NOTICE.md's Known gaps for the measured default
  and why it is left undeviated rather than matched to glitter.gtk's."
  (:require [glitter-uikit.app :as app]
            [glitter-uikit.appkit :as appkit]
            [glitter.core :as core]))

(defonce state (atom {:count 0}))

(defn view [{:keys [count]}]
  [:vbox {:spacing 12}
   [:label {:label (str "Count: " count)}]
   [:hbox {:spacing 8}
    [:button {:label "− 1"
              :on {:click [[:action/dec]]}}]
    [:button {:label "+ 1"
              :on {:click [[:action/inc]]}}]
    [:button {:label "reset"
              :on {:click [[:action/reset]]}}]]])

(defn execute-actions [_event actions]
  (doseq [[kind] actions]
    (case kind
      :action/inc (swap! state update :count inc)
      :action/dec (swap! state update :count dec)
      :action/reset (swap! state assoc :count 0)
      nil)))

(core/set-dispatch! execute-actions)

(defn -main [& _]
  (app/run (fn [window] (appkit/mount! window view state))
           :title "glitter-uikit counter" :width 320 :height 160))
