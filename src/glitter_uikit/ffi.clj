(ns glitter-uikit.ffi
  "Raw bindings for the Objective-C runtime and AppKit, built on jolt.ffi.

  Objective-C from a C FFI: classes come from objc_getClass, selectors from
  sel_registerName, and every method call goes through objc_msgSend bound at a
  concrete arity. On Apple arm64 objc_msgSend is a dispatcher that forwards the
  caller's registers to the method implementation, so a FIXED-arity binding
  whose struct args are flattened into their component doubles matches the real
  call layout (a CGRect is an HFA of 4 doubles -> d0-d3, exactly like four
  separate doubles). Never bind it with :varargs — the variadic convention
  passes FP args on the stack, which corrupts the registers a real method
  expects. Verified on this machine: string/pointer/double args, NSUInteger /
  BOOL / const-char* returns, and flattened NSRect/NSPoint args all round-trip.

  AppKit.framework is dlopen'd here on macOS so its classes register
  (objc_getClass only finds classes in loaded frameworks). On other platforms
  the load is skipped and only the headless helpers (markup, with-orientation
  in glitter-uikit.widget) are usable — which is what the CI unit tests need.

  Marshalling: a C string crosses the FFI as :string (UTF-8); NSStrings are
  created with stringWithUTF8String: and read back with UTF8String. BOOL is
  :char, NSInteger/NSUInteger :int64, CGFloat :double."
  (:require [jolt.ffi :as ffi]))

;; --- load AppKit on macOS so its classes register ----------------------------
(defn- macos? [] (= (System/getProperty "os.name") "Mac OS X"))
(when (macos?)
  (try
    (ffi/load-library "/System/Library/Frameworks/AppKit.framework/AppKit")
    (ffi/load-library "/System/Library/Frameworks/Foundation.framework/Foundation")
    (catch :default _
      (println "glitter-uikit: could not load AppKit.framework; headless helpers only"))))

;; --- constants ---------------------------------------------------------------
;; NSWindowStyleMask: titled | closable | miniaturizable | resizable
(def WINDOW-STYLE 15)
(def NS-BACKING-BUFFERED 2)
;; NSApplicationActivationPolicy
(def ACTIVATION-REGULAR 0)
;; NSUserInterfaceLayoutOrientation
(def ORIENTATION-HORIZONTAL 0)
(def ORIENTATION-VERTICAL 1)
;; NSLayoutAttribute
(def ATTR-LEFT 1)  (def ATTR-RIGHT 2)  (def ATTR-TOP 3) (def ATTR-BOTTOM 4)
(def ATTR-LEADING 5) (def ATTR-TRAILING 6)
(def ATTR-WIDTH 7) (def ATTR-HEIGHT 8)
(def ATTR-CENTER-X 9) (def ATTR-CENTER-Y 10)
;; NSLayoutAttributeNotAnAttribute — the second item of a self-constraint
;; (width == constant), where there is no other view to relate to.
(def ATTR-NOT-AN-ATTRIBUTE 0)
(def RELATION-EQUAL 0)
;; NSStackViewDistribution
(def DISTRIBUTION-GRAVITY -1)
(def DISTRIBUTION-FILL 0)
(def DISTRIBUTION-FILL-EQUALLY 1)
;; NSTextAlignment
(def TEXT-ALIGN-LEFT 0) (def TEXT-ALIGN-CENTER 1) (def TEXT-ALIGN-RIGHT 2)
;; NSLineBreakMode
(def LINE-BREAK-WRAP 0) (def LINE-BREAK-CLIP 2)
(def LINE-BREAK-HEAD 3) (def LINE-BREAK-TAIL 4) (def LINE-BREAK-MIDDLE 5)
;; NSBoxType / NSTitlePosition
(def BOX-SEPARATOR 2)
(def TITLE-POSITION-AT-TOP 0)
;; NSTextFieldBezelStyle
(def BEZEL-ROUNDED 1)
;; NSControlStateValue
(def STATE-OFF 0) (def STATE-ON 1)

;; NSProgressIndicatorStyle: bar is 0, spinning is 1 (NSProgressIndicator.h).
(def PROGRESS-STYLE-BAR 0)
(def PROGRESS-STYLE-SPINNING 1)
;; NSLevelIndicatorStyle (NSLevelIndicator.h): relevancy 0, continuous 1,
;; discrete 2, rating 3.
(def LEVEL-STYLE-CONTINUOUS 1)
(def LEVEL-STYLE-DISCRETE 2)
;; NSImageScaling (NSImageCell.h): proportionally-down 3 is the sane default for
;; an image view given an arbitrary file.
(def IMAGE-SCALE-PROPORTIONAL-DOWN 3)
;; NSEventType
(def EVENT-APPLICATION-DEFINED 15)
;; NSLayoutPriority
(def PRIORITY-REQUIRED 1000)
(def PRIORITY-LOW 250)
(def PRIORITY-VERY-LOW 1)
;; NSAttributedString attribute keys — the constants ARE these strings
(def NS-FONT-ATTR "NSFont")
(def NS-FOREGROUND-COLOR-ATTR "NSColor")
(def NS-STRIKETHROUGH-ATTR "NSStrikethrough")
(def NS-STRIKETHROUGH-COLOR-ATTR "NSStrikethroughColor")
(def NS-UNDERLINE-ATTR "NSUnderline")

