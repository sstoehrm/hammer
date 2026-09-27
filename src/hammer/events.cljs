(ns hammer.events
  "Event registry and queue. Handlers: (fn [db & args] effect-map)."
  (:require [hammer.state :as state]
            [hammer.trie :as trie]
            [hammer.cells :as cells]
            [hammer.scheduler :as sched]))

(deftype State [^:mutable scheduled ^:mutable in-handler])

(defonce ^:private handlers (atom {}))
(defonce ^:private fxs (atom {}))
(defonce ^:private queue #js [])
(defonce ^:private st (State. false false))

(defn reg-event
  "Registers (fn [db & args] effect-map) for event id."
  [id f]
  (swap! handlers assoc id f)
  nil)

(defn reg-fx
  "Registers (fn [value]) for effect key k."
  [k f]
  (swap! fxs assoc k f)
  nil)

(defn set-db!
  "Replaces app-db and marks every subscribed path whose value changed."
  [db]
  (let [old @state/app-db]
    (when-not (identical? old db)
      (reset! state/app-db db)
      (trie/notify! state/paths old db cells/mark!))))

(declare dispatch)

(defn- call-handler [h ev]
  (set! (.-in-handler st) true)
  (try
    (apply h @state/app-db (rest ev))
    (catch :default e
      (js/console.error "hammer: event handler failed" (pr-str ev) e)
      nil)
    (finally (set! (.-in-handler st) false))))

(defn- run-fx! [k v]
  (if-let [f (get @fxs k)]
    (try (f v)
         (catch :default e (js/console.error "hammer: fx failed" (pr-str k) e)))
    (js/console.error "hammer: no fx registered for" (pr-str k)
                      "- did the handler return db instead of {:db db}?")))

(defn- process! [ev]
  (cond
    (not (vector? ev))
    (js/console.error "hammer: event must be a vector, got" (pr-str ev))

    (nil? (get @handlers (first ev)))
    (js/console.error "hammer: no event handler for" (pr-str (first ev)))

    :else
    (let [fx (call-handler (get @handlers (first ev)) ev)]
      (cond
        (nil? fx) nil
        (not (map? fx)) (js/console.error "hammer: handler must return an effect map" (pr-str ev))
        :else (do (when (contains? fx :db) (set-db! (:db fx)))
                  (when-let [d (:dispatch fx)] (dispatch d))
                  (doseq [[k v] (dissoc fx :db :dispatch)] (run-fx! k v)))))))

(defn drain!
  "Processes queued events until the queue is empty."
  []
  (set! (.-scheduled st) false)
  (while (pos? (.-length queue))
    (process! (.shift queue))))

(defn dispatch
  "Queues event vector ev; the queue drains in a microtask."
  [ev]
  (.push queue ev)
  (when-not (.-scheduled st)
    (set! (.-scheduled st) true)
    ;; queueMicrotask, not a Promise job: same FIFO microtask queue, but
    ;; CDP's ScriptDuration (and so perf tooling built on it) does not count
    ;; Promise reaction jobs, which hid the handler and trie notify.
    (js/queueMicrotask drain!)))

(defn dispatch-sync
  "Processes ev now, then flushes rendering. Throws inside a handler."
  [ev]
  (when (.-in-handler st)
    (throw (js/Error. "hammer: dispatch-sync called inside an event handler")))
  (process! ev)
  (sched/flush!))
