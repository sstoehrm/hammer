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

(defn deps-with
  "deps-of plus the indices of the slots in watch (hidden auto-bound slots the
  form derefs through their global, so it does not name them)."
  [slots form watch]
  (vec (sort (into (set (deps-of slots form))
                   (keep #(let [i (.indexOf ^java.util.List slots %)] (when (>= i 0) i)))
                   watch))))

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
  [env macro slots [_ init] & [watch]]
  (let [deps (deps-with slots init watch)
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
;; watched :local slot when g is an atom, a plain value otherwise), and the body
;; or init that derefs g gets that slot added to its deps. The code itself is
;; emitted exactly as written and keeps reading @g: this pass only analyses it,
;; so a wrong guess costs at most an extra watch, never a wrong value.
;;
;; Only subtrees containing a deref are looked at. Binders are modeled on the
;; special forms (let*, loop*, fn*, letfn*, try/catch) and a few core macros;
;; any other macro is expanded (for analysis only) to see what it binds. Not
;; tracked: props and bindings, locals, derefs inside fns (they run at
;; event/draw time), non-symbol targets, ^:once targets, quote/comment, and vars
;; that are not plain user vars (js/, cljs.core, macros, fns, dynamic vars).
;; hammer.state/app-db warns instead: watching it would re-render on every db
;; change.

(defmethod cljs.analyzer/error-message ::app-db-deref [_ {:keys [msg]}] msg)

(defn- head
  "Name of form's head when it is an unqualified or core symbol."
  [form]
  (when (and (seq? form) (symbol? (first form))
             (contains? #{nil "cljs.core" "clojure.core"} (namespace (first form))))
    (name (first form))))

(def ^:private skip-heads #{"fn" "fn*" "defn" "reify" "deftype" "defrecord" "quote" "comment"})
(def ^:private let-heads #{"let" "let*" "loop" "loop*" "when-let" "when-some" "when-first"
                           "dotimes" "with-open"})
(def ^:private if-heads #{"if-let" "if-some"})
(def ^:private seq-heads #{"for" "doseq"})
(def ^:private plain-macros
  "Core macros that bind no local: walked as written."
  #{"when" "when-not" "if-not" "cond" "case" "condp" "and" "or" "->" "->>" "some->" "some->>"
    "cond->" "cond->>" "doto" "assert" "binding" "with-redefs"})

(defn- lhs-syms
  "Names bound by a destructuring form, over-approximated: every symbol in it,
  and the name of each keyword or qualified symbol in a :keys/:syms/:strs vector."
  [lhs]
  (let [forms (tree-seq coll? seq lhs)
        names (for [m forms :when (map? m)
                    [k v] m :when (and (keyword? k) (contains? #{"keys" "syms" "strs"} (name k)))
                    x v :when (or (symbol? x) (keyword? x))]
                (symbol (name x)))]
    (into (set names) (filter simple-symbol?) forms)))

(defn- deref-target [form]
  (when (and (seq? form) (= 2 (count form))
             (contains? #{'deref 'cljs.core/deref 'clojure.core/deref} (first form))
             (symbol? (second form)))
    (second form)))

(defn- has-deref? [form]
  (boolean (some deref-target (tree-seq coll? seq form))))

(declare scan)

(defn- scan-bindings
  "Scans the init forms of binding vector bv; returns the locals after it.
  seq?: a for/doseq vector, with :let [...] / :when / :while modifiers."
  [ctx locals bv seq?]
  (reduce (fn [locals [l r]]
            (cond
              (and seq? (= l :let)) (scan-bindings ctx locals r false)
              (and seq? (keyword? l)) (do (scan ctx locals r) locals)
              :else (do (scan ctx locals r) (into locals (lhs-syms l)))))
          locals (partition 2 bv)))

(defn- plain-var?
  "v (from cljs.analyzer.api/resolve) is a user var that can hold an atom."
  [v]
  (and v (not (contains? '#{js cljs.core} (:ns v)))
       (not (:macro v)) (not (:fn-var v)) (not (:dynamic v))))

(defn- hit!
  "Records (deref g): g's hidden slot is added to the current form's watch."
  [{:keys [env macro cname slots found hits pos]} locals g]
  (let [v (when-not (or (= "js" (namespace g)) (:once (meta g)) (contains? slots g)
                        (contains? locals g) (contains? (:locals env) g))
            (cljs.analyzer.api/resolve env g))]
    (cond
      (not (plain-var? v)) nil
      (= 'hammer.state/app-db (:name v))
      ;; bound here: a build that started before hammer.macros loaded has its own copy
      (binding [cljs.analyzer/*cljs-warnings*
                (if (contains? cljs.analyzer/*cljs-warnings* ::app-db-deref)
                  cljs.analyzer/*cljs-warnings*
                  (assoc cljs.analyzer/*cljs-warnings* ::app-db-deref true))]
        (cljs.analyzer/warning ::app-db-deref (merge env pos)
                               {:msg (str macro " " cname ": @" g " re-renders only by chance; read the db"
                                          " through a path binding, e.g. [todos [:todos]]")}))
      :else
      (let [k (:name v)
            s (or (get-in @found [k :slot])
                  (let [s (gensym (str (str/replace (name k) #"[^A-Za-z0-9_]" "_") "__auto"))]
                    (vswap! found assoc k {:slot s :sym g})
                    s))]
        (vswap! hits conj s)))))

(defn- scan-seq [ctx locals form]
  (let [h (head form)
        scan* (fn [locals xs] (run! #(scan ctx locals %) xs))]
    (cond
      (contains? skip-heads h) nil

      (and (contains? #{"letfn" "letfn*"} h) (vector? (second form)))
      (let [fns (second form)]
        (scan* (into locals (map #(if (seq? %) (first %) %)) (if (= h "letfn") fns (take-nth 2 fns)))
               (drop 2 form)))

      (= h "try")
      (doseq [x (rest form)]
        (if (and (seq? x) (= 'catch (first x)))
          (scan* (conj locals (nth x 2)) (drop 3 x))
          (scan ctx locals x)))

      (and (contains? let-heads h) (vector? (second form)))
      (scan* (scan-bindings ctx locals (second form) false) (drop 2 form))

      (and (contains? if-heads h) (vector? (second form)))
      (let [[_ bv then & else] form]
        (scan ctx (scan-bindings ctx locals bv false) then)
        (scan* locals else))

      (and (contains? seq-heads h) (vector? (second form)))
      (scan* (scan-bindings ctx locals (second form) true) (drop 2 form))

      (contains? plain-macros h) (scan* locals form)

      ;; any other macro may bind locals: look at its expansion (not emitted).
      ;; It sees the body's locals; one that throws here (it is expanded again,
      ;; in place, by the real compile) is scanned as written.
      (and (symbol? (first form)) (not (contains? locals (first form)))
           (cljs.analyzer/get-expander (first form) (:env ctx)))
      (let [env (update (:env ctx) :locals merge (into {} (map (fn [l] [l {:name l}])) locals))
            x (try (cljs.analyzer/macroexpand-1 env form) (catch Exception _ form))]
        (if (identical? x form) (scan* locals form) (scan ctx locals x)))

      :else (scan* locals form))))

(defn- scan
  "Records the hidden slots of the globals form derefs during render."
  [ctx locals form]
  (when (has-deref? form)
    (let [m (meta form)
          ;; @x reads as a deref list without position; report the nearest enclosing one
          ctx (if (:line m) (assoc ctx :pos (select-keys m [:line :column])) ctx)]
      (cond
        (deref-target form) (hit! ctx locals (deref-target form))
        (seq? form) (scan-seq ctx locals form)
        (map? form) (run! #(scan ctx locals %) (mapcat identity form))
        (coll? form) (run! #(scan ctx locals %) form)))))

(defn auto-bind
  "Hidden bindings for the global atoms a component derefs during render.
  Returns {:bindings b' :watch w :forms-watch fw}: b' is bindings with the
  hidden pairs in front, w a vector of hidden-slot sets aligned with b''s
  pairs (what each init derefs), fw the set for forms (the body, or a draw
  component's opts and fn)."
  [env macro cname props bindings forms]
  (let [pairs (partition 2 bindings)]
    (if (nil? env)
      {:bindings bindings :watch (vec (repeat (count pairs) #{})) :forms-watch #{}}
      (let [ctx {:env env :macro macro :cname cname :found (volatile! {})
                 :slots (into (set props) (map first pairs))}
            run (fn [form] (let [hits (volatile! #{})] (scan (assoc ctx :hits hits) #{} form) @hits))
            watch (mapv (fn [[_ init]] (run init)) pairs)
            forms-watch (reduce into #{} (map run forms))
            hidden (vals @(:found ctx))]
        {:bindings (into (vec (mapcat (fn [{:keys [slot sym]}] [slot sym]) hidden)) bindings)
         :watch (into (vec (repeat (count hidden) #{})) watch)
         :forms-watch forms-watch}))))

(defn draw-def
  "Expansion of defdraw/defloop for backend kind (:canvas or :gl).
  more is [opts? draw-fn]; opts, when present, is a literal map."
  [env macro kind loop? cname props bindings more]
  (check-slots! macro cname props bindings)
  (let [{:keys [bindings watch forms-watch]} (auto-bind env macro cname props bindings more)
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
                             (binding-spec env macro (subvec slots 0 (+ (count props) j)) pair (watch j)))
                           pairs))
        ~(deps-with slots [opts draw] forms-watch)
        (fn ~slots (cljs.core/array ~opts ~draw))
        ~kind
        ~loop?))))
