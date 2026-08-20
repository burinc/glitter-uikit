(ns glitter-uikit.appkit
  "The IRender/IMemory implementation of glitter.core's renderer seam, targeting
  real AppKit views via glitter-uikit.widget/glitter-uikit.ffi, plus the
  state-atom mount/render wiring (Replicant's state-atom pattern, adapted to an
  NSWindow instead of document.body).

  This is the AppKit counterpart of glitter.gtk. The two differ in three ways
  that matter:

  1. insert-before is SINGLE-BRANCH here. glitter.gtk must decide between
     `reorder-child!` and `insert-child-after!` depending on whether the child
     is already parented, because gtk_box_insert_child_after asserts an
     UNPARENTED child and no-ops with a GTK-CRITICAL otherwise. Measured live:
     -[NSStackView insertArrangedSubview:atIndex:] MOVES an already-arranged
     subview ([A B C] + insert C@0 -> [C A B], count unchanged), exactly like
     DOM insertBefore. One path covers both cases.
  2. There is no `suppressing?` guard. GTK's programmatic setters synchronously
     re-emit their own signal, so glitter.gtk must gate dispatch on a
     suppression set. AppKit does not fire action or delegate callbacks for
     programmatic setState:/setStringValue: at all.
  3. Event wiring is not connect-and-disconnect but register-and-forget.
     An NSControl has ONE target/action slot, and the ObjC IMP receives only the
     sender POINTER — so handlers live in a pointer-keyed registry
     (glitter-uikit.widget/actions and /changes) that this namespace owns
     exclusively, and mirrors onto each el atom for bookkeeping.

  IRender's el/child-node values are small tracking atoms — not raw view
  pointers — holding {:tag <hiccup tag> :view <AppKit view ptr> :children [<el>
  ...] :handlers {<event kw> <handler>}}. IMemory keys off the el atom itself
  (already a stable Clojure identity), not the pointer."
  (:require [glitter-uikit.app :as app]
            [glitter-uikit.ffi :as u]
            [glitter-uikit.widget :as w]
            [glitter.alias :as alias]
            [glitter.core :as core]
            [glitter.protocols :as proto]
            [jolt.ffi :as ffi]))

(defn- ptr [el] (:view @el))

(defonce ^:private memory (atom {}))

