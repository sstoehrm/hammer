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
                               (reset! fake/log []))
                     ;; unmount first so no live loop can request a real
                     ;; (setTimeout) frame, then give later namespaces the
                     ;; default requestAnimationFrame back.
                     :after (fn [] (t/reset-app!) (draw/set-raf! nil))})

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

(defloop uncapped [] [] {:size [10 10]}
  (fn [_ {:keys [dt]}] (swap! seen conj [:uncapped dt])))

(deftest default-max-dt-is-100
  (reset! state/app-db {})
  (cv/mount! [uncapped] (div))
  (t/frame! 0) (t/frame! 16) (t/frame! 2000)
  (is (= [[:uncapped 0] [:uncapped 16] [:uncapped 100]] @seen) "default :max-dt is 100"))

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
  ;; frames come only from the callbacks handed to the rAF hook (never from a
  ;; direct frame! call), so this fails if each loop requested its own frame
  ;; or if one requested frame drew only one loop.
  (reset! state/app-db {:paused? false})
  (let [cbs (atom [])
        run-frame! (fn [ts]
                     (is (= 1 (count @cbs)) "exactly one frame requested for both loops")
                     (let [f (first @cbs)] (reset! cbs []) (f ts)))]
    (draw/set-raf! (fn [f] (swap! cbs conj f)))
    (cv/mount! [ticker :a] (div))
    (cv/mount! [ticker :b] (div))
    (t/flush!)
    (run-frame! 0)
    (is (= [[:a 0 0 1] [:b 0 0 1]] @seen) "one frame draws both loops, in mount order")
    (reset! seen [])
    (run-frame! 16)
    (is (= [[:a 16 16 2] [:b 16 16 2]] @seen) "same t/dt/n for both in the shared frame")))

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

;; ---- #12: the loop clock stands still while the canvas can't be drawn

(defloop sized [] [sz [:sz]] {:size sz}
  (fn [_ {:keys [t dt n]}] (swap! seen conj [:sized t dt n])))

(deftest loop-clock-is-frozen-while-zero-size
  (reset! state/app-db {:sz [0 0]})
  (cv/mount! [sized] (div))
  (t/frame! 0) (t/frame! 16) (t/frame! 32)
  (is (empty? @seen) "0x0: nothing drawn")
  (events/dispatch [::set :sz [10 10]])
  (t/frame! 1000) (t/frame! 1016)
  (is (= [[:sized 0 0 1] [:sized 16 16 2]] @seen)
      "the first drawable frame starts the clock: t 0, dt 0, n 1")
  (reset! seen [])
  (events/dispatch [::set :sz [0 0]])
  (t/frame! 1032) (t/frame! 1048)
  (events/dispatch [::set :sz [10 10]])
  (t/frame! 2000)
  (is (= [[:sized 16 0 3]] @seen) "back from 0x0: t and n continue, dt restarts at 0"))
