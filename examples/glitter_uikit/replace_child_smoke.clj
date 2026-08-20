(ns glitter-uikit.replace-child-smoke
  "Automated replace-child smoke against the LIVE AppKit tree — the positional
  sibling of keyed_smoke.clj. Where that proves a keyed REORDER lands correctly,
  this proves a REPLACEMENT stays where it was.

  The scenario is the most ordinary update Replicant hiccup has: a text child
  whose string value changes. glitter.core handles that by calling
  IRender/replace-child, never by patching the existing view in place — so the
  new view has to go back at the replaced one's exact index.

  A stable sibling AFTER the text child is what makes a wrong position
  observable. With the text child last, an append-based implementation lands in
  the right place by accident — exactly why glimmer-uikit's bug (remove +
  addArrangedSubview:, relocating to the end) survived its own smoke suite and
  was only caught by review.

  Run via `jolt replace-child-smoke`. Needs a GUI session."
  (:require [glitter-uikit.app :as app]
            [glitter-uikit.appkit :as appkit]
            [glitter-uikit.ffi :as u]
            [glitter-uikit.widget :as w]
            [glitter.core :as core]))

(defonce state (atom {:txt "first"}))

(defn view [{:keys [txt]}]
  [:vbox {:spacing 4}
   txt
   [:label {:label "anchor"}]])

(core/set-dispatch! (fn [_ _] nil))

(defn- root-stack [window]
  (u/array-get (u/objc-msg-send-0 (u/window-content window) (u/sel "subviews")) 0))

(defn- texts [stack] (mapv u/control-string (w/stack-children stack)))

(defn -main [& _]
  (let [before (atom nil) after (atom nil)]
    (app/run
     (fn [window]
       (appkit/mount! window view state)
       (let [stack (root-stack window)]
         (reset! before (texts stack))
         (reset! state {:txt "second"})
         (reset! after (texts stack))))
     :title "replace-child smoke" :width 260 :height 140 :auto-quit-ms 700)
    (println :before @before)
    (println :after @after)
    (when (not= ["first" "anchor"] @before)
      (println :FAIL "expected initial [first anchor], got" @before)
      (System/exit 1))
    (when (not= ["second" "anchor"] @after)
      (println :FAIL "expected [second anchor], got" @after
               "— a trailing 'second' means replace-child appended instead of"
               "inserting at the replaced child's index")
      (System/exit 1))
    (println :PASS)))
