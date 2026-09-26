(ns hammer.cells
  "Per-instance binding cells: props, path, eq (is?), local and derived slots.
  Marking is eager; recomputation is lazy (refresh!)."
  (:require [hammer.state :as state]
            [hammer.trie :as trie]
            [hammer.scheduler :as sched]))

(deftype Comp [cname nprops specs body-deps body ^:mutable renders])
(deftype Spec [kind deps f])
;; A subscribed slot: kind 0 path, 1 eq (is?), 2 local. path: the db path
;; (path, eq); v: the compared value (eq).
(deftype Cell [inst i kind ^:mutable path ^:mutable v ^:mutable stale])
;; vals: slot values. cells: a Cell per path/eq/local slot, nil for props and
;; derived slots. changed: per-slot flags set by set-props!, consumed by refresh!.
(deftype Instance [comp depth ^:mutable dirty ^:mutable mounted ^:mutable vnode
                   vals cells ^:mutable changed])

(defn component
  "Built by defc. specs: one {:kind :path|:eq|:expr, :deps [slot], :f fn} per
  binding (an :eq f returns [path v]); body-deps: the slots the body names."
  [cname nprops specs body-deps body]
  (Comp. cname nprops
         (to-array (map (fn [{:keys [kind deps f]}] (Spec. kind (to-array deps) f)) specs))
         (to-array body-deps) body 0))

(defn component? [x] (instance? Comp x))

(defn mark!
  "Marks a path, eq or local cell stale and schedules its instance."
  [^Cell cell]
  (set! (.-stale cell) true)
  (sched/schedule! (.-inst cell)))

