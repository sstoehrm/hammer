(ns hammer.gl-test
  (:require [cljs.test :refer [deftest is use-fixtures]]
            [hammer.test-env]
            [hammer.fake-gl :as fgl]
            ;; side effect only: fake-gl's poisoned-canvas check relies on
            ;; fake-canvas's __fake2d flag, and must not depend on some other
            ;; namespace happening to load it into the bundle first.
            [hammer.fake-canvas]
            [hammer.test-util :refer [capture-errors capture-warnings]]
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
    (is (= [[:unsupported "no WebGL2 context (canvas already has another context type?)"] [:unsupported "no WebGL2 context (canvas already has another context type?)"]] @res-log))))

(def fb-log (atom []))

(defdraw fb-a [] [] {:size [10 10] :on-unsupported (fn [_] (throw (js/Error. "boom")))}
  (fn [_g _]))

(defdraw fb-b [] [] {:size [10 10] :on-unsupported (fn [r] (swap! fb-log conj r))}
  (fn [_g _]))

(deftest one-throwing-on-unsupported-does-not-block-the-other-or-relog
  (fgl/install! :none)
  (reset! fb-log [])
  (let [logs (capture-errors
              (fn [_]
                (gl/mount! [fb-a] (js/document.createElement "canvas"))
                (gl/mount! [fb-b] (js/document.createElement "canvas"))))]
    (is (= 1 (count (filter #(= "hammer: WebGL2 unavailable:" (first %)) logs)))
        "the page-level unavailable message is logged exactly once")
    (is (= 1 (count @fb-log)) "the second component's on-unsupported still ran despite the first throwing")))

(deftest a-canvas-with-a-2d-context-falls-back-while-another-component-draws
  (reset! state/app-db {:n 5})
  (reset! res-log [])
  (let [bad (js/document.createElement "canvas")
        host (js/document.createElement "div")]
    ;; a canvas that already has a 2d context can never get a webgl2 one:
    ;; getContext "webgl2" returns null, like a real browser (fake-gl's
    ;; __fake2d check). bad is mounted standalone (the poisoned canvas has to
    ;; be adopted -- a DOM-embedded component always gets a fresh canvas from
    ;; hammer, which can never already hold a context); the second, normal
    ;; component is DOM-embedded in the same page, via `two` (two tri
    ;; instances), exercising the wrap-span path core/mount! uses.
    (.getContext bad "2d")
    (let [logs (capture-errors
                (fn [_]
                  (gl/mount! [tri "bad"] bad)
                  (core/mount! [two] host)
                  (t/frame! 16)))]
      (is (= 1 (count (filter #(= "hammer: WebGL2 unavailable:" (first %)) logs)))
          "the bad canvas's missing context is logged once")
      (is (= [[:viewport 0 0 100 50] [:clearColor 0 0 0 1] [:drawArrays 4 0 5]] (ops "x"))
          "the other DOM-embedded component still draws")
      (is (= [[:viewport 0 0 100 50] [:clearColor 0 0 0 1] [:drawArrays 4 0 5]] (ops "y"))
          "...and so does its sibling")
      (is (= [[:unsupported "no WebGL2 context (canvas already has another context type?)"] [:init] [:draw :prog 5] [:init] [:draw :prog 5]]
             @res-log)))))

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

(deftest webglcontextlost-warns-once-naming-the-component
  (reset! state/app-db {:n 1})
  (let [c (js/document.createElement "canvas")]
    (gl/mount! [tri "w"] c)
    (t/frame! 16)
    (let [logs (capture-warnings (fn [_] (fire! c "webglcontextlost")))]
      (is (= 1 (count logs)) "exactly one console.warn for the loss")
      (is (some #(= "tri" %) (first logs)) "names the lost component"))))

(deftest loss-then-unmount-disposes-once
  (reset! state/app-db {:n 1})
  (reset! res-log [])
  (let [host (js/document.createElement "div")]
    (gl/mount! [tri "lu"] host)
    (t/frame! 16)
    (reset! res-log [])
    (let [^js c (.querySelector host "canvas")]
      (fire! c "webglcontextlost")
      (is (= [[:dispose :prog]] @res-log) "loss disposes")
      (t/reset-app!)
      (is (= [[:dispose :prog]] @res-log) "unmount after loss does not dispose a second time"))))

(deftest dispose-runs-on-a-plain-gl-unmount
  (reset! state/app-db {:n 1})
  (reset! res-log [])
  (let [host (js/document.createElement "div")]
    (gl/mount! [tri "p"] host)
    (t/frame! 16)
    (reset! res-log [])
    (t/reset-app!)
    (is (= [[:dispose :prog]] @res-log) "unmount (no loss) still runs :dispose")))

(deftest unmount-releases-a-hammer-created-canvas
  (reset! state/app-db {:n 1})
  (let [host (js/document.createElement "div")]
    (gl/mount! [tri "u"] host)
    (t/frame! 16)
    (let [^js c (.querySelector host "canvas")]
      (t/reset-app!)
      (is (some #(= [:loseContext "u"] %) @fgl/log))
      (is (not (.-defaultPrevented (fire! c "webglcontextlost"))) "listeners removed"))))

(deftest remount-on-an-adopted-canvas-keeps-drawing
  (reset! state/app-db {:n 1})
  (reset! res-log [])
  (let [c (js/document.createElement "canvas")]
    (gl/mount! [tri "r"] c)
    (t/frame! 16)
    (t/reset-app!)
    (reset! state/app-db {:n 1})
    (reset! res-log [])
    (gl/mount! [tri "r"] c)
    (t/frame! 32)
    (is (not-any? #(= [:loseContext "r"] %) @fgl/log) "an adopted canvas's context is not released on remount")
    (is (= [[:init] [:draw :prog 1]] @res-log) "the remounted instance still draws")))

(deftest setup-on-an-already-lost-context-waits-for-restore
  (reset! state/app-db {:n 1})
  (reset! res-log [])
  (let [c (js/document.createElement "canvas")]
    (gl/mount! [tri "lost"] c)
    (t/frame! 16)
    ;; the underlying context is lost directly (as a real driver loss would
    ;; leave it), without going through webglcontextlost -- simulating a loss
    ;; that happened, or finished, before a later mount's listeners exist.
    (.loseContext (.getExtension (.getContext c "webgl2") "WEBGL_lose_context"))
    (t/reset-app!)
    (reset! state/app-db {:n 1})
    (reset! res-log [])
    (gl/mount! [tri "lost"] c)
    (t/frame! 32)
    (is (= [] @res-log) "an already-lost context at setup: no init, no draw")
    (fire! c "webglcontextrestored")
    (t/frame! 48)
    (is (= [[:init] [:draw :prog 1]] @res-log))))

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
