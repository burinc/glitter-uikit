(ns glitter-uikit.temperature
  "The 7GUIs 'Temperature Converter' task
  (https://eugenkiss.github.io/7guis/tasks/#temp) over AppKit — two linked
  numeric fields (Celsius, Fahrenheit); editing one immediately updates the
  other. Per the spec: widgets 'indirectly linked to each other... in an
  equational way... must have their values updated eagerly... The exception
  being the widget that caused the change.'

  Ported from glitter's own examples/glitter/temperature.clj. The domain logic
  (fahrenheit->celsius, celsius->fahrenheit, set-temperature, format-number,
  parse-number) is carried across unchanged — it is pure, host-level Clojure
  with no toolkit in it, which is the whole point of glitter's renderer split.
  Only three things differ, all forced by the renderer:

  1. :width-request is a GTK size-request prop this renderer does not
     implement, and it has NO working equivalent for an :entry. entry-spec's
     :width-chars / :max-width-chars route to setPreferredMaxLayoutWidth:
     (ffi.clj:344), which is a text-WRAPPING hint, not a width constraint or a
     minimum — measured: an :entry carrying :width-chars 12 still stretched to
     fill its row and squeezed its sibling :entry to zero width. The two fields
     row is laid out :homogeneous instead, which forces equal shares.
  2. app/run takes no :app-id — that is a GApplication identifier with no
     AppKit counterpart.
  3. The requires point at glitter-uikit.app / glitter-uikit.appkit. Everything
     under glitter.* (core, nexus.registry) is shared verbatim.

  Why this task is a good fit for THIS renderer specifically: it writes back to
  the very field being typed in, on every keystroke. That is the exact case
  entry-spec's only-when-different guard exists for — unconditionally re-setting
  an NSTextField's stringValue mid-typing resets the insertion point. The guard
  is present here (widget.clj's entry-spec) but was MISSING from the
  glimmer-uikit original this port descends from; a verbatim port would have
  silently lost it, and this demo is where that loss would be felt first.

  Run: jolt -M:temperature or bb temperature. Needs a display; close the
  window to exit."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [glitter-uikit.app :as app]
            [glitter-uikit.appkit :as appkit]
            [glitter.core :as core]
            [glitter.nexus.registry :as nxr]))

(defn fahrenheit->celsius [f]
  (* (- f 32) (/ 5.0 9)))

(defn celsius->fahrenheit [c]
  (+ (* c (/ 9.0 5)) 32))

(defn set-temperature
  "Which field is the SOURCE and which is DERIVED depends on which one the user
  just edited, so this is a nexus action-expansion rather than a bare
  :effect/assoc-in.

  Returns an EXPLICIT no-op ([], no effects, no swap!, no re-render) when
  neither field parsed. Upstream replicant-7uis' bare
  `(or celsius (fahrenheit->celsius fahrenheit))` shape would call
  fahrenheit->celsius on nil and throw — harmlessly caught by nexus, but
  relying on exception handling as the invalid-input strategy is the
  silent-swallow risk glitter's own nexus review flagged. On a no-op the
  just-typed invalid text stays in the field's live buffer untouched and the
  other field keeps its last valid value."
  [{:keys [celsius fahrenheit]}]
  (cond
    (some? celsius)    [[:effect/assoc-in [:celsius] celsius]
                        [:effect/assoc-in [:fahrenheit] (celsius->fahrenheit celsius)]]
    (some? fahrenheit) [[:effect/assoc-in [:celsius] (fahrenheit->celsius fahrenheit)]
                        [:effect/assoc-in [:fahrenheit] fahrenheit]]
    :else               []))

(defn format-number
  "Displays a whole-number double without a trailing \".0\" (100.0 -> \"100\");
  anything else, including a value too large for (long n) to hold, falls back
  to str. The (long n) probe itself can throw — finite-but-huge doubles like
  1e300 are valid parse-number output — so it is wrapped rather than guarded by
  a Long/MAX_VALUE range check whose availability under Jolt is unverified."
  [n]
  (try
    (if (and (number? n) (== n (long n)))
      (str (long n))
      (str n))
    (catch Exception _ (str n))))

(defn parse-number
  "Parses a just-typed field's raw text into a finite double, or nil for
  anything that isn't a usable temperature — blank/non-numeric text, AND
  non-finite results. Double/parseDouble accepts \"Infinity\"/\"NaN\" and
  overflows like \"1e400\" (-> ##Inf) without throwing, so try/catch alone does
  not reject them.

  The finiteness check deliberately avoids Double/isFinite: glitter verified
  live that it does not resolve under Jolt (Chez-Scheme host, not the JVM) —
  `No matching field or method: Double/isFinite`. The two portable checks used
  instead work on any host: (= parsed parsed) excludes NaN, since IEEE-754 NaN
  is never equal to itself, and ##Inf / ##-Inf are reader literals Jolt
  supports directly."
  [s]
  (when (string? s)
    (let [trimmed (str/trim s)]
      (when (seq trimmed)
        (let [parsed (try (Double/parseDouble trimmed) (catch Exception _ nil))]
          (when (and parsed
                     (= parsed parsed)
                     (not= parsed ##Inf)
                     (not= parsed ##-Inf))
            parsed))))))

(defonce state
  (atom {:celsius 0.0
         :fahrenheit 32.0}))

(defn view [state]
  [:vbox {:spacing 12
          :margin 16}
   [:label {:markup "<span size='xx-large' weight='bold'>Temperature Converter</span>"
            :halign :start}]
   ;; :homogeneous true -> NSStackView DISTRIBUTION-FILL-EQUALLY (box-spec), so
   ;; the four children get equal widths. Without it the first :entry takes the
   ;; whole row's slack and the second is compressed to ZERO width — measured,
   ;; and :hexpand true on both does NOT prevent it, because nothing here gives
   ;; a field a minimum width (see docstring, deviation 1).
   [:hbox {:spacing 8
           :homogeneous true}
    [:entry {:text (format-number (:celsius state))
             :valign :center
             :on {:change [[:action/set-temperature {:celsius [:fmt/number [:glitter/value]]}]]}}]
    [:label {:label "Celsius ="
             :valign :center}]
    [:entry {:text (format-number (:fahrenheit state))
             :valign :center
             :on {:change [[:action/set-temperature {:fahrenheit [:fmt/number [:glitter/value]]}]]}}]
    [:label {:label "Fahrenheit"
             :valign :center}]]])

(nxr/register-effect! :effect/assoc-in
                      (fn [_ system path v] (swap! system assoc-in path v)))

;; :glitter/value is put in the raw event map by glitter-uikit.appkit's
;; dispatcher, for the [:entry :change] pair its signal-value atom registers;
;; glitter.core/build-event-map nests that raw map under :glitter/dom-event.
(nxr/register-placeholder! :glitter/value
                           (fn [event] (get-in event [:glitter/dom-event :glitter/value])))

(nxr/register-placeholder! :fmt/number
                           (fn [_ s] (parse-number s)))

(nxr/register-action! :action/set-temperature
                      (fn [_state temps] (set-temperature temps)))

(nxr/register-system->state! deref)
(nxr/on-error (fn [_ctx {:keys [err]
                         :as error}]
                (log/error err "glitter.nexus dispatch error" (dissoc error :err))))

(core/set-dispatch!
 (fn [event actions] (nxr/dispatch state event actions)))

(defn -main [& _]
  (app/run (fn [window] (appkit/mount! window view state))
           :title "glitter-uikit · Temperature Converter" :width 540 :height 140))
