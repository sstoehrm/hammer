(ns hammer.dom
  "Hiccup → DOM. Each instance keeps its last normalized hiccup (vnode) and is
  diffed only against itself; child components are boundaries."
  (:require [clojure.string :as str]
            [goog.object :as gobj]
            [hammer.cells :as cells]
            [hammer.events :as events]
            [hammer.scheduler :as sched]))

(deftype VNode [t tag text attrs kids key comp args ^:mutable el ^:mutable inst])

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
  "Hiccup → VNode (:text, :el or :comp)."
  [x]
  (cond
    (vector? x)
    (let [h (nth x 0)
          k (:key (meta x))]
      (if (cells/component? h)
        (VNode. :comp nil nil nil nil k h (subvec x 1) nil nil)
        (let [[tag id cls] (parse-tag h)
              a? (map? (nth x 1 nil))
              attrs (if a? (nth x 1) {})
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
  the root or on stopPropagation. The next node is read before each handler,
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
              (fn? h) (h e))
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

(defn- set-attr! [^js el k old v]
  (let [n (name k)]
    (cond
      (= k :ref) nil

      (str/starts-with? n "on-")
      (let [t (subs n 3)
            hs (or (.-__cuiH el) (let [o #js {}] (set! (.-__cuiH el) o) o))]
        (when-not old (.add new-types t))
        (gobj/set hs t v))

      (= k :style)
      (let [s (.-style el)]
        (doseq [[sk sv] v]
          (when (not= sv (get old sk)) (.setProperty s (name sk) (str sv))))
        (doseq [[sk _] old]
          (when-not (contains? v sk) (.removeProperty s (name sk)))))

      (prop? k)
      (let [v (if (= k :value) (str (or v "")) (boolean v))]
        (when (not= v (gobj/get el n)) (gobj/set el n v)))

      (or (nil? v) (false? v)) (.removeAttribute el n)
      (true? v) (.setAttribute el n "")
      :else (.setAttribute el n (str v)))))

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

;; ---- create / unmount

(defonce ^:private ref-queue #js [])

(defn- safe-ref!
  "Calls a user :ref fn, logging and swallowing any throw so one bad ref
  neither aborts an unmount/patch nor drops other queued refs."
  [f el]
  (try
    (f el)
    (catch :default e
      (js/console.error "hammer: :ref failed" e))))

(defn- run-refs!
  "Calls :ref fns queued by create! once their elements are in the document."
  []
  (.forEach (.splice ref-queue 0) (fn [[f el]] (safe-ref! f el))))

(defn- node-of [^VNode v]
  (if (keyword-identical? (.-t v) :comp)
    (node-of (.-vnode ^cells/Instance (.-inst v)))
    (.-el v)))

(declare mount-inst! patch!)

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
    :comp (let [inst (cells/create (.-comp v) (.-args v) (inc depth))]
            (set! (.-inst v) inst)
            (mount-inst! inst))))

(defn- body-vnode
  "Renders inst to a VNode; logs and returns nil if the body throws."
  [^cells/Instance inst]
  (try
    (normalize (cells/render inst))
    (catch :default e
      (js/console.error "hammer: render failed in" (.-cname ^cells/Comp (.-comp inst)) e)
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
    :comp (let [inst (.-inst v)]
            (unmount! (.-vnode ^cells/Instance inst))
            (cells/destroy! inst))))

;; ---- patch

(defn- same? [^VNode a ^VNode b]
  (and (keyword-identical? (.-t a) (.-t b))
       (case (.-t a)
         :text true
         :el (= (.-tag a) (.-tag b))
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
                 (js/console.error "hammer: render failed in" (.-cname ^cells/Comp (.-comp inst)) e)
                 false))
      (when-let [v (body-vnode inst)]
        (let [old (.-vnode inst)]
          (set! (.-vnode inst) v)
          (patch! old v (.-depth inst)))))))

(defn- key-index
  "key → index when every kid is keyed and keys are unique, else nil (warns on
  a duplicate). The verdict is kept on the array, so when these kids become the
  old side of the next patch they are not checked again."
  [^js kids]
  (let [n (alength kids)
        m (when (and (pos? n) (some? (.-key ^VNode (aget kids 0))))
            (loop [i 0 m (transient {})]
              (if (< i n)
                (let [k (.-key ^VNode (aget kids i))]
                  (cond
                    (nil? k) nil
                    (contains? m k) (js/console.warn "hammer: duplicate keys, falling back to index diff")
                    :else (recur (inc i) (assoc! m k i))))
                m)))]
    (set! (.-__keyed kids) (some? m))
    m))

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
  "Empties el in one operation, then unmounts old. Valid because an :el's
  child nodes are exactly its kids' nodes."
  [^js el ^js old]
  (set! (.-textContent el) "")
  (.forEach old (fn [k] (unmount! k))))

(defn- patch-indexed! [^js el ^js old ^js nu depth]
  (let [no (alength old)
        nn (alength nu)]
    (dotimes [i (min no nn)] (patch! (aget old i) (aget nu i) depth))
    (when (< no nn) (insert-from! el nu no nn nil depth))
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
  [^js el ^js old ^js nu m depth]
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
      (insert-from! el nu s ne (when (< ne nn) (node-of (aget nu ne))) depth)

      (and (zero? s) (zero? t) (not (.some old (fn [^VNode o] (some? (get m (.-key o)))))))
      (do (clear-kids! el old) ; no key survives: rebuild
          (insert-from! el nu 0 nn nil depth))

      :else
      (let [src (.fill (js/Array. (- ne s)) 0)] ; new middle pos → old index + 1, 0 = new
        (loop [j s]
          (when (< j (- no t))
            (let [o (aget old j)]
              (if-let [i (get m (.-key ^VNode o))]
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
                  (.insertBefore el (node-of (aget nu (+ s i))) (when (< at nn) (node-of (aget nu at))))
                  (recur (dec i) k))))))))))

(defn- patch-kids! [^js el ^js old ^js nu depth]
  (if (zero? (alength nu))
    (when (pos? (alength old)) (clear-kids! el old))
    (let [m (key-index nu)
          k (.-__keyed old)]
      (if (and m (if (nil? k) (key-index old) k))
        (patch-keyed! el old nu m depth)
        (patch-indexed! el old nu depth)))))

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
            (patch-kids! el (.-kids old) (.-kids nu) depth))
      :comp (let [inst (.-inst old)]
              (set! (.-inst nu) inst)
              (when (cells/set-props! inst (.-args nu))
                (update-inst! inst))))))

