(ns glitter-uikit.timer
  "The 7GUIs 'Timer' task (https://eugenkiss.github.io/7guis/tasks/#timer) over
  AppKit — an elapsed-time gauge, a duration slider, and a reset button. Per the
  spec the challenge is 'concurrency': the gauge must advance on its own while
  staying responsive to the slider, and changing the duration must take effect
  immediately rather than at the next tick.

  Ported from glitter's examples/glitter/timer.clj, with the domain half
  (format-seconds, get-view-state) carried across unchanged. Two things differ,
  and both are about HOW it ticks rather than what it computes:

  1. glitter self-perpetuates a ONE-SHOT g_timeout_add on every tick. This
     renderer got a real repeating NSTimer instead — glitter-uikit.widget's
     every! / cancel-every!, added for this demo. A repeating timer runs until
     invalidated, so every! hands back the NSTimer as the cancellation handle
     and cancel-every! both invalidates it and drops its entry from the tick
     registry; doing only one of the two either leaks the handler or leaves a
     live timer firing into an empty registry forever.

  2. glitter routes the tick through glitter.nexus with a :nexus/system->state
     that stamps a fresh :now onto every dispatch. This file uses counter.clj's
     plain state-atom dispatch, so there is no such hook — and that makes
     glitter's own hardest-won lesson here load-bearing in a different way:
     view must read System/nanoTime ITSELF at render time, never a :now key
     from state. The state atom never stores :now, and glitter's earlier
     version that trusted (:now state) threw a NullPointerException on every
     tick — swallowed by nexus's error handling, so the process survived while
     the display silently never advanced past its first paint.

  nanoTime, not currentTimeMillis: this measures ELAPSED DURATION within one
  process run, and wall-clock time can jump under an NTP adjustment.

  The ticker is started from app/run's on-activate, which runs on the AppKit
  main thread, so its swap! reaches the reconciler through the same path any
  other main-thread state write does.

  Run: jolt -M:timer or bb timer. Needs a display; close the window to exit."
  (:require [glitter-uikit.app :as app]
            [glitter-uikit.appkit :as appkit]
            [glitter-uikit.widget :as w]
            [glitter.core :as core]))

(defn format-seconds [s]
  (let [s10 (int (* 10 s))]
    (if (= 0 (mod s10 10))
      (int (/ s10 10))
      (float (/ s10 10)))))

(defn get-view-state
  "Pure: state (including a :now the CALLER supplies) -> what the view draws.

  The :pct division coerces via (double duration) rather than reusing the
  returned :duration, so a literal integer 0 — the :scale's own :min, and the
  most natural thing a test would write — cannot hit an integer divide-by-zero.
  The returned :duration deliberately keeps its ORIGINAL type: (= 10 10.0) is
  false under Jolt, unlike JVM Clojure, so coercing it would break equality
  assertions against an int duration."
  [state]
  (let [duration (or (:duration state) 20)
        divisor (double duration)
        elapsed (min (if-let [started (:started state)]
                       (/ (- (:now state) started) 1.0e9)
                       0)
                     divisor)]
    {:pct (int (* 100 (/ elapsed divisor)))
     :elapsed (str (format-seconds elapsed) "s")
     :duration duration}))

(defonce state (atom {:duration 20
                      :started nil
                      :tick 0}))

(defn view [state]
  ;; :now is read HERE, fresh, not taken from the state atom — see ns docstring
  ;; point 2. :tick is not read at all; its only job is to be a value that
  ;; changes, so the reconciler re-renders on every timer fire.
  (let [{:keys [pct duration elapsed]} (get-view-state (assoc state :now (System/nanoTime)))]
    [:vbox {:spacing 12
            :margin 16}
     [:label {:markup "<span size='xx-large' weight='bold'>Timer</span>"
              :halign :start}]
     [:hbox {:spacing 8}
      [:label {:label "Elapsed:"
               :width-request 90
               :valign :center}]
      [:progress-bar {:fraction (/ pct 100.0)
                      :width-request 240
                      :valign :center}]]
     [:label {:label elapsed
              :halign :start}]
     [:hbox {:spacing 8}
      [:label {:label "Duration:"
               :width-request 90
               :valign :center}]
      [:scale {:min 0
               :max 60
               :value duration
               :width-request 240
               :valign :center
               :on {:value-changed [[:action/duration]]}}]
      [:label {:label (str duration "s")
               :width-request 44
               :valign :center}]]
     [:button {:label "Reset"
               :on {:click [[:action/reset]]}}]]))

(defn execute-actions [event actions]
  (let [value (get-in event [:glitter/dom-event :glitter/value])]
    (doseq [[kind] actions]
      (case kind
        ;; The slider hands back a double; keep it as one. Rounding to an int
        ;; here would make the label jump while the knob moves smoothly.
        :action/duration (swap! state assoc :duration (int (or value 0)))
        :action/reset    (swap! state assoc :started (System/nanoTime))
        nil))))

(core/set-dispatch! execute-actions)

(defn -main [& _]
  (app/run (fn [window]
             (swap! state assoc :started (System/nanoTime))
             (appkit/mount! window view state)
             ;; 100ms: fast enough that the bar looks continuous, slow enough
             ;; that a full re-render per tick is not the bottleneck.
             (w/every! 100 (fn [] (swap! state update :tick inc))))
           :title "glitter-uikit · Timer" :width 460 :height 260))
