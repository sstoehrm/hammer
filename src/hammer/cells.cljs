(ns hammer.cells
  "Per-instance binding cells: props, path, local and derived slots.
  Marking is eager; recomputation is lazy (refresh!)."
  (:require [hammer.state :as state]
            [hammer.trie :as trie]
            [hammer.scheduler :as sched]))

(deftype Comp [cname nprops specs body-deps body ^:mutable renders])
(deftype Spec [kind deps f])
(deftype Cell [inst i])
(deftype Instance [comp depth ^:mutable dirty ^:mutable mounted ^:mutable vnode
                   vals stale kinds paths cells])

(defn component
  "Built by defc. specs: one {:kind :path|:expr, :deps [slot], :f fn} per binding;
  body-deps: the slots the body names."
  [cname nprops specs body-deps body]
  (Comp. cname nprops
         (to-array (map (fn [{:keys [kind deps f]}] (Spec. kind (to-array deps) f)) specs))
         (to-array body-deps) body 0))

(defn component? [x] (instance? Comp x))

(defn mark!
  "Marks a path or local cell stale and schedules its instance."
  [^Cell cell]
  (let [^Instance inst (.-inst cell)]
    (aset (.-stale inst) (.-i cell) true)
    (sched/schedule! inst)))

(defn- call
  "Calls f with the slot values at indices deps."
  [f vals ^js deps]
  (case (alength deps)
    0 (f)
    1 (f (aget vals (aget deps 0)))
    2 (f (aget vals (aget deps 0)) (aget vals (aget deps 1)))
    (.apply f nil (.map deps #(aget vals %)))))

(defn create
  "Instantiates c with positional args. Evaluates every binding once,
  subscribes paths, watches locals. A binding whose init throws is logged
  by component name and treated as a nil, unsubscribed value; it does not
  abort creation of this or any other instance."
  [^Comp c args depth]
  (let [np (.-nprops c)
        specs (.-specs c)
        n (+ np (alength specs))
        inst (Instance. c depth false true nil
                        (make-array n) (make-array n) (make-array n)
                        (make-array n) (make-array n))
        vals (.-vals inst)
        kinds (.-kinds inst)]
    (dotimes [i np]
      (aset vals i (nth args i nil))
      (aset kinds i :prop))
    (dotimes [j (alength specs)]
      (let [i (+ np j)
            ^Spec spec (aget specs j)
            kind (.-kind spec)
            cell (Cell. inst i)
            failed? (volatile! false)
            v (try
                (call (.-f spec) vals (.-deps spec))
                (catch :default e
                  (js/console.error "hammer: render failed in" (.-cname c) e)
                  (vreset! failed? true)
                  nil))]
        (aset (.-cells inst) i cell)
        (cond
          @failed?
          (do (aset kinds i :derived)
              (aset vals i nil))

          (= kind :path)
          (do (aset kinds i :path)
              (aset (.-paths inst) i v)
              (trie/register! state/paths v cell)
              (aset vals i (get-in @state/app-db v)))

          (satisfies? IWatchable v)
          (do (aset kinds i :local)
              (add-watch v cell (fn [_ _ o nv] (when-not (identical? o nv) (mark! cell))))
              (aset vals i v))

          :else
          (do (aset kinds i :derived)
              (aset vals i v)))))
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
                      (aset (.-stale inst) i true)
                      (recur (inc i) true))))
        any?))))

(defn- change! [vals changed i v]
  (let [o (aget vals i)]
    (when-not (or (identical? o v) (= o v))
      (aset vals i v)
      (aset changed i true))))

(defn refresh!
  "Recomputes stale slots in binding order. Returns true if a slot the body
  names changed; the others only feed later bindings."
  [^Instance inst]
  (let [^Comp c (.-comp inst)
        np (.-nprops c)
        specs (.-specs c)
        vals (.-vals inst)
        stale (.-stale inst)
        changed (make-array (alength vals))]
    (dotimes [i np]
      (when (aget stale i) (aset changed i true)))
    (dotimes [j (alength specs)]
      (let [i (+ np j)
            ^Spec spec (aget specs j)
            dep-changed? (.some (.-deps spec) #(aget changed %))]
        (case (aget (.-kinds inst) i)
          :path
          (do (when dep-changed?
                (let [old (aget (.-paths inst) i)
                      p (call (.-f spec) vals (.-deps spec))
                      cell (aget (.-cells inst) i)]
                  (when (not= old p)
                    (trie/unregister! state/paths old cell)
                    (trie/register! state/paths p cell)
                    (aset (.-paths inst) i p))))
              (when (or dep-changed? (aget stale i))
                (change! vals changed i (get-in @state/app-db (aget (.-paths inst) i)))))

          :local
          (when (aget stale i) (aset changed i true))

          :derived
          (when dep-changed?
            (change! vals changed i (call (.-f spec) vals (.-deps spec)))))))
    (.fill stale false)
    (.some (.-body-deps c) #(aget changed %))))

(defn render
  "Calls the component body with the current slot values."
  [^Instance inst]
  (let [^Comp c (.-comp inst)]
    (set! (.-renders c) (inc (.-renders c)))
    (.apply (.-body c) nil (.-vals inst))))

(defn destroy!
  "Unsubscribes paths, removes watches, marks the instance unmounted."
  [^Instance inst]
  (set! (.-mounted inst) false)
  (dotimes [i (alength (.-vals inst))]
    (case (aget (.-kinds inst) i)
      :path (trie/unregister! state/paths (aget (.-paths inst) i) (aget (.-cells inst) i))
      :local (remove-watch (aget (.-vals inst) i) (aget (.-cells inst) i))
      nil)))
