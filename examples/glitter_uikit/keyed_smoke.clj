(ns glitter-uikit.keyed-smoke
  "Automated keyed-reconciliation smoke against the LIVE AppKit tree — mirrors
  glitter's examples/glitter/keyed.clj. Mounts a keyed list, reorders it, and
  reads the stack's actual arrangedSubviews back to prove the real
  insertArrangedSubview:atIndex: calls landed, not merely that the algorithm's
  decisions were right in the abstract.

  Also asserts the no-suppression property this renderer is built on: a
  PROGRAMMATIC :active change must not fire :on {:toggled ...}. glitter.gtk
  needs a suppression set because gtk_check_button_set_active synchronously
  re-emits 'toggled'; AppKit does not fire actions for programmatic setState:.
  If that assertion ever fails, glitter-uikit.widget needs a suppression set
  after all and the ns docstring's claim is wrong.

  Run via `jolt keyed-smoke`. Needs a GUI session."
  (:require [glitter-uikit.app :as app]
            [glitter-uikit.appkit :as appkit]
            [glitter-uikit.ffi :as u]
            [glitter-uikit.widget :as w]
            [glitter.core :as core]
            [jolt.ffi :as ffi]))

(defonce state (atom {:order ["a" "b" "c"]}))

(def labels {"a" "Item A"
             "b" "Item B"
             "c" "Item C"})

(defn view [{:keys [order]}]
  (into [:vbox {:spacing 4}]
        (for [k order]
          [:label {:glitter/key k
                   :label (labels k)}])))

(core/set-dispatch! (fn [_ _] nil))

(defn- root-stack [window]
  (u/array-get (u/objc-msg-send-0 (u/window-content window) (u/sel "subviews")) 0))

(defn- no-suppression-needed?
  "Build a checkbutton OUTSIDE the reconciled tree, wire a toggle counter the
  way appkit/set-event-handler does, then change :active programmatically three
  times. AppKit must not fire the action for any of them."
  []
  (let [hits (atom 0)
        cb (w/create! :checkbutton {:active false})]
    (u/control-target! cb w/invoker)
    (u/control-action! cb (u/sel "fire:"))
    (swap! w/actions assoc-in [cb :toggled] (fn [_] (swap! hits inc)))
    (w/apply-props! :checkbutton cb {:active true})
    (w/apply-props! :checkbutton cb {:active false})
    (w/apply-props! :checkbutton cb {:active true})
    (let [quiet-after-programmatic? (zero? @hits)]
      ;; CORRECTION (P4.T3 review, 2026-08-20): non-vacuity guard. The zero
      ;; above is only meaningful if this control's fire path is actually ALIVE.
      ;; A broken invoker/fire: registration — class-add-method silently
      ;; failing, a future refactor of GlitterTarget — would ALSO leave hits at
      ;; 0, and this fn would report "no suppression needed" for entirely the
      ;; wrong reason, falsely closing the one question it exists to answer.
      ;; reactivity_smoke.clj proves the same action path via a :button, but a
      ;; cross-FILE dependency can be silently broken while both smokes stay
      ;; green. So prove it HERE: a real click must fire the very handler the
      ;; programmatic writes did not. This mirrors handler_cleanup_smoke.clj's
      ;; own `nested-button-registered-a-handler` guard.
      (u/objc-msg-send-1pvoid cb (u/sel "performClick:") ffi/null)
      (let [fire-path-alive? (pos? @hits)]
        (w/forget-view! cb)
        (and quiet-after-programmatic? fire-path-alive?)))))

(defn -main [& _]
  (let [failures (atom [])
        record! (fn [ok? label] (when-not ok? (swap! failures conj label)))]
    (app/run
     (fn [window]
       (appkit/mount! window view state)
       (let [stack (root-stack window)
             texts (fn [] (mapv u/control-string (w/stack-children stack)))
             ;; view pointers BEFORE the reorder, keyed by their text, so we can
             ;; prove reuse rather than recreation
             before (into {} (map (juxt u/control-string identity) (w/stack-children stack)))]
         (record! (= ["Item A" "Item B" "Item C"] (texts)) "baseline")

         (reset! state {:order ["c" "a" "b"]})
         (record! (= ["Item C" "Item A" "Item B"] (texts)) "reorder-order")
         (record! (= 3 (count (w/stack-children stack))) "reorder-no-duplicates")
         (record! (= (mapv before ["Item C" "Item A" "Item B"])
                     (w/stack-children stack))
                  "reorder-reuses-views")

         (record! (no-suppression-needed?) "programmatic-active-does-not-dispatch")

         ;; End deterministically rather than waiting out :auto-quit-ms. The
         ;; quit is SCHEDULED, not called inline: on-activate runs before
         ;; [NSApp run] has started, and -[NSApplication stop:] only takes
         ;; effect on a loop that is already running. :auto-quit-ms stays as a
         ;; backstop for the quit being posted but never running. NOTE it does
         ;; NOT rescue an assertion above throwing: run* calls on-activate
         ;; BEFORE it arms auto-quit! and before [NSApp run], so an exception
         ;; here propagates out of run* with the timer never armed — the
         ;; process crashes rather than hangs.
         (app/schedule! app/quit!)))
     :title "keyed smoke" :width 260 :height 200 :auto-quit-ms 900)
    (println :failures @failures)
    (when (seq @failures) (println :FAIL @failures) (System/exit 1))
    (println :PASS)))
