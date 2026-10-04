(ns hammer.track-test
  "Tracks: dispatch an event when the values at db paths change."
  (:require [cljs.test :refer [deftest is use-fixtures]]
            [hammer.test-env]
            [hammer.state :as state]
            [hammer.events :as events]
            [hammer.track :as track]
            [hammer.testing :as t]))

(def seen (atom []))
(def calls (atom 0))

(use-fixtures :each {:before #(do (t/reset-app!) (reset! seen []) (reset! calls 0))})

(events/reg-event ::set (fn [db k v] {:db (assoc db k v)}))
(events/reg-event ::set-in (fn [db p v] {:db (assoc-in db p v)}))
(events/reg-event ::saw (fn [_ & args] (swap! seen conj (vec args)) nil))
(events/reg-event ::fx (fn [_ k v] {k v}))

(deftest registers-through-the-effect-and-dispatches-on-change
  (reset! state/app-db {:filters {:q "a"} :other 1})
  (events/dispatch-sync [::fx ::track/register {:id :f :path [:filters] :event-fn (fn [f] [::saw f])}])
  (t/flush!)
  (is (= [[{:q "a"}]] @seen) "dispatches for the current value first")
  (events/dispatch-sync [::set :other 2])
  (t/flush!)
  (is (= 1 (count @seen)) "a change elsewhere does nothing")
  (events/dispatch-sync [::set-in [:filters :q] "b"])
  (t/flush!)
  (is (= [[{:q "a"}] [{:q "b"}]] @seen))
  (events/dispatch-sync [::set :filters {:q "b"}])
  (t/flush!)
  (is (= 2 (count @seen)) "an = value is no change"))

(deftest several-paths-and-nil-events
  (reset! state/app-db {:a 1 :b 2})
  (track/register! {:id :ab :paths [[:a] [:b]] :event-fn (fn [a b] (when (odd? (+ a b)) [::saw a b]))})
  (t/flush!)
  (is (= [[1 2]] @seen))
  (events/dispatch-sync [::set :b 3])
  (t/flush!)
  (is (= [[1 2]] @seen) "event-fn returning nil dispatches nothing")
  (events/dispatch-sync [::set :a 2])
  (t/flush!)
  (is (= [[1 2] [2 3]] @seen)))

(deftest dispatch-first-false-waits-for-a-change
  (reset! state/app-db {:a 1})
  (track/register! {:id :a :path [:a] :event-fn (fn [a] [::saw a]) :dispatch-first? false})
  (t/flush!)
  (is (= [] @seen))
  (events/dispatch-sync [::set :a 2])
  (t/flush!)
  (is (= [[2]] @seen)))

(deftest dispose-stops-and-misuse-warns
  (reset! state/app-db {:a 1})
  (events/dispatch-sync [::fx ::track/register [{:id :a1 :path [:a] :event-fn (fn [a] [::saw :a1 a])}
                                               {:id :a2 :path [:a] :event-fn (fn [a] [::saw :a2 a])}]])
  (t/flush!)
  (events/dispatch-sync [::fx ::track/dispose {:id (keyword "a1")}]) ; ids by value
  (events/dispatch-sync [::set :a 2])
  (t/flush!)
  (is (= [[:a1 1] [:a2 1] [:a2 2]] @seen))
  (let [r (t/expect-errors #(do (track/dispose! {:id :nope})
                                (track/register! {:id :bad :path :a :event-fn identity})
                                (track/register! {:id :both :path [:a] :paths [[:b]] :event-fn identity})))]
    (is (= ["hammer: no track :nope to dispose"
            "hammer: track :bad needs :path or :paths (db path vectors) and an :event-fn"
            "hammer: track :both takes :path or :paths, not both"]
           (mapv :message r)))))

(deftest registering-an-id-again-replaces-it
  (reset! state/app-db {:a 1})
  (track/register! {:id :hot :path [:a] :event-fn (fn [a] [::saw :old a])})
  (t/flush!)
  (track/register! {:id :hot :path [:a] :event-fn (fn [a] [::saw :new a])}) ; e.g. hot reload
  (t/flush!)
  (events/dispatch-sync [::set :a 2])
  (t/flush!)
  (is (= [[:old 1] [:new 1] [:new 2]] @seen) "the new event-fn replaces the old one"))

(def kept (atom nil))
(events/reg-event ::keep (fn [_ vs] (reset! kept vs) nil))

(deftest a-variadic-event-fn-keeps-its-values
  (reset! state/app-db {:a 1 :b 2})
  (track/register! {:id :var :paths [[:a] [:b]] :event-fn (fn [& vs] [::keep vs])})
  (t/flush!)
  (events/dispatch-sync [::set :a 99])
  (is (= [1 2] (vec @kept)) "values handed to event-fn don't change afterwards"))

(deftest only-affected-tracks-run
  (reset! state/app-db {:a 1 :b 1})
  (track/register! {:id :ta :path [:a] :event-fn (fn [_] (swap! calls inc) nil)})
  (track/register! {:id :tb :path [:b] :event-fn (fn [_] nil)})
  (t/flush!)
  (reset! calls 0)
  (dotimes [i 5] (events/dispatch-sync [::set :b i]))
  (t/flush!)
  (is (= 0 @calls) "the trie never marks a track whose path did not change"))

(deftest a-throwing-event-fn-is-reported
  (reset! state/app-db {:a 1})
  (let [r (t/expect-errors #(do (track/register! {:id :boom :path [:a] :event-fn (fn [_] (throw (js/Error. "x")))})
                                (t/flush!)))]
    (is (= ["hammer: track :boom failed"] (mapv :message r)))))

(deftest reset-app-disposes-tracks
  (reset! state/app-db {:a 1})
  (track/register! {:id :keep :path [:a] :event-fn (fn [a] [::saw a])})
  (t/flush!)
  (t/reset-app!)
  (reset! seen [])
  (events/dispatch-sync [::set :a 5])
  (t/flush!)
  (is (= [] @seen))
  (track/register! {:id :keep :path [:a] :event-fn (fn [a] [::saw a])})
  (t/flush!)
  (is (= [[5]] @seen) "the id is free again"))