;; --- ObjC runtime ------------------------------------------------------------
;; objc_msgSend bound at the arities the backend needs. Each is a distinct
;; foreign-procedure over the same symbol; the dispatcher forwards registers
;; verbatim, so the typed shape only has to match the method's calling
;; convention, not its declared prototype.
(ffi/defcfn objc-msg-send-0        "objc_msgSend" [:pointer :pointer] :pointer)
(ffi/defcfn objc-msg-send-0void    "objc_msgSend" [:pointer :pointer] :void)
(ffi/defcfn objc-msg-send-0i64     "objc_msgSend" [:pointer :pointer] :int64)
(ffi/defcfn objc-msg-send-0d       "objc_msgSend" [:pointer :pointer] :double)
(ffi/defcfn objc-msg-send-0char    "objc_msgSend" [:pointer :pointer] :char)
(ffi/defcfn objc-msg-send-0cstr    "objc_msgSend" [:pointer :pointer] :string)
(ffi/defcfn objc-msg-send-1p       "objc_msgSend" [:pointer :pointer :pointer] :pointer)
(ffi/defcfn objc-msg-send-1pvoid   "objc_msgSend" [:pointer :pointer :pointer] :void)
(ffi/defcfn objc-msg-send-1pchar   "objc_msgSend" [:pointer :pointer :pointer] :char)
(ffi/defcfn objc-msg-send-1s       "objc_msgSend" [:pointer :pointer :string] :pointer)
(ffi/defcfn objc-msg-send-1d       "objc_msgSend" [:pointer :pointer :double] :pointer)
(ffi/defcfn objc-msg-send-1dvoid   "objc_msgSend" [:pointer :pointer :double] :void)
(ffi/defcfn objc-msg-send-1i64     "objc_msgSend" [:pointer :pointer :int64] :pointer)
(ffi/defcfn objc-msg-send-1i64void "objc_msgSend" [:pointer :pointer :int64] :void)
;; BOOL args are :int — jolt's :char is a Scheme CHARACTER, not an 8-bit int
;; (the GTK backend used :int for the same reason). BOOL returns stay :char.
(ffi/defcfn objc-msg-send-1intvoid "objc_msgSend" [:pointer :pointer :int] :void)
(ffi/defcfn objc-msg-send-1d1i64void "objc_msgSend" [:pointer :pointer :double :int64] :void)
;; setContentHuggingPriority:forOrientation: — NSLayoutPriority is a FLOAT
(ffi/defcfn objc-msg-send-1f1i64void "objc_msgSend" [:pointer :pointer :float :int64] :void)
;; setPriority: (NSLayoutConstraint) — NSLayoutPriority is a FLOAT
(ffi/defcfn objc-msg-send-1fvoid "objc_msgSend" [:pointer :pointer :float] :void)
(ffi/defcfn objc-msg-send-2dvoid   "objc_msgSend" [:pointer :pointer :double :double] :void)
(ffi/defcfn objc-msg-send-4d       "objc_msgSend" [:pointer :pointer :double :double :double :double] :pointer)
(ffi/defcfn objc-msg-send-4dvoid   "objc_msgSend" [:pointer :pointer :double :double :double :double] :void)
;; initWithFrame:pullsDown: (NSPopUpButton) — CGRect as 4 doubles, then a BOOL
;; (:int, per the BOOL-args rule above)
(ffi/defcfn objc-msg-send-4d1i "objc_msgSend" [:pointer :pointer :double :double :double :double :int] :pointer)
;; window init: (id, SEL, CGRect as 4 doubles, NSUInteger style, NSInteger backing, BOOL defer)
(ffi/defcfn objc-msg-send-4d3
  "objc_msgSend" [:pointer :pointer :double :double :double :double :int64 :int64 :int] :pointer)
;; button/checkbox factories: (id, SEL, NSString* title, id target, SEL action)
(ffi/defcfn objc-msg-send-3p "objc_msgSend" [:pointer :pointer :pointer :pointer :pointer] :pointer)
(ffi/defcfn objc-msg-send-1p1i64void "objc_msgSend" [:pointer :pointer :pointer :int64] :void)
;; (id, SEL, id) -> NSInteger (indexOfArrangedSubview:)
(ffi/defcfn objc-msg-send-1p-i64ret "objc_msgSend" [:pointer :pointer :pointer] :int64)
;; addAttribute:value:range: — (id, SEL, id key, id value, NSUInteger loc, NSUInteger len)
(ffi/defcfn objc-msg-send-2p2i64void "objc_msgSend" [:pointer :pointer :pointer :pointer :int64 :int64] :void)
;; performSelectorOnMainThread:withObject:waitUntilDone:
(ffi/defcfn objc-msg-send-3pchar "objc_msgSend" [:pointer :pointer :pointer :pointer :int] :void)
;; scheduledTimerWithTimeInterval:target:selector:userInfo:repeats:
(ffi/defcfn objc-msg-send-1d3pchar
  "objc_msgSend" [:pointer :pointer :double :pointer :pointer :pointer :int] :pointer)
