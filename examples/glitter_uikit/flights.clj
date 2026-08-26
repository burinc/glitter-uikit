(ns glitter-uikit.flights
  "The 7GUIs 'Flight Booker' task
  (https://eugenkiss.github.io/7guis/tasks/#flight-booker) over AppKit — a
  drop-down choosing one-way/return, two date fields, and a Book button whose
  enabled state depends on both fields' validity and, for return flights, their
  relative order. Per the spec, 'the primary challenge lies on modelling
  constraints between widgets... and within a widget.'

  Ported from glitter's examples/glitter/flights.clj. As with temperature.clj,
  the domain half — parse-date, format-date, get-form-state — is carried across
  unchanged, because it is pure Clojure over tick with no toolkit in it. tick
  arrives transitively through glitter's own jolt-lang/time dependency; verified
  live here that t/today answers the machine's zone (2026-08-26, not UTC), that
  a dd.MM.yyyy parse/format round-trips exactly, and that t/< orders two dates.

  Renderer-forced deviations, all visible in the view:

  1. app/run takes no :app-id (a GApplication identifier, no AppKit counterpart).
  2. :class [\"error\"] does nothing here. glitter colours an ill-formatted date
     field red through GTK CSS; AppKit has no stylesheet layer and this
     renderer's :class prop is accepted-and-ignored (a documented v1 limitation
     — see docs/guide/limitations.md). The 7GUIs spec explicitly wants the
     invalid state VISIBLE, so rather than silently lose it, each field carries
     an inline :label that appears only when that field is ill-formatted, using
     :markup — which this renderer does implement. The :class prop is kept
     alongside it so the view still reads as glitter's.
  3. :width-chars on a :label is a wrapping hint here, not a width (it routes to
     setPreferredMaxLayoutWidth:, ffi.clj), so the label column is not forced to
     a fixed width the way GTK's :width-chars forces it. Left in for parity; the
     rows simply size to their content.

  Uses the :drop-down tag (NSPopUpButton) added after v1's nine tags, with
  glitter's own :selected-changed event name so this view is renderer-agnostic.

  Run: jolt -M:flights or bb flights. Needs a display; close the window to exit."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [glitter-uikit.app :as app]
            [glitter-uikit.appkit :as appkit]
            [glitter.core :as core]
            [glitter.nexus.registry :as nxr]
            [tick.core :as t]))

(def ^:private date-formatter (t/formatter "dd.MM.yyyy"))

(defn parse-date
  "Parses DD.MM.YYYY strictly, returning nil for anything ill-formatted.

  NOT a bare t/parse-date call: glitter verified that t/parse-date is LENIENT
  under this Jolt port — '27.03.2014x' parses to 2014-03-27 ignoring the
  trailing garbage, 'not-a-date' parses to -0001-11-30, and '31.02.2014' rolls
  over to 2014-03-03. The round-trip (parse, reformat with the SAME formatter,
  reject unless it matches the trimmed input exactly) is what actually makes the
  spec's 'T is coloured red when ill-formatted' rule work."
  [s]
  (when (string? s)
    (let [trimmed (str/trim s)]
      (when (seq trimmed)
        (try
          (let [d (t/parse-date trimmed date-formatter)]
            (when (= trimmed (t/format date-formatter d))
              d))
          (catch Exception _ nil))))))

(defn format-date [d]
  (t/format date-formatter d))

(defonce state
  (atom {:type :one-way
         :departure-date nil                                ;; nil = "today"
         :return-date nil                                   ;; nil = departure's value
         :booked? false}))

(defn get-form-state
  "Pure domain logic — the 'separation of domain and presentation logic' the
  spec calls out by name. `today` is read fresh each render rather than
  snapshotted at load, so the demo stays correct across a day boundary."
  [{:keys [type departure-date return-date]}]
  (let [today (format-date (t/today))
        departure-value (or departure-date today)
        departure-parsed (parse-date departure-value)
        departure-invalid? (and departure-date (nil? departure-parsed))
        roundtrip? (= type :roundtrip)
        return-value (or return-date departure-value)
        return-parsed (parse-date return-value)
        return-invalid? (and roundtrip? return-date (nil? return-parsed))
        return-before-departure? (and roundtrip?
                                      (not return-invalid?)
                                      (not departure-invalid?)
                                      (t/< return-parsed departure-parsed))]
    {:type type
     :departure {:value departure-value
                 :invalid? departure-invalid?}
     :return {:value return-value
              :disabled? (not roundtrip?)
              :invalid? return-invalid?}
     :book-disabled? (boolean (or departure-invalid? return-invalid? return-before-departure?))}))

