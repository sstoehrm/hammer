(ns hammer.canvas-test
  (:require [cljs.test :refer [deftest is use-fixtures]]
            [hammer.test-env]
            [hammer.fake-canvas :as fake]
            [hammer.core :as core :refer [defc]]
            [hammer.canvas :as cv :refer [defdraw]]
            [hammer.events :as events]
            [hammer.state :as state]
            [hammer.testing :as t]))

(use-fixtures :each {:before (fn [] (t/reset-app!) (t/use-fake-frames!) (reset! fake/log []))})

(events/reg-event ::set (fn [db k v] {:db (assoc db k v)}))

(defn- div [] (js/document.createElement "div"))
(defn- fills [id] (filterv #(= :fillRect (first %)) (fake/ops id)))

(defdraw bars [color id]
  [n [:n]]
  {:size [100 50] :attrs {:id id}}
  (fn [ctx {:keys [h]}]
    (set! (.-fillStyle ctx) color)
    (.fillRect ctx 0 0 n h)))

(deftest draws-once-per-frame-and-only-on-change
  (reset! state/app-db {:n 1})
  (cv/mount! [bars "red" "b"] (div))
  (is (empty? @fake/log) "nothing before the frame")
  (t/frame! 16)
  (is (= [[:setTransform 1 0 0 1 0 0] [:fillRect 0 0 1 50]] (fake/ops "b")))
  (reset! fake/log [])
  (events/dispatch [::set :n 5])
  (events/dispatch [::set :n 7])
  (t/frame! 32)
  (is (= [[:fillRect 0 0 7 50]] (fills "b")) "two events, one draw, latest value")
  (reset! fake/log [])
  (t/frame! 48)
  (is (empty? @fake/log) "no change, no redraw"))

(deftest backing-store-follows-size-and-dpr
  (set! (.-devicePixelRatio js/globalThis) 2)
  (try
    (reset! state/app-db {:n 1})
    (let [host (div)]
      (cv/mount! [bars "red" "b"] host)
      (t/frame! 16)
      (let [c (.-firstChild host)]
        (is (= "CANVAS" (.-tagName c)))
        (is (= [200 100] [(.-width c) (.-height c)]))
        (is (= ["100px" "50px"] [(.. c -style -width) (.. c -style -height)]))
        (is (= [:setTransform 2 0 0 2 0 0] (first (fake/ops "b"))))))
    (finally (js-delete js/globalThis "devicePixelRatio"))))

(deftest two-instances-are-independent
  (reset! state/app-db {:n 3})
  (let [a (div) b (div)]
    (cv/mount! [bars "red" "a"] a)
    (cv/mount! [bars "blue" "b2"] b)
    (t/frame! 16)
    (is (= [[:fillRect 0 0 3 50]] (fills "a") (fills "b2")))
    (is (= "red" (.-fillStyle (.getContext (.-firstChild a) "2d"))))
    (is (= "blue" (.-fillStyle (.getContext (.-firstChild b) "2d"))))))

(deftest unmount-before-frame-draws-nothing
  (reset! state/app-db {:n 1})
  (let [host (div)]
    (cv/mount! [bars "red" "u"] host)
    (t/frame! 16)
    (reset! fake/log [])
    (events/dispatch [::set :n 9])
    (t/flush!)
    (t/reset-app!)
    (t/frame! 32)
    (is (empty? @fake/log))))

(def calls (atom []))

(defdraw boom [] [n [:n]] {:size [10 10] :attrs {:id "boom"}}
  (fn [_ _] (swap! calls conj n) (when (= n 2) (throw (js/Error. "boom")))))

(deftest a-throwing-draw-is-logged-and-retried
  (reset! state/app-db {:n 1})
  (reset! calls [])
  (let [orig js/console.error errs (atom 0)]
    (set! js/console.error (fn [& _] (swap! errs inc)))
    (try
      (cv/mount! [boom] (div))
      (t/frame! 16)
      (events/dispatch [::set :n 2]) (t/frame! 32)
      (events/dispatch [::set :n 3]) (t/frame! 48)
      (is (= [1 2 3] @calls))
      (is (= 1 @errs))
      (finally (set! js/console.error orig)))))

(def res-log (atom []))

(defdraw with-res [] [n [:n]]
  {:size [10 10]
   :init (fn [_ctx {:keys [w]}] (swap! res-log conj [:init w]) {:r n})
   :dispose (fn [r] (swap! res-log conj [:dispose r]))}
  (fn [_ctx _info res] (swap! res-log conj [:draw res n])))

(deftest init-runs-once-dispose-on-remount
  (reset! state/app-db {:n 1})
  (reset! res-log [])
  (let [c (js/document.createElement "canvas")]
    (cv/mount! [with-res] c)
    (t/frame! 16)
    (events/dispatch [::set :n 2]) (t/frame! 32)
    (is (= [[:init 10] [:draw {:r 1} 1] [:draw {:r 1} 2]] @res-log))
    (reset! res-log [])
    (cv/mount! [with-res] c)                ; same canvas again (hot reload)
    (t/frame! 48)
    (is (= [[:dispose {:r 1}] [:init 10] [:draw {:r 2} 2]] @res-log))))

(defdraw dot [id] [on? (cv/is? [:sel] id)] {:size [10 10] :attrs {:id (str "d" id)}}
  (fn [ctx _] (set! (.-fillStyle ctx) (if on? "red" "gray")) (.fillRect ctx 0 0 10 10)))

(defc dots [] [ids [:ids]] [:div (for [id ids] ^{:key id} [dot id])])

(deftest embedded-in-defc-keyed-and-is?
  (reset! state/app-db {:ids [1 2 3] :sel nil})
  (let [host (div)]
    (core/mount! [dots] host)
    (t/frame! 16)
    (is (= 3 (.-length (.querySelectorAll host "canvas"))))
    (reset! fake/log [])
    (events/dispatch [::set :sel 2])
    (t/frame! 32)
    (is (= #{"d2"} (set (map second @fake/log))) "only the dot whose is? flipped redraws")
    (let [[c1 _ c3] (js/Array.from (.querySelectorAll host "canvas"))]
      (events/dispatch [::set :ids [3 1]])
      (t/frame! 48)
      (is (= [c3 c1] (vec (js/Array.from (.querySelectorAll host "canvas"))))))))