;; postEvent:atStart:
(ffi/defcfn objc-msg-send-1pcharvoid "objc_msgSend" [:pointer :pointer :pointer :int] :void)
;; constraintWithItem:attribute:relatedBy:toItem:attribute:multiplier:constant:
(ffi/defcfn objc-msg-send-constraint
  "objc_msgSend" [:pointer :pointer :pointer :int64 :int64 :pointer :int64 :double :double] :pointer)
;; otherEventWithType:location:modifierFlags:timestamp:windowNumber:context:subtype:data1:data2:
(ffi/defcfn objc-msg-send-other-event
  "objc_msgSend"
  [:pointer :pointer :int64 :double :double :int64 :double :int64 :pointer :int :int64 :int64] :pointer)
;; [NSApp run] blocks for the app's lifetime — :blocking so it won't pin the GC.
(ffi/defcfn objc-msg-send-run "objc_msgSend" [:pointer :pointer] :void :blocking)

(ffi/defcfn objc-get-class           "objc_getClass"           [:string] :pointer)
(ffi/defcfn sel-register-name        "sel_registerName"        [:string] :pointer)
(ffi/defcfn objc-allocate-class-pair "objc_allocateClassPair"  [:pointer :string :size_t] :pointer)
(ffi/defcfn objc-register-class-pair "objc_registerClassPair"  [:pointer] :void)
(ffi/defcfn class-add-method         "class_addMethod"         [:pointer :pointer :pointer :string] :char)
(ffi/defcfn objc-autorelease-pool-push "objc_autoreleasePoolPush" [] :pointer)
(ffi/defcfn objc-autorelease-pool-pop  "objc_autoreleasePoolPop"  [:pointer] :void)

;; --- CFRunLoop (scheduling onto the main loop, pure C — no blocks needed) ----
(ffi/defcfn cf-run-loop-get-main      "CFRunLoopGetMain"      [] :pointer)
(ffi/defcfn cf-run-loop-source-create "CFRunLoopSourceCreate" [:pointer :int64 :pointer] :pointer)
(ffi/defcfn cf-run-loop-add-source    "CFRunLoopAddSource"    [:pointer :pointer :pointer] :void)
(ffi/defcfn cf-run-loop-remove-source "CFRunLoopRemoveSource" [:pointer :pointer :pointer] :void)
(ffi/defcfn cf-run-loop-contains-source "CFRunLoopContainsSource" [:pointer :pointer :pointer] :char)
(ffi/defcfn cf-run-loop-run-in-mode
  "CFRunLoopRunInMode" [:pointer :double :int] :int)
(ffi/defcfn cf-run-loop-source-signal "CFRunLoopSourceSignal" [:pointer] :void)
(ffi/defcfn cf-run-loop-wake-up       "CFRunLoopWakeUp"       [:pointer] :void)
;; CFRunLoopAddSource hashes the mode string and does NOT accept NULL (unlike
;; CFRunLoopAddTimer, whose docs bless NULL as "default mode"). Create the
;; mode string once instead of relying on a global we can't bind. Note the
;; special kCFRunLoopCommonModes constant must NOT be reproduced this way —
;; CF recognizes that one by identity and a value-equal copy doesn't get added
;; to the loop's common modes — so use the default mode, which is where
;; [NSApp run] runs.
(ffi/defcfn cf-string-create-with-cstring
  "CFStringCreateWithCString" [:pointer :string :int64] :pointer)

(def ^:private kCFRunLoopDefaultMode
  ;; kCFStringEncodingUTF8
  (cf-string-create-with-cstring ffi/null "kCFRunLoopDefaultMode" 134217984))

(defn default-mode
  "The CFString naming the run loop's default mode (retained by the loop)."
  [] kCFRunLoopDefaultMode)

;; --- selector / class caches -------------------------------------------------
(def ^:private sel-cache (atom {}))
(defn sel
  "Register (once) and return the selector for a method name."
  [name]
  (or (get @sel-cache name)
      (let [s (sel-register-name name)]
        (swap! sel-cache assoc name s)
        s)))

(def ^:private class-cache (atom {}))
(defn cls
  "Look up (once) and return the ObjC class for a name."
  [name]
  (or (get @class-cache name)
      (let [c (objc-get-class name)]
        (swap! class-cache assoc name c)
        c)))

