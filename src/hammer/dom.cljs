(ns hammer.dom
  "Hiccup → DOM. Each instance keeps its last normalized hiccup (vnode) and is
  diffed only against itself; child components are boundaries. defc compiles
  literal element trees to :tpl vnodes: a template cloned per instance plus
  an array of hole values, diffed hole by hole."
  (:require [clojure.string :as str]
            [goog.object :as gobj]
            [hammer.cells :as cells]
            [hammer.events :as events]
            [hammer.log :as log]))

;; t :text/:el/:comp, or :tpl (from defc): tag = Tpl, attrs = hole values,
;; args (:comp) = the whole hiccup vector, props from index 1;
;; kids = hole nodes, inst = per-hole region vnodes (nil in text mode).
(deftype VNode [t tag text attrs ^:mutable kids key comp args ^:mutable el ^:mutable inst])

;; ---- normalize

(defonce ^:private tag-cache (js/Map.))

(defn- parse-tag
  "\"div.a.b#x\" → #js [\"div\" \"x\" \"a b\"], cached by name."
  [kw]
  (let [s (name kw)]
    (or (.get tag-cache s)
        (let [[_ tag more] (re-matches #"([^.#]+)(.*)" s)
              id (second (re-find #"#([^.#]+)" more))
              cls (seq (map second (re-seq #"\.([^.#]+)" more)))
              v #js [tag id (when cls (str/join " " cls))]]
          (.set tag-cache s v)
          v))))

(defn- class-str [c]
  (if (coll? c) (str/join " " (remove nil? c)) c))

(defn- text-vnode [s] (VNode. :text nil s nil nil nil nil nil nil nil))

(declare normalize)

(defn- push-kid! [out x]
  (cond
    (or (nil? x) (false? x)) out
    (seq? x) (reduce push-kid! out x)
    :else (doto out (.push (normalize x)))))

(defn- push-kids!
  "Normalizes the children of hiccup vector v from index i on."
  [out v i]
  (loop [i i]
    (when (< i (count v))
      (push-kid! out (nth v i))
      (recur (inc i))))
  out)

(defn normalize
  "Hiccup → VNode (:text, :el or :comp); a compiled :tpl VNode passes through."
  [x]
  (cond
    (instance? VNode x) x

    (vector? x)
    (let [h (nth x 0)
          k (:key (meta x))]
      (if (cells/component? h)
        (VNode. :comp nil nil nil nil k h x nil nil)
        (let [[tag id cls] (parse-tag h)
              a? (map? (nth x 1 nil))
              attrs (if a? (nth x 1) {})
              ;; :key in attrs is the element's key (metadata wins), never an attribute
              ak (when a? (:key attrs))
              k (if (nil? k) ak k)
              attrs (if (some? ak) (dissoc attrs :key) attrs)
              c (class-str (:class attrs))
              c (if cls (if c (str cls " " c) cls) c)
              attrs (cond-> attrs id (assoc :id id) c (assoc :class c))]
          (VNode. :el tag nil attrs (push-kids! #js [] x (if a? 2 1)) k nil nil nil nil))))

    (or (nil? x) (false? x)) (text-vnode "")
    :else (text-vnode (str x))))

;; ---- attributes

(defn- delegate
  "Capture listener on a mount root: runs the __cuiH handlers for the event
  type from target outward (target only for non-bubbling events), stopping at
  the root or on stopPropagation. A fn handler gets (e el), el the element it
  is on (currentTarget is the root and read-only). The next node is read before each handler,
  like the browser's precomputed path. Flagged so nested roots run it once."
  [^js e]
  (let [root (.-currentTarget e)
        t (.-type e)]
    (when-not (.-__cuiD e)
      (set! (.-__cuiD e) true)
      (loop [^js n (.-target e)]
        (when (and n (not (identical? n root)))
          (let [nxt (when (.-bubbles e) (.-parentNode n))
                h (some-> (.-__cuiH n) (gobj/get t))]
            (cond
              (vector? h) (events/dispatch h)
              (fn? h) (h e n))
            (when-not (.-cancelBubble e) (recur nxt))))))))

(defonce ^:private new-types (js/Set.))

(defn- listen-root!
  "Adds the capture listener for each event type seen since the last call."
  [^js root]
  (when (and root (pos? (.-size new-types)))
    (let [ts (.-__cuiT root)]
      (.forEach new-types (fn [t]
                            (when-not (.has ts t)
                              (.add ts t)
                              (.addEventListener root t delegate true)))))
    (.clear new-types)))

(defn- prop? [k]
  (or (keyword-identical? k :value) (keyword-identical? k :checked) (keyword-identical? k :selected)))

(defn- set-on!
  "Stores handler v for event type t on el; registers t for delegation when
  reg? (the element had no handler for t before)."
  [^js el t v reg?]
  (let [hs (or (.-__cuiH el) (let [o #js {}] (set! (.-__cuiH el) o) o))]
    (when reg? (.add new-types t))
    (gobj/set hs t v)))

(defn- set-style!
  "A map of CSS property names is diffed against old; a string replaces
  cssText."
  [^js el old v]
  (let [s (.-style el)]
    (if (string? v)
      (set! (.-cssText s) v)
      (let [old (if (string? old) (do (set! (.-cssText s) "") nil) old)]
        (doseq [[sk sv] v]
          (when (not= sv (get old sk))
            (let [n (name sk)]
              (when (and ^boolean goog/DEBUG (re-find #"[A-Z]" n) (not (str/starts-with? n "--")))
                (log/report! :warn (str "hammer: :style keys are CSS names, got " (pr-str sk)) nil))
              (.setProperty s n (str sv)))))
        (doseq [[sk _] old]
          (when-not (contains? v sk) (.removeProperty s (name sk))))))))

(defn- set-prop!
  "Writes :value/:checked/:selected (n is its name) only if the live element differs."
  [^js el n v]
  (if (and (= n "value") (= "OPTION" (.-tagName el)))
    ;; an option's value property reads its text when the attribute is
    ;; missing, so compare and write the attribute: the markup always has it
    (let [v (str (or v ""))]
      (when (not= v (.getAttribute el "value")) (.setAttribute el "value" v)))
    (let [v (if (= n "value") (str (or v "")) (boolean v))]
      (when (not= v (gobj/get el n)) (gobj/set el n v)))))

(defn- enumerated?
  "Attributes whose value is the string \"true\" or \"false\", not presence."
  [n]
  (or (= n "draggable") (= n "spellcheck") (= n "contenteditable") (= n "writingsuggestions")
      (str/starts-with? n "aria-")))

(defn- set-plain! [^js el n v]
  (cond
    (nil? v) (.removeAttribute el n)
    (and (boolean? v) (enumerated? n)) (.setAttribute el n (if v "true" "false"))
    (false? v) (.removeAttribute el n)
    (true? v) (.setAttribute el n "")
    :else (.setAttribute el n (str v))))

(defn- set-attr! [^js el k old v]
  (let [n (name k)]
    (cond
      (= k :ref) nil
      (str/starts-with? n "on-") (set-on! el (subs n 3) v (not old))
      (= k :style) (set-style! el old v)
      (prop? k) (set-prop! el n v)
      :else (set-plain! el n v))))

(defn- set-attrs!
  "Applies attrs nu over old. :value/:checked/:selected are always compared
  against the live element so user input is never overwritten needlessly."
  [^js el old nu]
  (reduce-kv (fn [_ k v]
               (let [o (get old k)]
                 (when (or (prop? k) (not= v o))
                   (set-attr! el k o v))))
             nil nu)
  (when old
    (reduce-kv (fn [_ k v]
                 (when-not (contains? nu k)
                   (set-attr! el k v nil)))
               nil old)))

;; ---- refs and kid ranges

(defonce ^:private ref-queue #js [])

(defn- safe-ref!
  "Calls a user :ref fn, logging and swallowing any throw so one bad ref
  neither aborts an unmount/patch nor drops other queued refs."
  [f el]
  (try
    (f el)
    (catch :default e
      (log/report! :error "hammer: :ref failed" e))))

(defn- run-refs!
  "Calls :ref fns queued by create! once their elements are in the document."
  []
  (when (pos? (alength ref-queue))
    (.forEach (.splice ref-queue 0) (fn [[f el]] (safe-ref! f el)))))

(defn- node-of [^VNode v]
  (if (and (keyword-identical? (.-t v) :comp) (nil? (.-el v)))
    (node-of (.-vnode ^cells/Instance (.-inst v)))
    (.-el v)))

(declare mount-inst! patch! create! unmount! patch-kids! host-render)

(defn- insert-from!
  "Creates nu[from..to) into one DocumentFragment and inserts it before anchor
  (nil: appends)."
  [^js el ^js nu from to anchor depth]
  (let [f (.createDocumentFragment js/document)]
    (loop [i from]
      (when (< i to)
        (.appendChild f (create! (aget nu i) depth))
        (recur (inc i))))
    (.insertBefore el f anchor)))

(defn- clear-kids!
  "Removes old's nodes from el, then unmounts old. With no end anchor the kids
  are all of el's child nodes, so el is emptied in one operation."
  [^js el ^js old end]
  (if end
    (.forEach old (fn [k] (.removeChild el (node-of k))))
    (set! (.-textContent el) ""))
  (.forEach old (fn [k] (unmount! k))))

;; ---- templates (compiled by defc)

(deftype Tpl [skel ^:mutable proto kinds names resolve])

(defn template
  "Built by defc. skel: the static hiccup, with \"\" at each kid hole and
  without the attrs that are holes. kinds/names: per hole, the kind and its
  name (attr name, event type, or the tag's static classes). resolve: clone
  root → array of hole nodes. Kinds: 0 kid among siblings (node: its text
  node, also the region's end anchor), 1 sole kid (node: its element),
  2 attr, 3 :class, 4 :style, 5 :value/:checked/:selected, 6 :on-*, 7 :ref."
  [skel kinds names resolve]
  (Tpl. skel nil kinds names resolve))

(defn- proto
  "The template's DOM, built once on first use and then cloned."
  [^Tpl d]
  (or (.-proto d)
      (let [n (create! (normalize (.-skel d)) 0)]
        (set! (.-proto d) n)
        n)))

(defn- hiccup? [x] (or (vector? x) (seq? x) (instance? VNode x)))

(defn- text-of [x]
  (cond
    (string? x) x
    (or (nil? x) (false? x)) ""
    :else (str x)))

(defn- join-class
  "The class attribute for static tag classes cls and :class value c, as normalize builds it."
  [cls c]
  (let [c (class-str c)]
    (if cls (if c (str cls " " c) cls) c)))

(defn- set-kid!
  "Sets kid hole i of v from o to x. Hiccup (vector, seq, vnode) becomes a
  region of vnodes patched like an element's kids; anything else is text.
  one?: n is the hole's element and the region owns all its child nodes;
  otherwise n is the hole's text node, which stays as the region's end."
  [^VNode v i one? ^js n o x depth]
  (let [^js regs (.-inst v)
        old (when regs (aget regs i))]
    (if (and (not (string? x)) (hiccup? x))
      (let [nu (push-kid! #js [] x)
            el (if one? n (.-parentNode n))
            end (when-not one? n)]
        (if old
          (patch-kids! el old nu depth end)
          (do (if one? (set! (.-textContent n) "") (set! (.-data n) ""))
              (when (pos? (alength nu)) (insert-from! el nu 0 (alength nu) end depth))))
        (let [^js regs (or regs
                           (let [r (make-array (alength (.-kinds ^Tpl (.-tag v))))]
                             (set! (.-inst v) r)
                             r))]
          (aset regs i nu)))
      (let [s (text-of x)]
        (if old
          (do (if one?
                (do (clear-kids! n old nil)
                    (.appendChild n (.createTextNode js/document s)))
                (do (clear-kids! (.-parentNode n) old n)
                    (set! (.-data n) s)))
              (aset regs i nil))
          (when-not (identical? s (text-of o)) ; both strings: ===
            (set! (.-data ^js (if one? (.-firstChild n) n)) s)))))))

(defn- set-hole!
  "Writes hole i (kind k, node n, name nm) of v from value o to x."
  [^VNode v i k ^js n nm o x depth]
  (case k
    0 (set-kid! v i false n o x depth)
    1 (set-kid! v i true n o x depth)
    2 (when (not= o x) (set-plain! n nm x))
    3 (let [b (join-class nm x)]
        (when (not= (join-class nm o) b) (set-plain! n "class" b)))
    4 (when (not= o x) (set-style! n o x))
    5 (set-prop! n nm x)
    6 (set-on! n nm x (nil? o))
    nil))

(defn- create-tpl!
  "Clones the template, resolves the hole nodes and writes every hole.
  Holes are in post-order, so kids exist before their element's attrs
  (select :value) and refs queue children first."
  [^VNode v depth]
  (let [^Tpl d (.-tag v)
        root (.cloneNode ^js (proto d) true)
        nodes ((.-resolve d) root)
        vals (.-attrs v)
        kinds (.-kinds d)
        names (.-names d)]
    (set! (.-el v) root)
    (set! (.-kids v) nodes)
    (dotimes [i (alength kinds)]
      (let [k (aget kinds i)
            x (aget vals i)]
        (cond
          (== k 7) (when x (.push ref-queue #js [x (aget nodes i)]))
          (or (some? x) (== k 5)) (set-hole! v i k (aget nodes i) (aget names i) nil x depth))))
    root))

(defn- patch-tpl!
  "Writes the holes whose value is not identical? to the old one; props are
  always compared against the live element, refs are kept for unmount."
  [^VNode old ^VNode nu depth]
  (let [^Tpl d (.-tag nu)
        nodes (.-kids old)
        ov (.-attrs old)
        nv (.-attrs nu)
        kinds (.-kinds d)
        names (.-names d)]
    (set! (.-el nu) (.-el old))
    (set! (.-kids nu) nodes)
    (set! (.-inst nu) (.-inst old))
    (dotimes [i (alength kinds)]
      (let [k (aget kinds i)
            o (aget ov i)
            x (aget nv i)]
        (when (or (== k 5) (not (identical? o x)))
          (set-hole! nu i k (aget nodes i) (aget names i) o x depth))))))

(defn- unmount-tpl! [^VNode v]
  (let [^Tpl d (.-tag v)
        kinds (.-kinds d)
        vals (.-attrs v)
        ^js regs (.-inst v)]
    (dotimes [i (alength kinds)]
      (let [k (aget kinds i)]
        (cond
          (== k 7) (when-let [r (aget vals i)] (safe-ref! r nil))
          (and regs (< k 2)) (when-let [ks (aget regs i)] (.forEach ks (fn [x] (unmount! x)))))))))

;; ---- create / unmount

(defn- create!
  "Builds the DOM for v, owned by an instance at depth. Returns the node."
  [^VNode v depth]
  (case (.-t v)
    :text (let [n (.createTextNode js/document (.-text v))]
            (set! (.-el v) n)
            n)
    :el (let [el (.createElement js/document (.-tag v))
              attrs (.-attrs v)]
          (.forEach (.-kids v) (fn [k] (.appendChild el (create! k depth))))
          (set-attrs! el nil attrs)
          (when-let [r (:ref attrs)] (.push ref-queue #js [r el]))
          (set! (.-el v) el)
          el)
    :tpl (create-tpl! v depth)
    :comp (let [c (.-comp v)
                inst (cells/create c (.-args v) 1 (inc depth))]
            (set! (.-inst v) inst)
            (if-let [^cells/Host h (cells/host c)]
              (let [n (try
                        ((.-create h) inst host-render nil)
                        (catch :default e
                          ;; the instance's bindings already subscribed paths
                          ;; in cells/create above; without this, a Host whose
                          ;; create throws leaks that subscription forever.
                          (cells/destroy! inst)
                          (throw e)))]
                (when-not n
                  ;; node-of treats a :comp vnode with an el as hosted; a nil
                  ;; node would surface later as an opaque DOM error. A Host
                  ;; destroy that throws, or one that returns without calling
                  ;; cells/destroy! itself, must not leak the subscriptions
                  ;; cells/create already made above -- so this always runs,
                  ;; not just from the catch.
                  (try ((.-destroy h) inst)
                       (catch :default _ nil))
                  (when (.-mounted inst) (cells/destroy! inst))
                  (throw (js/Error. (str "hammer: Host create of " (.-cname ^cells/Comp c)
                                         " returned nil; it must return a DOM node"))))
                (set! (.-el v) n)
                n)
              (mount-inst! inst)))))

(defn- host-render
  "Given to Host create: builds plain hiccup (no components) into a node."
  [hiccup]
  (create! (normalize hiccup) 0))

(defn- body-vnode
  "Renders inst to a VNode; logs and returns nil if the body throws."
  [^cells/Instance inst]
  (try
    (normalize (cells/render inst))
    (catch :default e
      (log/report! :error (str "hammer: render failed in " (.-cname ^cells/Comp (.-comp inst))) e)
      nil)))

(defn- mount-inst! [^cells/Instance inst]
  (let [v (or (body-vnode inst) (text-vnode ""))]
    (set! (.-vnode inst) v)
    (create! v (.-depth inst))))

(defn- unmount!
  "Depth-first: unsubscribes instances and calls :ref with nil. Leaves the DOM."
  [^VNode v]
  (case (.-t v)
    :text nil
    :el (do (.forEach (.-kids v) (fn [k] (unmount! k)))
            (when-let [r (:ref (.-attrs v))] (safe-ref! r nil)))
    :tpl (unmount-tpl! v)
    :comp (let [^cells/Instance inst (.-inst v)]
            (if-let [^cells/Host h (cells/host (.-comp v))]
              (try
                ((.-destroy h) inst)
                (catch :default e
                  (log/report! :error (str "hammer: destroy failed in " (.-cname ^cells/Comp (.-comp v))) e)
                  ;; whatever the host left undone, its subscriptions go
                  (when (.-mounted inst) (cells/destroy! inst))))
              (do (unmount! (.-vnode inst))
                  (cells/destroy! inst))))))

;; ---- patch

(defn- same? [^VNode a ^VNode b]
  (and (keyword-identical? (.-t a) (.-t b))
       (case (.-t a)
         :text true
         :el (= (.-tag a) (.-tag b))
         :tpl (identical? (.-tag a) (.-tag b))
         :comp (identical? (.-comp a) (.-comp b)))))

(defn- replace! [^VNode old ^VNode nu depth]
  (let [o (node-of old)
        n (create! nu depth)]
    (.replaceChild (.-parentNode ^js o) n o)
    (unmount! old)))

(defn update-inst!
  "Recomputes inst; if a value changed, re-renders and patches its DOM."
  [^cells/Instance inst]
  (set! (.-dirty inst) false)
  (when (.-mounted inst)
    (when (try (cells/refresh! inst)
               (catch :default e
                 (log/report! :error (str "hammer: render failed in " (.-cname ^cells/Comp (.-comp inst))) e)
                 false))
      (when-let [v (body-vnode inst)]
        (let [old (.-vnode inst)]
          (set! (.-vnode inst) v)
          (patch! old v (.-depth inst)))))))

(defn- js-key? [k] (or (number? k) (string? k)))

(defn- key-index
  "key → index when every kid is keyed and keys are unique, else nil (warns on
  a duplicate, or when only some kids are keyed): a js/Map when every key is
  a number or string (the same value semantics as =), else a persistent map.
  The verdict is kept on the array, so when these kids become the old side of
  the next patch they are not checked again."
  [^js kids]
  (let [n (alength kids)
        key-at (fn [i] (.-key ^VNode (aget kids i)))
        dup (fn [] (log/report! :warn "hammer: duplicate keys, falling back to index diff" nil))
        unkeyed (fn [] (log/report! :warn "hammer: some list items have no key, falling back to index diff" nil))
        _ (when (and ^boolean goog/DEBUG (pos? n) (nil? (key-at 0))
                     (loop [i 1] (and (< i n) (or (some? (key-at i)) (recur (inc i))))))
            (unkeyed))
        m (when (and (pos? n) (some? (key-at 0)))
            (if (loop [i 0] (or (== i n) (and (js-key? (key-at i)) (recur (inc i)))))
              (let [m (js/Map.)]
                (loop [i 0]
                  (if (< i n)
                    (let [k (key-at i)]
                      (if (.has m k) (dup) (do (.set m k i) (recur (inc i)))))
                    m)))
              (loop [i 0 m (transient {})]
                (if (< i n)
                  (let [k (key-at i)]
                    (cond
                      (nil? k) (unkeyed)
                      (contains? m k) (dup)
                      :else (recur (inc i) (assoc! m k i))))
                  m))))]
    (set! (.-__keyed kids) (some? m))
    m))

(defn- index-of
  "The new index of key k in key-index m, or nil."
  [m k]
  (if (instance? js/Map m)
    (let [i (.get ^js m k)] (when-not (undefined? i) i))
    (get m k)))

;; The kids functions below take end: the node after the kids in el, or nil
;; when they are all of el's child nodes (an :el, or a template's sole kid).

(defn- patch-indexed! [^js el ^js old ^js nu depth end]
  (let [no (alength old)
        nn (alength nu)]
    (dotimes [i (min no nn)] (patch! (aget old i) (aget nu i) depth))
    (when (< no nn) (insert-from! el nu no nn end depth))
    (loop [i nn]
      (when (< i no)
        (let [o (aget old i)]
          (.removeChild el (node-of o))
          (unmount! o))
        (recur (inc i))))))

(defn- lis
  "Indices of a longest increasing subsequence of a, skipping 0s (new kids)."
  [^js a]
  (let [p (js/Array. (alength a))
        r #js []]
    (dotimes [i (alength a)]
      (let [x (aget a i)]
        (when (pos? x)
          (let [k (loop [lo 0 hi (alength r)]
                    (if (< lo hi)
                      (let [mid (bit-shift-right (+ lo hi) 1)]
                        (if (< (aget a (aget r mid)) x) (recur (inc mid) hi) (recur lo mid)))
                      lo))]
            (when (pos? k) (aset p i (aget r (dec k))))
            (aset r k i)))))
    (loop [k (dec (alength r)) i (aget r k)]
      (when (>= k 0)
        (aset r k i)
        (recur (dec k) (aget p i))))
    r))

(defn- patch-keyed!
  "Patches the common prefix and suffix in place, then matches the middle by
  key (m: new key → index) and moves only the kids outside the longest run
  already in order, right to left, so swapping two rows moves two nodes."
  [^js el ^js old ^js nu m depth end]
  (let [no (alength old)
        nn (alength nu)
        same (fn [i j] (= (.-key ^VNode (aget old i)) (.-key ^VNode (aget nu j))))
        s (loop [i 0]
            (if (and (< i no) (< i nn) (same i i))
              (do (patch! (aget old i) (aget nu i) depth) (recur (inc i)))
              i))
        t (loop [t 0]
            (let [oi (- no t 1) ni (- nn t 1)]
              (if (and (>= oi s) (>= ni s) (same oi ni))
                (do (patch! (aget old oi) (aget nu ni) depth) (recur (inc t)))
                t)))
        ne (- nn t)
        oe (- no t)]
    (cond
      (= s oe) ; nothing old left in the middle: insert the new middle at once
      (insert-from! el nu s ne (if (< ne nn) (node-of (aget nu ne)) end) depth)

      (and (zero? s) (zero? t) (not (.some old (fn [^VNode o] (some? (index-of m (.-key o)))))))
      (do (clear-kids! el old end) ; no key survives: rebuild
          (insert-from! el nu 0 nn end depth))

      :else
      (let [src (.fill (js/Array. (- ne s)) 0)] ; new middle pos → old index + 1, 0 = new
        (loop [j s]
          (when (< j (- no t))
            (let [o (aget old j)]
              (if-let [i (index-of m (.-key ^VNode o))]
                (aset src (- i s) (inc j))
                (do (.removeChild el (node-of o))
                    (unmount! o))))
            (recur (inc j))))
        (dotimes [i (alength src)]
          (let [j (aget src i)]
            (if (pos? j)
              (patch! (aget old (dec j)) (aget nu (+ s i)) depth)
              (create! (aget nu (+ s i)) depth))))
        (let [keep (lis src)]
          (loop [i (dec (alength src)) k (dec (alength keep))]
            (when (>= i 0)
              (if (and (>= k 0) (== i (aget keep k)))
                (recur (dec i) (dec k))
                (let [at (+ s i 1)]
                  (.insertBefore el (node-of (aget nu (+ s i))) (if (< at nn) (node-of (aget nu at)) end))
                  (recur (dec i) k))))))))))

(defn- patch-kids! [^js el ^js old ^js nu depth end]
  (cond
    (zero? (alength nu))
    (when (pos? (alength old)) (clear-kids! el old end))

    (zero? (alength old)) ; nothing to match: skip building the key index
    (insert-from! el nu 0 (alength nu) end depth)

    :else
    (let [m (key-index nu)
          k (.-__keyed old)]
      (if (and m (if (nil? k) (key-index old) k))
        (patch-keyed! el old nu m depth end)
        (patch-indexed! el old nu depth end)))))

(defn- patch! [^VNode old ^VNode nu depth]
  (if-not (same? old nu)
    (replace! old nu depth)
    (case (.-t nu)
      :text (let [el (.-el old)]
              (set! (.-el nu) el)
              (when (not= (.-text old) (.-text nu))
                (set! (.-nodeValue ^js el) (.-text nu))))
      :el (let [el (.-el old)]
            (set! (.-el nu) el)
            (set-attrs! el (.-attrs old) (.-attrs nu))
            (patch-kids! el (.-kids old) (.-kids nu) depth nil))
      :tpl (patch-tpl! old nu depth)
      :comp (let [inst (.-inst old)]
              (set! (.-inst nu) inst)
              (set! (.-el nu) (.-el old))
              (when (cells/set-props! inst (.-args nu) 1)
                (if-let [^cells/Host h (cells/host (.-comp nu))]
                  ;; isolated like the scheduler's own runs: a throwing host
                  ;; must not abort the parent's patch
                  (try
                    ((.-run h) inst)
                    (catch :default e
                      (set! (.-dirty ^cells/Instance inst) false)
                      (log/report! :error (str "hammer: update failed in " (.-cname ^cells/Comp (.-comp nu))) e)))
                  (update-inst! inst)))))))

;; ---- roots

(defn mount!
  "Renders hiccup into container el, replacing what an earlier mount! (of any
  renderer: hammer.canvas/hammer.gl mount! too) put there."
  [hiccup el]
  (cells/unmount-root! el)
  (set! (.-textContent ^js el) "")
  (when-not (.-__cuiT ^js el) (set! (.-__cuiT ^js el) (js/Set.)))
  (.clear new-types)
  (let [v (normalize hiccup)]
    (.appendChild ^js el (create! v 0))
    (cells/set-root! el (fn [] (unmount! v) (set! (.-textContent ^js el) "")))
    (listen-root! el)
    (run-refs!)))

(defn- root-of
  "The mount! container above inst's DOM, found via its __cuiT marker."
  [^cells/Instance inst]
  (loop [^js n (some-> (.-vnode inst) node-of)]
    (when n
      (if (.-__cuiT n) n (recur (.-parentNode n))))))

(defn unmount-all!
  "Unmounts every root and empties its container. The registry is shared
  with hammer.draw, so draw roots are unmounted too."
  []
  (cells/unmount-roots!))

(cells/set-default-runner!
 (fn [^cells/Instance inst]
   (if-not (.-mounted inst)
     (set! (.-dirty inst) false) ; unmounted by an earlier patch in this flush
     (try
       (update-inst! inst)
       (catch :default e
         (log/report! :error (str "hammer: update failed in " (.-cname ^cells/Comp (.-comp inst))) e))
       (finally
         (when (pos? (.-size new-types)) (listen-root! (root-of inst)))
         (.clear new-types)
         (run-refs!))))))
