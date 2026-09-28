(ns hammer.macros
  "Binding compilation shared by defc (hammer.core) and defdraw/defloop
  (hammer.canvas, hammer.gpu).")

(def ^:private facades '#{hammer.core hammer.app hammer.canvas hammer.gpu hammer.gl})

(defn deps-of
  "Indices of the slots named anywhere in form (metadata included)."
  [slots form]
  (let [used (set (filter symbol? (tree-seq coll? #(concat (seq %) (meta %)) form)))]
    (vec (keep-indexed (fn [i s] (when (used s) i)) slots))))

(defn is?-form?
  "True for (is? ...), or (q/is? ...) where q is a hammer facade namespace or
  an alias of one in the ns requires. Decided by symbol, not by resolution."
  [env init]
  (and (seq? init)
       (symbol? (first init))
       (= "is?" (name (first init)))
       (let [q (namespace (first init))]
         (or (nil? q)
             (contains? facades (symbol q))
             (contains? facades (get-in env [:ns :requires (symbol q)]))))))

(defn lit? [x] (or (string? x) (number? x) (keyword? x) (boolean? x) (nil? x)))

(defn slot-fn
  "fn of the dep slots returning form; a vector of literals (a constant path)
  is built once and shared by every call."
  [args form]
  (if (and (vector? form) (every? lit? form))
    `(let [p# ~form] (fn ~args p#))
    `(fn ~args ~form)))

(defn binding-spec
  "slots: props and earlier binding names visible to this init. An :eq spec
  has :f for the path and :g for the compared value. macro names the calling
  macro in error messages."
  [env macro slots [_ init]]
  (let [deps (deps-of slots init)
        args (mapv slots deps)
        eq? (is?-form? env init)]
    (when (and eq? (not= 3 (count init)))
      (throw (ex-info (str macro ": is? takes a path and a value") {:form init})))
    (cond
      eq? `{:kind :eq :deps ~deps :f ~(slot-fn args (nth init 1)) :g (fn ~args ~(nth init 2))}
      (vector? init) `{:kind :path :deps ~deps :f ~(slot-fn args init)}
      :else `{:kind :expr :deps ~deps :f (fn ~args ~init)})))

(defn check-slots!
  "The binding checks shared by every component macro; messages start with macro."
  [macro cname props bindings]
  (let [slots (into (vec props) (map first (partition 2 bindings)))]
    (when (odd? (count bindings))
      (throw (ex-info (str macro ": bindings need an even number of forms") {:name cname})))
    (when-not (every? simple-symbol? slots)
      (throw (ex-info (str macro ": props and binding names must be plain symbols") {:name cname})))
    (when-not (= (count slots) (count (set slots)))
      (throw (ex-info (str macro ": duplicate prop or binding name") {:name cname :slots slots})))))

(defn draw-def
  "Expansion of defdraw/defloop for backend kind (:canvas or :gpu).
  more is [opts? draw-fn]; opts, when present, is a literal map."
  [env macro kind loop? cname props bindings more]
  (check-slots! macro cname props bindings)
  (let [[opts draw] (case (count more)
                      1 (if (map? (first more))
                          (throw (ex-info (str macro ": missing draw-fn after opts") {:name cname}))
                          [nil (first more)])
                      2 (if (map? (first more))
                          more
                          (throw (ex-info (str macro ": opts must be a literal map") {:name cname})))
                      (throw (ex-info (str macro ": expected [props] [bindings] opts? draw-fn") {:name cname})))
        pairs (partition 2 bindings)
        slots (into (vec props) (map first pairs))]
    `(def ~cname
       (hammer.draw/component
        ~(str cname)
        ~(count props)
        ~(vec (map-indexed (fn [j pair]
                             (binding-spec env macro (subvec slots 0 (+ (count props) j)) pair))
                           pairs))
        ~(deps-of slots [opts draw])
        (fn ~slots (cljs.core/array ~opts ~draw))
        ~kind
        ~loop?))))
