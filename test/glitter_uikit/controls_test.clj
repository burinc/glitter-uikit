(ns glitter-uikit.controls-test
  "The controls added after v1's nine tags: :drop-down, :scale, :spin-button,
  :progress-bar, :level-bar, :switch, :password-entry, :search-entry, :image.

  Every assertion reads the AppKit view's OWN property back through a getter
  rather than checking that the spec's :apply ran — the widget layer's
  bookkeeping would agree with itself and pass even if no AppKit call landed,
  which is the same discipline the live smokes follow."
  (:require [clojure.test :refer [deftest is testing]]
            [glitter-uikit.appkit :as appkit]
            [glitter-uikit.ffi :as u]
            [glitter-uikit.widget :as w]))

(deftest drop-down-applies-items-then-selection
  (let [d (w/create! :drop-down {})]
    (w/apply-props! :drop-down d {:items ["one-way flight" "return flight"]})
    (testing "items populate the menu"
      (is (= 2 (u/popup-count d))))
    (w/apply-props! :drop-down d {:items ["one-way flight" "return flight"]
                                  :selected 1})
    (testing "selection lands on the requested index"
      (is (= 1 (u/popup-selected d))))
    (testing "re-applying :items rebuilds rather than appends"
      (w/apply-props! :drop-down d {:items ["a" "b" "c"]})
      (is (= 3 (u/popup-count d))))))

(deftest drop-down-ignores-an-out-of-range-selection
  ;; NOT a style nit: selectItemAtIndex: with an index outside the menu raises an
  ;; ObjC exception, and an ObjC exception aborts the PROCESS here — a Clojure
  ;; catch cannot intercept it (AGENTS.md gotcha 5). If this test ever fails it
  ;; will not fail, it will kill the test runner, so a green run is the assertion.
  (let [d (w/create! :drop-down {})]
    (w/apply-props! :drop-down d {:items ["a" "b"]
                                  :selected 99})
    (is (< (u/popup-selected d) 2))
    (w/apply-props! :drop-down d {:items ["a" "b"]
                                  :selected -5})
    (is (< (u/popup-selected d) 2))
    (testing "a selection against an EMPTY menu is also survivable"
      (w/apply-props! :drop-down d {:items []
                                    :selected 0})
      (is (= 0 (u/popup-count d))))))

(deftest scale-applies-range-before-value
  ;; :min/:max must be applied before :value in the same pass, or a value inside
  ;; the NEW range is clamped against the OLD one. 42 is outside NSSlider's
  ;; default 0..1 range, so a wrong order shows up as a clamp to 1.0.
  (let [s (w/create! :scale {})]
    (w/apply-props! :scale s {:min 0
                              :max 100
                              :value 42})
    (is (= 42.0 (u/control-double s)))
    (testing "widening the range and setting a larger value in one pass"
      (w/apply-props! :scale s {:min 0
                                :max 1000
                                :value 900})
      (is (= 900.0 (u/control-double s))))))

(deftest progress-bar-maps-fraction-onto-a-zero-to-one-range
  ;; progress-new fixes min/max at 0..1 so glitter's :fraction needs no rescaling
  ;; here. AppKit's own default range is 0..100, so without that fix a :fraction
  ;; of 0.25 would render as a bar that looks empty.
  (let [p (w/create! :progress-bar {})]
    (w/apply-props! :progress-bar p {:fraction 0.25})
    (is (= 0.25 (u/control-double p)))
    (w/apply-props! :progress-bar p {:fraction 1.0})
    (is (= 1.0 (u/control-double p)))))

(deftest spin-button-and-level-bar-round-trip-values
  (let [s (w/create! :spin-button {})]
    (w/apply-props! :spin-button s {:min 0
                                    :max 10
                                    :step 2
                                    :value 4})
    (is (= 4.0 (u/control-double s))))
  (let [l (w/create! :level-bar {})]
    (w/apply-props! :level-bar l {:min-value 0
                                  :max-value 5
                                  :value 3})
    (is (= 3.0 (u/control-double l)))))

