(ns hammer.track
  "Tracks: dispatch an event when the values at db paths change, without a
  component. Requiring this namespace registers the effects:

    {:hammer.track/register {:id :reload :path [:filters]
                             :event-fn (fn [filters] [:load filters])}}
    {:hammer.track/dispose {:id :reload}}

  :path, or :paths for several (event-fn then gets one value per path).
  event-fn returns an event vector to dispatch, or nil for none.
  :dispatch-first? (default true) also dispatches for the values at
  registration. Both effects take a map or a vector of maps.

  A track is a component instance without a body or DOM: its paths subscribe
  in the path trie like any binding, so only tracks whose paths changed run,
  in the render flush after the event that changed them."
  (:require [hammer.cells :as cells]
            [hammer.events :as events]
            [hammer.log :as log]))

;; id → Instance, by value (= ids, not identical ones); the instance's vnode
;; slot holds #js {:id :f}
(defonce ^:private tracks (volatile! {}))

(defn- fire!
  "Dispatches (event-fn values...) unless it returns nil."
  [^cells/Instance inst]
  (let [^js t (.-vnode inst)]
    (try
      (when-some [ev (apply (.-f t) (array-seq (.-vals inst)))]
        (events/dispatch ev))
      (catch :default e
        (log/report! :error (str "hammer: track " (pr-str (.-id t)) " failed") e)))))

(defn- step!
  "The Host run: recompute the marked paths, fire when a value changed."
  [^cells/Instance inst]
  (set! (.-dirty inst) false)
  (when (cells/refresh! inst)
    (fire! inst)))

(def ^:private host (cells/Host. step! nil nil))

(defn register!
  "Registers a track: {:id :path|:paths :event-fn :dispatch-first?}."
  [{:keys [id path paths event-fn dispatch-first?] :or {dispatch-first? true}}]
  (let [paths (or paths (when path [path]))]
    (cond
      (contains? @tracks id)
      (log/report! :warn (str "hammer: track " (pr-str id) " is already registered") nil)

      (not (and (some? id) (seq paths) (every? vector? paths) (fn? event-fn)))
      (log/report! :error (str "hammer: track " (pr-str id) " needs :path or :paths (db path"
                               " vectors) and an :event-fn")
                   nil)

      :else
      (let [n (count paths)
            c (cells/component (str "track " (pr-str id)) 0
                               (mapv (fn [p] {:kind :path :deps [] :f (constantly p)}) paths)
                               (vec (range n))
                               (fn [& _] nil)
                               host)
            inst (cells/create c [] 0)]
        (set! (.-vnode inst) #js {:id id :f event-fn})
        (vswap! tracks assoc id inst)
        (when dispatch-first? (fire! inst))))
    nil))

(defn dispose!
  "Disposes the track {:id id}: unsubscribes its paths."
  [{:keys [id]}]
  (if-let [inst (get @tracks id)]
    (do (cells/destroy! inst)
        (vswap! tracks dissoc id))
    (log/report! :warn (str "hammer: no track " (pr-str id) " to dispose") nil))
  nil)

(defn dispose-all!
  "Disposes every track (hammer.testing/reset-app! calls it)."
  []
  (run! cells/destroy! (vals @tracks))
  (vreset! tracks {})
  nil)

(defn- each [f x] (if (sequential? x) (doseq [m x] (f m)) (f x)))

(events/reg-fx ::register #(each register! %))
(events/reg-fx ::dispose #(each dispose! %))
