(ns hammer.gpu-test
  (:require [cljs.test :refer [deftest is async use-fixtures]]
            [hammer.test-env]
            [hammer.fake-gpu :as fg]
            [hammer.core :as core :refer [defc]]
            [hammer.gpu :as gpu :refer [defdraw]]
            [hammer.state :as state]
            [hammer.testing :as t]))

(use-fixtures :each {:before (fn [] (t/reset-app!) (t/use-fake-frames!) (gpu/reset-device!))})

(def inits (atom 0))
(def unsupported (atom []))

(defdraw tri [] [n [:n]]
  {:size [10 10]
   :init (fn [_g _] (swap! inits inc) :pipe)
   :dispose (fn [_] (swap! fg/log conj [:dispose]))
   :fallback [:p "needs WebGPU"]
   :on-unsupported (fn [r] (swap! unsupported conj r))}
  (fn [g _ res]
    (gpu/pass g {:clear [0 0 0 1]} (fn [p] (.draw p n)))))

(defc page [] [] [:div [tri]])

(deftest draws-after-device-is-ready
  (async done
    (fg/install! :ok)
    (reset! state/app-db {:n 3})
    (reset! inits 0)
    (let [c (js/document.createElement "canvas")]
      (gpu/mount! [tri] c)
      (t/frame! 16)
      (is (not-any? #(= :pass (first %)) @fg/log) "no device yet: no draw")
      (fg/settle
       (fn []
         (t/frame! 32)
         (is (= [[:device] [:configure "bgra8unorm"] [:pass "clear" 1] [:draw 3] [:end] [:submit 1]] @fg/log))
         (is (= 1 @inits))
         (done))))))

(deftest unsupported-renders-fallback-and-reports
  (async done
    (fg/install! :missing)
    (reset! unsupported [])
    (let [orig js/console.error]
      (set! js/console.error (fn [& _]))
      (let [host (js/document.createElement "div")]
        (core/mount! [page] host)
        (fg/settle
         (fn []
           (set! js/console.error orig)
           (is (= "<div><span style=\"display: contents;\"><p>needs WebGPU</p></span></div>" (.-innerHTML host)))
           (is (= 1 (count @unsupported)))
           (done)))))))

(deftest no-adapter-is-unsupported-too
  (async done
    (fg/install! :no-adapter)
    (reset! unsupported [])
    (let [orig js/console.error]
      (set! js/console.error (fn [& _]))
      (gpu/mount! [tri] (js/document.createElement "canvas"))
      (fg/settle (fn [] (set! js/console.error orig) (is (= 1 (count @unsupported))) (done))))))

(deftest device-loss-disposes-reinits-and-redraws
  (async done
    (fg/install! :ok)
    (reset! state/app-db {:n 1})
    (reset! inits 0)
    (gpu/mount! [tri] (js/document.createElement "canvas"))
    (fg/settle
     (fn []
       (t/frame! 16)
       (reset! fg/log [])
       (@fg/lose!)
       (fg/settle
        (fn []
          (t/frame! 32)
          (is (= [[:dispose] [:device] [:configure "bgra8unorm"] [:pass "clear" 1] [:draw 1] [:end] [:submit 1]]
                 @fg/log))
          (is (= 2 @inits))
          (done)))))))
