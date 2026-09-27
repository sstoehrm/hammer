(ns hammer.gpu
  (:require [hammer.macros :as m]))

(defmacro defdraw
  "(defdraw name [props*] [bindings*] opts? draw-fn): a WebGPU component
  redrawn when a binding its opts or draw-fn name changes. draw-fn:
  (fn [gpu info]) or, with :init, (fn [gpu info res]); gpu is
  {:device :queue :context :format :view}. :fallback, when given, is static:
  plain hiccup only, no components, no :on-* handlers, no :ref. It is
  rendered once via hammer.dom's host-render (DOM embedding only), never
  mounted or unmounted as a component tree, so nothing in it is reactive."
  [cname props bindings & more]
  (m/draw-def &env "defdraw" :gpu false cname props bindings more))

(defmacro defloop
  "(defloop name [props*] [bindings*] opts? draw-fn): like defdraw (same
  props, bindings, opts and draw-fn), but also redrawn every animation frame
  while mounted and :run? (default true) is truthy. info also has :t (ms of
  running time; frozen while paused), :dt (ms since the previous frame,
  capped at :max-dt, default 100; 0 for a redraw while paused) and :n (frame
  count). The clock also freezes (no change to :t/:n, next drawn frame gets
  :dt 0) on any frame the canvas can't draw, e.g. zero size or the WebGPU
  device still pending. :fallback, when given, is static: plain hiccup only,
  no components, no :on-* handlers, no :ref. It is rendered once via
  hammer.dom's host-render (DOM embedding only), never mounted or unmounted
  as a component tree, so nothing in it is reactive."
  [cname props bindings & more]
  (m/draw-def &env "defloop" :gpu true cname props bindings more))
