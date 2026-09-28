(ns hammer.fake-gl
  "jsdom has no WebGL: getContext(\"webgl2\", attrs) returns a recording fake
  (mode :ok) or null (mode :none), cached per canvas like a real browser (a
  later getContext call, e.g. on an adopted/remounted canvas, returns the same
  object). isContextLost() reflects loseContext(); firing webglcontextrestored
  on the canvas clears it, like a real recovered context. restore! puts the
  original getContext back."
  (:require [hammer.test-env]))

(defonce log (atom []))
(defonce ^:private saved (atom nil))

(def ^:private ops ["viewport" "clearColor" "clear" "drawArrays" "useProgram"])

(defn- fake [^js c attrs]
  (let [o #js {:canvas c :attrs attrs :__lost false}]
    (doseq [m ops]
      (aset o m (fn [& args] (swap! log conj (into [(keyword m) (.-id c)] args)))))
    (js/Object.defineProperty o "drawingBufferWidth" #js {:get (fn [] (.-width c))})
    (js/Object.defineProperty o "drawingBufferHeight" #js {:get (fn [] (.-height c))})
    (aset o "isContextLost" (fn [] (.-__lost o)))
    (aset o "getExtension"
          (fn [n]
            (when (= n "WEBGL_lose_context")
              #js {:loseContext (fn []
                                   (set! (.-__lost o) true)
                                   (swap! log conj [:loseContext (.-id c)]))})))
    ;; a real browser's context is no longer lost by the time it fires
    ;; webglcontextrestored; this listener is added once, at creation, so it
    ;; always runs before any listener a later mount attaches to the same
    ;; (adopted) canvas.
    (.addEventListener c "webglcontextrestored" (fn [_] (set! (.-__lost o) false)))
    o))

(defn restore! []
  (when-let [f @saved]
    (set! (.. js/window -HTMLCanvasElement -prototype -getContext) f)
    (reset! saved nil)))

(defn install!
  "mode: :ok or :none."
  [mode]
  (restore!)
  (reset! log [])
  (let [proto (.. js/window -HTMLCanvasElement -prototype)
        prev (.-getContext proto)]
    (reset! saved prev)
    (set! (.-getContext proto)
          (fn [kind attrs]
            (this-as ^js c
              (if (= kind "webgl2")
                (when (= mode :ok)
                  (or (.-__fakegl c)
                      (let [o (fake c attrs)] (set! (.-__fakegl c) o) o)))
                (.call prev c kind attrs)))))))
