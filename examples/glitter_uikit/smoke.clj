(ns glitter-uikit.smoke
  "End-to-end proof that a glitter view function renders into a real NSWindow
  and that a state-atom write re-renders it.

  Asserts against the LIVE AppKit tree (arrangedSubviews + each field's
  stringValue), never against glitter-uikit.appkit's own :children bookkeeping —
  bookkeeping would agree with itself and pass even if no AppKit call landed.

  Run via `jolt smoke`. Needs a GUI session. Exits non-zero on failure."
  (:require [glitter-uikit.app :as app]
            [glitter-uikit.appkit :as appkit]
            [glitter-uikit.ffi :as u]
            [glitter-uikit.widget :as w]
            [glitter.core :as core]))

(defonce state (atom {:count 0}))

(defn view [{:keys [count]}]
  [:vbox {:spacing 8}
   [:label {:label (str "Count: " count)}]
   [:label {:label "anchor"}]])

(core/set-dispatch! (fn [_ _] nil))

(defn- live-texts
  "The mounted vbox's label texts, read back through AppKit. :window is a
  single-child container, so the mounted [:vbox ...] is the window's ONE
  content subview — descend one level before reading arranged subviews."
  [window]
  (let [content (u/window-content window)
        vbox (u/array-get (u/objc-msg-send-0 content (u/sel "subviews")) 0)]
    (mapv u/control-string (w/stack-children vbox))))

(defn -main [& _]
  (let [before (atom nil)
        after  (atom nil)]
    (app/run
     (fn [window]
       (appkit/mount! window view state)
       (reset! before (live-texts window))
       (reset! state {:count 42})
       (reset! after (live-texts window)))
     :title "glitter-uikit smoke" :width 280 :height 160 :auto-quit-ms 700)
    (println :before @before)
    (println :after @after)
    (when (not= [["Count: 0" "anchor"] ["Count: 42" "anchor"]] [@before @after])
      (println :FAIL "expected [Count: 0 anchor] then [Count: 42 anchor]")
      ;; Direct call. System/exit is a static-method interop FORM, not a var,
      ;; so (resolve 'System/exit) is always nil and a resolve-guarded exit
      ;; silently never fires — the smoke would print :FAIL and exit 0.
      (System/exit 1))))
