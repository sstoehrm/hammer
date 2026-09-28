(ns hammer.gl
  (:require [hammer.macros :as m]))

(defmacro defdraw
  "(defdraw name [props*] [bindings*] opts? draw-fn): a WebGL2 component redrawn
  (at the next animation frame) when a binding its opts or draw-fn name
  changes. draw-fn: (fn [gl info]) or, with :init, (fn [gl info res]); gl is
  the WebGL2RenderingContext with the viewport already set to the backing
  store. :context-attrs (e.g. {:antialias false}) is read once, at creation.
  :fallback is static plain hiccup (no components, :on-*, :ref), rendered
  when the browser has no WebGL2. Each component owns one context; browsers
  cap live contexts per page (about 16), and unmount releases it."
  [cname props bindings & more]
  (m/draw-def &env "defdraw" :gl false cname props bindings more))

(defmacro defloop
  "Like defdraw, but redrawn every animation frame while mounted and :run? is
  truthy; info also has :t :dt :n. The clock freezes while the canvas can't
  draw (0×0 size, context lost). :fallback and the context rules as defdraw."
  [cname props bindings & more]
  (m/draw-def &env "defloop" :gl true cname props bindings more))
