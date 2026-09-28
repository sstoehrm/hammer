(ns hammer.canvas
  (:require [hammer.macros :as m]))

(defmacro defdraw
  "(defdraw name [props*] [bindings*] opts? draw-fn): a Canvas 2D component
  redrawn (at the next animation frame) when a binding its opts or draw-fn name
  changes. draw-fn: (fn [ctx info]) or, with :init, (fn [ctx info res])."
  [cname props bindings & more]
  (m/draw-def &env "defdraw" :canvas false cname props bindings more))

(defmacro defloop
  "(defloop name [props*] [bindings*] opts? draw-fn): like defdraw (same
  props, bindings, opts and draw-fn), but also redrawn every animation frame
  while mounted and :run? (default true) is truthy. info also has :t (ms of
  running time; frozen while paused), :dt (ms since the previous frame,
  capped at :max-dt, default 100; 0 for a redraw while paused) and :n (frame
  count). The clock also freezes (no change to :t/:n, next drawn frame gets
  :dt 0) on any frame the canvas can't draw, e.g. zero size. Canvas 2D has no
  :fallback (that option is hammer.gl only)."
  [cname props bindings & more]
  (m/draw-def &env "defloop" :canvas true cname props bindings more))
