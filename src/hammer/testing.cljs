(ns hammer.testing
  "Test helpers: synchronous flush and render counters. Loading this ns turns
  on hammer.log's collector: flush! throws when hammer reported an error."
  (:require [hammer.events :as events]
            [hammer.log :as log]
            [hammer.track :as track]
            [hammer.tubes :as tubes]
            [hammer.scheduler :as sched]
            [hammer.cells :as cells]
            [hammer.dom :as dom]
            [hammer.draw :as draw]
            [hammer.state :as state]))

(defonce ^:private collecting (log/collect!))
(defonce ^:private expecting (volatile! 0))

(defn check-errors!
  "Throws ex-info {:errors [...]} when hammer reported an :error since the
  last check (flush!, frame! and this check); warnings are dropped. Does
  nothing inside expect-errors. flush! and frame! call it; call it yourself,
  e.g. as an :after fixture, in tests that only use dispatch-sync."
  []
  (when (zero? @expecting)
    (let [errs (filterv #(keyword-identical? :error (:level %)) (log/take!))]
      (when (seq errs)
        (throw (ex-info (str "hammer: " (count errs) " error(s) reported: " (:message (first errs)))
                        {:errors errs}))))))

(defn flush!
  "Drains queued events, then renders until nothing is dirty. Throws if
  hammer reported an error since the last flush! (outside expect-errors)."
  []
  (events/drain!)
  (loop [n 0]
    (when (sched/pending?)
      (when (= n 10)
        (throw (js/Error. "hammer: flush did not settle after 10 rounds")))
      (sched/flush!)
      (events/drain!)
      (recur (inc n))))
  (check-errors!))

(defn expect-errors
  "Runs (f) and returns the reports made during it, [{:level :message :error}],
  instead of letting them fail flush!. Reports pending before the call stay.
  Nested inside another expect-errors, the inner call takes its reports: the
  outer one does not see them."
  [f]
  (vswap! expecting inc)
  (try
    (log/isolated f)
    (finally (vswap! expecting dec))))

(defn renders
  "Total renders of component c since the last reset-renders!."
  [^cells/Comp c]
  (.-renders c))

(defn reset-renders! [& cs]
  (doseq [^cells/Comp c cs] (set! (.-renders c) 0)))

(defn reset-app!
  "Unmounts every root, empties app-db and drops collected reports. Use as a
  :before fixture."
  []
  (draw/unmount-all!)
  (dom/unmount-all!)
  (track/dispose-all!)
  (tubes/destroy-all!)
  (reset! state/app-db {})
  (log/take!)
  nil)

(defn use-fake-frames!
  "Draw components get frames only from frame!, never from requestAnimationFrame."
  []
  (draw/set-raf! (fn [_] nil)))

(defn frame!
  "Flushes pending events and updates, then runs one draw frame at time ms.
  Throws like flush! when hammer reported an error, the frame's included."
  [ms]
  (flush!)
  (draw/frame! ms)
  (check-errors!))
