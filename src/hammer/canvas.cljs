(ns hammer.canvas
  "Canvas 2D facade: the event API, defdraw/defloop and mount!."
  (:require-macros [hammer.canvas])
  (:require [hammer.app :as app]
            [hammer.draw :as draw]))

(def reg-event app/reg-event)
(def reg-fx app/reg-fx)
(def dispatch app/dispatch)
(def dispatch-sync app/dispatch-sync)
(def is? app/is?)
(def mount! draw/mount!)

(draw/register-backend!
 :canvas
 (draw/Backend.
  (fn [^draw/State st _render]
    (set! (.-ctx st) (.getContext ^js (.-canvas st) "2d"))
    (.-canvas st))
  (fn [^draw/State st]
    (when-let [^js ctx (.-ctx st)]
      (let [d (.-dpr st)] (.setTransform ctx d 0 0 d 0 0))
      ctx))
  (fn [_] nil)
  (fn [_] nil)))