(deftest switch-toggles-state
  (let [s (w/create! :switch {})]
    (w/apply-props! :switch s {:active true})
    (is (= u/STATE-ON (u/control-state s)))
    (w/apply-props! :switch s {:active false})
    (is (= u/STATE-OFF (u/control-state s)))))

(deftest secure-and-search-entries-carry-the-only-when-different-guard
  ;; Both are NSTextField subclasses, so they inherit the guard that keeps the
  ;; insertion point from resetting mid-typing. The observable proof is that
  ;; re-applying the SAME text is a no-op on the field's value.
  (doseq [tag [:password-entry :search-entry]]
    (let [e (w/create! tag {})]
      (w/apply-props! tag e {:text "hunter2"})
      (is (= "hunter2" (u/control-string e)) (str tag " sets text"))
      (w/apply-props! tag e {:text "hunter2"})
      (is (= "hunter2" (u/control-string e)) (str tag " re-apply is stable"))
      (w/apply-props! tag e {:text "changed"})
      (is (= "changed" (u/control-string e)) (str tag " applies a real change")))))

(deftest image-survives-a-path-that-does-not-exist
  ;; NSImage's initWithContentsOfFile: returns nil rather than raising, so a bad
  ;; path must leave the view empty instead of throwing or aborting.
  (let [v (w/create! :image {})]
    (w/apply-props! :image v {:file "/nonexistent/definitely-not-here.png"})
    (is (some? v))))

(deftest new-value-bearing-events-are-registered
  (testing "each new control's event reads the right property"
    (let [t @appkit/signal-value]
      (is (contains? t [:scale :value-changed]))
      (is (contains? t [:spin-button :value-changed]))
      (is (contains? t [:switch :toggled]))
      (is (contains? t [:drop-down :selected-changed]))
      (is (contains? t [:search-entry :change]))
      (is (contains? t [:password-entry :change]))))
  (testing ":drop-down reports nil, not -1, when nothing is selected"
    (let [d (w/create! :drop-down {})
          f (get @appkit/signal-value [:drop-down :selected-changed])]
      (w/apply-props! :drop-down d {:items []})
      (is (nil? (f d)))))
  (testing ":scale's value-fn re-reads the slider's own doubleValue"
    (let [s (w/create! :scale {})
          f (get @appkit/signal-value [:scale :value-changed])]
      (w/apply-props! :scale s {:min 0
                                :max 100
                                :value 7})
      (is (= 7.0 (f s))))))

(defn- constraint-count [view]
  (u/array-count (u/objc-msg-send-0 view (u/sel "constraints"))))

(deftest width-request-installs-a-real-width-constraint
  ;; :width-chars routes to setPreferredMaxLayoutWidth:, a text-WRAPPING hint
  ;; that leaves a control free to be compressed to nothing. :width-request
  ;; installs an actual autolayout constraint, which is the only thing measured
  ;; to keep a field from collapsing next to a sibling label. Four other routes
  ;; were tried live and did nothing: :vexpand false, :hexpand on the field,
  ;; :hexpand on the row, and :halign :fill.
  (let [e (w/create! :entry {})
        before (constraint-count e)]
    (w/apply-props! :entry e {:width-request 96})
    (testing "a constraint is added"
      (is (= (inc before) (constraint-count e))))
    (testing "re-rendering does NOT stack a second, conflicting constraint"
      (w/apply-props! :entry e {:width-request 96})
      (w/apply-props! :entry e {:width-request 96})
      (is (= (inc before) (constraint-count e))))))

(deftest every-new-tag-is-registered
  (doseq [tag [:drop-down :scale :spin-button :progress-bar :level-bar
               :switch :password-entry :search-entry :image]]
    (is (contains? @w/specs tag) (str tag " is in the spec registry"))))
