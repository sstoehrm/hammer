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
  "Like defdraw, but redrawn every animation frame while mounted and :run? is
  truthy; info also has :t :dt :n. :fallback, if given, is the same static
  hiccup-only option defdraw takes (see defdraw)."
  [cname props bindings & more]
  (m/draw-def &env "defloop" :gpu true cname props bindings more))
