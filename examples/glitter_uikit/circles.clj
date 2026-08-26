(ns glitter-uikit.circles
  "The 7GUIs 'Circle Drawer' task
  (https://eugenkiss.github.io/7guis/tasks/#circle-drawer) over AppKit — click
  to place a circle, click one to select it, adjust its diameter, undo and redo.
  The spec names three challenges: undo/redo, custom drawing, and a dialog that
  edits the model.

  There is no glitter version of this task to port — glitter ships 7GUIs 1-5 and
  stops — so both the model and the rendering are original here.

  How the drawing works, since it is the part with no precedent in this
  renderer: circles are **CALayers**, not views. That is load-bearing rather
  than incidental. A CALayer takes no part in hit-testing, so a click lands on
  the canvas even when a circle sits under the pointer, and hit-testing stays
  where it belongs — a pure function over the model, `circle-at`. Sub-VIEWS
  would swallow those clicks and each would need its own custom `hitTest:` to
  opt out. A layer whose corner radius is half its side IS a circle, so there is
  no drawing code and no custom NSView subclass anywhere in this port.

  The canvas itself is an NSButton with its border off: a canvas has to receive
  clicks, and only an NSControl carries the target/action slot this renderer
  routes events through. Its `:click` reports WHERE it was clicked, in canvas
  coordinates — see glitter-uikit.appkit's signal-value table, which reads the
  pointer position fresh and converts screen -> window -> view.

  Two deliberate deviations from the spec, both visible rather than hidden:

  1. The diameter control is an inline :scale that appears when a circle is
     selected, not a right-click context menu opening a modal dialog. The
     behaviour the spec actually specifies — the diameter changes LIVE, and the
     whole adjustment is ONE undo step rather than one per movement — is
     preserved exactly; see `adjust` and `:adjusting?` below.
  2. Selection is shown by filling the circle, as the spec asks, but there is no
     right-click anywhere: this renderer wires one action per control and has no
     context-menu support.

  Run: jolt -M:circles or bb circles. Needs a display; close the window to exit."
  (:require [glitter-uikit.app :as app]
            [glitter-uikit.appkit :as appkit]
            [glitter.core :as core]))

(def ^:private default-radius 22.0)
(def ^:private canvas-w 520.0)
(def ^:private canvas-h 300.0)

(defonce state
  (atom {:circles []                                    ; [{:id :x :y :r}]
         :selected nil
         :next-id 0
         :adjusting? false                              ; coalesces a drag into one undo step
         :past []
         :future []}))

(defn circle-at
  "The TOPMOST circle containing [x y], or nil. Pure — the whole hit-test.

  Reverse order because later circles draw on top, so the last one containing
  the point is the one a person would say they clicked."
  [circles x y]
  (->> circles
       reverse
       (filter (fn [{:keys [cx cy r]}]
                 (let [dx (- x cx) dy (- y cy)]
                   (<= (+ (* dx dx) (* dy dy)) (* r r)))))
       first))

(defn- snapshot
  "Push the current drawing onto the undo stack and drop any redo future.

  Only :circles and :selected are captured — :past/:future are the history
  itself, and capturing them would nest history inside history."
  [s]
  (-> s
      (update :past conj (select-keys s [:circles :selected]))
      (assoc :future [])))

(defn undo [{:keys [past]
             :as s}]
  (if (empty? past)
    s
    (-> s
        (assoc :circles (:circles (peek past))
               :selected (:selected (peek past))
               :adjusting? false)
        (update :past pop)
        (update :future conj (select-keys s [:circles :selected])))))

(defn redo [{:keys [future]
             :as s}]
  (if (empty? future)
    s
    (-> s
        (assoc :circles (:circles (peek future))
               :selected (:selected (peek future))
               :adjusting? false)
        (update :future pop)
        (update :past conj (select-keys s [:circles :selected])))))

(defn click
  "A click either selects the circle under it, or creates one there."
  [{:keys [circles next-id]
    :as s} x y]
  (if-let [hit (circle-at circles x y)]
    (assoc s :selected (:id hit) :adjusting? false)
    (-> s
        snapshot
        (update :circles conj {:id next-id
                               :cx x
                               :cy y
                               :r default-radius})
        (update :next-id inc)
        (assoc :selected next-id :adjusting? false))))

(defn adjust
  "Set the selected circle's radius.

  The FIRST movement of an adjustment snapshots; the rest do not. That is what
  makes a whole drag one undo step rather than one per pixel — the spec's
  \"closing the dialog is a single undoable change\", expressed for an inline
  control that has no close event."
  [{:keys [selected adjusting?]
    :as s} r]
  (if (nil? selected)
    s
    (let [s (if adjusting? s (-> s snapshot (assoc :adjusting? true)))]
      (update s :circles
              (fn [cs] (mapv (fn [c] (if (= (:id c) selected) (assoc c :r (double r)) c)) cs))))))

(defn- selected-circle [{:keys [circles selected]}]
  (first (filter (fn [c] (= (:id c) selected)) circles)))

(defn view [{:keys [circles selected past future]
             :as s}]
  (let [sel (selected-circle s)]
    [:vbox {:spacing 10
            :margin 16}
     [:label {:markup "<span size='xx-large' weight='bold'>Circle Drawer</span>"
              :halign :start}]
     [:hbox {:spacing 8}
      [:button {:label "Undo"
                :sensitive (boolean (seq past))
                :on {:click [[:action/undo]]}}]
      [:button {:label "Redo"
                :sensitive (boolean (seq future))
                :on {:click [[:action/redo]]}}]
      [:label {:label (str (count circles)
                           (if (= 1 (count circles)) " circle" " circles")
                           (when sel "  ·  one selected"))
               :valign :center}]]
     [:canvas {:width-request canvas-w
               :height-request canvas-h
               :circles (mapv (fn [c] {:x (:cx c)
                                       :y (:cy c)
                                       :r (:r c)
                                       :selected? (= (:id c) selected)})
                              circles)
               :on {:click [[:action/click]]}}]
     ;; The adjuster only exists while something is selected — the spec's dialog
     ;; is modal on a selection, and this is the inline equivalent.
     (if sel
       [:hbox {:spacing 8}
        [:label {:label "Diameter:"
                 :width-request 76
                 :valign :center}]
        [:scale {:min 8
                 :max 90
                 :value (:r sel)
                 :width-request 300
                 :valign :center
                 :on {:value-changed [[:action/adjust]]}}]
        [:label {:label (str (int (* 2 (:r sel))) " px")
                 :valign :center}]]
       [:label {:markup "<span color='#888888'>Click the canvas to add a circle; click a circle to select it.</span>"
                :halign :start}])]))

(defn execute-actions [event actions]
  (let [value (get-in event [:glitter/dom-event :glitter/value])]
    (doseq [[kind] actions]
      (case kind
        ;; :canvas's value is the click point in canvas coordinates.
        :action/click  (when (map? value) (swap! state click (:x value) (:y value)))
        :action/adjust (when (number? value) (swap! state adjust value))
        :action/undo   (swap! state undo)
        :action/redo   (swap! state redo)
        nil))))

(core/set-dispatch! execute-actions)

(defn -main [& _]
  (app/run (fn [window] (appkit/mount! window view state))
           :title "glitter-uikit · Circle Drawer" :width 580 :height 460))
