(ns hammer.gpu-test
  (:require [cljs.test :refer [deftest is async use-fixtures]]
            [hammer.test-env]
            [hammer.fake-gpu :as fg]
            ;; side effect only: fake-gpu's poisoned-canvas check relies on
            ;; fake-canvas's __fake2d flag, and must not depend on some other
            ;; namespace happening to load it into the bundle first.
            [hammer.fake-canvas]
            [hammer.core :as core :refer [defc]]
            [hammer.gpu :as gpu :refer [defdraw]]
            [hammer.state :as state]
            [hammer.testing :as t]))

(use-fixtures :each {:before (fn [] (t/reset-app!) (t/use-fake-frames!) (gpu/reset-device!))
                     :after (fn [] (t/reset-app!) (fg/restore!))})

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
          (is (= [[:dispose] [:unconfigure] [:device] [:configure "bgra8unorm"] [:pass "clear" 1] [:draw 1] [:end] [:submit 1]]
                 @fg/log)
              "the lost device's context is unconfigured before the new device configures it")
          (is (= 2 @inits))
          (done)))))))

;; ---- fix round 1: one component's configure! failure must not break the page

(deftest one-components-configure-failure-does-not-break-others
  (async done
    (fg/install! :ok)
    (reset! state/app-db {:n 5})
    (reset! inits 0)
    (reset! unsupported [])
    (let [bad (js/document.createElement "canvas")
          good (js/document.createElement "canvas")]
      ;; a canvas that already has a 2d context can never get a webgpu one:
      ;; configure! will call .configure on a nil context and throw.
      (.getContext bad "2d")
      (let [orig js/console.error errs (atom 0)]
        (set! js/console.error (fn [& _] (swap! errs inc)))
        (gpu/mount! [tri] bad)
        (gpu/mount! [tri] good)
        (fg/settle
         (fn []
           (t/frame! 16)
           (set! js/console.error orig)
           (is (= 1 @errs) "the bad canvas's configure! failure is logged once")
           (is (= [[:device] [:configure "bgra8unorm"] [:pass "clear" 1] [:draw 5] [:end] [:submit 1]] @fg/log)
               "the good canvas still configures and draws")
           (is (= 1 @inits) "only the good component initialized")
           (is (empty? @unsupported) "status stayed :ready -- on-unsupported was not called")
           (done)))))))

;; ---- fix round 1: a throwing fallback callback must not block other waiters

(def fb-log (atom []))

(defdraw fb-a [] [] {:size [10 10] :on-unsupported (fn [_] (throw (js/Error. "boom")))}
  (fn [_g _]))

(defdraw fb-b [] [] {:size [10 10] :on-unsupported (fn [r] (swap! fb-log conj r))}
  (fn [_g _]))

