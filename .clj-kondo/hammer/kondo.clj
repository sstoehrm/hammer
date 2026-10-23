(ns hammer.kondo
  "What clj-kondo sees in place of hammer's component macros: a fn of the
  props whose bindings are a let around the body. A db-path init is wrapped
  so it isn't typed as a vector, and (is? path v) becomes (do path v).
  Malformed forms (the macros' own error tests) lint as an empty fn.")

(defn- names? [v] (and (vector? v) (every? symbol? v)))

(defn- init [x]
  (cond (vector? x) `(deref (atom ~x))
        (and (seq? x) (symbol? (first x)) (= "is?" (name (first x)))) `(do ~@(rest x))
        :else x))

(defn- component [cname props bindings body]
  (if (and (names? props) (vector? bindings) (even? (count bindings))
           (names? (vec (take-nth 2 bindings))) (seq body))
    `(defn ~cname ~props
       (let ~(vec (mapcat (fn [[s i]] [s (init i)]) (partition 2 bindings)))
         ~@body))
    `(defn ~cname [])))

(defmacro defc
  "(defc name [props*] [bindings*] body+)"
  [cname props & [bindings & body]]
  (component cname props bindings body))

(defmacro defdraw
  "(defdraw name [props*] [bindings*] opts? draw-fn), and defloop alike"
  [cname props & [bindings & more]]
  (component cname props bindings more))

(defmacro defboth
  "test/hammer/tpl_macros.clj: name, a defc of args, and name-plain"
  [cname args body]
  `(do (defn ~cname ~args ~body)
       (defn ~(symbol (str cname "-plain")) ~args ~body)))
