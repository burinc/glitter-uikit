(ns glitter-uikit.ffi-test
  "Headless checks on the FFI layer's pure helpers. Deliberately avoids any
  objc_msgSend call so this runs where AppKit is absent (upstream CI is Linux),
  matching glimmer-uikit's own headless-test posture."
  (:require [clojure.test :refer [deftest is testing]]
            [glitter-uikit.ffi :as u]))

(deftest constants-match-appkit-values
  (testing "NSWindowStyleMask titled|closable|miniaturizable|resizable"
    (is (= 15 u/WINDOW-STYLE)))
  (testing "NSUserInterfaceLayoutOrientation"
    (is (= 0 u/ORIENTATION-HORIZONTAL))
    (is (= 1 u/ORIENTATION-VERTICAL)))
  (testing "NSControlStateValue"
    (is (= 0 u/STATE-OFF))
    (is (= 1 u/STATE-ON)))
  (testing "NSLayoutPriority — required must outrank low, low must outrank very low"
    (is (< u/PRIORITY-VERY-LOW u/PRIORITY-LOW u/PRIORITY-REQUIRED))))

(deftest attribute-keys-are-the-literal-appkit-strings
  (testing "the NSAttributedString attribute constants ARE these strings"
    (is (= "NSFont" u/NS-FONT-ATTR))
    (is (= "NSColor" u/NS-FOREGROUND-COLOR-ATTR))
    (is (= "NSStrikethrough" u/NS-STRIKETHROUGH-ATTR))
    (is (= "NSUnderline" u/NS-UNDERLINE-ATTR))))
