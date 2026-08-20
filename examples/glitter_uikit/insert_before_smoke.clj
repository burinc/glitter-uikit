(ns glitter-uikit.insert-before-smoke
  "Automated insert-before smoke against the LIVE AppKit tree.

  Pins three things glitter.core's insert-before depends on, neither of the
  first two of which glimmer-uikit could do (it has no insert-child-after! at
  all):

  1. A genuinely NEW child inserted mid-list lands at the right index.
  2. An EXISTING child repositioned by a keyed reorder MOVES rather than
     duplicating — measured that -[NSStackView insertArrangedSubview:atIndex:]
     auto-moves an already-arranged subview, which is why this renderer needs
     no equivalent of glitter.gtk's two-branch insert-before.
  3. A FORWARD keyed move (the moved child currently sits BEFORE its target
     sibling) lands at the sibling's un-incremented index, not `(inc i)`.
     insertArrangedSubview:atIndex: is remove-then-insert internally, and its
     index is interpreted against the POST-removal array, so incrementing
     unconditionally overshoots by one slot in exactly this case. Cases 1 and
     2 above (3-element scenarios) never exercised this: case 1 moves a
     FRESH (not-yet-arranged) child, and case 2 moves an arranged child to a
     nil sibling (first position) — neither takes the sibling-lookup branch
     where the bug lived. This is a real defect this port shipped and fixed
     during final review; see `insert-child-after!` in `widget.clj`.

  A count assertion accompanies case 2. Note precisely what it does and does
  not add: `reorder-order` compares with full Clojure vector `=`, which is
  count-sensitive, so a duplicated subview (4 entries where 3 are expected)
  already fails THAT assertion on length alone. The count check is not
  catching a case the order check would miss — it exists to give a specific,
  unambiguous failure label (\"reorder-no-duplicates\") when the failure IS a
  duplication, instead of a generic order mismatch that a reader then has to
  diagnose.

  Run via `jolt insert-before-smoke`. Needs a GUI session."
  (:require [glitter-uikit.app :as app]
            [glitter-uikit.appkit :as appkit]
            [glitter-uikit.ffi :as u]
            [glitter-uikit.widget :as w]
            [glitter.core :as core]))

(defonce state (atom {:items ["a" "c"]}))

(defn view [{:keys [items]}]
  (into [:vbox {:spacing 4}]
        (for [i items]
          [:label {:glitter/key i
                   :label i}])))

(core/set-dispatch! (fn [_ _] nil))

(defn- root-stack [window]
  (u/array-get (u/objc-msg-send-0 (u/window-content window) (u/sel "subviews")) 0))

(defn- texts [stack] (mapv u/control-string (w/stack-children stack)))

(defn -main [& _]
  (let [failures (atom [])
        ;; Records expected vs actual (not just a bare ok?), so a failure
        ;; prints what was expected and what actually happened instead of
        ;; just a label a reader then has to go reproduce to diagnose.
        record! (fn [expected actual label]
                  (when (not= expected actual)
                    (swap! failures conj {:label label :expected expected :actual actual})))]
    (app/run
     (fn [window]
       (appkit/mount! window view state)
       (let [stack (root-stack window)]
         (record! ["a" "c"] (texts stack) "baseline")

         ;; 1. a genuinely new keyed child inserted BETWEEN two existing ones
         (reset! state {:items ["a" "b" "c"]})
         (record! ["a" "b" "c"] (texts stack) "fresh-insert-mid-list")

         ;; 2. a keyed REORDER of existing children — must move, not duplicate
         (reset! state {:items ["c" "a" "b"]})
         (record! ["c" "a" "b"] (texts stack) "reorder-order")
         (record! 3 (count (w/stack-children stack)) "reorder-no-duplicates")

         ;; 3. a keyed FORWARD move — the moved child ("1") currently sits
         ;; BEFORE its target sibling ("3"). Measured: glitter.core's diff
         ;; calls insert-child-after! with child "1", sibling "3" for exactly
         ;; this transition.
         (reset! state {:items ["1" "2" "3" "4"]})
         (record! ["1" "2" "3" "4"] (texts stack) "forward-move-setup")
         (reset! state {:items ["2" "3" "1" "4"]})
         (record! ["2" "3" "1" "4"] (texts stack) "forward-move")))
     :title "insert-before smoke" :width 260 :height 180 :auto-quit-ms 800)
    (println :failures @failures)
    (when (seq @failures)
      (doseq [{:keys [label expected actual]} @failures]
        (println :FAIL label "expected" expected "got" actual))
      (System/exit 1))
    (println :PASS)))
