(ns glitter-uikit.crud
  "The 7GUIs 'CRUD' task (https://eugenkiss.github.io/7guis/tasks/#crud) over
  AppKit — a prefix filter, a selectable list of names, Name/Surname fields, and
  Create/Update/Delete. The spec's stated challenge is 'separation of domain and
  presentation logic', which here is `get-people`: one pure function, filtering
  and sorting, called by the view and by nothing else.

  glitter's own crud.clj renders the list with :list-box (GtkListBox). This
  renderer has no such tag, and the obvious conclusion — that CRUD needs
  NSTableView first — turned out to be wrong. A list box is functionally a
  SCROLLABLE COLUMN OF SELECTABLE ROWS, and that is buildable from tags this
  renderer already has: a :scrolled wrapping a :vbox of one :button per person.
  Selecting is just a click handler carrying the row's id as data.

  What that costs, stated plainly rather than glossed:

  - Rows look like buttons, because they are. A real NSTableView would give
    proper selection highlighting, keyboard navigation and alternating row
    colours for free. The selected row is marked with a caret in its label
    instead — visible, but not the platform's own selection affordance.
  - There is no keyboard navigation of the list at all.

  Both are presentation, not behaviour: every rule the 7GUIs spec states is
  satisfied. When :list-box / NSTableView does land, this view swaps its list
  section and nothing else changes — which is itself the point the renderer
  split keeps making.

  Uses counter.clj's plain state-atom dispatch rather than glitter.nexus. The
  three actions all need to read current state before deciding what to do, which
  is exactly what glitter needed nexus's action-EXPANSION layer for; a plain
  dispatch fn reads @state directly and needs no such layer.

  Run: jolt -M:crud or bb crud. Needs a display; close the window to exit."
  (:require [clojure.string :as str]
            [glitter-uikit.app :as app]
            [glitter-uikit.appkit :as appkit]
            [glitter.core :as core]))

(defn get-people
  "The whole domain half: filter by surname prefix, then sort. Pure, and the one
  function the spec's 'separation of domain and presentation logic' refers to."
  [{:keys [people]
    prefix :filter}]
  (let [f (some-> prefix str/trim not-empty str/lower-case)]
    (cond->> people
      f (filter (fn [p] (str/starts-with? (str/lower-case (:family-name p)) f)))
      :then (sort-by (juxt :family-name :given-name)))))

(defonce state
  (atom {:people [{:id 0
                   :given-name "Hans"
                   :family-name "Emil"}
                  {:id 1
                   :given-name "Max"
                   :family-name "Mustermann"}
                  {:id 2
                   :given-name "Roman"
                   :family-name "Tisch"}]
         :next-id 3
         :filter ""
         :given-name ""
         :family-name ""
         :selected-id nil}))

(defn- person-row [{:keys [id given-name family-name]} selected?]
  ;; The caret is the selection affordance — see the ns docstring. A leading
  ;; space keeps the unselected rows aligned with the selected one.
  [:button {:label (str (if selected? "▸ " "   ") family-name ", " given-name)
            :on {:click [[:action/select id]]}}])

(defn- field-row [label value path]
  [:hbox {:spacing 8}
   [:label {:label label
            :width-request 76
            :valign :center}]
   [:entry {:text value
            :width-request 150
            :valign :center
            :on {:change [[:action/field path]]}}]])

(defn view [{:keys [filter given-name family-name selected-id]
             :as state}]
  (let [people (get-people state)
        selected? (some? selected-id)]
    [:vbox {:spacing 12
            :margin 16}
     [:label {:markup "<span size='xx-large' weight='bold'>CRUD</span>"
              :halign :start}]
     (field-row "Filter:" filter :filter)
     [:hbox {:spacing 12}
      [:frame {:label "People"}
       [:scrolled {:vexpand true}
        (into [:vbox {:spacing 2
                      :margin 6}]
              (if (seq people)
                (map (fn [p] (person-row p (= (:id p) selected-id))) people)
                [[:label {:markup "<span color='#888888'>No match for that filter.</span>"
                          :halign :start}]]))]]
      [:vbox {:spacing 8}
       (field-row "Name:" given-name :given-name)
       (field-row "Surname:" family-name :family-name)]]
     [:hbox {:spacing 8}
      [:button {:label "Create"
                :on {:click [[:action/create]]}}]
      [:button {:label "Update"
                :sensitive selected?
                :on {:click [[:action/update]]}}]
      [:button {:label "Delete"
                :sensitive selected?
                :on {:click [[:action/delete]]}}]]]))

(defn- create [{:keys [given-name family-name next-id]
                :as s}]
  ;; The spec does not say what an empty name should do. Refusing beats creating
  ;; a blank row that can then only be found by clearing the filter.
  (if (and (str/blank? given-name) (str/blank? family-name))
    s
    (-> s
        (update :people conj {:id next-id
                              :given-name (str/trim given-name)
                              :family-name (str/trim family-name)})
        (update :next-id inc)
        (assoc :given-name "" :family-name "" :selected-id next-id))))

(defn- update-selected [{:keys [selected-id given-name family-name]
                         :as s}]
  (if (nil? selected-id)
    s
    (update s :people
            (fn [ps] (mapv (fn [p] (if (= (:id p) selected-id)
                                     (assoc p :given-name (str/trim given-name)
                                            :family-name (str/trim family-name))
                                     p))
                           ps)))))

(defn- delete-selected [{:keys [selected-id]
                         :as s}]
  (if (nil? selected-id)
    s
    (-> s
        (update :people (fn [ps] (filterv (fn [p] (not= (:id p) selected-id)) ps)))
        ;; Clearing the selection matters: leaving a dangling id would keep
        ;; Update and Delete enabled against a row that no longer exists.
        (assoc :selected-id nil :given-name "" :family-name ""))))

(defn- select [s id]
  ;; Selecting fills the name fields, which is what makes Update meaningful.
  (if-let [p (first (clojure.core/filter (fn [p] (= (:id p) id)) (:people s)))]
    (assoc s :selected-id id
           :given-name (:given-name p)
           :family-name (:family-name p))
    s))

(defn execute-actions [event actions]
  (let [value (get-in event [:glitter/dom-event :glitter/value])]
    (doseq [[kind arg] actions]
      (case kind
        :action/field  (swap! state assoc arg (or value ""))
        :action/select (swap! state select arg)
        :action/create (swap! state create)
        :action/update (swap! state update-selected)
        :action/delete (swap! state delete-selected)
        nil))))

(core/set-dispatch! execute-actions)

(defn -main [& _]
  (app/run (fn [window] (appkit/mount! window view state))
           :title "glitter-uikit · CRUD" :width 560 :height 420))
