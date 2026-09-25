(ns cljs-ui.dom
  "Hiccup → DOM. Each instance keeps its last normalized hiccup (vnode) and is
  diffed only against itself; child components are boundaries."
  (:require [clojure.string :as str]
            [goog.object :as gobj]
            [cljs-ui.cells :as cells]
            [cljs-ui.events :as events]
            [cljs-ui.scheduler :as sched]))

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

(defn- push-kids! [out xs]
  (doseq [x xs]
    (cond
      (or (nil? x) (false? x)) nil
      (seq? x) (push-kids! out x)
      :else (.push out (normalize x))))
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
          (VNode. :el tag nil attrs (push-kids! #js [] (subvec x (if a? 2 1))) k nil nil nil nil))))

    (or (nil? x) (false? x)) (text-vnode "")
    :else (text-vnode (str x))))

;; ---- attributes

(defn- listener [^js e]
  (let [h (gobj/get (.-__cuiH (.-currentTarget e)) (.-type e))]
    (cond
      (vector? h) (events/dispatch h)
      (fn? h) (h e))))

(def ^:private props #{:value :checked :selected})

(defn- set-attr! [^js el k old v]
  (let [n (name k)]
    (cond
      (= k :ref) nil

      (str/starts-with? n "on-")
      (let [t (subs n 3)
            hs (or (.-__cuiH el) (let [o #js {}] (set! (.-__cuiH el) o) o))]
        (when (and (nil? old) (some? v)) (.addEventListener el t listener))
        (when (and (some? old) (nil? v)) (.removeEventListener el t listener))
        (gobj/set hs t v))

      (= k :style)
      (let [s (.-style el)]
        (doseq [[sk sv] v]
          (when (not= sv (get old sk)) (.setProperty s (name sk) (str sv))))
        (doseq [[sk _] old]
          (when-not (contains? v sk) (.removeProperty s (name sk)))))

      (props k)
      (let [v (if (= k :value) (str (or v "")) (boolean v))]
        (when (not= v (gobj/get el n)) (gobj/set el n v)))

      (or (nil? v) (false? v)) (.removeAttribute el n)
      (true? v) (.setAttribute el n "")
      :else (.setAttribute el n (str v)))))

(defn- set-attrs!
  "Applies attrs nu over old. :value/:checked/:selected are always compared
  against the live element so user input is never overwritten needlessly."
  [^js el old nu]
  (doseq [[k v] nu]
    (when (or (props k) (not= v (get old k)))
      (set-attr! el k (get old k) v)))
  (doseq [[k v] old]
    (when-not (contains? nu k)
      (set-attr! el k v nil))))

;; ---- create / unmount

(defonce ^:private ref-queue #js [])

(defn- safe-ref!
  "Calls a user :ref fn, logging and swallowing any throw so one bad ref
  neither aborts an unmount/patch nor drops other queued refs."
  [f el]
  (try
    (f el)
    (catch :default e
      (js/console.error "cljs-ui: :ref failed" e))))

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
      (js/console.error "cljs-ui: render failed in" (.-cname ^cells/Comp (.-comp inst)) e)
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
                 (js/console.error "cljs-ui: render failed in" (.-cname ^cells/Comp (.-comp inst)) e)
                 false))
      (when-let [v (body-vnode inst)]
        (let [old (.-vnode inst)]
          (set! (.-vnode inst) v)
          (patch! old v (.-depth inst)))))))

(defn- keyed? [^js kids]
  (and (pos? (alength kids))
       (.every kids (fn [^VNode k] (some? (.-key k))))
       (or (= (alength kids) (count (into #{} (map (fn [^VNode k] (.-key k))) kids)))
           (do (js/console.warn "cljs-ui: duplicate keys, falling back to index diff")
               false))))

(defn- patch-indexed! [^js el ^js old ^js nu depth]
  (let [no (alength old)
        nn (alength nu)]
    (dotimes [i (min no nn)] (patch! (aget old i) (aget nu i) depth))
    (loop [i no]
      (when (< i nn)
        (.appendChild el (create! (aget nu i) depth))
        (recur (inc i))))
    (loop [i nn]
      (when (< i no)
        (let [o (aget old i)]
          (.removeChild el (node-of o))
          (unmount! o))
        (recur (inc i))))))

(defn- patch-keyed! [^js el ^js old ^js nu depth]
  (let [new-keys (into #{} (map (fn [^VNode n] (.-key n))) nu)
        remaining (volatile!
                   (reduce (fn [m ^VNode o]
                             (if (contains? new-keys (.-key o))
                               (assoc m (.-key o) o)
                               (do (.removeChild el (node-of o))
                                   (unmount! o)
                                   m)))
                           {} old))]
    (dotimes [i (alength nu)]
      (let [n (aget nu i)
            o (get @remaining (.-key ^VNode n))
            node (if o
                   (do (vswap! remaining dissoc (.-key ^VNode n))
                       (patch! o n depth)
                       (node-of n))
                   (create! n depth))
            at (.item (.-childNodes el) i)]
        (when-not (identical? node at)
          (.insertBefore el node at))))))

(defn- patch-kids! [^js el old nu depth]
  (if (and (keyed? old) (keyed? nu))
    (patch-keyed! el old nu depth)
    (patch-indexed! el old nu depth)))

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
  (let [v (normalize hiccup)]
    (.appendChild ^js el (create! v 0))
    (swap! roots assoc el v)
    (run-refs!)))

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
       (js/console.error "cljs-ui: update failed in" (.-cname ^cells/Comp (.-comp inst)) e))
     (finally
       (run-refs!)))))
