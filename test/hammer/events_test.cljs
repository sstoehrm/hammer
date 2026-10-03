(ns hammer.events-test
  (:require [cljs.test :refer [deftest is async testing]]
            [hammer.test-env]
            [hammer.state :as state]
            [hammer.events :as ev]
            [hammer.testing :as t]
            [hammer.test-util :refer [capture-errors]]
            [hammer.cells]
            [hammer.dom]))

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
  (let [logs (capture-errors (fn [_] (ev/dispatch-sync [:boom])
                               (ev/dispatch-sync [:missing])
                               (ev/dispatch-sync [:bad-fx])))]
    (is (= {:n 0} @state/app-db))
    (is (= ["hammer: event handler failed [:boom]"
            "hammer: no event handler for :missing"
            "hammer: handler for :bad-fx returned no known effect keys (:nope) - did it return db instead of {:db db}, or miss a reg-fx?"]
           (mapv first logs)))))

(deftest dispatch-sync-inside-handler-fails
  (ev/reg-event :inner (fn [db] {:db db}))
  (ev/reg-event :outer (fn [_] (ev/dispatch-sync [:inner]) {:db {:ran true}}))
  (reset! state/app-db {})
  (let [logs (capture-errors (fn [_] (ev/dispatch-sync [:outer])))]
    (is (= {} @state/app-db))
    (is (re-find #"dispatch-sync called inside" (.-message (last (first logs)))))))

(deftest bad-event-shapes-are-reported
  (reset! state/app-db {:n 0})
  (ev/reg-event :db-only (fn [db] db))
  (let [logs (capture-errors (fn [_] (ev/dispatch-sync :oops)
                               (ev/dispatch-sync [:db-only])))]
    (is (= "hammer: event must be a vector, got :oops" (first (first logs))))
    (is (re-find #"handler for :db-only returned no known effect keys \(:n\) - did it return db instead of \{:db db\}"
                 (first (second logs))))
    (is (= 2 (count logs)) "one error for the returned db, not one per key")
    (is (= {:n 0} @state/app-db))))

(deftest returned-db-never-runs-an-fx
  (reset! state/app-db {:a 1 :b 2})
  (ev/reg-event :db-only-2 (fn [db] db))
  (let [ran (atom [])]
    (ev/reg-fx :c (fn [v] (swap! ran conj v)))
    (let [logs (capture-errors (fn [_] (ev/dispatch-sync [:db-only-2])))]
      (is (re-find #"no known effect keys \(:a :b\)" (first (first logs))))
      (is (= 1 (count logs))))
    (testing "a map with a registered fx key still runs it and reports the others"
      (ev/reg-event :mixed (fn [_] {:c 1 :unknown 2}))
      (let [logs (capture-errors (fn [_] (ev/dispatch-sync [:mixed])))]
        (is (= [1] @ran))
        (is (= ["hammer: no fx registered for :unknown"] (mapv first logs)))))
    (testing "an empty map does nothing and reports nothing"
      (ev/reg-event :empty (fn [_] {}))
      (is (= [] (capture-errors (fn [_] (ev/dispatch-sync [:empty]))))))))

(deftest dispatching-an-fx-id-says-so
  (ev/reg-fx :some-fx (fn [_]))
  (let [logs (capture-errors (fn [_] (ev/dispatch-sync [:some-fx 1])
                               (ev/dispatch-sync [:not-anything])))]
    (is (= ["hammer: no event handler for :some-fx (:some-fx is an fx: return {:some-fx value} from an event handler)"
            "hammer: no event handler for :not-anything"]
           (mapv first logs)))))

(deftest dispatch-renders-before-next-task
  (async done
    (t/reset-app!)
    (ev/reg-event :test/set (fn [db v] {:db (assoc db :v v)}))
    (let [el (js/document.createElement "div")
          view (hammer.cells/component "view" 0 [{:kind :path :deps [] :f (fn [] [:v])}] [0] (fn [v] [:p (str v)]))]
      (hammer.dom/mount! [view] el)
      (ev/dispatch [:test/set 1])
      (ev/dispatch [:test/set 2])
      (is (= "" (.-textContent el)) "nothing renders synchronously")
      (js/setTimeout (fn []
                       (is (= "2" (.-textContent el)) "rendered once, within the microtasks after dispatch")
                       (done))
                     0))))
