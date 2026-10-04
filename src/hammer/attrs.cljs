(ns hammer.attrs
  "How an attribute value is written, shared by hammer.dom and hammer.draw
  (the canvas :attrs), so both follow the same rules."
  (:require [clojure.string :as str]))

(defn- enumerated?
  "Attributes whose value is the string \"true\" or \"false\", not presence."
  [n]
  (or (= n "draggable") (= n "spellcheck") (= n "contenteditable") (= n "writingsuggestions")
      (str/starts-with? n "aria-")))

(defn set-plain!
  "nil removes attribute n; booleans add or remove it (\"true\"/\"false\" for
  the enumerated ones above); anything else is written with str."
  [^js el n v]
  (cond
    (nil? v) (.removeAttribute el n)
    (and (boolean? v) (enumerated? n)) (.setAttribute el n (if v "true" "false"))
    (false? v) (.removeAttribute el n)
    (true? v) (.setAttribute el n "")
    :else (.setAttribute el n (str v))))
