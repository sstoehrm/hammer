(ns hammer.core)

(defn- deps-of
  "Indices of the slots named anywhere in form (metadata included)."
  [slots form]
  (let [used (set (filter symbol? (tree-seq coll? #(concat (seq %) (meta %)) form)))]
    (vec (keep-indexed (fn [i s] (when (used s) i)) slots))))

(defn- binding-spec
  "slots: props and earlier binding names visible to this init."
  [slots [_ init]]
  (let [deps (deps-of slots init)]
    `{:kind ~(if (vector? init) :path :expr)
      :deps ~deps
      :f (fn ~(mapv slots deps) ~init)}))

(defmacro defc
  "(defc name [props*] [bindings*] body+)
  A vector-literal init is a db path; an init that evaluates to an atom is
  local state; anything else is derived from the props and earlier bindings
  it names, and re-runs only when one of them changed."
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
                             (binding-spec (subvec slots 0 (+ (count props) j)) pair))
                           pairs))
        ~(deps-of slots (vec body))
        (fn ~slots ~@body)))))
