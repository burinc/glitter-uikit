(ns glitter-uikit.todo
  "A task board demo — the AppKit sibling of glitter's examples/glitter/todo.clj,
  and a port of glimmer-uikit's examples/glimmer_uikit/todo.clj. Exercises
  derived counts computed inline from one state atom (no reaction — glitter has
  no reactive-derivation primitive; the whole view is re-run on every change),
  an entry (:change / :activate, with a placeholder), checkbutton toggles, and
  list rendering inside a frame.

  Contrast with glimmer-uikit's original: there, :done toggling and the draft
  entry are component-local ratoms mutated by closures captured per-row at
  render time. Here ALL state lives in one top-level atom, the view is a pure
  function of it, and every handler is DATA — an action tuple carrying whatever
  the closure used to close over (the row index, the new entry text) —
  dispatched through glitter.nexus.

  This file mirrors glitter's own todo.clj's view shape and state model —
  same tags (both already use :vbox/:hbox rather than bare :box, so this
  file doesn't hit the box-orientation divergence documented in NOTICE.md's
  Known gaps), same actions, same nexus dispatch. It is NOT a byte-for-byte
  port apart from requires, though: the docstrings, inline comments, and
  prop-map formatting differ throughout (a ~150-line diff) — only the
  renderer-facing shape (view, state, actions) is what the seam guarantees
  stays unchanged.

  Run: jolt -M:todo. Needs a GUI session; close the window to exit."
  (:require [clojure.tools.logging :as log]
            [glitter-uikit.app :as app]
            [glitter-uikit.appkit :as appkit]
            [glitter.core :as core]
            [glitter.nexus.registry :as nxr]))

(defonce state
  (atom {:tasks [{:text "Try the glitter-uikit counter demo"
                  :done true}
                 {:text "Toggle a task below"
                  :done false}
                 {:text "Add one of your own"
                  :done false}]
         :draft ""}))

;; Called as a plain function — (stat-card n label), NOT [stat-card n label].
;; glitter's hiccup (ported from Replicant) has no function-as-tag convention:
;; glitter.hiccup/hiccup? requires a KEYWORD in position 0, so a vector whose
;; first element is a function value falls through to being treated as an opaque
;; child and stringified. Replicant's real component mechanism is
;; glitter.alias/defalias with a qualified-keyword tag.
(defn- stat-card [n label]
  [:vbox {:spacing 0
          :margin-start 14
          :margin-end 14
          :margin-top 10
          :margin-bottom 10}
   [:label {:markup (str "<span size='xx-large' weight='bold'>" n "</span>")
            :halign :start}]
   [:label {:markup (str "<span color='#888888'>" label "</span>")
            :halign :start}]])

;; The toggle handler carries idx as DATA ([:action/toggle idx]) rather than
;; closing over it.
(defn- task-row [idx {:keys [text done]}]
  [:hbox {:spacing 8}
   [:checkbutton {:active done
                  :valign :center
                  :on {:toggled [[:action/toggle idx]]}}]
   [:label {:markup (if done (str "<s>" text "</s>") text)
            :halign :start
            :hexpand true
            :valign :center}]])

(defn view [{:keys [tasks draft]}]
  (let [total (count tasks)
        done  (count (filter :done tasks))
        left  (count (remove :done tasks))]
    [:vbox {:spacing 16
            :margin 20}
     [:label {:markup "<span size='xx-large' weight='bold'>Tasks</span>"
              :halign :start}]

     [:hbox {:spacing 8}
      (stat-card total "total")
      (stat-card done  "done")
      (stat-card left  "left")]

     [:frame {:label (str left " remaining")
              :vexpand true}
      [:vbox {:spacing 6
              :margin 12}
       (if (empty? tasks)
         [[:label {:markup "<span color='#888888'>Nothing here yet — add a task below.</span>"
                   :halign :start}]]
         (for [[idx t] (map-indexed vector tasks)]
           (task-row idx t)))]]

     [:hbox {:spacing 8}
      [:entry {:text draft
               :placeholder "Add a task…"
               :hexpand true
               :valign :center
               :on {:change   [[:effect/assoc-in [:draft] [:glitter/value]]]
                    :activate [[:action/add-task]]}}]
      [:button {:label "Add"
                :valign :center
                :on {:click [[:action/add-task]]}}]]]))

;; :action/toggle and :action/add-task need to READ current state (the row's
;; CURRENT :done to `not`; :draft's current value to decide whether to add
;; anything) — so they are nexus ACTION-EXPANSIONS, not plain effects.
(nxr/register-effect! :effect/assoc-in
                      (fn [_ system path v] (swap! system assoc-in path v)))

;; The renderer's raw event map is nested under :glitter/dom-event by
;; glitter.core/build-event-map — see step 1. :glitter/value is put there by
;; glitter-uikit.appkit's signal-value entry for [:entry :change].
(nxr/register-placeholder! :glitter/value
                           (fn [event] (get-in event [:glitter/dom-event :glitter/value])))

(nxr/register-action! :action/toggle
                      (fn [state idx]
                        [[:effect/assoc-in [:tasks idx :done] (not (get-in state [:tasks idx :done]))]]))

(nxr/register-action! :action/add-task
                      (fn [{:keys [draft tasks]}]
                        (if (seq draft)
                          [[:effect/assoc-in [:tasks] (conj tasks {:text draft
                                                                   :done false})]
                           [:effect/assoc-in [:draft] ""]]
                          [])))

(nxr/register-system->state! deref)
(nxr/on-error (fn [_ctx {:keys [err]
                         :as error}]
                (log/error err "glitter.nexus dispatch error" (dissoc error :err))))

(core/set-dispatch!
 (fn [event actions] (nxr/dispatch state event actions)))

(defn -main [& _]
  (app/run (fn [window] (appkit/mount! window view state))
           :title "glitter-uikit · tasks" :width 480 :height 420))
