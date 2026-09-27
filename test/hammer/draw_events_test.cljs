(ns hammer.draw-events-test
  (:require [cljs.test :refer [deftest is use-fixtures]]
            [hammer.test-env]
            [hammer.fake-canvas]
            [hammer.canvas :as cv :refer [defdraw]]
            [hammer.events :as events]
            [hammer.state :as state]
            [hammer.testing :as t]))

(use-fixtures :each {:before (fn [] (t/reset-app!) (t/use-fake-frames!))})

(def got (atom []))
(events/reg-event ::pick (fn [db x y] {:db (assoc db :picked [x y])}))
(events/reg-event ::set (fn [db k v] {:db (assoc db k v)}))

(defdraw clicky [] [fn? [:fn?]]
  {:size [100 100]
   :on-click (if fn? (fn [_e xy] (swap! got conj xy)) [::pick])}
  (fn [_ _]))

(defn- click! [^js c x y]
  (.dispatchEvent c (new (.-MouseEvent js/window) "click" #js {:clientX x :clientY y :bubbles true})))

(deftest vector-handler-dispatches-local-xy
  (reset! state/app-db {:fn? false})
  (let [c (js/document.createElement "canvas")]
    (cv/mount! [clicky] c)
    (click! c 12 34)
    (t/flush!)
    (is (= [12 34] (:picked @state/app-db)))))

(deftest fn-handler-gets-event-and-xy-and-updates-live
  (reset! state/app-db {:fn? false})
  (reset! got [])
  (let [c (js/document.createElement "canvas")]
    (cv/mount! [clicky] c)
    (events/dispatch [::set :fn? true])
    (t/frame! 16)
    (click! c 5 6)
    (is (= [{:x 5 :y 6}] @got))))

(deftest remount-does-not-duplicate-listeners
  (reset! state/app-db {:fn? true})
  (reset! got [])
  (let [c (js/document.createElement "canvas")]
    (cv/mount! [clicky] c)
    (cv/mount! [clicky] c)
    (click! c 1 1)
    (is (= 1 (count @got)))))
