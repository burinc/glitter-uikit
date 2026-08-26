(ns glitter-uikit.widgets
  "Every widget tag this renderer registers, in one window.

  `counter.clj` and `todo.clj` between them exercise most of the registry, but
  two registered tags — :separator and :scrolled — had no runnable
  demonstration anywhere in the repo before this file. That is what this demo
  is for: it is the visual index of the widget layer, so a reader can see what
  `widget.clj`'s nine specs actually look like without reading the specs.

  Same model as counter.clj, not todo.clj: one top-level state atom, a pure
  state -> hiccup view, handlers as DATA dispatched through one global fn.
  todo.clj's glitter.nexus machinery is deliberately absent — the subject here
  is the widgets, and nexus would be a second thing to learn at the same time.

  Two gotchas this file has to respect, both documented in AGENTS.md:

  - :vbox / :hbox are named explicitly and never a bare :box. A bare [:box …]
    is VERTICAL under glitter.gtk but HORIZONTAL here (NSStackView's own
    default), so a literal port of a glitter view renders rotated 90°.
  - :frame and :scrolled are SINGLE-CHILD containers (:container :frame and
    :scrolled in widget.clj), so each wraps exactly one :vbox rather than
    taking a list of children directly."
  (:require [clojure.string :as str]
            [glitter-uikit.app :as app]
            [glitter-uikit.appkit :as appkit]
            [glitter.core :as core]))

(defonce state
  (atom {:draft ""
         :loud? false
         :items ["first item" "second item" "third item"
                 "fourth item" "fifth item" "sixth item"]
         ;; second section — the controls added after v1's nine tags
         :choice 1
         :level 6.0
         :steps 3.0
         :on? true
         :query ""
         :secret ""}))

(defn- item-row [text]
  [:label {:label (str "•  " text)
           :halign :start}])

(defn- one-dp
  "One decimal place without a format spec — keeps a label stable as a slider
  sweeps."
  [n]
  (str (/ (Math/round (* 10.0 (double n))) 10.0)))

