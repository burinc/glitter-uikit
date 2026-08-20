(ns glitter-uikit.insert-before-smoke
  "Automated insert-before smoke against the LIVE AppKit tree.

  Pins two things glitter.core's insert-before depends on, neither of which
  glimmer-uikit could do (it has no insert-child-after! at all):

  1. A genuinely NEW child inserted mid-list lands at the right index.
  2. An EXISTING child repositioned by a keyed reorder MOVES rather than
     duplicating — measured that -[NSStackView insertArrangedSubview:atIndex:]
     has DOM-like auto-move semantics, which is why this renderer needs no
     equivalent of glitter.gtk's two-branch insert-before.

  A count assertion guards case 2: a duplicate would still satisfy an
  order check that only looks at the first N entries.

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
          [:label {:glitter/key i :label i}])))

(core/set-dispatch! (fn [_ _] nil))

(defn- root-stack [window]
  (u/array-get (u/objc-msg-send-0 (u/window-content window) (u/sel "subviews")) 0))

(defn- texts [stack] (mapv u/control-string (w/stack-children stack)))

(defn -main [& _]
  (let [failures (atom [])
        record! (fn [ok? label] (when-not ok? (swap! failures conj label)))]
    (app/run
     (fn [window]
       (appkit/mount! window view state)
       (let [stack (root-stack window)]
         (record! (= ["a" "c"] (texts stack)) "baseline")

         ;; 1. a genuinely new keyed child inserted BETWEEN two existing ones
         (reset! state {:items ["a" "b" "c"]})
         (record! (= ["a" "b" "c"] (texts stack)) "fresh-insert-mid-list")

         ;; 2. a keyed REORDER of existing children — must move, not duplicate
         (reset! state {:items ["c" "a" "b"]})
         (record! (= ["c" "a" "b"] (texts stack)) "reorder-order")
         (record! (= 3 (count (w/stack-children stack))) "reorder-no-duplicates")))
     :title "insert-before smoke" :width 260 :height 180 :auto-quit-ms 800)
    (println :failures @failures)
    (when (seq @failures) (println :FAIL @failures) (System/exit 1))
    (println :PASS)))
