(ns hammer.gl-test
  (:require [cljs.test :refer [deftest is use-fixtures]]
            [hammer.test-env]
            [hammer.fake-gl :as fgl]
            [hammer.test-util :refer [capture-errors]]
            [hammer.core :as core :refer [defc]]
            [hammer.gl :as gl :refer [defdraw defloop]]
            [hammer.draw :as draw]
            [hammer.events :as events]
            [hammer.state :as state]
            [hammer.testing :as t]))

(use-fixtures :each {:before (fn [] (t/reset-app!) (t/use-fake-frames!) (gl/reset-log!) (fgl/install! :ok))
                     :after (fn [] (t/reset-app!) (draw/set-raf! nil) (fgl/restore!))})

(events/reg-event ::set (fn [db k v] {:db (assoc db k v)}))

(def res-log (atom []))

(defdraw tri [id] [n [:n]]
  {:size [100 50] :attrs {:id id}
   :context-attrs {:antialias false :alpha false}
   :init (fn [_gl _] (swap! res-log conj [:init]) :prog)
   :dispose (fn [r] (swap! res-log conj [:dispose r]))
   :fallback [:p "needs WebGL2"]
   :on-unsupported (fn [r] (swap! res-log conj [:unsupported r]))}
  (fn [g _ res] (.clearColor g 0 0 0 1) (.drawArrays g 4 0 n) (swap! res-log conj [:draw res n])))

(defn- ops [id] (into [] (comp (filter #(= id (second %))) (map #(into [(first %)] (drop 2 %)))) @fgl/log))

(deftest draws-with-viewport-and-context-attrs
  (set! (.-devicePixelRatio js/globalThis) 2)
  (try
    (reset! state/app-db {:n 3})
    (reset! res-log [])
    (let [c (js/document.createElement "canvas")]
      (gl/mount! [tri "a"] c)
      (t/frame! 16)
      (is (= [[:viewport 0 0 200 100] [:clearColor 0 0 0 1] [:drawArrays 4 0 3]] (ops "a")))
      (is (= {:antialias false :alpha false} (js->clj (.-attrs (.getContext c "webgl2")) :keywordize-keys true)))
      (is (= [[:init] [:draw :prog 3]] @res-log)))
    (finally (js-delete js/globalThis "devicePixelRatio"))))

(defc two [] [] [:div [tri "x"] [tri "y"]])

(deftest unsupported-renders-fallback-and-logs-once
  (fgl/install! :none)
  (reset! res-log [])
  (let [host (js/document.createElement "div")
        logs (capture-errors (fn [_] (core/mount! [two] host)))]
    (is (= 1 (count (filter #(= "hammer: WebGL2 unavailable:" (first %)) logs))))
    (is (= 2 (.-length (.querySelectorAll host "p"))))
    (is (= 0 (.-length (.querySelectorAll host "canvas"))))
    (is (= [[:unsupported "no WebGL2 context"] [:unsupported "no WebGL2 context"]] @res-log))))

(defn- fire! [^js c type]
  (let [e (new (.-Event js/window) type #js {:cancelable true})]
    (.dispatchEvent c e)
    e))

(deftest context-loss-disposes-and-restore-reinits
  (reset! state/app-db {:n 1})
  (reset! res-log [])
  (let [c (js/document.createElement "canvas")]
    (gl/mount! [tri "l"] c)
    (t/frame! 16)
    (reset! res-log [])
    (is (.-defaultPrevented (fire! c "webglcontextlost")))
    (is (= [[:dispose :prog]] @res-log))
    (events/dispatch [::set :n 2])
    (t/frame! 32)
    (is (= [[:dispose :prog]] @res-log) "lost: no draw")
    (fire! c "webglcontextrestored")
    (t/frame! 48)
    (is (= [[:dispose :prog] [:init] [:draw :prog 2]] @res-log))))

(deftest unmount-releases-the-context
  (reset! state/app-db {:n 1})
  (let [c (js/document.createElement "canvas")]
    (gl/mount! [tri "u"] c)
    (t/frame! 16)
    (t/reset-app!)
    (is (some #(= [:loseContext "u"] %) @fgl/log))
    (reset! res-log [])
    (fire! c "webglcontextlost")
    (is (empty? @res-log) "listeners removed")))

(def frames (atom []))

(defloop spin [] [] {:size [10 10] :attrs {:id "s"}}
  (fn [_ {:keys [t dt n]}] (swap! frames conj [t dt n])))

(deftest loop-clock-freezes-while-context-is-lost
  (reset! frames [])
  (let [c (js/document.createElement "canvas")]
    (gl/mount! [spin] c)
    (t/frame! 0) (t/frame! 16)
    (fire! c "webglcontextlost")
    (t/frame! 32) (t/frame! 48)
    (fire! c "webglcontextrestored")
    (t/frame! 500) (t/frame! 516)
    (is (= [[0 0 1] [16 16 2] [16 0 3] [32 16 4]] @frames))))