(defn- call
  "Calls f with the slot values at indices deps."
  [f vals ^js deps]
  (case (alength deps)
    0 (f)
    1 (f (aget vals (aget deps 0)))
    2 (f (aget vals (aget deps 0)) (aget vals (aget deps 1)))
    (.apply f nil (.map deps #(aget vals %)))))

(defn- lookup
  "(get-in db p), without seq overhead for vector paths."
  [db p]
  (if (vector? p)
    (let [n (count p)]
      (loop [m db i 0]
        (if (< i n) (recur (get m (nth p i)) (inc i)) m)))
    (get-in db p)))

(defn- cell-value [^Cell cell]
  (let [v (lookup @state/app-db (.-path cell))]
    (if (== (.-kind cell) 1) (= v (.-v cell)) v)))

(defn- subscribe! [^Cell cell]
  (if (== (.-kind cell) 0)
    (trie/register! state/paths (.-path cell) cell)
    (trie/register-eq! state/paths (.-path cell) (.-v cell) cell)))

(defn- unsubscribe! [^Cell cell]
  (if (== (.-kind cell) 0)
    (trie/unregister! state/paths (.-path cell) cell)
    (trie/unregister-eq! state/paths (.-path cell) (.-v cell) cell)))

(def ^:private failed #js {})

(defn- init
  "Runs a binding init; failed (logged) if it throws."
  [^Comp c ^Spec spec vals]
  (try
    (call (.-f spec) vals (.-deps spec))
    (catch :default e
      (js/console.error "hammer: render failed in" (.-cname c) e)
      failed)))

(defn create
  "Instantiates c with positional args. Evaluates every binding once,
  subscribes paths, watches locals. A binding whose init throws is logged
  by component name and treated as a nil, unsubscribed value; it does not
  abort creation of this or any other instance."
  [^Comp c args depth]
  (let [np (.-nprops c)
        specs (.-specs c)
        n (+ np (alength specs))
        vals (make-array n)
        cells (make-array n)
        inst (Instance. c depth false true nil vals cells nil)]
    (dotimes [i np]
      (aset vals i (nth args i nil)))
    (dotimes [j (alength specs)]
      (let [i (+ np j)
            ^Spec spec (aget specs j)
            kind (.-kind spec)
            v (init c spec vals)]
        (cond
          (identical? v failed)
          (aset vals i nil)

          (keyword-identical? kind :path)
          (let [cell (Cell. inst i 0 v nil false)]
            (aset cells i cell)
            (subscribe! cell)
            (aset vals i (cell-value cell)))

          (keyword-identical? kind :eq)
          (let [cell (Cell. inst i 1 (nth v 0) (nth v 1) false)]
            (aset cells i cell)
            (subscribe! cell)
            (aset vals i (cell-value cell)))

          (satisfies? IWatchable v)
          (let [cell (Cell. inst i 2 nil nil false)]
            (aset cells i cell)
            (add-watch v cell (fn [_ _ o nv] (when-not (identical? o nv) (mark! cell))))
            (aset vals i v))

          :else
          (aset vals i v))))
    inst))

(defn set-props!
  "Stores new positional args. Returns true if any arg is not = to the old one."
  [^Instance inst args]
  (let [vals (.-vals inst)
        ^Comp c (.-comp inst)
        np (.-nprops c)]
    (loop [i 0 any? false]
      (if (< i np)
        (let [o (aget vals i)
              v (nth args i nil)]
          (cond
            (identical? o v) (recur (inc i) any?)
            (= o v) (do (aset vals i v) (recur (inc i) any?))
            :else (do (aset vals i v)
                      (aset (or (.-changed inst)
                                (set! (.-changed inst) (make-array (alength vals))))
                            i true)
                      (recur (inc i) true))))
        any?))))

(defn- change! [vals changed i v]
  (let [o (aget vals i)]
    (when-not (or (identical? o v) (= o v))
      (aset vals i v)
      (aset changed i true))))

(defn- any-at? [^js changed ^js idx]
  (loop [k 0]
    (cond
      (== k (alength idx)) false
      (aget changed (aget idx k)) true
      :else (recur (inc k)))))

(defn refresh!
  "Recomputes stale slots in binding order. Returns true if a slot the body
  names changed; the others only feed later bindings."
  [^Instance inst]
  (let [^Comp c (.-comp inst)
        np (.-nprops c)
        specs (.-specs c)
        vals (.-vals inst)
        cells (.-cells inst)
        changed (or (.-changed inst) (make-array (alength vals)))]
    (set! (.-changed inst) nil)
    (dotimes [j (alength specs)]
      (let [i (+ np j)
            ^Spec spec (aget specs j)
            dep-changed? (any-at? changed (.-deps spec))
            ^Cell cell (aget cells i)]
        (cond
          (nil? cell)
          (when dep-changed?
            (change! vals changed i (call (.-f spec) vals (.-deps spec))))

          (== (.-kind cell) 2)
          (when (.-stale cell)
            (set! (.-stale cell) false)
            (aset changed i true))

          :else
          (do (when dep-changed?
                (let [x (call (.-f spec) vals (.-deps spec))
                      eq? (== (.-kind cell) 1)
                      p (if eq? (nth x 0) x)
                      v (when eq? (nth x 1))]
                  (when-not (and (= p (.-path cell)) (= v (.-v cell)))
                    (unsubscribe! cell)
                    (set! (.-path cell) p)
                    (set! (.-v cell) v)
                    (subscribe! cell))))
              (when (or dep-changed? (.-stale cell))
                (set! (.-stale cell) false)
                (change! vals changed i (cell-value cell)))))))
    (any-at? changed (.-body-deps c))))

(defn render
  "Calls the component body with the current slot values."
  [^Instance inst]
  (let [^Comp c (.-comp inst)]
    (set! (.-renders c) (inc (.-renders c)))
    (.apply (.-body c) nil (.-vals inst))))

(defn destroy!
  "Unsubscribes paths and is? cells, removes watches, marks the instance unmounted."
  [^Instance inst]
  (set! (.-mounted inst) false)
  (let [cells (.-cells inst)]
    (dotimes [i (alength cells)]
      (when-let [^Cell cell (aget cells i)]
        (if (== (.-kind cell) 2)
          (remove-watch (aget (.-vals inst) i) cell)
          (unsubscribe! cell))))))
