(ns hammer.core)

(defn- deps-of
  "Indices of the slots named anywhere in form (metadata included)."
  [slots form]
  (let [used (set (filter symbol? (tree-seq coll? #(concat (seq %) (meta %)) form)))]
    (vec (keep-indexed (fn [i s] (when (used s) i)) slots))))

(defn- is?-form?
  "True for (is? ...), (hammer.core/is? ...) or (alias/is? ...) where alias
  names hammer.core in the ns requires. Decided by symbol, not by resolution."
  [env init]
  (and (seq? init)
       (symbol? (first init))
       (= "is?" (name (first init)))
       (let [q (namespace (first init))]
         (or (nil? q)
             (= 'hammer.core (symbol q))
             (= 'hammer.core (get-in env [:ns :requires (symbol q)]))))))

(defn- binding-spec
  "slots: props and earlier binding names visible to this init."
  [env slots [_ init]]
  (let [deps (deps-of slots init)
        eq? (is?-form? env init)]
    (when (and eq? (not= 3 (count init)))
      (throw (ex-info "defc: is? takes a path and a value" {:form init})))
    `{:kind ~(cond eq? :eq (vector? init) :path :else :expr)
      :deps ~deps
      :f (fn ~(mapv slots deps) ~(if eq? (vec (rest init)) init))}))

(defmacro defc
  "(defc name [props*] [bindings*] body+)
  A vector-literal init is a db path; (is? path v) is true iff the db value at
  path is = to v, and marks the instance only when that flips; an init that
  evaluates to an atom is local state; anything else is derived from the props
  and earlier bindings it names, and re-runs only when one of them changed.
  is? is recognized by symbol: unqualified is?, hammer.core/is? or an alias of
  hammer.core, as the whole init. path and v may name props and earlier bindings."
  [cname props bindings & body]
  (let [pairs (partition 2 bindings)
        slots (into (vec props) (map first pairs))]
    (when (odd? (count bindings))
      (throw (ex-info "defc: bindings need an even number of forms" {:name cname})))
    (when-not (every? simple-symbol? slots)
      (throw (ex-info "defc: props and binding names must be plain symbols" {:name cname})))
    (when-not (= (count slots) (count (set slots)))
      (throw (ex-info "defc: duplicate prop or binding name" {:name cname :slots slots})))
    `(def ~cname
       (hammer.cells/component
        ~(str cname)
        ~(count props)
        ~(vec (map-indexed (fn [j pair]
                             (binding-spec &env (subvec slots 0 (+ (count props) j)) pair))
                           pairs))
        ~(deps-of slots (vec body))
        (fn ~slots ~@body)))))
