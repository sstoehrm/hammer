(ns hammer.fake-gl
  "jsdom has no WebGL: getContext(\"webgl2\", attrs) returns a recording fake
  (mode :ok) or null (mode :none). restore! puts the original getContext back."
  (:require [hammer.test-env]))

(defonce log (atom []))
(defonce ^:private saved (atom nil))

(def ^:private ops ["viewport" "clearColor" "clear" "drawArrays" "useProgram"])

(defn- fake [^js c attrs]
  (let [o #js {:canvas c :attrs attrs}]
    (doseq [m ops]
      (aset o m (fn [& args] (swap! log conj (into [(keyword m) (.-id c)] args)))))
    (js/Object.defineProperty o "drawingBufferWidth" #js {:get (fn [] (.-width c))})
    (js/Object.defineProperty o "drawingBufferHeight" #js {:get (fn [] (.-height c))})
    (aset o "getExtension"
          (fn [n]
            (when (= n "WEBGL_lose_context")
              #js {:loseContext (fn [] (swap! log conj [:loseContext (.-id c)]))})))
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
