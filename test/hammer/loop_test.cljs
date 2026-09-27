(ns hammer.loop-test
  (:require [cljs.test :refer [deftest is use-fixtures]]
            [hammer.test-env]
            [hammer.fake-canvas :as fake]
            [hammer.canvas :as cv :refer [defloop defdraw]]
            [hammer.draw :as draw]
            [hammer.events :as events]
            [hammer.state :as state]
            [hammer.testing :as t]))

(def seen (atom []))
(def raf-calls (atom 0))

(use-fixtures :each {:before (fn []
                               (t/reset-app!)
                               (draw/set-raf! (fn [_] (swap! raf-calls inc)))
                               (reset! raf-calls 0)
                               (reset! seen [])
                               (reset! fake/log []))})

(events/reg-event ::set (fn [db k v] {:db (assoc db k v)}))

(defloop ticker [id] [paused? [:paused?]]
  {:size [10 10] :run? (not paused?) :max-dt 50}
  (fn [_ {:keys [t dt n]}] (swap! seen conj [id t dt n])))

(defn- div [] (js/document.createElement "div"))

(deftest runs-every-frame-with-capped-dt
  (reset! state/app-db {:paused? false})
  (cv/mount! [ticker :a] (div))
  (t/frame! 1000) (t/frame! 1016) (t/frame! 2000)
  (is (= [[:a 0 0 1] [:a 16 16 2] [:a 66 50 3]] @seen) "dt capped at :max-dt"))

(deftest pause-freezes-t-and-redraws-on-change-with-dt-0
  (reset! state/app-db {:paused? false :x 1})
  (cv/mount! [ticker :a] (div))
  (t/frame! 0) (t/frame! 16)
  (events/dispatch [::set :paused? true])
  (t/frame! 32)
  (is (= [:a 16 0 2] (last @seen)) "the pause itself redraws once with dt 0, n unchanged")
  (reset! seen [])
  (t/frame! 48) (t/frame! 64)
  (is (empty? @seen) "paused: no frames")
  (events/dispatch [::set :paused? false])
  (t/frame! 500) (t/frame! 516)
  (is (= [[:a 16 0 3] [:a 32 16 4]] @seen) "resume: first dt 0, t continues"))

(deftest idle-app-requests-no-frames
  (reset! state/app-db {:paused? true})
  (cv/mount! [ticker :a] (div))
  (t/frame! 0)
  (reset! raf-calls 0)
  (t/frame! 16)
  (is (zero? @raf-calls) "no running loop, nothing queued: no rAF"))

(deftest two-loops-share-one-frame-request
  (reset! state/app-db {:paused? false})
  (cv/mount! [ticker :a] (div))
  (cv/mount! [ticker :b] (div))
  (t/frame! 0)
  (reset! raf-calls 0)
  (t/frame! 16)
  (is (= 1 @raf-calls))
  (is (= #{[:a 16 16 2] [:b 16 16 2]} (set (take-last 2 @seen)))))

(defloop crashy [] [] {:size [10 10]}
  (fn [_ {:keys [n]}] (swap! seen conj [:crashy n]) (throw (js/Error. "x"))))

(defdraw steady [] [x [:x]] {:size [10 10]} (fn [_ _] (swap! seen conj [:steady x])))

(deftest a-throwing-loop-keeps-running-and-others-draw
  (reset! state/app-db {:x 1})
  (let [orig js/console.error]
    (set! js/console.error (fn [& _]))
    (try
      (cv/mount! [crashy] (div))
      (cv/mount! [steady] (div))
      (t/frame! 0) (t/frame! 16) (t/frame! 32)
      (is (= [[:crashy 1] [:steady 1] [:crashy 2] [:crashy 3]] @seen))
      (finally (set! js/console.error orig)))))