;; --- strings ----------------------------------------------------------------
(defn nsstring
  "Create an NSString (autoreleased) from a jolt string."
  [s]
  (objc-msg-send-1s (cls "NSString") (sel "stringWithUTF8String:") s))

(defn nsstring->str
  "Read an NSString as a jolt string, or nil when the pointer is null."
  [s]
  (when-not (ffi/null? s)
    (objc-msg-send-0cstr s (sel "UTF8String"))))

(defn number-int
  "An NSNumber wrapping an integer (for attributed-string attribute values)."
  [n]
  (objc-msg-send-1i64 (cls "NSNumber") (sel "numberWithInt:") n))

;; --- NSApplication ----------------------------------------------------------
(defn shared-application [] (objc-msg-send-0 (cls "NSApplication") (sel "sharedApplication")))
(defn set-activation-policy! [app v] (objc-msg-send-1i64void app (sel "setActivationPolicy:") v))
(defn run-app! [app] (objc-msg-send-run app (sel "run")))
(defn stop-app! [app] (objc-msg-send-1pvoid app (sel "stop:") ffi/null))
(defn terminate-app! [app] (objc-msg-send-1pvoid app (sel "terminate:") ffi/null))
(defn activate! [app] (objc-msg-send-1intvoid app (sel "activateIgnoringOtherApps:") 1))
(defn set-app-delegate! [app d] (objc-msg-send-1pvoid app (sel "setDelegate:") d))
(defn post-event-at-start! [app e] (objc-msg-send-1pcharvoid app (sel "postEvent:atStart:") e 1))
(defn application-defined-event []
  (objc-msg-send-other-event
   (cls "NSEvent") (sel "otherEventWithType:location:modifierFlags:timestamp:windowNumber:context:subtype:data1:data2:")
   EVENT-APPLICATION-DEFINED 0.0 0.0 0 0.0 0 ffi/null 0 0 0))

;; --- NSWindow ---------------------------------------------------------------
(defn window-title! [w t] (objc-msg-send-1pvoid w (sel "setTitle:") (nsstring t)))
(defn window-new
  "Create an NSWindow with the given content size and title."
  [title width height]
  (let [w (objc-msg-send-4d3
           (objc-msg-send-0 (cls "NSWindow") (sel "alloc"))
           (sel "initWithContentRect:styleMask:backing:defer:")
           0.0 0.0 (double width) (double height) WINDOW-STYLE NS-BACKING-BUFFERED 0)]
    (objc-msg-send-1intvoid w (sel "setReleasedWhenClosed:") 0)
    (when title (window-title! w title))
    w))
(defn window-content [w] (objc-msg-send-0 w (sel "contentView")))
(defn window-center! [w] (objc-msg-send-0void w (sel "center")))
(defn window-show! [w] (objc-msg-send-1pvoid w (sel "makeKeyAndOrderFront:") ffi/null))
(defn window-hide! [w] (objc-msg-send-1pvoid w (sel "orderOut:") ffi/null))

;; --- NSView layout ----------------------------------------------------------
(defn add-subview! [parent child] (objc-msg-send-1pvoid parent (sel "addSubview:") child))
(defn remove-from-superview! [v] (objc-msg-send-0void v (sel "removeFromSuperview")))
(defn set-translates-autoresizing! [v b]
  (objc-msg-send-1intvoid v (sel "setTranslatesAutoresizingMaskIntoConstraints:") (if b 1 0)))
(defn set-tooltip! [v s] (objc-msg-send-1pvoid v (sel "setToolTip:") (nsstring s)))
(defn set-hidden! [v b] (objc-msg-send-1intvoid v (sel "setHidden:") (if b 1 0)))
(defn set-hugging! [v priority orientation]
  ;; NSLayoutPriority is a FLOAT (32-bit), not CGFloat — a double's low bits
  ;; read as ~0.0f and AppKit rejects it.
  (objc-msg-send-1f1i64void v (sel "setContentHuggingPriority:forOrientation:") (float priority) orientation))
(defn constraint [view1 attr1 view2 attr2 multiplier constant]
  (objc-msg-send-constraint
   (cls "NSLayoutConstraint")
   (sel "constraintWithItem:attribute:relatedBy:toItem:attribute:multiplier:constant:")
   view1 attr1 RELATION-EQUAL view2 attr2 (double multiplier) (double constant)))
(defn- pin-constraints! [child parent priority]
  (doseq [[a1 a2] [[ATTR-LEADING ATTR-LEADING] [ATTR-TRAILING ATTR-TRAILING]
                   [ATTR-TOP ATTR-TOP] [ATTR-BOTTOM ATTR-BOTTOM]]]
    (let [c (constraint child a1 parent a2 1.0 0.0)]
      (when (< priority PRIORITY-REQUIRED)
        (objc-msg-send-1fvoid c (sel "setPriority:") (float priority)))
      (objc-msg-send-1pvoid parent (sel "addConstraint:") c))))
