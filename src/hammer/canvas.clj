(ns hammer.canvas
  (:require [hammer.macros :as m]))

(defmacro defdraw
  "(defdraw name [props*] [bindings*] opts? draw-fn): a Canvas 2D component
  redrawn (at the next animation frame) when a binding its opts or draw-fn name
  changes. draw-fn: (fn [ctx info]) or, with :init, (fn [ctx info res])."
  [cname props bindings & more]
  (m/draw-def &env "defdraw" :canvas false cname props bindings more))

(defmacro defloop
  "Like defdraw, but redrawn every animation frame while mounted and :run? is
  truthy; info also has :t :dt :n."
  [cname props bindings & more]
  (m/draw-def &env "defloop" :canvas true cname props bindings more))
