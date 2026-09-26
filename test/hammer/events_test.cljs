(ns hammer.events-test
  (:require [cljs.test :refer [deftest is async]]
            [hammer.test-env]
            [hammer.state :as state]
            [hammer.events :as ev]
            [hammer.testing :as t]
            [hammer.cells]
            [hammer.dom]))

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
    (is (= ["hammer: event handler failed"
            "hammer: no event handler for"
            "hammer: no fx registered for"]
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
    (is (= "hammer: event must be a vector, got" (first (first logs))))
    (is (re-find #"\{:db db\}" (last (second logs))))
    (is (= {:n 0} @state/app-db))))

(deftest dispatch-renders-before-next-task
  (async done
    (t/reset-app!)
    (ev/reg-event :test/set (fn [db v] {:db (assoc db :v v)}))
    (let [el (js/document.createElement "div")
          view (hammer.cells/component "view" 0 [{:kind :path :deps [] :f (fn [] [:v])}] (fn [v] [:p (str v)]))]
      (hammer.dom/mount! [view] el)
      (ev/dispatch [:test/set 1])
      (ev/dispatch [:test/set 2])
      (is (= "" (.-textContent el)) "nothing renders synchronously")
      (js/setTimeout (fn []
                       (is (= "2" (.-textContent el)) "rendered once, within the microtasks after dispatch")
                       (done))
                     0))))