(defn pin!
  "Pin `child` to fill `parent` via autolayout (required priority)."
  [child parent]
  (set-translates-autoresizing! child false)
  (pin-constraints! child parent PRIORITY-REQUIRED))
(defn set-width!
  "Give `v` a fixed width through autolayout: a self-constraint of
  width == points.

  This is what :width-request needs and what setPreferredMaxLayoutWidth: is NOT
  — the latter is a text-WRAPPING hint that leaves a control free to be
  compressed to nothing. Measured before adding this: an :entry in a stack with
  no width constraint is squeezed to zero width by a sibling, or clipped so a
  10-character date renders as \"26.08.20\"."
  [v points]
  (set-translates-autoresizing! v false)
  (objc-msg-send-1pvoid v (sel "addConstraint:")
                        (constraint v ATTR-WIDTH ffi/null ATTR-NOT-AN-ATTRIBUTE 1.0 points)))

(defn pin-low!
  "Pin `child` to `parent` at low priority — the child's intrinsic size wins
  when larger, so it can scroll. For a scroll view's document view."
  [child parent]
  (set-translates-autoresizing! child false)
  (pin-constraints! child parent PRIORITY-LOW))

;; --- NSStackView ------------------------------------------------------------
(defn stack-new []
  (objc-msg-send-1p (cls "NSStackView") (sel "stackViewWithViews:")
                    (objc-msg-send-0 (cls "NSArray") (sel "array"))))
(defn stack-orientation [s] (objc-msg-send-0i64 s (sel "orientation")))
(defn stack-orientation! [s o] (objc-msg-send-1i64void s (sel "setOrientation:") o))
(defn stack-spacing! [s d] (objc-msg-send-1dvoid s (sel "setSpacing:") (double d)))
(defn stack-alignment! [s a] (objc-msg-send-1i64void s (sel "setAlignment:") a))
(defn stack-distribution! [s d] (objc-msg-send-1i64void s (sel "setDistribution:") d))
(defn stack-edge-insets! [s top left bottom right]
  (objc-msg-send-4dvoid s (sel "setEdgeInsets:")
                        (double (or top 0)) (double (or left 0))
                        (double (or bottom 0)) (double (or right 0))))
(defn stack-add-arranged! [s v] (objc-msg-send-1pvoid s (sel "addArrangedSubview:") v))
(defn stack-remove-arranged! [s v] (objc-msg-send-1pvoid s (sel "removeArrangedSubview:") v))
(defn stack-insert-arranged! [s v i] (objc-msg-send-1p1i64void s (sel "insertArrangedSubview:atIndex:") v i))
(defn stack-index-of!
  "The arranged index of `v` (NSStackView has no indexOfArrangedSubview:; ask its
  arrangedSubviews array, which does)."
  [s v]
  (objc-msg-send-1p-i64ret (objc-msg-send-0 s (sel "arrangedSubviews")) (sel "indexOfObject:") v))
(defn stack-arranged! [s] (objc-msg-send-0 s (sel "arrangedSubviews")))

;; --- NSArray ----------------------------------------------------------------
(defn array-count [a] (objc-msg-send-0i64 a (sel "count")))
(defn array-get [a i] (objc-msg-send-1i64 a (sel "objectAtIndex:") i))

;; --- controls ---------------------------------------------------------------
(defn button-new
  "A push button with an optional title (target/action wired later)."
  [title]
  (objc-msg-send-3p (cls "NSButton") (sel "buttonWithTitle:target:action:")
                    (nsstring title) ffi/null ffi/null))
(defn checkbox-new
  "A switch-style checkbox (the AppKit analogue of GTK's checkbutton)."
  [title]
  (objc-msg-send-3p (cls "NSButton") (sel "checkboxWithTitle:target:action:")
                    (nsstring title) ffi/null ffi/null))
(defn label-new
  "A non-editable, non-bordered label."
  [s]
  (objc-msg-send-1p (cls "NSTextField") (sel "labelWithString:") (nsstring s)))
(defn entry-new
  "An editable, bordered text field."
  []
  (let [e (objc-msg-send-4d (objc-msg-send-0 (cls "NSTextField") (sel "alloc"))
                            (sel "initWithFrame:") 0.0 0.0 0.0 0.0)]
    (objc-msg-send-1i64void e (sel "setBezelStyle:") BEZEL-ROUNDED)
    (objc-msg-send-1intvoid e (sel "setEditable:") 1)
    (objc-msg-send-1intvoid e (sel "setSelectable:") 1)
    (objc-msg-send-1intvoid e (sel "setBordered:") 1)
    (objc-msg-send-1intvoid e (sel "setDrawsBackground:") 1)
    e))
