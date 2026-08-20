(ns glitter-uikit.container-test
  "Container-management tests against REAL AppKit views. These construct
  NSStackViews and NSButtons but never run an event loop, so they are safe in a
  plain `jolt -M:test` on macOS. They pin the four fixes carried in from the
  glimmer-uikit review — each corresponds to a real defect in glimmer-uikit
  v0.1.0."
  (:require [clojure.test :refer [deftest is testing]]
            [glitter-uikit.ffi :as u]
            [glitter-uikit.widget :as w]))

(defn- labelled [t] (w/create! :label {:label t}))
(defn- texts [stack] (mapv #(u/control-string %) (w/stack-children stack)))

(deftest replace-child-keeps-position
  ;; FIX 1. glimmer-uikit's :box branch is remove + addArrangedSubview:, and
  ;; add always lands at the END — silently relocating any non-final child.
  ;; A stable sibling AFTER the replaced child is what makes it observable.
  (let [stack (w/create! :box {})
        a (labelled "a") b (labelled "b") c (labelled "c")
        b2 (labelled "b2")]
    (doseq [v [a b c]] (w/append-child! :box stack v))
    (is (= ["a" "b" "c"] (texts stack)))
    (w/replace-child! :box stack b b2)
    (testing "the replacement lands at the replaced child's index, not the end"
      (is (= ["a" "b2" "c"] (texts stack))))))

(deftest insert-child-after-places-fresh-and-moves-existing
  ;; FIX 2 — insert-child-after! does not exist upstream at all; glitter.core's
  ;; insert-before requires it. Measured fact F1: one code path covers both a
  ;; fresh insert and a move, because insertArrangedSubview:atIndex: has
  ;; DOM-like auto-move semantics (unlike gtk_box_insert_child_after).
  (let [stack (w/create! :box {})
        a (labelled "a") b (labelled "b") c (labelled "c") d (labelled "d")]
    (doseq [v [a b c]] (w/append-child! :box stack v))
    (testing "a fresh view inserts after the named sibling"
      (w/insert-child-after! :box stack d a)
      (is (= ["a" "d" "b" "c"] (texts stack))))
    (testing "nil sibling means first position"
      (w/insert-child-after! :box stack c nil)
      (is (= ["c" "a" "d" "b"] (texts stack))))
    (testing "an ALREADY-arranged view is moved, not duplicated"
      (w/insert-child-after! :box stack c b)
      (is (= ["a" "d" "b" "c"] (texts stack)))
      (is (= 4 (count (w/stack-children stack)))))))

(deftest arranged-index-is-nsnotfound-safe
  ;; FIX 3. Measured F4: indexOfObject: returns NSIntegerMax for a non-member.
  ;; Measured F5: feeding NSNotFound+1 to insertArrangedSubview:atIndex: aborts
  ;; the PROCESS with an uncatchable NSException. So a nil-returning guard is a
  ;; crash fix, not a nicety — and this test passing at all proves the guard
  ;; fired, because the unguarded path would have killed the test runner.
  (let [stack (w/create! :box {})
        a (labelled "a")
        orphan (labelled "orphan")]
    (w/append-child! :box stack a)
    (is (= 0 (w/arranged-index stack a)))
    (testing "a view that is not arranged reports nil, never NSNotFound"
      (is (nil? (w/arranged-index stack orphan))))
    (testing "inserting after an unknown sibling appends instead of aborting"
      (w/insert-child-after! :box stack (labelled "z") orphan)
      (is (= ["a" "z"] (texts stack))))))

(deftest remove-child-forgets-handler-registrations
  ;; FIX 4. The upstream registries are never cleaned, so they grow without
  ;; bound AND — because AppKit reuses freed addresses — a new view can land on
  ;; a dead view's address and inherit its handler.
  (let [stack (w/create! :box {})
        btn (w/create! :button {:label "x"})]
    (w/append-child! :box stack btn)
    (swap! w/actions assoc btn {:click (fn [_] nil)})
    (is (contains? @w/actions btn))
    (w/remove-child! :box stack btn)
    (testing "removing a child drops its registry entries"
      (is (not (contains? @w/actions btn))))))
