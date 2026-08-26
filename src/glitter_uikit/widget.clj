(ns glitter-uikit.widget
  "Hiccup -> AppKit views. A data-driven registry maps hiccup tags to view
  constructors and prop maps to AppKit setters. This layer creates/patches views
  and manages container children; glitter.core decides when, reaching these
  functions through glitter-uikit.appkit's IRender implementation.

  Tag mapping (GTK widget -> AppKit view):
    :window    NSWindow                (single child, pinned to the content view)
    :box/:hbox/:vbox  NSStackView      (orientation = NSUserInterfaceLayoutOrientation)
    :button    NSButton (push)
    :label     NSTextField (label style)
    :entry     NSTextField (editable, bordered)
    :checkbutton NSButton (switch style)
    :separator NSBox separator (horizontal only in v1)
    :frame     NSBox (titled)
    :scrolled  NSScrollView (single document view)

  Added after v1's nine tags:
    :drop-down      NSPopUpButton (pullsDown:NO — select-one, not a menu)
    :scale          NSSlider
    :spin-button    NSStepper (arrows only — no built-in text field, unlike GTK)
    :progress-bar   NSProgressIndicator (bar style, range fixed at 0..1)
    :level-bar      NSLevelIndicator
    :switch         NSSwitch  (macOS 10.15+ — the only version-gated tag)
    :password-entry NSSecureTextField (NSTextField subclass)
    :search-entry   NSSearchField     (NSTextField subclass)
    :image          NSImageView

  Note on events: this namespace was forked from glimmer-uikit, whose
  Reagent-style model connects target/action ONCE at mount and lets the handler
  close over a reactive cell. glitter does NOT work that way. Its handlers are
  data, and glitter.core's diff calls IRender/set-event-handler again whenever
  the handler DATA changes between renders — so glitter-uikit.appkit owns signal
  lifecycle end to end, and the upstream connect-signals! is deliberately absent
  rather than adapted: two writers of setTarget:/setAction: would fight.

  Note on suppression: glitter.widget carries a `suppressing` set because GTK's
  programmatic setters (gtk_editable_set_text, gtk_check_button_set_active)
  synchronously re-emit their own signal, which would feed a re-render back into
  app dispatch. AppKit does NOT fire action or delegate callbacks for
  programmatic setState:/setStringValue:, so there is nothing to suppress and no
  such set exists here. This absence is intentional — do not add one. (The
  property is asserted live by examples/glitter_uikit/keyed_smoke.clj.)

  v1 layout notes: box :margin maps to the stack's edge insets; per-child
  :halign/:valign drive the PARENT stack's alignment (last child with an
  alignment wins), which matches every bundled example; :hexpand/:vexpand lower
  the child's content-hugging priority so it stretches along the stacking axis."
  (:require [clojure.string :as str]
            [glitter-uikit.ffi :as u]
            [hiccup2.core :as hiccup]
            [jolt.ffi :as ffi]))