(defn control-title! [c s] (objc-msg-send-1pvoid c (sel "setTitle:") (nsstring s)))
(defn control-string! [c s] (objc-msg-send-1pvoid c (sel "setStringValue:") (nsstring s)))
(defn control-string [c] (nsstring->str (objc-msg-send-0 c (sel "stringValue"))))
(defn control-placeholder! [c s] (objc-msg-send-1pvoid c (sel "setPlaceholderString:") (nsstring s)))
(defn control-enabled! [c b] (objc-msg-send-1intvoid c (sel "setEnabled:") (if b 1 0)))
(defn control-target! [c t] (objc-msg-send-1pvoid c (sel "setTarget:") t))
(defn control-action! [c s] (objc-msg-send-1pvoid c (sel "setAction:") s))
(defn control-delegate! [c d] (objc-msg-send-1pvoid c (sel "setDelegate:") d))
(defn control-state! [c v] (objc-msg-send-1i64void c (sel "setState:") v))
;; New relative to the glimmer-uikit original, which only ever SET the state.
;; glitter's renderer re-reads a value-bearing widget's own property AFTER its
;; event fires (see glitter-uikit.appkit's signal-value table), so :checkbutton
;; needs a real getter. NSControl's -state returns NSControlStateValue, an
;; NSInteger.
(defn control-state [c] (objc-msg-send-0i64 c (sel "state")))
(defn control-align! [c a] (objc-msg-send-1i64void c (sel "setAlignment:") a))
(defn control-line-break! [c m] (objc-msg-send-1i64void c (sel "setLineBreakMode:") m))
(defn control-max-lines! [c n] (objc-msg-send-1i64void c (sel "setMaximumNumberOfLines:") n))
(defn control-preferred-width! [c d] (objc-msg-send-1dvoid c (sel "setPreferredMaxLayoutWidth:") (double d)))
(defn control-attributed! [c a] (objc-msg-send-1pvoid c (sel "setAttributedStringValue:") a))
(defn control-font! [c f] (objc-msg-send-1pvoid c (sel "setFont:") f))

(defn control-double! [c d] (objc-msg-send-1dvoid c (sel "setDoubleValue:") (double d)))
(defn control-double [c] (objc-msg-send-0d c (sel "doubleValue")))
(defn control-min! [c d] (objc-msg-send-1dvoid c (sel "setMinValue:") (double d)))
(defn control-max! [c d] (objc-msg-send-1dvoid c (sel "setMaxValue:") (double d)))

;; --- NSPopUpButton (the :drop-down tag) --------------------------------------
;; initWithFrame:pullsDown: rather than the +popUpButtonWithTitle: convenience,
;; because pullsDown:NO is what makes it a select-one POP-UP rather than a
;; pull-down MENU, and the convenience constructors do not expose that choice.
(defn popup-new []
  (objc-msg-send-4d1i
   (objc-msg-send-0 (cls "NSPopUpButton") (sel "alloc"))
   (sel "initWithFrame:pullsDown:") 0.0 0.0 0.0 0.0 0))
(defn popup-add-item! [p s] (objc-msg-send-1pvoid p (sel "addItemWithTitle:") (nsstring s)))
(defn popup-remove-all! [p] (objc-msg-send-0void p (sel "removeAllItems")))
(defn popup-count [p] (objc-msg-send-0i64 p (sel "numberOfItems")))
(defn popup-select! [p i] (objc-msg-send-1i64void p (sel "selectItemAtIndex:") i))
;; -indexOfSelectedItem returns -1 when nothing is selected, which is a real
;; state (an emptied menu), not an error. Callers get the raw NSInteger and
;; decide; the widget layer maps it through selected-index.
(defn popup-selected [p] (objc-msg-send-0i64 p (sel "indexOfSelectedItem")))

;; --- NSSlider (:scale) / NSStepper (:spin-button) ----------------------------
(defn slider-new []
  (objc-msg-send-4d (objc-msg-send-0 (cls "NSSlider") (sel "alloc"))
                    (sel "initWithFrame:") 0.0 0.0 0.0 0.0))
(defn slider-ticks! [s n] (objc-msg-send-1i64void s (sel "setNumberOfTickMarks:") n))
(defn slider-only-ticks! [s b] (objc-msg-send-1intvoid s (sel "setAllowsTickMarkValuesOnly:") (if b 1 0)))
(defn stepper-new []
  (objc-msg-send-4d (objc-msg-send-0 (cls "NSStepper") (sel "alloc"))
                    (sel "initWithFrame:") 0.0 0.0 0.0 0.0))
(defn stepper-increment! [s d] (objc-msg-send-1dvoid s (sel "setIncrement:") (double d)))
(defn stepper-wraps! [s b] (objc-msg-send-1intvoid s (sel "setValueWraps:") (if b 1 0)))

;; --- NSProgressIndicator (:progress-bar) / NSLevelIndicator (:level-bar) -----
;; NSProgressIndicator is an NSView, NOT an NSControl, so it has no target/action
;; and no -doubleValue via NSControl — but it does declare its own -doubleValue /
;; -setDoubleValue:, so control-double!/control-double work on it by selector.
(defn progress-new []
  (let [p (objc-msg-send-4d (objc-msg-send-0 (cls "NSProgressIndicator") (sel "alloc"))
                            (sel "initWithFrame:") 0.0 0.0 0.0 0.0)]
    (objc-msg-send-1i64void p (sel "setStyle:") PROGRESS-STYLE-BAR)
    (objc-msg-send-1intvoid p (sel "setIndeterminate:") 0)
    ;; 0..1, so glitter's :fraction maps straight onto -setDoubleValue: without
    ;; the widget layer rescaling. AppKit's own default range is 0..100.
    (objc-msg-send-1dvoid p (sel "setMinValue:") 0.0)
    (objc-msg-send-1dvoid p (sel "setMaxValue:") 1.0)
    p))
(defn progress-indeterminate! [p b] (objc-msg-send-1intvoid p (sel "setIndeterminate:") (if b 1 0)))
(defn progress-start! [p] (objc-msg-send-1pvoid p (sel "startAnimation:") ffi/null))
(defn progress-stop! [p] (objc-msg-send-1pvoid p (sel "stopAnimation:") ffi/null))
(defn level-new []
  (objc-msg-send-4d (objc-msg-send-0 (cls "NSLevelIndicator") (sel "alloc"))
                    (sel "initWithFrame:") 0.0 0.0 0.0 0.0))
(defn level-style! [l v] (objc-msg-send-1i64void l (sel "setLevelIndicatorStyle:") v))

;; --- NSSwitch (:switch) ------------------------------------------------------
;; API_AVAILABLE(macos(10.15)) in NSSwitch.h — the only widget here that is not
;; available on this project's former 10.13 floor. objc-get-class returns null
;; on an older system rather than trapping, so switch-supported? lets the widget
;; layer fail with a clear message instead of a null-pointer crash.
(defn switch-supported? [] (let [c (objc-get-class "NSSwitch")] (and c (not (ffi/null? c)))))
(defn switch-new []
  (objc-msg-send-4d (objc-msg-send-0 (cls "NSSwitch") (sel "alloc"))
                    (sel "initWithFrame:") 0.0 0.0 0.0 0.0))

;; --- NSSecureTextField (:password-entry) / NSSearchField (:search-entry) -----
;; Both are NSTextField subclasses, so every entry prop applier (control-string!,
;; control-placeholder!, control-enabled!) and the controlTextDidChange: delegate
;; path work on them unchanged.
(defn secure-entry-new []
  (let [e (objc-msg-send-4d (objc-msg-send-0 (cls "NSSecureTextField") (sel "alloc"))
                            (sel "initWithFrame:") 0.0 0.0 0.0 0.0)]
    (objc-msg-send-1i64void e (sel "setBezelStyle:") BEZEL-ROUNDED)
    (objc-msg-send-1intvoid e (sel "setEditable:") 1)
    (objc-msg-send-1intvoid e (sel "setBordered:") 1)
    e))
(defn search-entry-new []
  (let [e (objc-msg-send-4d (objc-msg-send-0 (cls "NSSearchField") (sel "alloc"))
                            (sel "initWithFrame:") 0.0 0.0 0.0 0.0)]
    (objc-msg-send-1intvoid e (sel "setEditable:") 1)
    e))

;; --- NSImageView (:image) ----------------------------------------------------
(defn image-view-new []
  (let [v (objc-msg-send-4d (objc-msg-send-0 (cls "NSImageView") (sel "alloc"))
                            (sel "initWithFrame:") 0.0 0.0 0.0 0.0)]
    (objc-msg-send-1i64void v (sel "setImageScaling:") IMAGE-SCALE-PROPORTIONAL-DOWN)
    v))
;; Returns null for a missing/unreadable path — NSImage's initWithContentsOfFile:
;; returns nil rather than raising, so the widget layer checks before setting.
(defn image-from-file [path]
  (objc-msg-send-1p (objc-msg-send-0 (cls "NSImage") (sel "alloc"))
                    (sel "initWithContentsOfFile:") (nsstring path)))
(defn image-named [name] (objc-msg-send-1p (cls "NSImage") (sel "imageNamed:") (nsstring name)))
(defn image-view-image! [v img] (objc-msg-send-1pvoid v (sel "setImage:") img))

;; --- NSBox / NSScrollView ---------------------------------------------------
(defn box-new
  "A titled NSBox — the AppKit analogue of GTK's frame."
  []
  (let [b (objc-msg-send-4d
           (objc-msg-send-0 (cls "NSBox") (sel "alloc"))
           (sel "initWithFrame:") 0.0 0.0 0.0 0.0)]
    (objc-msg-send-1i64void b (sel "setTitlePosition:") TITLE-POSITION-AT-TOP)
    (objc-msg-send-2dvoid b (sel "setContentViewMargins:") 0.0 0.0)
    b))
(defn box-title! [b s] (objc-msg-send-1pvoid b (sel "setTitle:") (nsstring s)))
(defn box-content [b] (objc-msg-send-0 b (sel "contentView")))
(defn separator-new
  "An NSBox separator — a horizontal line. (AppKit has no vertical separator
  primitive; a :vertical :separator renders as nothing in v1.)"
  []
  (let [b (objc-msg-send-4d
           (objc-msg-send-0 (cls "NSBox") (sel "alloc"))
           (sel "initWithFrame:") 0.0 0.0 0.0 0.0)]
    (objc-msg-send-1i64void b (sel "setBoxType:") BOX-SEPARATOR)
    b))
