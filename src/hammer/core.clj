(ns hammer.core
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [hammer.macros :as m]))

;; ---- template compiler
;;
;; Literal element vectors in hiccup position (the body's value, through
;; if/when/let/cond/case/for tails, and the children of such elements) become
;; a template, built once and cloned per instance, plus the values of its
;; holes. Hole kinds are those of hammer.dom/template.

(defn- el? [x] (and (vector? x) (keyword? (first x))))

(defn- op
  "Name of form's head when it is an unqualified or core symbol."
  [form]
  (when (seq? form)
    (let [s (first form)]
      (when (and (symbol? s) (contains? #{nil "cljs.core" "clojure.core"} (namespace s)))
        (name s)))))

(declare compile-el)

(defn- comp-vec? [x] (and (vector? x) (symbol? (first x))))

(defn- compile-comp
  "[c & args] in hiccup position: a :comp VNode built directly (no key
  metadata map, no normalize) when c is a component at runtime, else the
  plain vector."
  [v]
  (let [h (gensym "c")
        hv (with-meta (into [h] (rest v)) (meta v))]
    `(let [~h ~(first v)]
       (if (hammer.cells/component? ~h)
         (new hammer.dom/VNode :comp nil nil nil nil ~(:key (meta v)) ~h ~(with-meta hv nil) nil nil)
         ~hv))))

(defn- compile-pos
  "Compiles the literal elements that are form's value: form itself, or the
  tails of the control forms below. Other positions (binding inits, call and
  component args) keep plain hiccup data."
  [ctx form]
  (let [n (when (seq? form) (count form))
        at (fn [idx]
             (with-meta (apply list (map-indexed (fn [i x] (if (idx i) (compile-pos ctx x) x)) form))
               (meta form)))]
    (cond
      (el? form) (compile-el ctx form)
      (comp-vec? form) (compile-comp form)
      :else
      (case (op form)
        ("if" "if-not" "if-let" "if-some") (at #{2 3})
        ("when" "when-not" "when-let" "when-some" "when-first" "let" "binding")
        (if (> n 2) (at #{(dec n)}) form)
        "do" (if (> n 1) (at #{(dec n)}) form)
        "cond" (at (set (range 2 n 2)))
        "case" (at (into (set (range 3 n 2)) (when (odd? n) [(dec n)])))
        "for" (if (= n 3) (at #{2}) form)
        form))))

(defn- attrs-at
  "[attrs index-of-first-child maybe-attrs?] of element v, or nil for a map
  literal with non-keyword keys. maybe-attrs?: the second item is an
  expression that might be an attrs map at runtime; it is compiled as a kid,
  and the template falls back to plain hiccup whenever it is a map."
  [v]
  (let [a (nth v 1 nil)]
    (cond
      (map? a) (when (every? keyword? (keys a)) [a 2 false])
      (or (m/lit? a) (vector? a)) [nil 1 false]
      :else [nil 1 true])))

(defn- tag-classes
  "The classes in a tag keyword, joined as hammer.dom/parse-tag does."
  [tag]
  (let [[_ _ more] (re-matches #"([^.#]+)(.*)" (name tag))
        cls (seq (map second (re-seq #"\.([^.#]+)" (or more ""))))]
    (when cls (str/join " " cls))))

(defn- static-class? [x] (or (m/lit? x) (and (vector? x) (every? m/lit? x))))

(defn- static-style? [x]
  (or (nil? x) (and (map? x) (every? keyword? (keys x)) (every? m/lit? (vals x)))))

(defn- hole!
  "Binds expr to a fresh local, in source order; returns the local."
  [ctx expr]
  (let [s (gensym "h")]
    (vswap! (:binds ctx) conj s expr)
    s))

(defn- analyze
  "Element v at path (child indices from the template root) →
  {:skel static hiccup, :plain the same hiccup with hole locals,
   :holes [{:kind :name :path :sym}] in post-order (kids before their
   element's attrs)}, or nil if v's attrs are not literal. Maybe-attrs
  locals are added to (:checks ctx)."
  [ctx v path]
  (when-let [[attrs start maybe?] (attrs-at v)]
    (let [tag (first v)
          plain-attrs (volatile! {})
          [static ahs]
          (reduce-kv
           (fn [[st hs] k x]
             (let [n (name k)
                   hole (fn [kind nm]
                          (let [h (hole! ctx x)]
                            (vswap! plain-attrs assoc k h)
                            [st (conj hs {:kind kind :name nm :path path :sym h})]))]
               (vswap! plain-attrs assoc k x)
               (cond
                 ;; a key, never an attribute (compile-el lifts the root's; a nested one has no siblings to match)
                 (= k :key) [st hs]
                 (= k :ref) (if (nil? x) [st hs] (hole 7 nil))
                 (str/starts-with? n "on-") (if (nil? x) [st hs] (hole 6 (subs n 3)))
                 (#{:value :checked :selected} k) (hole 5 n)
                 (= k :class) (if (static-class? x) [(assoc st k x) hs] (hole 3 (tag-classes tag)))
                 (= k :style) (if (static-style? x) [(assoc st k x) hs] (hole 4 nil))
                 (m/lit? x) [(assoc st k x) hs]
                 :else (hole 2 n))))
           [{} []]
           (or attrs {}))
          kids (vec (remove #(or (nil? %) (false? %)) (subvec v start)))
          [skids pkids khs]
          (reduce (fn [[sk pk hs] c]
                    (let [p (conj path (count sk))
                          sub (when (el? c) (analyze ctx c p))]
                      (cond
                        (m/lit? c) [(conj sk c) (conj pk c) hs]
                        sub [(conj sk (:skel sub)) (conj pk (:plain sub)) (into hs (:holes sub))]
                        :else (let [h (hole! ctx (compile-pos ctx c))]
                                (when (and maybe? (empty? sk)) (vswap! (:checks ctx) conj h))
                                [(conj sk "") (conj pk h) (conj hs {:kind 0 :path p :sym h})]))))
                  [[] [] []]
                  kids)
          sole? (and (= [""] skids) (= 1 (count khs)) (= 0 (:kind (first khs))))
          khs (if sole? [(assoc (first khs) :kind 1 :path path)] khs)]
      {:skel (into [tag static] skids)
       :plain (into (if attrs [tag @plain-attrs] [tag]) pkids)
       :holes (into khs ahs)})))

(defn- compare-paths
  "Document order; a parent before its children."
  [a b]
  (loop [a (seq a) b (seq b)]
    (cond
      (and (nil? a) (nil? b)) 0
      (nil? a) -1
      (nil? b) 1
      :else (let [c (compare (first a) (first b))]
              (if (zero? c) (recur (next a) (next b)) c)))))

(defn- resolver
  "fn of a clone's root returning the node at each path: straight-line
  firstChild/nextSibling steps, each node visited at most once."
  [paths]
  (let [need (into (sorted-set-by compare-paths)
                   (for [p paths
                         n (range 1 (inc (count p)))
                         :let [q (subvec p 0 n)]
                         i (range (inc (peek q)))]
                     (conj (pop q) i)))
        root (gensym "n")
        syms (reduce #(assoc %1 %2 (gensym "n")) {[] root} need)
        js (fn [p] (with-meta (syms p) {:tag 'js}))
        binds (mapcat (fn [p]
                        [(syms p)
                         (if (zero? (peek p))
                           `(.-firstChild ~(js (pop p)))
                           `(.-nextSibling ~(js (conj (pop p) (dec (peek p))))))])
                      need)]
    `(fn [~root] (let [~@binds] (cljs.core/array ~@(map syms paths))))))

(defn- strip-meta [form]
  (walk/postwalk #(if (instance? clojure.lang.IObj %) (with-meta % nil) %) form))

(defn- compile-el
  "Template VNode expression for literal element v, registering the template
  in (:defs ctx); plain hiccup (children still compiled) if v's attrs are not
  literal."
  [ctx v]
  (let [ctx (assoc ctx :binds (volatile! []) :checks (volatile! []))
        a (nth v 1 nil)
        ;; :key in a literal attrs map is the element's key (metadata wins), never
        ;; an attribute; other maps fall back to plain hiccup, where normalize does it
        akey? (and (map? a) (every? keyword? (keys a)) (contains? a :key))
        k (if (contains? (meta v) :key) (:key (meta v)) (when akey? (:key a)))
        v (if akey? (with-meta (assoc v 1 (dissoc a :key)) (meta v)) v)]
    (if-let [{:keys [skel plain holes]} (analyze ctx v [])]
      (let [defs (:defs ctx)
            d (symbol (str (:cname ctx) "__tpl" (count @defs)))
            tpl `(new hammer.dom/VNode :tpl ~d nil (cljs.core/array ~@(map :sym holes))
                      nil ~k nil nil nil nil)]
        (swap! defs conj {:sym d
                          :skel (strip-meta skel)
                          :kinds (mapv :kind holes)
                          :names (mapv :name holes)
                          :resolve (resolver (mapv :path holes))})
        `(let [~@@(:binds ctx)]
           ~(if-let [cs (seq @(:checks ctx))]
              `(if (or ~@(map (fn [c] `(map? ~c)) cs))
                 ~(if (some? k) `(with-meta ~plain {:key ~k}) plain)
                 ~tpl)
              tpl)))
      (with-meta (into [(first v)] (map #(compile-pos ctx %)) (rest v)) (meta v)))))

(defmacro defc
  "(defc name [props*] [bindings*] body+)
  A vector-literal init is a db path; (is? path v) is true iff the db value at
  path is = to v, and marks the instance only when that flips; an init that
  evaluates to an atom is local state; anything else is derived from the props
  and earlier bindings it names, and re-runs only when one of them changed.
  is? is recognized by symbol: unqualified is?, a qualified is? on any hammer
  facade (hammer.core, hammer.app, hammer.canvas, hammer.gl) or an alias of
  one, as the whole init. path and v may name props and earlier bindings.
  Literal hiccup in the body compiles to cloned templates (see compile-pos)."
  [cname props & [bindings & body]]
  (m/check-slots! "defc" cname props bindings)
  ;; [child id] where bindings go parses as one binding and leaves no body
  (when (and (empty? body) (seq bindings))
    (throw (ex-info (str "defc " cname ": missing bindings vector, write (defc " cname " " (pr-str props) " [] body)")
                    {:name cname})))
  (let [pairs (partition 2 bindings)
        slots (into (vec props) (map first pairs))]
    (let [defs (atom [])
          out (if (seq body)
                (conj (vec (butlast body)) (compile-pos {:cname cname :defs defs} (last body)))
                [])]
      `(do
         ~@(for [{:keys [sym skel kinds names resolve]} @defs]
             `(def ~(vary-meta sym assoc :private true)
                (hammer.dom/template ~skel (cljs.core/array ~@kinds) (cljs.core/array ~@names) ~resolve)))
         (def ~cname
           (hammer.cells/component
            ~(str cname)
            ~(count props)
            ~(vec (map-indexed (fn [j pair]
                                 (m/binding-spec &env "defc" (subvec slots 0 (+ (count props) j)) pair))
                               pairs))
            ~(m/deps-of slots (vec body))
            (fn ~slots ~@out)))))))