(defn- field-row
  "A label + date field, plus the red note that stands in for glitter's :class
  (deviation 2 in the ns docstring).

  The note is APPENDED rather than rendered as an empty label in the valid case:
  an always-present [:label {:label \"\"}] still occupies a row and leaves a
  visible gap under every field. `into` with a `when` contributes zero children
  when the field is valid.

  :width-request 140 is what keeps the field visible and un-truncated. Without a
  real width the :entry is compressed to zero by its sibling label, and the
  :homogeneous / :hexpand / :halign :fill routes were each measured and each did
  nothing here. :width-request installs an actual autolayout width constraint
  (ffi.clj set-width!), which is the only one that held."
  [label value error? disabled? path]
  (into [:vbox {:spacing 2}
         [:hbox {:spacing 8}
          [:label {:label label
                   :xalign 0.0
                   :valign :center}]
          [:entry {:text value
                   :width-request 140
                   :valign :center
                   :sensitive (not disabled?)
                   :class (if error? ["error"] [])
                   :on {:change [[:effect/assoc-in [path] [:glitter/value]]]}}]]]
        (when error?
          [[:label {:markup "<span color='#ff6b6b'>not a valid DD.MM.YYYY date</span>"
                    :halign :start}]])))

(defn view [state]
  (if (:booked? state)
    (let [{:keys [type departure return]} (get-form-state state)]
      [:vbox {:spacing 12
              :margin 16}
       [:label {:markup "<span size='xx-large' weight='bold'>Flight Booker</span>"
                :halign :start}]
       [:label {:label (str "You have booked a " (name type) " flight on " (:value departure)
                            (when (= type :roundtrip) (str ", returning on " (:value return)))
                            ".")
                :halign :start
                :wrap true}]
       [:button {:label "Try again"
                 :on {:click [[:effect/assoc-in [:booked?] false]
                              [:effect/assoc-in [:type] :one-way]]}}]])
    (let [{:keys [type departure return book-disabled?]} (get-form-state state)]
      [:vbox {:spacing 12
              :margin 16}
       [:label {:markup "<span size='xx-large' weight='bold'>Flight Booker</span>"
                :halign :start}]
       [:drop-down {:items ["one-way flight" "return flight"]
                    :selected (if (= type :roundtrip) 1 0)
                    :on {:selected-changed
                         [[:effect/assoc-in [:type] [:fmt/nth [:one-way :roundtrip] [:glitter/value]]]]}}]
       (field-row "Departure:" (:value departure) (:invalid? departure) false :departure-date)
       (field-row "Return:" (:value return) (:invalid? return) (:disabled? return) :return-date)
       ;; :halign :fill on the LAST child sets the whole vbox's alignment (the
       ;; last child carrying one wins — widget.clj), which is what makes every
       ;; row span the window instead of shrinking to its own text.
       [:button {:label "Book"
                 :sensitive (not book-disabled?)
                 :halign :fill
                 :on {:click [[:effect/assoc-in [:booked?] true]]}}]])))

(nxr/register-effect! :effect/assoc-in
                      (fn [_ system path v] (swap! system assoc-in path v)))

(nxr/register-placeholder! :glitter/value
                           (fn [event] (get-in event [:glitter/dom-event :glitter/value])))

;; :selected-changed carries the popup's selected INDEX (appkit.clj's
;; signal-value table), which this maps onto the domain's :one-way/:roundtrip.
(nxr/register-placeholder! :fmt/nth (fn [_ coll idx] (nth coll idx)))

(nxr/register-system->state! deref)
(nxr/on-error (fn [_ctx {:keys [err]
                         :as error}]
                (log/error err "glitter.nexus dispatch error" (dissoc error :err))))

(core/set-dispatch!
 (fn [event actions] (nxr/dispatch state event actions)))

(defn -main [& _]
  (app/run (fn [window] (appkit/mount! window view state))
           :title "glitter-uikit · Flight Booker" :width 460 :height 260))