(defn scroll-new []
  (let [s (objc-msg-send-4d (objc-msg-send-0 (cls "NSScrollView") (sel "alloc"))
                            (sel "initWithFrame:") 0.0 0.0 0.0 0.0)]
    (objc-msg-send-1intvoid s (sel "setHasVerticalScroller:") 1)
    (objc-msg-send-1intvoid s (sel "setHasHorizontalScroller:") 0)
    (objc-msg-send-1intvoid s (sel "setAutohidesScrollers:") 1)
    s))
(defn scroll-document! [s v] (objc-msg-send-1pvoid s (sel "setDocumentView:") v))
(defn scroll-clip [s] (objc-msg-send-0 s (sel "contentView")))
(defn scroll-top! [s]
  (let [clip (scroll-clip s)]
    (when-not (ffi/null? clip)
      (objc-msg-send-2dvoid clip (sel "scrollToPoint:") 0.0 0.0))))

;; --- fonts / colors / attributed strings ------------------------------------
(defn system-font-size [size] (objc-msg-send-1d (cls "NSFont") (sel "systemFontOfSize:") (double size)))
(defn bold-font-size [size] (objc-msg-send-1d (cls "NSFont") (sel "boldSystemFontOfSize:") (double size)))
(defn italic-font-size [size] (objc-msg-send-1d (cls "NSFont") (sel "italicSystemFontOfSize:") (double size)))
(defn- hex-digit [c]
  (let [n (int c)]
    (cond (<= 48 n 57) (- n 48)
          (<= 97 n 102) (- n 87)
          (<= 65 n 70) (- n 55)
          :else (throw (ex-info (str "glitter-uikit: bad hex digit " c) {})))))
