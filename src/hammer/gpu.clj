(ns hammer.gpu
  (:require [hammer.macros :as m]))

(defmacro defdraw
  "(defdraw name [props*] [bindings*] opts? draw-fn): a WebGPU component
  redrawn when a binding its opts or draw-fn name changes. draw-fn:
  (fn [gpu info]) or, with :init, (fn [gpu info res]); gpu is
  {:device :queue :context :format :view}. :fallback, when given, must be
  plain hiccup with no components: it is rendered once via hammer.dom's
  host-render (DOM embedding only), never mounted or unmounted as a
  component tree."
  [cname props bindings & more]
  (m/draw-def &env "defdraw" :gpu false cname props bindings more))

(defmacro defloop
  "Like defdraw, but redrawn every animation frame while mounted and :run? is
  truthy; info also has :t :dt :n."
  [cname props bindings & more]
  (m/draw-def &env "defloop" :gpu true cname props bindings more))