;; --- events ------------------------------------------------------------------
;; Which glitter event keywords route through an NSControl's single
;; target/action slot, versus the NSTextField delegate.
(def ^:private action-events #{:click :toggled :activate})

;; Which [tag event] pairs carry a value the handler wants: [tag event] ->
;; (fn [view] value). Keyed by [tag event], NOT by bare event, carried from
;; glitter.widget's own hard-won GTK finding: more than one widget type can emit
;; the same event meaning different things, and discovering that after the fact
;; cost real bugs there. No v1 AppKit tag currently collides, but the table
;; shape is what stops the next widget from re-learning it.
;;
;; Every value-fn RE-READS the view's own property rather than taking a value
;; from the callback, mirroring glitter.gtk. Safe here because AppKit updates
;; the control's property before invoking its action/delegate.
(defonce signal-value
  (atom {[:entry :change]        (fn [view] (u/control-string view))
         [:entry :activate]      (fn [view] (u/control-string view))
         [:checkbutton :toggled] (fn [view] (= u/STATE-ON (u/control-state view)))}))

(defn register-signal-value!
  "Teach the renderer to extract a value for `event` on views of type `tag`.
  Lets an extension add a value-bearing widget without editing this namespace."
  [tag event value-fn]
  (swap! signal-value assoc [tag event] value-fn)
  nil)

(defn- dispatcher
  "Wrap glitter's `handler` (which takes one event map) as the one-arg fn the
  widget layer's ObjC callbacks invoke with the acting view pointer.

  :glitter/node is the key glitter.core's own build-event-map reads on its :clj
  branch — that deviation from Replicant exists precisely so a live, non-DOM
  renderer can supply the acting element."
  [el tag event handler]
  (fn [sender]
    (let [value-fn (@signal-value [tag event])]
      (handler (cond-> {:glitter/node el
                        :glitter/appkit-view sender}
                 value-fn (assoc :glitter/value (value-fn sender)))))))

(defn- clear-target-if-unused!
  "Drop the control's target/action once no action event is registered for it,
  so a stale selector can't fire on a view glitter no longer routes."
  [view]
  (when (empty? (get @w/actions view))
    (swap! w/actions dissoc view)
    (u/control-target! view ffi/null)))

(defn renderer
  "A fresh IRender/IMemory implementation over glitter-uikit.widget. One instance
  is enough for the life of an app; `mount!` builds one per mounted window."
  []
  (reify proto/IRender
    (create-element [_ tag-name options]
      (let [tag (keyword tag-name)
            view (w/create! tag (or options {}))]
        (atom {:tag tag :view view :children [] :handlers {}})))

    (create-text-node [_ text]
      ;; AppKit has no text-node primitive; a bare string/number hiccup child
      ;; becomes its own :label view (mirrors glitter.gtk and glimmer's leaf
      ;; convention).
      (let [view (w/create! :label {:label text})]
        (atom {:tag :label :view view :children [] :handlers {} :text text})))

    (attached? [_ _el] true)

    ;; AppKit has no inline-style property and no CSS-class system — there is no
    ;; counterpart to gtk_widget_add_css_class, which is what glitter.gtk maps
    ;; these onto. Hiccup :style/:class props are still accepted and diffed by
    ;; glitter.core (calling these), but are inert. Deliberate v1 boundary, not
    ;; an unfinished method.
    (set-style [_ _el _k _v] nil)
    (remove-style [_ _el _k] nil)
    (add-class [_ _el _cn] nil)
    (remove-class [_ _el _cn] nil)

    (set-attribute [_ el a v _opt]
      (w/apply-props! (:tag @el) (ptr el) {(keyword a) v})
      nil)

    ;; KNOWN V1 GAP, inherited from glitter.gtk's identical one: this is a
    ;; no-op. w/apply-props! filters out any key whose value is nil before it
    ;; reaches a spec's :apply closure, so {(keyword a) nil} reduces to {} and
    ;; the underlying AppKit property is left untouched. AppKit has no generic
    ;; "unset a property" call the way DOM's removeAttribute does. Setting an
    ;; attribute to a NEW value always works; removing it so it reverts to a
    ;; type default does not.
    (remove-attribute [_ el a]
      (w/apply-props! (:tag @el) (ptr el) {(keyword a) nil})
      nil)

    (set-event-handler [_ el event handler _opt]
      ;; Replace any existing registration for this event first — handler DATA
      ;; can change between renders without the event key changing, and
      ;; glitter.core calls set-event-handler (not remove- then set-) for that
      ;; case.
      (let [view (ptr el)
            tag  (:tag @el)
            f    (dispatcher el tag event handler)]
        (cond
          (contains? action-events event)
          (do (u/control-target! view w/invoker)
              (u/control-action! view (u/sel "fire:"))
              (swap! w/actions assoc-in [view event] f))

          (= :change event)
          (do (u/control-delegate! view w/invoker)
              (swap! w/changes assoc view f))

          ;; An unknown event is a no-op rather than an error: glitter.core
          ;; happily diffs any :on key, and an app naming an event this
          ;; renderer has no wiring for should render, not crash.
          :else nil)
        (swap! el assoc-in [:handlers event] f))
      nil)

    (remove-event-handler [_ el event _opt]
      (let [view (ptr el)]
        (cond
          (contains? action-events event)
          (do (swap! w/actions update view dissoc event)
              (clear-target-if-unused! view))

          (= :change event)
          (do (swap! w/changes dissoc view)
              (u/control-delegate! view ffi/null))

          :else nil)
        (swap! el update :handlers dissoc event))
      nil)

    (insert-before [_ el child-node reference-node]
      ;; SINGLE BRANCH — see the ns docstring. NSStackView's insert moves an
      ;; already-arranged subview, so the same call serves a fresh insert and a
      ;; keyed reorder. The Clojure-side bookkeeping below still needs the
      ;; remove-then-resplice formula, because it tracks OUR view of the order.
      (let [cs (:children @el)
            idx (.indexOf cs reference-node)
            prev-sibling (when (pos? idx) (ptr (nth cs (dec idx))))]
        (w/insert-child-after! (:tag @el) (ptr el) (ptr child-node) prev-sibling))
      (swap! el update :children
             (fn [cs]
               (let [without (vec (remove #(= % child-node) cs))
                     idx (.indexOf without reference-node)]
                 (into (conj (subvec without 0 idx) child-node) (subvec without idx)))))
      nil)

    (append-child [_ el child-node]
      (w/append-child! (:tag @el) (ptr el) (ptr child-node))
      (swap! el update :children conj child-node)
      nil)

    (remove-child [_ el child-node]
      ;; Drop registrations for the whole removed SUBTREE, not just its root.
      ;; glitter.core never calls remove-event-handler for an unmounted node
      ;; (the DOM lets GC handle it), so without this walk every descendant's
      ;; entry would leak — and AppKit reuses freed addresses, so a later view
      ;; could land on a dead one's address and inherit its handler.
      (letfn [(forget-subtree! [e]
                (w/forget-view! (ptr e))
                (run! forget-subtree! (:children @e)))]
        (forget-subtree! child-node))
      (w/remove-child! (:tag @el) (ptr el) (ptr child-node))
      (swap! el update :children (fn [cs] (into [] (remove #(= % child-node) cs))))
      nil)

    ;; No animation in v1 — fire the callback immediately, synchronously.
    (on-transition-end [_ _el f] (f) nil)

    (replace-child [_ el insert-child replace-child]
      (w/replace-child! (:tag @el) (ptr el) (ptr replace-child) (ptr insert-child))
      (swap! el update :children
             (fn [cs] (mapv #(if (= % replace-child) insert-child %) cs)))
      nil)

    (remove-all-children [_ el]
      (doseq [c (:children @el)] (w/remove-child! (:tag @el) (ptr el) (ptr c)))
      (swap! el assoc :children [])
      nil)

    (get-child [_ el idx] (nth (:children @el) idx nil))

    (next-frame [_ f] (app/on-gui f) nil)

    ;; IMemory, folded into the SAME reify form rather than composed via
    ;; metadata: :extend-via-metadata is verified broken under Jolt (see
    ;; glitter's porting-and-attribution.md). Keyed off the el atom, which is
    ;; already a stable Clojure identity, rather than the raw view pointer.
    proto/IMemory
    (remember [_ node data] (swap! memory assoc node data) nil)
    (recall [_ node] (get @memory node))))

(defn mount!
  "Mount `view` (a `state -> hiccup` pure function) into `window` (an NSWindow
  pointer from glitter-uikit.app/run's on-activate callback), watching
  `state-atom` and re-reconciling on every change — Replicant's state-atom
  pattern. Registered aliases (glitter.alias/get-registered-aliases) are merged
  into every reconcile call automatically."
  [window view state-atom]
  (let [r (renderer)
        root-el (atom {:tag :window :view window :children [] :handlers {}})
        vdom (atom nil)
        render! (fn [state]
                  (reset! vdom (:vdom (core/reconcile r root-el (view state) @vdom
                                                      {:aliases (alias/get-registered-aliases)}))))]
    (render! @state-atom)
    (add-watch state-atom ::render (fn [_ _ _ state] (app/on-gui (fn [] (render! state)))))
    nil))