;; ---- roots

(defonce ^:private roots (atom {}))

(defn mount!
  "Renders hiccup into container el, replacing what an earlier mount! put there."
  [hiccup el]
  (when-let [old (get @roots el)]
    (unmount! old)
    (swap! roots dissoc el))
  (set! (.-textContent ^js el) "")
  (when-not (.-__cuiT ^js el) (set! (.-__cuiT ^js el) (js/Set.)))
  (.clear new-types)
  (let [v (normalize hiccup)]
    (.appendChild ^js el (create! v 0))
    (swap! roots assoc el v)
    (listen-root! el)
    (run-refs!)))

(defn- root-of
  "The mount! container above inst's DOM, found via its __cuiT marker."
  [^cells/Instance inst]
  (loop [^js n (some-> (.-vnode inst) node-of)]
    (when n
      (if (.-__cuiT n) n (recur (.-parentNode n))))))

(defn unmount-all!
  "Unmounts every root and empties its container."
  []
  (doseq [[el v] @roots]
    (unmount! v)
    (set! (.-textContent ^js el) ""))
  (reset! roots {}))

(sched/set-runner!
 (fn [^cells/Instance inst]
   (try
     (update-inst! inst)
     (catch :default e
       (js/console.error "hammer: update failed in" (.-cname ^cells/Comp (.-comp inst)) e))
     (finally
       (when (pos? (.-size new-types)) (listen-root! (root-of inst)))
       (.clear new-types)
       (run-refs!)))))
