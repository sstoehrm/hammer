(ns hammer.draw-size-test
  (:require [cljs.test :refer [deftest is use-fixtures]]
            [hammer.test-env]
            [hammer.fake-canvas]
            [hammer.canvas :as cv :refer [defdraw]]
            [hammer.state :as state]
            [hammer.testing :as t]))

(def observers (atom []))

(defn- fake-ro [cb]
  (this-as ^js o
    (set! (.-cb o) cb)
    (set! (.-observe o) (fn [el] (swap! observers conj o) (set! (.-el o) el)))
    (set! (.-disconnect o) (fn [] (set! (.-gone o) true)))
    o))

(defn- resize! [w h]
  (doseq [^js o @observers]
    ((.-cb o) #js [#js {:contentRect #js {:width w :height h}}])))

(use-fixtures :each {:before (fn []
                               (t/reset-app!) (t/use-fake-frames!) (reset! observers [])
                               (set! (.-ResizeObserver js/globalThis) fake-ro))
                     :after (fn [] (js-delete js/globalThis "ResizeObserver"))})

(def sizes (atom []))

(defdraw auto [] [] (fn [_ {:keys [w h]}] (swap! sizes conj [w h])))

(deftest zero-size-skips-drawing-until-positive
  (reset! sizes [])
  (let [host (js/document.createElement "div")]
    (cv/mount! [auto] host)
    (t/frame! 16)
    (is (empty? @sizes) "0×0: no draw")
    (resize! 120 80)
    (t/frame! 32)
    (is (= [[120 80]] @sizes))
    (let [c (.-firstChild host)]
      (is (= [120 80] [(.-width c) (.-height c)]))
      (is (= "100%" (.. c -style -width))))))

(deftest unmount-disconnects-the-observer
  (let [host (js/document.createElement "div")]
    (cv/mount! [auto] host)
    (t/reset-app!)
    (is (true? (.-gone ^js (first @observers))))))

(deftest window-resize-redraws
  (reset! sizes [])
  (cv/mount! [auto] (js/document.createElement "div"))
  (resize! 10 10)
  (t/frame! 16)
  (reset! sizes [])
  (.dispatchEvent js/window (new (.-Event js/window) "resize"))
  (t/frame! 32)
  (is (= [[10 10]] @sizes)))
