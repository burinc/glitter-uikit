(ns glitter-uikit.appkit-test
  "Headless tests for the renderer's pure parts — the signal-value table and the
  el-atom shape create-element produces. The end-to-end render is covered by the
  live smokes, which need a GUI session and so cannot guard CI."
  (:require [clojure.test :refer [deftest is testing]]
            [glitter-uikit.appkit :as appkit]
            [glitter-uikit.ffi :as u]
            [glitter-uikit.widget :as w]
            [glitter.protocols :as proto]))

(deftest signal-value-table-covers-the-value-bearing-tags
  (testing "an entry's :change carries the field's current text"
    (let [f (@appkit/signal-value [:entry :change])
          entry (w/create! :entry {:text "typed"})]
      (is (some? f))
      (is (= "typed" (f entry)))))
  (testing "a checkbutton's :toggled carries a real boolean, not an NSInteger"
    (let [f (@appkit/signal-value [:checkbutton :toggled])
          cb (w/create! :checkbutton {:label "x" :active true})]
      (is (some? f))
      (is (true? (f cb)))
      (w/apply-props! :checkbutton cb {:active false})
      (is (false? (f cb)))))
  (testing "a tag with no value-bearing event has no entry"
    (is (nil? (@appkit/signal-value [:button :click])))))

(deftest register-signal-value-is-open
  (testing "an extension can add a value-bearing event without editing appkit"
    (appkit/register-signal-value! :label :click (fn [_] :sentinel))
    (is (= :sentinel ((@appkit/signal-value [:label :click]) nil)))
    (swap! appkit/signal-value dissoc [:label :click])))

(deftest create-element-produces-a-tracking-atom
  (let [r (appkit/renderer)
        el (proto/create-element r "label" nil)]
    (testing "el is an atom holding the tag, view pointer, and empty child/handler maps"
      (is (= :label (:tag @el)))
      (is (pos? (:view @el)))
      (is (= [] (:children @el)))
      (is (= {} (:handlers @el))))
    (testing "create-element receives nil options — props arrive via set-attribute"
      ;; The label was created with NO props, so its string is empty until
      ;; set-attribute runs. This is the ctor/apply invariant in test form.
      (is (= "" (u/control-string (:view @el))))
      (proto/set-attribute r el "label" "after" nil)
      (is (= "after" (u/control-string (:view @el)))))))

(deftest text-nodes-become-labels
  (let [r (appkit/renderer)
        el (proto/create-text-node r "bare string")]
    (is (= :label (:tag @el)))
    (is (= "bare string" (:text @el)))
    (is (= "bare string" (u/control-string (:view @el))))))
