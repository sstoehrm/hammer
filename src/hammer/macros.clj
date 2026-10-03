(ns hammer.macros
  "Binding compilation shared by defc (hammer.core) and defdraw/defloop
  (hammer.canvas, hammer.gl)."
  (:require [clojure.string :as str]
            [cljs.analyzer]
            [cljs.analyzer.api]))

(def ^:private facades '#{hammer.core hammer.app hammer.canvas hammer.gl})

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
  ;; the bindings vector is required even when empty; a body in its place
  ;; would otherwise surface as a confusing slot error, or compile to nothing
  (when (or (not (vector? props)) (keyword? (first props)))
    (throw (ex-info (str macro " " cname ": missing props and bindings vectors, write ("
                         macro " " cname " [] [] body)")
                    {:name cname})))
  (when (or (not (vector? bindings)) (keyword? (first bindings)))
    (throw (ex-info (str macro " " cname ": missing bindings vector, write ("
                         macro " " cname " " (pr-str props) " [] body)")
                    {:name cname})))
  (let [slots (into (vec props) (map first (partition 2 bindings)))]
    (when (odd? (count bindings))
      (throw (ex-info (str macro ": bindings need an even number of forms") {:name cname})))
    (when-not (every? simple-symbol? slots)
      (throw (ex-info (str macro ": props and binding names must be plain symbols") {:name cname})))
    (when-not (= (count slots) (count (set slots)))
      (throw (ex-info (str macro ": duplicate prop or binding name") {:name cname :slots slots})))))

;; ---- auto-bound derefs
;;
;; @g in a body (or a binding init) where g is a global var is read during
;; render but tracked by nothing, so the component would never re-render when
;; g changes. Each such g gets a hidden binding initialised to g itself (a
;; watched :local slot when g is an atom, a plain value otherwise) and @g is
;; rewritten to deref that slot.
;;
;; Only subtrees containing a deref are walked. Binders are modeled on the
;; special forms (let*, loop*, fn*, letfn*, try/catch) and the core macros
;; compile-pos relies on; any other macro is expanded and its expansion walked,
;; so a local bound by any macro is seen as a local. Left alone: props and
;; bindings, locals, derefs inside fns (they run at event/draw time), non-symbol
;; targets, ^:once targets, and vars that are not plain user vars (js/, cljs.core,
;; macros, fns, dynamic vars). hammer.state/app-db warns instead: watching it
;; would re-render on every db change.

(defmethod cljs.analyzer/error-message ::app-db-deref [_ {:keys [msg]}] msg)