(defn view [{:keys [draft loud? items choice level steps on? query secret]}]
  [:vbox {:spacing 12
          :margin 16}
   [:label {:markup "<b>glitter-uikit widgets</b>"
            :halign :start}]
   [:label {:label "Every tag the renderer registers, in one window."
            :halign :start}]

   ;; :separator — registered in widget.clj, takes no props at all.
   [:separator {}]

   ;; :label is passed and does nothing visible: frame-spec forwards it to
   ;; NSBox setTitle:, but no title renders under this renderer — todo.clj's
   ;; [:frame {:label "N remaining"}] is title-less in its committed screenshot
   ;; too. Kept anyway, so the prop is exercised rather than quietly avoided.
   [:frame {:label "Controls"}
    [:vbox {:spacing 8
            :margin 12}
     [:hbox {:spacing 8}
      [:label {:label "Item"
               :valign :center}]
      [:entry {:text draft
               :placeholder "Type a name, then Add…"
               :hexpand true
               :valign :center
               ;; Both are value-bearing: appkit.clj's signal-value registers
               ;; [:entry :change] and [:entry :activate], so :glitter/value
               ;; carries the field's current string to the dispatch fn.
               :on {:change   [[:action/draft]]
                    :activate [[:action/add]]}}]]
     [:checkbutton {:label "Add items in CAPITALS"
                    :active loud?
                    ;; [:checkbutton :toggled] is value-bearing too, and its
                    ;; :glitter/value is a boolean rather than a string.
                    :on {:toggled [[:action/loud]]}}]
     [:hbox {:spacing 8}
      [:button {:label "Add"
                :on {:click [[:action/add]]}}]
      [:button {:label "Clear list"
                :on {:click [[:action/clear]]}}]
      [:button {:label "Disabled"
                :sensitive false
                :tooltip "Shows :sensitive false — this button is inert."}]]]]

   [:separator {}]

   ;; The controls added after v1's nine tags. :level is deliberately read by
   ;; THREE of them at once — :scale drives it while :progress-bar and :level-bar
   ;; display it — so dragging the slider shows one state key re-rendering every
   ;; widget that reads it, which is the whole model in one row.
   [:frame {:label "Added after v1"}
    [:vbox {:spacing 8
            :margin 12}
     [:hbox {:spacing 8}
      [:label {:label "drop-down"
               :width-request 96
               :valign :center}]
      [:drop-down {:items ["first choice" "second choice" "third choice"]
                   :selected choice
                   :valign :center
                   :on {:selected-changed [[:action/choice]]}}]]
     [:hbox {:spacing 8}
      [:label {:label "scale"
               :width-request 96
               :valign :center}]
      [:scale {:min 0
               :max 10
               :value level
               :width-request 200
               :valign :center
               :on {:value-changed [[:action/level]]}}]
      [:label {:label (one-dp level)
               :width-request 40
               :valign :center}]]
     [:hbox {:spacing 8}
      [:label {:label "progress-bar"
               :width-request 96
               :valign :center}]
      [:progress-bar {:fraction (/ level 10.0)
                      :width-request 200
                      :valign :center}]]
     [:hbox {:spacing 8}
      [:label {:label "level-bar"
               :width-request 96
               :valign :center}]
      [:level-bar {:min-value 0
                   :max-value 10
                   :value level
                   :width-request 200
                   :valign :center}]]
     [:hbox {:spacing 8}
      [:label {:label "spin-button"
               :width-request 96
               :valign :center}]
      [:spin-button {:min 0
                     :max 10
                     :step 1
                     :value steps
                     :valign :center
                     :on {:value-changed [[:action/steps]]}}]
      [:label {:label (one-dp steps)
               :width-request 40
               :valign :center}]
      [:label {:label "switch"
               :valign :center}]
      [:switch {:active on?
                :valign :center
                :on {:toggled [[:action/switch]]}}]]
     [:hbox {:spacing 8}
      [:label {:label "search-entry"
               :width-request 96
               :valign :center}]
      [:search-entry {:text query
                      :placeholder "search…"
                      :width-request 140
                      :valign :center
                      :on {:change [[:action/query]]}}]
      ;; A password field shows bullets, so the label beside it is the only way
      ;; to see that the state behind it really is changing.
      [:password-entry {:text secret
                        :placeholder "secret"
                        :width-request 140
                        :valign :center
                        :on {:change [[:action/secret]]}}]]
     [:hbox {:spacing 8}
      [:label {:label "image"
               :width-request 96
               :valign :center}]
      ;; :icon-name resolves a NAMED system image, so the demo needs no asset
      ;; file committed beside it.
      [:image {:icon-name "NSApplicationIcon"
               :width-request 32
               :valign :center}]
      [:label {:label (str "query=" (pr-str query)
                           "  secret=" (apply str (repeat (count secret) "•")))
               :valign :center}]]]]

   [:separator {}]

   ;; :scrolled — the other tag nothing else demonstrates. Its single child is
   ;; the document view; the list is deliberately longer than the area is tall
   ;; so the scroller actually has something to scroll.
   ;;
   ;; NOT wrapped in a :frame: an NSBox's title does not render under this
   ;; renderer (todo.clj's [:frame {:label "N remaining"}] is title-less in its
   ;; committed screenshot too), so a frame here would add a box and no label.
   ;; A plain :label above the list says the same thing and actually shows.
   [:label {:label (str (count items)
                        (if (= 1 (count items)) " item" " items")
                        " in a :scrolled viewport")
            :halign :start}]
   [:scrolled {:vexpand true}
    [:vbox {:spacing 4
            :margin 8}
     ;; ONE hiccup vector, not [[...]]. A seq (from map/for) is spliced into the
     ;; children; a VECTOR is read as a single element, so [[:label …]] puts a
     ;; vector in tag position. glitter.hiccup's hiccup? check then rejects it
     ;; and renders it as a stringified literal instead of throwing — the exact
     ;; silent bug class AGENTS.md convention #1 describes. It showed up as the
     ;; raw "[[:label {:markup ...}]]" text on screen after Clear list.
     (if (empty? items)
       [:label {:markup "<span color='#888888'>List is empty — add one above.</span>"
                :halign :start}]
       (map item-row items))]]])

(defn- add-draft [{:keys [draft loud?]
                   :as s}]
  (let [t (str/trim (or draft ""))]
    (if (str/blank? t)
      s
      (-> s
          (update :items conj (if loud? (str/upper-case t) t))
          (assoc :draft "")))))

(defn execute-actions [event actions]
  ;; glitter.core/build-event-map nests the renderer's raw event map under
  ;; :glitter/dom-event, and appkit.clj's dispatcher is what puts :glitter/value
  ;; in that raw map. todo.clj reaches the same value through a nexus
  ;; placeholder; this is the same path without the indirection.
  (let [value (get-in event [:glitter/dom-event :glitter/value])]
    (doseq [[kind] actions]
      (case kind
        :action/draft (swap! state assoc :draft value)
        :action/loud  (swap! state assoc :loud? (boolean value))
        :action/add   (swap! state add-draft)
        :action/clear (swap! state assoc :items [])
        :action/choice (swap! state assoc :choice (or value 0))
        :action/level  (swap! state assoc :level (or value 0.0))
        :action/steps  (swap! state assoc :steps (or value 0.0))
        :action/switch (swap! state assoc :on? (boolean value))
        :action/query  (swap! state assoc :query (or value ""))
        :action/secret (swap! state assoc :secret (or value ""))
        nil))))

(core/set-dispatch! execute-actions)

(defn -main [& _]
  (app/run (fn [window] (appkit/mount! window view state))
           :title "glitter-uikit widgets" :width 660 :height 780))
