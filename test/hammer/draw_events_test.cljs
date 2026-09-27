(ns hammer.draw-events-test
  (:require [cljs.test :refer [deftest is use-fixtures]]
            [hammer.test-env]
            [hammer.fake-canvas]
            [hammer.canvas :as cv :refer [defdraw]]
            [hammer.draw :as draw]
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

(def steady-draws (atom []))

(defdraw steady [] [n [:n] cls [:cls]]
  {:size [10 10] :attrs {:class cls} :on-click [::pick]}
  (fn [_ _] (swap! steady-draws conj n)))

(deftest rerender-with-equal-opts-keeps-listeners-and-attrs
  (reset! state/app-db {:n 0 :cls "a"})
  (reset! steady-draws [])
  (let [c (js/document.createElement "canvas")]
    (cv/mount! [steady] c)
    (t/frame! 16)
    (events/dispatch [::set :n 1]) ; opts = the previous ones
    (t/frame! 32)
    (is (= [0 1] @steady-draws))
    (is (= "a" (.-className c)))
    (is (= "10px" (.. c -style -width)))
    (click! c 3 4)
    (t/flush!)
    (is (= [3 4] (:picked @state/app-db)) "listener still registered")
    (events/dispatch [::set :cls "b"]) ; opts changed: applied
    (t/frame! 48)
    (is (= "b" (.-className c)))))

(defdraw toggled [] [on? [:on?]]
  {:size [10 10] :on-click (when on? [::pick])}
  (fn [_ _]))

(deftest nil-on-handler-ignores-events-until-set-again
  (reset! state/app-db {:on? true})
  (let [c (js/document.createElement "canvas")]
    (cv/mount! [toggled] c)
    (click! c 1 2)
    (t/flush!)
    (is (= [1 2] (:picked @state/app-db)))
    (events/dispatch [::set :on? false])
    (t/frame! 16)
    (click! c 7 8)
    (t/flush!)
    (is (= [1 2] (:picked @state/app-db)) "nil handler: nothing dispatched")
    (events/dispatch [::set :on? true])
    (t/frame! 32)
    (click! c 9 9)
    (t/flush!)
    (is (= [9 9] (:picked @state/app-db)))
    (is (= 1 (.-size (.-listeners ^js (first (draw/states :canvas))))) "one listener throughout")))