(deftest one-throwing-on-unsupported-does-not-block-others-or-relog
  (async done
    (fg/install! :no-adapter)
    (reset! fb-log [])
    (let [logs (atom [])
          orig js/console.error]
      (set! js/console.error (fn [& args] (swap! logs conj (vec args))))
      ;; both mount while the device is still :pending, so both land in the
      ;; same `waiting` set and get fallback! called from the same forEach.
      (gpu/mount! [fb-a] (js/document.createElement "canvas"))
      (gpu/mount! [fb-b] (js/document.createElement "canvas"))
      (fg/settle
       (fn []
         (set! js/console.error orig)
         (is (= 1 (count @fb-log)) "the second component's on-unsupported still ran despite the first throwing")
         (is (= 1 (count (filter #(= "hammer: WebGPU unavailable:" (first %)) @logs)))
             "the page-level unavailable message is logged exactly once")
         (done))))))

;; ---- #9: fake-gpu install!/restore! leave no trace

(deftest fake-gpu-install-restore-leaves-no-trace
  (fg/restore!)
  (let [proto (.. js/window -HTMLCanvasElement -prototype)
        orig (.-getContext proto)
        had-gpu? (.hasOwnProperty js/navigator "gpu")]
    (fg/install! :ok)
    (fg/install! :no-adapter)
    (fg/install! :ok)
    (is (not (identical? orig (.-getContext proto))) "installed")
    (fg/restore!)
    (is (identical? orig (.-getContext proto)) "getContext is the pre-install fn, not a wrapper chain")
    (is (= had-gpu? (.hasOwnProperty js/navigator "gpu")) "navigator.gpu back to its original state")))

;; ---- #5: a device request that is pending across reset-device! is stale

(def dev-ids (atom []))

(defdraw dev-probe [] [] {:size [10 10] :on-unsupported (fn [r] (swap! unsupported conj r))}
  (fn [g _] (swap! dev-ids conj (.-id ^js (:device g)))))

(defn- stale-request-then-fresh-device!
  "Mounts on a :deferred gpu (request pending), resets the device, mounts on
  a fresh :ok gpu, and calls (then stale) once that device is ready, where
  stale is the old pending request's {:resolve :reject}."
  [then]
  (fg/install! :deferred)
  (gpu/mount! [dev-probe] (js/document.createElement "canvas"))
  (let [stale @fg/adapter-request]
    (t/reset-app!)
    (gpu/reset-device!)
    (fg/install! :ok)
    (gpu/mount! [dev-probe] (js/document.createElement "canvas"))
    (fg/settle #(then stale))))

(deftest stale-adapter-result-does-not-replace-the-device
  (async done
    (reset! dev-ids [])
    (stale-request-then-fresh-device!
     (fn [stale]
       ((:resolve stale))
       (fg/settle
        (fn []
          (t/frame! 16)
          (is (= [1] @dev-ids) "draws on the current device (id 1), not the stale request's")
          (is (= 1 (count (filter #(= [:device] %) @fg/log))) "the stale adapter never requests a device")
          (done)))))))

(deftest stale-adapter-rejection-does-not-mark-unsupported
  (async done
    (reset! dev-ids [])
    (reset! unsupported [])
    (let [orig js/console.error errs (atom 0)]
      (set! js/console.error (fn [& _] (swap! errs inc)))
      (stale-request-then-fresh-device!
       (fn [stale]
         ((:reject stale) (js/Error. "stale"))
         (fg/settle
          (fn []
            (set! js/console.error orig)
            (t/frame! 16)
            (is (empty? @unsupported) "the stale rejection does not run the unsupported path")
            (is (zero? @errs))
            (is (= [1] @dev-ids) "the current device keeps drawing")
            (done))))))))

;; ---- #6: a throw after the device resolves is routed to unsupported!, not
;; left as an unhandled promise rejection

(defn- no-unhandled-rejection-test [mode done]
  (fg/install! mode)
  (reset! unsupported [])
  (let [rejections (atom [])
        on-rej (fn [r _] (swap! rejections conj r))
        orig js/console.error
        logs (atom [])]
    (.on js/process "unhandledRejection" on-rej)
    (set! js/console.error (fn [& args] (swap! logs conj (first args))))
    (gpu/mount! [tri] (js/document.createElement "canvas"))
    (js/setTimeout
     (fn []
       (.off js/process "unhandledRejection" on-rej)
       (set! js/console.error orig)
       (is (empty? @rejections) "no unhandled promise rejection")
       (is (= 1 (count @unsupported)) "the waiting component gets :on-unsupported")
       (is (= ["hammer: WebGPU unavailable:"] @logs) "logged once, as unavailable")
       (done))
     10)))

(deftest throwing-preferred-canvas-format-goes-unsupported
  (async done (no-unhandled-rejection-test :bad-format done)))

(deftest throw-inside-ready-goes-unsupported
  (async done (no-unhandled-rejection-test :bad-device done)))

;; ---- #8: untested lifecycle paths

(deftest unmount-while-device-pending-never-configures
  (async done
    (fg/install! :deferred)
    (reset! inits 0)
    (reset! state/app-db {:n 1})
    (gpu/mount! [tri] (js/document.createElement "canvas"))
    (t/reset-app!)
    ((:resolve @fg/adapter-request))
    (fg/settle
     (fn []
       (t/frame! 16)
       (is (= [[:device]] @fg/log) "the device arrives, but the unmounted component is no longer waiting")
       (is (zero? @inits))
       (done)))))

(defn- ready-then [f]
  (fg/install! :ok)
  (reset! state/app-db {:n 1})
  (reset! inits 0)
  (gpu/mount! [tri] (js/document.createElement "canvas"))
  (fg/settle (fn [] (t/frame! 16) (reset! fg/log []) (f))))

(deftest lost-with-reason-destroyed-is-ignored
  (async done
    (ready-then
     (fn []
       (@fg/lose! "destroyed")
       (fg/settle
        (fn []
          (t/frame! 32)
          (is (= [] @fg/log) "no dispose, no new device request, nothing to redraw")
          (is (= 1 @inits))
          (done)))))))

(deftest lost-from-a-replaced-device-is-ignored
  (async done
    (ready-then
     (fn []
       (let [lose-old @fg/lose!]
         (gpu/reset-device!)
         (t/reset-app!)
         (ready-then
          (fn []
            (lose-old)
            (fg/settle
             (fn []
               (t/frame! 32)
               (is (= [] @fg/log) "the old device's loss does not dispose or re-acquire")
               (is (= 1 @inits))
               (done))))))))))

(deftest request-device-rejecting-is-unsupported
  (async done
    (fg/install! :device-rejects)
    (reset! unsupported [])
    (let [orig js/console.error logs (atom [])]
      (set! js/console.error (fn [& args] (swap! logs conj (vec args))))
      (gpu/mount! [tri] (js/document.createElement "canvas"))
      (fg/settle
       (fn []
         (set! js/console.error orig)
         (is (= 1 (count @unsupported)))
         (is (= ["hammer: WebGPU unavailable:" "Error: device refused"] (first @logs)))
         (done))))))

(defdraw no-clear [] [] {:size [10 10]}
  (fn [g _] (gpu/pass g {} (fn [_]))))

(deftest pass-without-clear-clears-to-transparent-black
  (async done
    (fg/install! :ok)
    (gpu/mount! [no-clear] (js/document.createElement "canvas"))
    (fg/settle
     (fn []
       (t/frame! 16)
       (is (= [[0 0 0 0]] @fg/clears))
       (done)))))

(def many-unsupported (atom 0))

(defdraw quiet [] [] {:size [10 10] :on-unsupported (fn [_] (swap! many-unsupported inc))}
  (fn [_ _]))

(deftest unavailable-is-logged-once-for-many-components
  (async done
    (reset! many-unsupported 0)
    (let [orig js/console.error logs (atom [])]
      (set! js/console.error (fn [& args] (swap! logs conj (first args))))
      (fg/install! :no-adapter)
      ;; two mount while the request is pending, one after it failed
      (gpu/mount! [quiet] (js/document.createElement "canvas"))
      (gpu/mount! [quiet] (js/document.createElement "canvas"))
      (fg/settle
       (fn []
         (gpu/mount! [quiet] (js/document.createElement "canvas"))
         (set! js/console.error orig)
         (is (= ["hammer: WebGPU unavailable:"] @logs) "one log for the page")
         (is (= 3 @many-unsupported) "every component is told")
         (done))))))