(defn- hex->int [s] (reduce (fn [acc c] (+ (* acc 16) (hex-digit c))) 0 s))
(defn color-hex
  "Parse \"#rrggbb\" (or \"#rgb\") into an NSColor."
  [hex]
  (let [h (subs hex 1)
        h (if (= 3 (count h)) (apply str (mapcat (fn [c] [c c]) h)) h)]
    (objc-msg-send-4d (cls "NSColor") (sel "colorWithSRGBRed:green:blue:alpha:")
                      (/ (hex->int (subs h 0 2)) 255.0)
                      (/ (hex->int (subs h 2 4)) 255.0)
                      (/ (hex->int (subs h 4 6)) 255.0)
                      1.0)))
(defn attributed-new
  "An NSMutableAttributedString over a plain string."
  [s]
  (objc-msg-send-1p (objc-msg-send-0 (cls "NSMutableAttributedString") (sel "alloc"))
                    (sel "initWithString:") (nsstring s)))
(defn attributed-add!
  "Add an attribute over [start, start+len)."
  [a key value start len]
  (objc-msg-send-2p2i64void a (sel "addAttribute:value:range:") (nsstring key) value start len))
(defn attributed-length [a] (objc-msg-send-0i64 a (sel "length")))

;; --- NSTimer / NSRunLoop ----------------------------------------------------
(defn timer-after!
  "Schedule a one-shot timer on the current thread's run loop; `target`/`selector`
  fire on the main loop while NSApplication runs."
  [ms target selector]
  (objc-msg-send-1d3pchar
   (cls "NSTimer")
   (sel "scheduledTimerWithTimeInterval:target:selector:userInfo:repeats:")
   (/ (double ms) 1000.0) target selector ffi/null 0))
(defn timer-every!
  "Schedule a REPEATING timer on the current thread's run loop. Same selector
  shape as timer-after!; the only difference is repeats:YES, which is the last
  argument (a BOOL, so :int per the BOOL-args rule above).

  The returned NSTimer is the cancellation handle — a repeating timer runs until
  it is invalidated, so a caller that drops the handle can never stop it."
  [ms target selector]
  (objc-msg-send-1d3pchar
   (cls "NSTimer")
   (sel "scheduledTimerWithTimeInterval:target:selector:userInfo:repeats:")
   (/ (double ms) 1000.0) target selector ffi/null 1))
(defn timer-invalidate! [t] (objc-msg-send-0void t (sel "invalidate")))
(defn current-run-loop [] (objc-msg-send-0 (cls "NSRunLoop") (sel "currentRunLoop")))
(defn run-loop-until! [rl date] (objc-msg-send-1p rl (sel "runUntilDate:") date))
(defn date-in [secs] (objc-msg-send-1d (cls "NSDate") (sel "dateWithTimeIntervalSinceNow:") (double secs)))
