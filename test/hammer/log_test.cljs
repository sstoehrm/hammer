(ns hammer.log-test
  (:require [cljs.test :refer [deftest is testing]]
            [hammer.test-env]
            [hammer.state :as state]
            [hammer.events :as ev]
            [hammer.log :as log]
            [hammer.core :as core]
            [hammer.canvas :as canvas]
            [hammer.gl :as gl]
            [hammer.testing :as t]
            [hammer.test-util :refer [capture-errors capture-warnings]]))

(defn- silent
  "Runs f with a no-op reporter: reports reach the test collector but not the console."
  [f]
  (core/on-error! (fn [_]))
  (try (f) (finally (core/on-error! nil))))

(deftest report-goes-to-console-by-default
  (let [e (js/Error. "x")
        errs (capture-errors (fn [_] (log/report! :error "hammer: a" e)
                               (log/report! :error "hammer: b" nil)))
        warns (capture-warnings (fn [_] (log/report! :warn "hammer: c" nil)))]
    (is (= [["hammer: a" e] ["hammer: b"]] errs))
    (is (= [["hammer: c"]] warns))))

(deftest on-error-replaces-the-reporter
  (let [seen (atom [])]
    (core/on-error! #(swap! seen conj %))
    (try
      (let [logs (capture-errors (fn [_] (log/report! :error "hammer: a" nil)))]
        (is (= [] logs) "the console is not called")
        (is (= [{:level :error :message "hammer: a" :error nil}] @seen)))
      (finally (core/on-error! nil))))
  (is (= [["hammer: b"]] (capture-errors (fn [_] (log/report! :error "hammer: b" nil))))
      "nil restores the console"))

(deftest every-facade-exports-on-error
  (is (identical? core/on-error! log/on-error!))
  (is (identical? canvas/on-error! log/on-error!))
  (is (identical? gl/on-error! log/on-error!)))

(deftest a-throwing-reporter-falls-back-to-the-console
  (core/on-error! (fn [_] (throw (js/Error. "reporter"))))
  (try
    (let [logs (capture-errors (fn [_] (log/report! :error "hammer: a" nil)))]
      (is (= "hammer: a" (first (first logs))))
      (is (re-find #"on-error!" (first (second logs)))))
    (finally (core/on-error! nil))))

(deftest tests-see-errors-even-with-an-app-reporter
  (core/on-error! (fn [_]))
  (try
    (is (= [{:level :error :message "hammer: a" :error nil}]
           (t/expect-errors #(log/report! :error "hammer: a" nil))))
    (finally (core/on-error! nil))))

(deftest flush-throws-on-reported-errors
  (t/reset-app!)
  (ev/reg-event :log-test/missing-fx (fn [_] {:log-test/nope 1}))
  (silent
   (fn []
     (ev/dispatch [:log-test/missing-fx])
     (let [thrown (try (t/flush!) nil (catch :default e e))]
       (is (some? thrown) "flush! throws")
       (is (re-find #"1 error" (ex-message thrown)))
       (is (re-find #"returned no known effect keys \(:log-test/nope\)" (ex-message thrown)))
       (is (= 1 (count (:errors (ex-data thrown))))))))
  (is (nil? (t/flush!)) "the errors were taken: the next flush! is clean"))

(deftest flush-throws-for-errors-reported-before-it
  (t/reset-app!)
  (silent #(ev/dispatch-sync [:log-test/unregistered]))
  (is (thrown-with-msg? js/Error #"no event handler" (t/flush!))))

(deftest warnings-never-make-flush-throw
  (t/reset-app!)
  (silent #(log/report! :warn "hammer: w" nil))
  (is (nil? (t/flush!))))

(deftest expect-errors-returns-and-keeps-pending-ones
  (t/reset-app!)
  (silent #(log/report! :error "hammer: before" nil))
  (testing "entries reported inside are returned, not left for flush!"
    (let [got (t/expect-errors #(silent (fn [] (log/report! :error "hammer: inside" nil)
                                          (log/report! :warn "hammer: w" nil))))]
      (is (= ["hammer: inside" "hammer: w"] (mapv :message got)))))
  (testing "an error pending before the call stays pending"
    (is (thrown-with-msg? js/Error #"hammer: before" (t/flush!)))))

(deftest reset-app-clears-the-collector
  (silent #(log/report! :error "hammer: stale" nil))
  (t/reset-app!)
  (is (nil? (t/flush!))))

(deftest capture-helpers-do-not-leak-into-flush
  (t/reset-app!)
  (capture-errors (fn [_] (log/report! :error "hammer: deliberate" nil)))
  (is (nil? (t/flush!))))

(deftest flush-inside-expect-errors-does-not-throw
  (t/reset-app!)
  (ev/reg-event :log-test/missing-fx (fn [_] {:log-test/nope 1}))
  (let [got (t/expect-errors #(silent (fn [] (ev/dispatch [:log-test/missing-fx]) (t/flush!))))]
    (is (= 1 (count got))))
  (is (nil? (t/flush!))))

(deftest check-errors-throws-without-a-flush
  (t/reset-app!)
  (silent #(ev/dispatch-sync [:log-test/unregistered-2]))
  (is (thrown-with-msg? js/Error #"no event handler for :log-test/unregistered-2" (t/check-errors!)))
  (is (nil? (t/check-errors!)) "taken"))

(deftest unrelated-state-is-untouched
  (reset! state/app-db {:k 1})
  (t/expect-errors #(silent (fn [] (log/report! :error "hammer: x" nil))))
  (is (= {:k 1} @state/app-db)))
