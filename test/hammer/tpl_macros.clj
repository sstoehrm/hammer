(ns hammer.tpl-macros)

(defmacro defboth
  "Defines name, a defc whose slots args read the db keys of the same name
  (so body is compiled), and name-plain, a fn of args returning body as
  plain uncompiled hiccup."
  [name args body]
  `(do (hammer.core/defc ~name [] [~@(mapcat (fn [a] [a [(keyword a)]]) args)] ~body)
       (defn ~(symbol (str name "-plain")) [~@args] ~body)))

(defmacro with-local
  "A user macro that binds a local through let, for auto-bind tests."
  [[sym init] & body]
  `(let [~sym ~init] ~@body))

(defmacro local?
  "Whether sym is a local where the macro is expanded (as core.match checks),
  then body."
  [sym & body]
  `(str ~(contains? (:locals &env) sym) "/" ~@body))

(defmacro need-local
  "Throws at expansion unless sym is a local there, then body."
  [sym & body]
  (when-not (contains? (:locals &env) sym)
    (throw (ex-info (str "not a local: " sym) {})))
  `(do ~@body))