;; --- value marshalling -------------------------------------------------------
(defn escape-markup
  "Escape `&`, `<`, `>` so `s` can be embedded safely inside a Pango markup
  string passed to a label's :markup prop. `&` is escaped first so the
  angle-bracket escapes are not themselves re-encoded."
  ^String [^String s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

;; --- Pango markup from hiccup data ------------------------------------------
;; Pango's text-attribute markup is a small XML subset (b, i, span, a, ...), NOT
;; HTML. Hiccup serializes vectors to a string and escapes content/attrs, but it
;; is HTML-flavoured — it will happily emit <div>, <br>, or a typo'd span
;; attribute. So we validate the hiccup *data* against Pango's vocabulary before
;; handing it to hiccup for serialization: a bad fragment fails loudly at the
;; call site instead of rendering silently wrong. Attribute names mirror Pango's
;; own (underscores: :font_family, :letter_spacing).
(def ^:private pango-tags
  "Pango markup vocabulary: tag -> the set of attributes it accepts, or nil when
  the tag takes no attributes."
  {:span #{:font_desc :font_family :face :size :style :weight :variant :stretch
           :foreground :color :background :alpha :underline :underline_color :rise
           :strikethrough :strikethrough_color :fallback :lang :letter_spacing
           :show :line_height :allow_breaks :insert_hyphens :text_transform
           :gravity :gravity_hint :overline :overline_color}
   :a    #{:href}
   :b nil
   :big nil
   :i nil
   :mark nil
   :s nil
   :small nil
   :sub nil
   :sup nil
   :tt nil
   :u nil})

(defn- markup-element? [form] (and (vector? form) (keyword? (first form))))

(declare markup-validate!)

(defn- markup-validate-element! [form]
  (let [tag     (first form)
        body    (rest form)
        attrs?  (map? (first body))
        attrs   (if attrs? (first body) nil)
        children (if attrs? (rest body) body)]
    (if-not (contains? pango-tags tag)
      (throw (ex-info (str "glitter-uikit/markup: :" (name tag) " is not a Pango tag")
                      {:tag tag})))
    (let [allowed (get pango-tags tag)]
      (when (and attrs (seq attrs))
        (if (nil? allowed)
          (throw (ex-info (str "glitter-uikit/markup: :" (name tag) " takes no attributes")
                          {:tag tag
                           :attrs (keys attrs)}))
          (doseq [k (keys attrs)]
            (when-not (contains? allowed k)
              (throw (ex-info (str "glitter-uikit/markup: :" (name k)
                                   " is not a :" (name tag) " attribute")
                              {:tag tag
                               :attr k}))))))
      (run! markup-validate! children))))

(defn- markup-validate! [form]
  (cond
    (markup-element? form)  (markup-validate-element! form)
    (sequential? form)      (run! markup-validate! form)
    :else                   nil))

(defn markup
  "Render hiccup `form` to a Pango markup string for a label's :markup prop.

  [:span {:foreground \"#8e939d\"} \"Nothing to do yet\"]
  [:b [:i \"bold italic\"]]

  Serialization (escaping, seq expansion) is delegated to hiccup; the data is
  first validated against Pango's tag/attribute vocabulary, so an HTML-only tag
  (:div, :br) or a typo'd attribute (:forground) throws here rather than
  producing markup a label can't render. Pango attribute names use underscores
  (:font_family, :letter_spacing) to match Pango's own spelling."
  [form]
  (markup-validate! form)
  (str (hiccup/html form)))

(defn markup-string
  "Coerce a label's :markup prop to a Pango markup string. A string passes
  through as-is (already markup); anything else is treated as hiccup and
  rendered via `markup`."
  [m]
  (if (string? m) m (markup m)))

;; --- Pango markup -> NSAttributedString -------------------------------------
;; AppKit has no Pango. :markup renders through a small subset mapped onto
;; NSAttributedString attributes: <b> <i> <s> <u> and span{foreground|color,
;; size (named, or an integer in 1/1024ths of a point), weight=bold,
;; strikethrough, underline}. Unknown-but-valid tags/attrs (validated above
;; against the full Pango vocabulary) are parsed and dropped — their text
;; survives, which keeps a Pango fragment from silently vanishing.
(def ^:private named-sizes
  {"xx-small" 9
   "x-small" 11
   "small" 12
   "medium" 13
   "large" 15
   "x-large" 17
   "xx-large" 22})

(defn- parse-attrs
  "Extract k='v' pairs from a tag string."
  [tag]
  (into {}
        (for [[_ k v] (re-seq #"([a-zA-Z_]+)=['\"]([^'\"]*)['\"]" tag)]
          [(keyword k) v])))

(defn- tag-name [tag] (str/replace (str/replace tag #"[<>/]" "") #"\s.*$" ""))

(defn- open-tag [stack tag]
  (case (tag-name tag)
    "b"    (conj stack {:bold true})
    "i"    (conj stack {:italic true})
    "s"    (conj stack {:strike true})
    "u"    (conj stack {:underline true})
    "span" (let [a (parse-attrs tag)
                 style (cond-> {}
                         (or (:foreground a) (:color a))
                         (assoc :color (or (:foreground a) (:color a)))
                         (:size a) (assoc :size (:size a))
                         (= "bold" (:weight a)) (assoc :weight true)
                         (= "true" (:strikethrough a)) (assoc :strike true)
                         (= "true" (:underline a)) (assoc :underline true))]
             (conj stack style))
    stack))

(defn- markup->segments
  "Parse a Pango markup string into [[text style-map] ...] segments."
  [^String s]
  (first
   (reduce
    (fn [[segs stack] [_full tag txt]]
      (cond
        tag (if (str/starts-with? tag "</")
              [segs (pop stack)]
              [segs (open-tag stack tag)])
        :else [(conj segs [txt (apply merge stack)]) stack]))
    [[] []]
    (re-seq #"(</?[a-zA-Z]+(?:\s+[^<>]*)?>)|([^<>]+)" s))))

(defn- decode-entities [s]
  (-> s
      (str/replace "&lt;" "<")
      (str/replace "&gt;" ">")
      (str/replace "&quot;" "\"")
      (str/replace "&#39;" "'")
      (str/replace "&apos;" "'")
      (str/replace "&amp;" "&")))

(defn- pango-size->pt
  "Pango font sizes are a named size (\"large\") or an integer in 1/1024ths of
  a point (\"30000\" = 29.3pt). Map either to a point size."
  [s]
  (or (named-sizes s)
      (when (and (string? s) (re-matches #"\d+" s))
        (/ (reduce (fn [acc c] (+ (* acc 10) (- (int c) 48))) 0 s) 1024.0))))

(defn- style-font [style]
  (let [size (double (or (pango-size->pt (:size style)) 13.0))]
    (cond
      (:weight style) (u/bold-font-size size)
      (:italic style) (u/italic-font-size size)
      :else           (u/system-font-size size))))

(defn- apply-style! [a style start len]
  (when (pos? len)
    (let [font (style-font style)]
      (when font (u/attributed-add! a u/NS-FONT-ATTR font start len)))
    (when (:strike style)
      (u/attributed-add! a u/NS-STRIKETHROUGH-ATTR (u/number-int 1) start len))
    (when (:underline style)
      (u/attributed-add! a u/NS-UNDERLINE-ATTR (u/number-int 1) start len))
    (when-let [c (:color style)]
      (u/attributed-add! a u/NS-FOREGROUND-COLOR-ATTR (u/color-hex c) start len))))

(defn markup->attributed
  "Render a Pango markup STRING to an NSAttributedString (AppKit label content)."
  [^String s]
  (let [segs (map (fn [[t st]] [(decode-entities t) st]) (markup->segments s))
        a    (u/attributed-new (apply str (map first segs)))]
    (loop [segs (seq segs) pos 0]
      (when-let [[txt style] (first segs)]
        (let [len (count txt)]
          (apply-style! a style pos len)
          (recur (next segs) (+ pos len)))))
    a))

;; --- tag aliases (sugar) -----------------------------------------------------
;; :hbox / :vbox are both NSStackView; the difference is orientation.
;; normalize-tag maps them to the :box spec, and with-orientation injects the
;; matching :orientation so a bare [:hbox ...] lays out horizontally.
(def ^:private aliases {:hbox :box
                        :vbox :box})
(def ^:private tag-orientation {:hbox :horizontal
                                :vbox :vertical})

(defn- normalize-tag [tag] (get aliases tag tag))

(defn with-orientation
  "Inject the orientation implied by an :hbox/:vbox tag into its props, unless
  the caller already set :orientation. A no-op for any other tag."
  [tag props]
  (if-let [o (tag-orientation tag)]
    (if (contains? props :orientation) props (assoc props :orientation o))
    props))

;; --- the shared action/delegate target ---------------------------------------
;; One dynamic ObjC class ("GlitterTarget") carries every event. Its method IMPs
;; are jolt foreign-callables that dispatch on the sender pointer, so a single
;; instance serves as every control's target, every text field's delegate, and
;; the app delegate.
;;
;; Renamed from glimmer-uikit's "GlimmerTarget": objc_allocateClassPair registers
;; the class PROCESS-WIDE by name, and the existing-class branch below looks it
;; up by that name — so sharing the name with glimmer-uikit would silently hand
;; back glimmer's class, carrying glimmer's IMPs and its handler registries.
;;
;; The registries are keyed by raw view pointer because that is all an ObjC IMP
;; receives. glitter-uikit.appkit owns every write AND every delete — see its
;; forget-view! / set-event-handler / remove-event-handler.
(defonce actions (atom {}))      ; view ptr -> {event-keyword handler-fn}
(defonce changes (atom {}))      ; view ptr -> handler-fn (text changed)
(defonce ^:private auto-quit-app (atom nil))

(defonce ^:private fire-cb
  (ffi/foreign-callable
   (fn [_self _cmd sender]
      ;; An NSControl has ONE action slot, so at most one action event is
      ;; registered per view in practice; run whatever is registered.
     (doseq [[_event h] (get @actions sender)] (h sender))
     0)
   [:pointer :pointer :pointer] :void :collect-safe))

(defonce ^:private change-cb
  (ffi/foreign-callable
   (fn [_self _cmd notif]
     (let [control (u/objc-msg-send-0 notif (u/sel "object"))]
       (when-let [h (get @changes control)] (h control)))
     0)
   [:pointer :pointer :pointer] :void :collect-safe))

(defonce ^:private quit-cb
  (ffi/foreign-callable
   (fn [_self _cmd _timer]
     (when-let [app @auto-quit-app]
       (u/stop-app! app)
       (u/post-event-at-start! app (u/application-defined-event)))
     0)
   [:pointer :pointer :pointer] :void :collect-safe))

(defonce ^:private terminate-cb
  ;; Returns (char 1), NOT the integer 1. jolt's :char is a Scheme CHARACTER —
  ;; the same fact ffi.clj records for BOOL ARGUMENTS, which is why those are
  ;; declared :int. The rule applies to a callable's RETURN too, and this is the
  ;; only foreign-callable here with a non-:void return, so it is the only place
  ;; it bites: `(char? 1)` is false, so returning the int raised
  ;; "Exception in foreign-callable: invalid return value 1" and crashed the app
  ;; whenever a human closed the window (issue #1).
  ;;
  ;; The declared :char must stay: the method is registered with ObjC type
  ;; encoding "c@:@", where c is BOOL/char. Declaring :int would mismatch the ABI.
  (ffi/foreign-callable (fn [_ _ _] (char 1)) [:pointer :pointer :pointer] :char :collect-safe))

(defonce invoker
  (let [existing (u/objc-get-class "GlitterTarget")]
    (if (and existing (not (ffi/null? existing)))
      (u/objc-msg-send-0 existing (u/sel "new"))
      (let [c (u/objc-allocate-class-pair (u/cls "NSObject") "GlitterTarget" 0)]
        (u/class-add-method c (u/sel "fire:") fire-cb "v@:@")
        (u/class-add-method c (u/sel "controlTextDidChange:") change-cb "v@:@")
        (u/class-add-method c (u/sel "autoQuit:") quit-cb "v@:@")
        (u/class-add-method c (u/sel "applicationShouldTerminateAfterLastWindowClosed:") terminate-cb "c@:@")
        (u/objc-register-class-pair c)
        (u/objc-msg-send-0 c (u/sel "new"))))))

(defn auto-quit!
  "Schedule the app to quit after `ms` (the :auto-quit-ms run option)."
  [app ms]
  (reset! auto-quit-app app)
  (u/timer-after! ms invoker (u/sel "autoQuit:")))

;; --- widget specs ------------------------------------------------------------
;; Each spec: {:ctor (fn [props] view) :apply (fn [view props]) :container kw}
(defn- window-spec []
  {:ctor    (fn [p] (u/window-new (:title p) (or (:width p) 400) (or (:height p) 300)))
   :apply   (fn [w p]
              (when (:title p) (u/window-title! w (:title p)))
              (when (false? (:visible p)) (u/window-hide! w)))
   :container :window})

(defn- box-margins
  "The stack's edge insets implied by a box's margin props, or nil."
  [p]
  (let [m (:margin p)
        top (or (:margin-top p) m) bottom (or (:margin-bottom p) m)
        left (or (:margin-left p) (:margin-start p) m)
        right (or (:margin-right p) (:margin-end p) m)]
    (when (or top left bottom right) [top left bottom right])))

(defn- box-spec []
  {:ctor  (fn [_] (u/stack-new))
   :apply (fn [w p]
            (when (contains? p :spacing)     (u/stack-spacing! w (:spacing p)))
            (when (contains? p :homogeneous)
              (u/stack-distribution! w (if (:homogeneous p)
                                         u/DISTRIBUTION-FILL-EQUALLY
                                         u/DISTRIBUTION-GRAVITY)))
            (when (contains? p :orientation)
              (u/stack-orientation! w (if (= :vertical (:orientation p))
                                        u/ORIENTATION-VERTICAL
                                        u/ORIENTATION-HORIZONTAL)))
            (when-let [[t l b r] (box-margins p)]
              (u/stack-edge-insets! w t l b r)))
   :container :box})

(defn- button-spec []
  {:ctor  (fn [p] (u/button-new (or (:label p) "")))
   :apply (fn [w p]
            (when (contains? p :label)     (u/control-title! w (:label p)))
            (when (:tooltip p)             (u/set-tooltip! w (:tooltip p)))
            (when (contains? p :sensitive) (u/control-enabled! w (:sensitive p))))
   :container :none})

(defn- ->text-align [x]
  (cond
    (<= x 0.34) u/TEXT-ALIGN-LEFT
    (>= x 0.66) u/TEXT-ALIGN-RIGHT
    :else       u/TEXT-ALIGN-CENTER))

(defn- ->line-break [e]
  (case e
    :start  u/LINE-BREAK-HEAD
    :middle u/LINE-BREAK-MIDDLE
    :end    u/LINE-BREAK-TAIL
    :none   nil))

(defn- label-spec []
  {:ctor  (fn [p] (u/label-new (or (:label p) (:text p) "")))
   :apply (fn [w p]
            (when (contains? p :label)  (u/control-string! w (:label p)))
            (when (contains? p :text)   (u/control-string! w (:text p)))
            (when (contains? p :markup) (u/control-attributed! w (markup->attributed (markup-string (:markup p)))))
            (when (contains? p :xalign) (u/control-align! w (->text-align (:xalign p))))
            (when (contains? p :wrap)
              (u/control-line-break! w u/LINE-BREAK-WRAP)
              (u/control-max-lines! w 0))
            (when (contains? p :lines)  (u/control-max-lines! w (:lines p)))
            (when-let [m (and (contains? p :ellipsize) (->line-break (:ellipsize p)))]
              (u/control-line-break! w m))
            (when-let [n (or (:max-width-chars p) (:width-chars p))]
              (u/control-preferred-width! w (* 8.0 (double n)))))
   :container :none})

(defn- entry-spec []
  {:ctor  (fn [_] (u/entry-new))
   :apply (fn [w p]
            ;; only-when-different: NOT for loop suppression (AppKit doesn't
            ;; fire delegate callbacks for programmatic setStringValue:, so
            ;; there's no re-render loop to break) but because unconditionally
            ;; re-setting stringValue on every re-render resets the insertion
            ;; point mid-typing. The AppKit port needed this restored — it
            ;; exists in glimmer-gtk and glitter, but the glimmer-uikit
            ;; original this file was ported from lacks it, and a verbatim
            ;; port silently lost the guard along with it.
            (when (and (contains? p :text) (not= (:text p) (u/control-string w)))
              (u/control-string! w (:text p)))
            (when (contains? p :placeholder) (u/control-placeholder! w (:placeholder p)))
            (when (contains? p :sensitive)   (u/control-enabled! w (:sensitive p)))
            (when (:tooltip p)               (u/set-tooltip! w (:tooltip p))))
   :container :none})

(defn- checkbutton-spec []
  {:ctor  (fn [p] (u/checkbox-new (or (:label p) "")))
   :apply (fn [w p]
            (when (contains? p :label)  (u/control-title! w (:label p)))
            (when (contains? p :active) (u/control-state! w (if (:active p) u/STATE-ON u/STATE-OFF)))
            (when (:tooltip p)          (u/set-tooltip! w (:tooltip p))))
   :container :none})

(defn- separator-spec []
  {:ctor  (fn [_] (u/separator-new))
   :apply (fn [_ _] nil)
   :container :none})

(defn- frame-spec []
  {:ctor     (fn [_] (u/box-new))
   :apply    (fn [w p] (when (contains? p :label) (u/box-title! w (or (:label p) ""))))
   :container :frame})

(defn- drop-down-spec []
  ;; :items is applied BEFORE :selected on purpose — selectItemAtIndex: with an
  ;; index outside the current menu raises an ObjC exception, and an ObjC
  ;; exception ABORTS the process here (a Clojure catch cannot intercept it; see
  ;; AGENTS.md gotcha 5). Rebuilding the menu first, then bounds-checking the
  ;; index against the menu that now exists, is what keeps that unreachable.
  {:ctor  (fn [_] (u/popup-new))
   :apply (fn [w p]
            (when (contains? p :items)
              (u/popup-remove-all! w)
              (run! (fn [i] (u/popup-add-item! w (str i))) (:items p)))
            (when (contains? p :selected)
              (let [i (:selected p)
                    n (u/popup-count w)]
                (when (and (integer? i) (<= 0 i) (< i n))
                  (u/popup-select! w i))))
            (when (contains? p :sensitive) (u/control-enabled! w (:sensitive p)))
            (when (:tooltip p) (u/set-tooltip! w (:tooltip p))))
   :container :none})

(defn- scale-spec []
  ;; :min/:max before :value, so a value inside the new range is not clamped
  ;; against the OLD range on a render that widens both at once.
  ;;
  ;; glitter's GTK :scale also takes :step, :digits and :draw-value. NSSlider has
  ;; no counterpart for any of them: it is continuous, shows no value label, and
  ;; quantises only via tick marks. They are accepted and ignored rather than
  ;; rejected, so a glitter view ports unchanged; :ticks below is the AppKit-side
  ;; way to quantise. Recorded in docs/guide/limitations.md.
  {:ctor  (fn [_] (u/slider-new))
   :apply (fn [w p]
            (when (contains? p :min) (u/control-min! w (:min p)))
            (when (contains? p :max) (u/control-max! w (:max p)))
            (when (contains? p :value) (u/control-double! w (:value p)))
            (when (contains? p :ticks)
              (u/slider-ticks! w (:ticks p))
              (u/slider-only-ticks! w (boolean (:ticks-only p))))
            (when (contains? p :sensitive) (u/control-enabled! w (:sensitive p)))
            (when (:tooltip p) (u/set-tooltip! w (:tooltip p))))
   :container :none})

(defn- spin-button-spec []
  ;; An NSStepper is ONLY the up/down arrows — unlike GtkSpinButton it has no
  ;; built-in text field, so a view that wants to show the number pairs it with
  ;; a sibling :label or :entry. :digits has no counterpart and is ignored.
  {:ctor  (fn [_] (u/stepper-new))
   :apply (fn [w p]
            (when (contains? p :min) (u/control-min! w (:min p)))
            (when (contains? p :max) (u/control-max! w (:max p)))
            (when (contains? p :step) (u/stepper-increment! w (:step p)))
            (when (contains? p :wrap) (u/stepper-wraps! w (:wrap p)))
            (when (contains? p :value) (u/control-double! w (:value p)))
            (when (contains? p :sensitive) (u/control-enabled! w (:sensitive p))))
   :container :none})

(defn- progress-bar-spec []
  ;; progress-new fixes the range at 0..1 so :fraction maps straight through.
  ;; :show-text / :text are GTK-only (NSProgressIndicator draws no text) and are
  ;; accepted and ignored; pair it with a :label to show a number.
  {:ctor  (fn [_] (u/progress-new))
   :apply (fn [w p]
            (when (contains? p :indeterminate)
              (u/progress-indeterminate! w (:indeterminate p))
              (if (:indeterminate p) (u/progress-start! w) (u/progress-stop! w)))
            (when (contains? p :fraction) (u/control-double! w (:fraction p)))
            (when (contains? p :value) (u/control-double! w (:value p))))
   :container :none})

(defn- level-bar-spec []
  {:ctor  (fn [_] (u/level-new))
   :apply (fn [w p]
            (when (contains? p :min-value) (u/control-min! w (:min-value p)))
            (when (contains? p :max-value) (u/control-max! w (:max-value p)))
            (when (contains? p :discrete)
              (u/level-style! w (if (:discrete p) u/LEVEL-STYLE-DISCRETE u/LEVEL-STYLE-CONTINUOUS)))
            (when (contains? p :value) (u/control-double! w (:value p))))
   :container :none})

(defn- switch-spec []
  ;; NSSwitch is API_AVAILABLE(macos(10.15)). ctor throws a named error rather
  ;; than letting (cls "NSSwitch") hand back null and crash inside objc_msgSend
  ;; with nothing pointing at the cause.
  {:ctor  (fn [_]
            (when-not (u/switch-supported?)
              (throw (ex-info "glitter-uikit: :switch needs macOS 10.15+ (NSSwitch)"
                              {:tag :switch
                               :required "10.15"})))
            (u/switch-new))
   :apply (fn [w p]
            (when (contains? p :active)
              (u/control-state! w (if (:active p) u/STATE-ON u/STATE-OFF)))
            (when (contains? p :sensitive) (u/control-enabled! w (:sensitive p))))
   :container :none})

(defn- password-entry-spec []
  ;; NSSecureTextField is an NSTextField subclass, so it reuses entry-spec's
  ;; only-when-different guard verbatim — re-setting stringValue mid-typing
  ;; resets the insertion point here exactly as it does for a plain :entry.
  ;; GTK's :show-peek-icon has no counterpart and is ignored.
  {:ctor  (fn [_] (u/secure-entry-new))
   :apply (fn [w p]
            (when (and (contains? p :text) (not= (:text p) (u/control-string w)))
              (u/control-string! w (:text p)))
            (when (contains? p :placeholder) (u/control-placeholder! w (:placeholder p)))
            (when (contains? p :sensitive)   (u/control-enabled! w (:sensitive p))))
   :container :none})

(defn- search-entry-spec []
  ;; Same NSTextField lineage and the same guard. GTK's :search-delay has no
  ;; counterpart (NSSearchField sends its action as you type) and is ignored.
  {:ctor  (fn [_] (u/search-entry-new))
   :apply (fn [w p]
            (when (and (contains? p :text) (not= (:text p) (u/control-string w)))
              (u/control-string! w (:text p)))
            (when (contains? p :placeholder) (u/control-placeholder! w (:placeholder p)))
            (when (contains? p :sensitive)   (u/control-enabled! w (:sensitive p))))
   :container :none})

(defn- image-spec []
  ;; :file is a filesystem path, :icon-name a named system image. A path that
  ;; does not resolve leaves the view empty rather than throwing: NSImage's
  ;; initWithContentsOfFile: returns nil for a missing file, and setImage: nil
  ;; is legal. GTK's :pixel-size has no counterpart; size the view with the
  ;; surrounding layout instead.
  {:ctor  (fn [_] (u/image-view-new))
   :apply (fn [w p]
            (when (contains? p :file)
              (let [img (u/image-from-file (:file p))]
                (when-not (ffi/null? img) (u/image-view-image! w img))))
            (when (contains? p :icon-name)
              (let [img (u/image-named (:icon-name p))]
                (when-not (ffi/null? img) (u/image-view-image! w img)))))
   :container :none})

(defn- scrolled-spec []
  ;; A single-document viewport. The document is pinned to the clip view at LOW
  ;; priority so it scrolls inside the allotted area instead of forcing the
  ;; window bigger (mirrors GTK's propagate-natural-size off).
  {:ctor     (fn [_] (u/scroll-new))
   :apply    (fn [w p] (when (contains? p :scroll-top) (u/scroll-top! w)))
   :container :scrolled})

(def specs
  (atom {:window      (window-spec)
         :box         (box-spec)
         :button      (button-spec)
         :label       (label-spec)
         :entry       (entry-spec)
         :checkbutton (checkbutton-spec)
         :separator   (separator-spec)
         ;; --- added after v1's nine tags; see docs/guide/appkit-widget-layer.md
         :drop-down      (drop-down-spec)
         :scale          (scale-spec)
         :spin-button    (spin-button-spec)
         :progress-bar   (progress-bar-spec)
         :level-bar      (level-bar-spec)
         :switch         (switch-spec)
         :password-entry (password-entry-spec)
         :search-entry   (search-entry-spec)
         :image          (image-spec)
         :frame       (frame-spec)
         :scrolled    (scrolled-spec)}))

(defn register-widget!
  "Register a widget spec under hiccup `tag`. A spec is
  {:ctor (fn [props] view) :apply (fn [view props]) :container kw}.
  :container is :none for a leaf, or :box / :window / :frame / :scrolled to
  reuse an existing child-management strategy."
  [tag spec] (swap! specs assoc tag spec) nil)

(defn- spec-for [tag] (@specs (normalize-tag tag)))

(defn container-kind
  "How a tag holds children: :box (ordered append/remove), :window / :frame /
  :scrolled (single child), or :none (leaf)."
  [tag] (:container (spec-for tag)))

;; --- universal props (apply to every widget, every tag) ----------------------
;; :hexpand/:vexpand lower the child's content-hugging priority so an NSStackView
;; (gravity-area distribution) stretches it along the stacking axis. :halign/
;; :valign are recorded so the PARENT stack's alignment can be derived at append
;; time (NSStackView alignment is a stack-wide property, not per-view).
(def ^:private alignments (atom {}))    ; view -> [halign valign]
;; Views that already carry a width constraint, so a re-render does not stack a
;; second one on top of the first.
(def ^:private sized (atom #{}))

(defn- ->stack-alignment
  "Map a child's :halign/:valign onto the PARENT stack's NSStackView alignment.

  :fill is AppKit-specific and has no GTK counterpart, but it is the one that
  makes a row of children actually span the stack: NSStackView aligns a vertical
  stack's children on WIDTH (and a horizontal stack's on HEIGHT) rather than on
  an edge or a centre line. Without it a vertical stack sizes every child to its
  own natural width and centres it, which is why a label+entry row renders as a
  narrow island in the middle of the window with the entry truncated.

  MEASURED, and not yet the whole story: setting this alone did NOT make the
  Flight Booker's rows span the window — its date fields still truncated. The
  attribute is mapped correctly (NSStackView accepts WIDTH for a vertical stack)
  but something further down still sizes the row to its content, so treat :fill
  as available-but-unproven rather than as the fix for a narrow row. Whatever
  actually governs this is unresolved; see docs/guide/limitations.md."
  [halign valign orientation]
  (if (= orientation u/ORIENTATION-VERTICAL)
    (case halign
      :start u/ATTR-LEADING
      :end   u/ATTR-TRAILING
      :fill  u/ATTR-WIDTH
      u/ATTR-CENTER-X)
    (case valign
      :top    u/ATTR-TOP
      :bottom u/ATTR-BOTTOM
      :fill   u/ATTR-HEIGHT
      u/ATTR-CENTER-Y)))

(defn apply-widget-props!
  [widget props]
  (when (contains? props :hexpand)
    (u/set-hugging! widget (if (:hexpand props) u/PRIORITY-VERY-LOW u/PRIORITY-REQUIRED)
                    u/ORIENTATION-HORIZONTAL))
  (when (contains? props :vexpand)
    (u/set-hugging! widget (if (:vexpand props) u/PRIORITY-VERY-LOW u/PRIORITY-REQUIRED)
                    u/ORIENTATION-VERTICAL))
  ;; :width-request / :height-request install a real autolayout constraint.
  ;; Applied once per view: a constraint is cumulative, so re-adding it on every
  ;; re-render would pile up conflicting constraints on the same view.
  (when-let [w (:width-request props)]
    (when-not (contains? @sized widget)
      (swap! sized conj widget)
      (u/set-width! widget (double w))))
  (when (or (contains? props :halign) (contains? props :valign))
    (swap! alignments assoc widget [(:halign props) (:valign props)])))

;; --- public create / patch ---------------------------------------------------
(defn create!
  "Construct a fresh AppKit view for `tag` and apply `props`. Returns the view
  pointer.

  Two differences from the glimmer-uikit original, both because glitter owns
  event lifecycle: no connect-signals! call, and no :connect hook. Children are
  NOT added here — glitter.core appends them so it can reuse existing children
  across renders."
  [tag props]
  (let [props (with-orientation tag props)
        s (spec-for tag)
        widget ((:ctor s) props)]
    ((:apply s) widget props)
    (apply-widget-props! widget props)
    widget))

(defn apply-props!
  "Re-apply the prop map to an existing view (the re-render path). Skips keys
  whose value is nil, and skips :on (glitter's event map — glitter-uikit.appkit
  owns event lifecycle; see the ns docstring).

  Only keys PRESENT in `props` are touched, so this is safe to call with a
  single-key partial map like {:label \"new text\"}, which is exactly how
  glitter-uikit.appkit's set-attribute uses it.

  Filters on `some?`, NOT truthiness: an explicit `false` (:sensitive false,
  :active false) is a real value that must reach the view. This mirrors
  glitter.core's own deviation #3 from Replicant, made for the same reason."
  [tag widget props]
  (let [applied (into {} (filter (fn [[k v]] (and (not= :on k) (some? v)))
                                 (with-orientation tag props)))]
    ((:apply (spec-for tag)) widget applied)
    (apply-widget-props! widget applied)))

(defn show!
  "Make a view visible. AppKit views are visible by default; :visible false
  hides instead."
  [widget props]
  (u/set-hidden! widget (false? (:visible props))))

;; --- container child management ----------------------------------------------
(defn- maybe-align!
  "Derive the parent stack's alignment from a child's :halign/:valign."
  [parent child]
  (when-let [[halign valign] (get @alignments child)]
    (u/stack-alignment! parent (->stack-alignment halign valign (u/stack-orientation parent)))))

;; NSNotFound is NSIntegerMax (9223372036854775807), NOT NSUIntegerMax/-1 —
;; measured, not assumed. Feeding NSNotFound+1 to insertArrangedSubview:atIndex:
;; raises an uncaught NSException that ABORTS THE PROCESS, and a Clojure
;; `catch :default` does not intercept it (ObjC exceptions do not unwind into
;; Scheme). So every index read goes through here and yields nil for "absent",
;; never a sentinel that can reach arithmetic.
(def ^:private NS-NOT-FOUND 9223372036854775807)

(defn arranged-index
  "The index of `view` among `stack`'s arranged subviews, or nil when it is not
  one. New relative to the glimmer-uikit original, which called
  stack-index-of! directly and did `(inc i)` on the result — a process abort
  whenever the sibling was absent."
  [stack view]
  (let [i (u/stack-index-of! stack view)]
    (when-not (or (= i NS-NOT-FOUND) (neg? i)) i)))

(defn forget-view!
  "Drop every handler/alignment registration held for `view`. New relative to
  the glimmer-uikit original, whose registries were never cleaned: they grew
  without bound, and because AppKit reuses freed addresses a newly allocated
  view could land on a dead view's address and inherit its handler."
  [view]
  (swap! actions dissoc view)
  (swap! changes dissoc view)
  (swap! alignments dissoc view)
  nil)

(defn append-child!
  "Add `child` to the end of `parent`. Dispatches on the parent's container kind."
  [parent-tag parent child]
  (case (container-kind parent-tag)
    :box      (do (u/stack-add-arranged! parent child) (maybe-align! parent child))
    :window   (let [c (u/window-content parent)] (u/add-subview! c child) (u/pin! child c))
    :frame    (let [c (u/box-content parent)]    (u/add-subview! c child) (u/pin! child c))
    :scrolled (do (u/scroll-document! parent child)
                  (u/pin-low! child (u/scroll-clip parent)))
    nil))

(defn insert-child-after!
  "Place `child` immediately after `sibling` within `parent` (nil sibling = first
  position). Falls back to append when `sibling` is not an arranged subview.

  New relative to the glimmer-uikit original — glimmer's reconciler never needs
  it; glitter.core's insert-before does.

  ONE code path handles both a fresh insert and a move of an existing child,
  because -[NSStackView insertArrangedSubview:atIndex:] MOVES a view that is
  already arranged. This is where AppKit is genuinely simpler than GTK:
  glitter.gtk/insert-before must branch on whether the child is already
  tracked, because gtk_box_insert_child_after asserts its child is UNPARENTED
  and no-ops with a GTK-CRITICAL otherwise. Do not port that branch here.

  BUT the DOM `insertBefore` analogy is misleading, because `insertBefore`
  takes a reference NODE and AppKit's call takes an INDEX. Measured:
  insertArrangedSubview:atIndex: is remove-then-insert internally, and the
  index it takes is interpreted against the POST-removal array ([A B C D] +
  insert A at index 3 -> [B C D A], not [B C D A] read as \"insert before the
  view currently at 3\" — the removal of A shifts everything after it left by
  one BEFORE the index is applied). A fresh insert or a BACKWARD move (child
  currently at or after sibling) is unaffected: sibling's pre-removal index
  and post-removal index are the same, since nothing before sibling moved. A
  FORWARD move (child currently sits before sibling) is not: removing child
  shifts sibling's index left by one, so inserting at `(inc i)` (i = sibling's
  PRE-removal index) lands one slot too far right. The un-incremented index i
  is already correct for that case."
  [parent-tag parent child sibling]
  (when (= :box (container-kind parent-tag))
    (if (nil? sibling)
      (u/stack-insert-arranged! parent child 0)
      (if-let [i (arranged-index parent sibling)]
        (let [ci     (arranged-index parent child)
              target (if (and ci (< ci i)) i (inc i))]
          (u/stack-insert-arranged! parent child target))
        (u/stack-add-arranged! parent child)))
    (maybe-align! parent child))
  nil)

(defn remove-child!
  "Remove `child` from `parent`, and drop every registration held for it.

  removeArrangedSubview: alone is not enough: it un-manages the view but leaves
  it a plain subview (measured — its superview is still non-null afterwards), so
  removeFromSuperview is also required or the view stays on screen unmanaged."
  [parent-tag parent child]
  (case (container-kind parent-tag)
    :box      (do (u/stack-remove-arranged! parent child)
                  (u/remove-from-superview! child))
    :window   (u/remove-from-superview! child)
    :frame    (u/remove-from-superview! child)
    :scrolled (u/scroll-document! parent ffi/null)
    nil)
  (forget-view! child)
  nil)

(defn replace-child!
  "Replace `old-child` with `new-child` at the SAME position in `parent`.

  The glimmer-uikit original did remove + append for every container kind, and
  addArrangedSubview: always lands at the END of the stack — so replacing any
  non-final child silently relocated it last. This is the identical defect
  glitter already fixed on the GTK side (NOTICE Bucket 2, deviation 1). Here the
  fix is to capture the index BEFORE removing, then insert at it."
  [parent-tag parent old-child new-child]
  (if (= :box (container-kind parent-tag))
    (let [i (arranged-index parent old-child)]
      (remove-child! parent-tag parent old-child)
      (if i
        (do (u/stack-insert-arranged! parent new-child i)
            (maybe-align! parent new-child))
        (append-child! parent-tag parent new-child)))
    (do (remove-child! parent-tag parent old-child)
        (append-child! parent-tag parent new-child)))
  nil)

(defn reorder-child!
  "Move `child` to sit immediately after `sibling` (nil = first position) within
  `parent`. Identical to insert-child-after! — NSStackView's insert MOVES an
  already-arranged view, so reordering needs no separate call. Kept as its own
  name because glitter-uikit.appkit reads better calling it, and because
  glitter.widget exposes both."
  [parent-tag parent child sibling]
  (insert-child-after! parent-tag parent child sibling))

;; --- reading the live tree (for tests / smoke examples) ----------------------
(defn stack-children
  "The arranged subviews of a stack, in visual order."
  [stack]
  (let [arr (u/stack-arranged! stack)
        n   (u/array-count arr)]
    (mapv (fn [i] (u/array-get arr i)) (range n))))
