(ns cljs-ui.events-test
  (:require [cljs.test :refer [deftest is async]]
            [cljs-ui.test-env]
            [cljs-ui.state :as state]
            [cljs-ui.events :as ev]
            [cljs-ui.testing :as t]))

(defn- capture-errors [f]
  (let [orig js/console.error
        logged (atom [])]
    (set! js/console.error (fn [& args] (swap! logged conj (vec args))))
    (try (f) (finally (set! js/console.error orig)))
    @logged))

(deftest handler-gets-db-and-args
  (reset! state/app-db {:n 1})
  (ev/reg-event :test/add (fn [db x y] {:db (update db :n + x y)}))
  (ev/dispatch-sync [:test/add 2 3])
  (is (= {:n 6} @state/app-db)))

(deftest db-applied-before-other-fx-then-dispatch-queued
  (reset! state/app-db {})
  (let [seen (atom nil)]
    (ev/reg-fx :peek (fn [v] (reset! seen [v @state/app-db])))
    (ev/reg-event :a (fn [_] {:db {:a 1} :peek :x :dispatch [:b]}))
    (ev/reg-event :b (fn [db] {:db (assoc db :b 2)}))
    (ev/dispatch-sync [:a])
    (is (= [:x {:a 1}] @seen))
    (is (= {:a 1} @state/app-db))
    (t/flush!)
    (is (= {:a 1 :b 2} @state/app-db))))

(deftest dispatch-is-async
  (async done
    (reset! state/app-db {:n 0})
    (ev/reg-event :inc (fn [db] {:db (update db :n inc)}))
    (ev/dispatch [:inc])
    (is (= 0 (:n @state/app-db)))
    (js/setTimeout (fn []
                     (is (= 1 (:n @state/app-db)))
                     (done))
                   0)))

(deftest failures-are-logged-and-skipped
  (reset! state/app-db {:n 0})
  (ev/reg-event :boom (fn [_] (throw (js/Error. "boom"))))
  (ev/reg-event :bad-fx (fn [_] {:nope 1}))
  (let [logs (capture-errors #(do (ev/dispatch-sync [:boom])
                                  (ev/dispatch-sync [:missing])
                                  (ev/dispatch-sync [:bad-fx])))]
    (is (= {:n 0} @state/app-db))
    (is (= ["cljs-ui: event handler failed"
            "cljs-ui: no event handler for"
            "cljs-ui: no fx registered for"]
           (mapv first logs)))))

(deftest dispatch-sync-inside-handler-fails
  (ev/reg-event :inner (fn [db] {:db db}))
  (ev/reg-event :outer (fn [_] (ev/dispatch-sync [:inner]) {:db {:ran true}}))
  (reset! state/app-db {})
  (let [logs (capture-errors #(ev/dispatch-sync [:outer]))]
    (is (= {} @state/app-db))
    (is (re-find #"dispatch-sync called inside" (.-message (last (first logs)))))))

(deftest bad-event-shapes-are-reported
  (reset! state/app-db {:n 0})
  (ev/reg-event :db-only (fn [db] db))
  (let [logs (capture-errors #(do (ev/dispatch-sync :oops)
                                  (ev/dispatch-sync [:db-only])))]
    (is (= "cljs-ui: event must be a vector, got" (first (first logs))))
    (is (re-find #"\{:db db\}" (last (second logs))))
    (is (= {:n 0} @state/app-db))))