;; on by default for the stock cljs warning handler too (shadow-cljs shows any
;; warning type that is not switched off)
(alter-var-root #'cljs.analyzer/*cljs-warnings* assoc ::app-db-deref true)

(defn- head
  "Name of form's head when it is an unqualified or core symbol."
  [form]
  (when (and (seq? form) (symbol? (first form))
             (contains? #{nil "cljs.core" "clojure.core"} (namespace (first form))))
    (name (first form))))

(def ^:private fn-heads #{"fn" "fn*" "defn" "reify" "deftype" "defrecord"})
(def ^:private let-heads #{"let" "let*" "loop" "loop*" "when-let" "if-let" "when-some" "if-some"
                           "when-first" "dotimes" "with-open"})
(def ^:private seq-heads #{"for" "doseq"})
(def ^:private plain-macros
  "Core macros walked as written (they bind nothing); compile-pos in core.clj
  needs several of them unexpanded."
  #{"when" "when-not" "if-not" "cond" "case" "condp" "and" "or" "->" "->>" "some->" "some->>"
    "cond->" "cond->>" "doto" "comment" "assert"})

(defn- lhs-syms
  "Names bound by a destructuring form, over-approximated: every symbol in it,
  and the name of every keyword or qualified symbol (as in {:keys [:a b/c]})."
  [lhs]
  (into #{} (keep #(when (or (symbol? %) (keyword? %)) (symbol (name %)))) (tree-seq coll? seq lhs)))

(defn- deref-target [form]
  (when (and (seq? form) (= 2 (count form))
             (contains? #{'deref 'cljs.core/deref 'clojure.core/deref} (first form))
             (symbol? (second form)))
    (second form)))

(defn- has-deref? [form]
  (boolean (some deref-target (tree-seq coll? seq form))))

(declare rewrite)

(defn- rewrite-bindings
  "Rewrites the init forms of binding vector bv; returns [bv' locals']. seq?:
  a for/doseq vector, with :let [...] / :when / :while modifiers."
  [ctx locals bv seq?]
  (loop [pairs (partition 2 bv) out [] locals locals]
    (if-let [[l r] (first pairs)]
      (cond
        (and seq? (= l :let))
        (let [[r' locals'] (rewrite-bindings ctx locals r false)]
          (recur (rest pairs) (conj out l r') locals'))
        (and seq? (keyword? l))
        (recur (rest pairs) (conj out l (rewrite ctx locals r)) locals)
        :else
        (recur (rest pairs) (conj out l (rewrite ctx locals r)) (into locals (lhs-syms l))))
      [(with-meta out (meta bv)) locals])))

(defn- plain-var?
  "v (from cljs.analyzer.api/resolve) is a user var that can hold an atom."
  [v]
  (and v (not (contains? '#{js cljs.core} (:ns v)))
       (not (:macro v)) (not (:fn-var v)) (not (:dynamic v))))

(defn- auto-deref
  "The rewrite of (deref g): a deref of g's hidden slot, or form unchanged."
  [{:keys [env macro cname slots found pos]} locals form g]
  (let [v (when-not (or (nil? env) (= "js" (namespace g)) (:once (meta g)) (contains? slots g)
                        (contains? locals g) (contains? (:locals env) g))
            (cljs.analyzer.api/resolve env g))]
    (cond
      (not (plain-var? v)) form
      (= 'hammer.state/app-db (:name v))
      (do (cljs.analyzer/warning ::app-db-deref (merge env pos)
                                 {:msg (str macro " " cname ": @" g " re-renders only by chance; read the db"
                                            " through a path binding, e.g. [todos [:todos]]")})
          form)
      :else
      (let [k (:name v)
            s (or (get-in @found [k :slot])
                  (let [s (gensym (str (str/replace (name k) #"[^A-Za-z0-9_]" "_") "__auto"))]
                    (vswap! found assoc k {:slot s :sym g})
                    s))]
        (with-meta (list (first form) s) (meta form))))))

(defn- walk-all [ctx locals form]
  (with-meta (apply list (map #(rewrite ctx locals %) form)) (meta form)))

(defn- rewrite-seq [ctx locals form]
  (let [h (head form)
        m (meta form)
        rewrite* (fn [locals xs] (map #(rewrite ctx locals %) xs))]
    (cond
      (or (contains? fn-heads h) (= h "quote")) form

      (and (contains? #{"letfn" "letfn*"} h) (vector? (second form)))
      (let [fns (second form)
            names (into #{} (map #(if (seq? %) (first %) %)) (if (= h "letfn") fns (take-nth 2 fns)))]
        (with-meta (apply list (first form) fns (rewrite* (into locals names) (drop 2 form))) m))

      (= h "try")
      (with-meta (apply list (first form)
                        (map (fn [x]
                               (if (and (seq? x) (= 'catch (first x)))
                                 (apply list 'catch (second x) (nth x 2)
                                        (rewrite* (conj locals (nth x 2)) (drop 3 x)))
                                 (rewrite ctx locals x)))
                             (rest form)))
        m)

      (and (contains? let-heads h) (vector? (second form)))
      (let [[bv' locals'] (rewrite-bindings ctx locals (second form) false)]
        (with-meta (apply list (first form) bv' (rewrite* locals' (drop 2 form))) m))

      (and (contains? seq-heads h) (vector? (second form)))
      (let [[bv' locals'] (rewrite-bindings ctx locals (second form) true)]
        (with-meta (apply list (first form) bv' (rewrite* locals' (drop 2 form))) m))

      (contains? plain-macros h) (walk-all ctx locals form)

      ;; any other macro may bind locals: walk its expansion
      (and (symbol? (first form)) (not (contains? locals (first form)))
           (cljs.analyzer/get-expander (first form) (:env ctx)))
      (let [x (cljs.analyzer/macroexpand-1 (:env ctx) form)]
        (if (identical? x form) (walk-all ctx locals form) (rewrite ctx locals x)))

      :else (walk-all ctx locals form))))

(defn- rewrite
  "form with each auto-bound @g replaced by a deref of g's hidden slot."
  [ctx locals form]
  (let [m (meta form)
        ;; @x reads as a deref list without position; report the nearest enclosing one
        ctx (if (:line m) (assoc ctx :pos (select-keys m [:line :column])) ctx)]
    (cond
      (not (has-deref? form)) form
      (deref-target form) (auto-deref ctx locals form (deref-target form))
      (seq? form) (rewrite-seq ctx locals form)
      (vector? form) (with-meta (mapv #(rewrite ctx locals %) form) m)
      (map? form) (with-meta (into {} (map (fn [[k v]] [(rewrite ctx locals k) (rewrite ctx locals v)])) form) m)
      (set? form) (with-meta (into #{} (map #(rewrite ctx locals %)) form) m)
      :else form)))

(defn auto-bind
  "[bindings' forms'] for a component macro: each global atom deref'd in a
  binding init or in forms (the body, or a draw component's opts and fn) gets
  a hidden binding in front of the others, and those derefs read it."
  [env macro cname props bindings forms]
  (if (nil? env)
    [bindings forms]
    (let [pairs (partition 2 bindings)
          ctx {:env env :macro macro :cname cname :found (volatile! {})
               :slots (into (set props) (map first pairs))}
          pairs' (mapv (fn [[s init]] [s (rewrite ctx #{} init)]) pairs)
          forms' (mapv #(rewrite ctx #{} %) forms)
          hidden (mapcat (fn [{:keys [slot sym]}] [slot sym]) (vals @(:found ctx)))]
      [(into (vec hidden) cat pairs') forms'])))

(defn draw-def
  "Expansion of defdraw/defloop for backend kind (:canvas or :gl).
  more is [opts? draw-fn]; opts, when present, is a literal map."
  [env macro kind loop? cname props bindings more]
  (check-slots! macro cname props bindings)
  (let [[bindings more] (auto-bind env macro cname props bindings more)
        [opts draw] (case (count more)
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
