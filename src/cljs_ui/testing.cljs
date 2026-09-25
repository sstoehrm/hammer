(ns cljs-ui.testing
  "Test helpers: synchronous flush and render counters."
  (:require [cljs-ui.events :as events]
            [cljs-ui.scheduler :as sched]
            [cljs-ui.cells :as cells]
            [cljs-ui.dom :as dom]
            [cljs-ui.state :as state]))

(defn flush!
  "Drains queued events, then renders until nothing is dirty."
  []
  (events/drain!)
  (loop [n 0]
    (when (sched/pending?)
      (when (= n 10)
        (throw (js/Error. "cljs-ui: flush did not settle after 10 rounds")))
      (sched/flush!)
      (events/drain!)
      (recur (inc n)))))

(defn renders
  "Total renders of component c since the last reset-renders!."
  [^cells/Comp c]
  (.-renders c))

(defn reset-renders! [& cs]
  (doseq [^cells/Comp c cs] (set! (.-renders c) 0)))

(defn reset-app!
  "Unmounts every root and empties app-db. Use as a :before fixture."
  []
  (dom/unmount-all!)
  (reset! state/app-db {}))
