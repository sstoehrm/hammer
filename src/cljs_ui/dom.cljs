(ns cljs-ui.dom
  "Hiccup → DOM. Each instance keeps its last normalized hiccup (vnode) and is
  diffed only against itself; child components are boundaries."
  (:require [clojure.string :as str]
            [goog.object :as gobj]
            [cljs-ui.cells :as cells]
            [cljs-ui.events :as events]))

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

(defn- run-refs!
  "Calls :ref fns queued by create! once their elements are in the document."
  []
  (.forEach (.splice ref-queue 0) (fn [[f el]] (f el))))

(defn- node-of [^VNode v]
  (if (keyword-identical? (.-t v) :comp)
    (node-of (.-vnode ^cells/Instance (.-inst v)))
    (.-el v)))

(declare mount-inst!)

(defn- create!
  "Builds the DOM for v, owned by an instance at depth. Returns the node."
  [^VNode v depth]
  (case (.-t v)
    :text (let [n (.createTextNode js/document (.-text v))]
            (set! (.-el v) n)
            n)
    :el (let [el (.createElement js/document (.-tag v))
              attrs (.-attrs v)]
          (set-attrs! el nil attrs)
          (.forEach (.-kids v) (fn [k] (.appendChild el (create! k depth))))
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
            (when-let [r (:ref (.-attrs v))] (r nil)))
    :comp (let [inst (.-inst v)]
            (unmount! (.-vnode ^cells/Instance inst))
            (cells/destroy! inst))))

;; ---- roots

(defonce ^:private roots (atom {}))

(defn mount!
  "Renders hiccup into container el, replacing what an earlier mount! put there."
  [hiccup el]
  (when-let [old (get @roots el)] (unmount! old))
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
