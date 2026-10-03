(ns hammer.canvas
  "Canvas 2D facade: the event API, defdraw/defloop and mount!."
  (:require-macros [hammer.canvas])
  (:require [hammer.app :as app]
            [hammer.draw :as draw]
            [hammer.log :as log]))

(def reg-event app/reg-event)
(def reg-fx app/reg-fx)
(def dispatch app/dispatch)
(def dispatch-sync app/dispatch-sync)
(def on-error! app/on-error!)
(def is? app/is?)
(def mount! draw/mount!)

(draw/register-backend!
 :canvas
 (draw/Backend.
  (fn [^draw/State st _render]
    (let [ctx (.getContext ^js (.-canvas st) "2d")]
      (when-not ctx
        ;; nil here means the canvas was already put into another mode (e.g.
        ;; "webgl2"); a 2d context can never be obtained from it afterwards.
        (log/report! :error "hammer: 2d context unavailable (canvas already has another context type, e.g. WebGL2?)" nil))
      (set! (.-ctx st) ctx))
    (.-canvas st))
  (fn [^draw/State st]
    (when-let [^js ctx (.-ctx st)]
      (let [d (.-dpr st)] (.setTransform ctx d 0 0 d 0 0))
      ctx))
  (fn [_] nil)
  (fn [_] nil)))
