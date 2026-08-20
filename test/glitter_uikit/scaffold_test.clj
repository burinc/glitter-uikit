(ns glitter-uikit.scaffold-test
  "Proves the project resolves: glitter (the :local/root dependency) is on the
  classpath and its toolkit-agnostic half loads. If this fails, nothing else in
  the repo can work — deps.edn or the sibling checkout is wrong."
  (:require [clojure.test :refer [deftest is testing]]
            [glitter.protocols :as proto]))

(deftest glitter-dependency-resolves
  (testing "glitter.protocols/IRender is reachable through the :local/root dep"
    (is (some? proto/IRender))
    ;; CORRECTION (pre-dispatch scan, 2026-08-20): a Jolt protocol map's :name
    ;; is an UN-namespaced symbol whose whole name part is the literal string
    ;; "glitter.protocols/IRender" — (namespace ...) on it returns nil. The
    ;; reader literal 'glitter.protocols/IRender parses to ns="glitter.protocols"
    ;; name="IRender", so symbol = between the two is ALWAYS false. An earlier
    ;; draft asserted that equality and would have failed the very first
    ;; `jolt -M:test` of the arc. Verified live under jolt v0.7.16 against this
    ;; exact glitter checkout: the assertion below returns true.
    (is (= "glitter.protocols/IRender" (name (:name proto/IRender)))))
  (testing "IRender declares the 19 methods this renderer must implement"
    ;; A Jolt protocol map exposes {:jolt/type :methods :name} and has NO
    ;; :sigs key — :sigs is a JVM-Clojure implementation detail that does
    ;; not carry over. Verified live against this exact glitter checkout
    ;; before this test was written; an earlier draft asserting on :sigs
    ;; would have silently compared against an empty set.
    (let [methods (set (keys (:methods proto/IRender)))]
      (is (= 19 (count methods)))
      (is (contains? methods :create-element))
      (is (contains? methods :set-event-handler))
      (is (contains? methods :insert-before))))
  (testing "IMemory is a separate protocol, folded into the same reify"
    (is (= #{:remember :recall} (set (keys (:methods proto/IMemory)))))))
