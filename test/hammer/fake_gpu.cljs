(ns hammer.fake-gpu
  "A stub navigator.gpu recording what hammer does with it."
  (:require [hammer.test-env]))

(defonce log (atom []))
(defonce lose! (atom nil))

(defn- device []
  (let [lost (js/Promise. (fn [res _] (reset! lose! #(res #js {:reason "unknown"}))))]
    #js {:queue #js {:submit (fn [cmds] (swap! log conj [:submit (alength cmds)]))}
         :lost lost
         :createCommandEncoder
         (fn []
           #js {:beginRenderPass (fn [^js d]
                                   (let [a (aget (.-colorAttachments d) 0)]
                                     (swap! log conj [:pass (.-loadOp a) (.. a -clearValue -a)]))
                                   #js {:end (fn [] (swap! log conj [:end]))
                                        :draw (fn [n] (swap! log conj [:draw n]))})
                :finish (fn [] #js {})})}))

(defn install!
  "mode: :ok, :no-adapter or :missing."
  [mode]
  (reset! log [])
  (let [gpu (case mode
              :missing js/undefined
              #js {:getPreferredCanvasFormat (fn [] "bgra8unorm")
                   :requestAdapter (fn []
                                     (js/Promise.resolve
                                      (when (= mode :ok)
                                        #js {:requestDevice (fn [] (swap! log conj [:device])
                                                              (js/Promise.resolve (device)))})))})]
    (js/Object.defineProperty js/navigator "gpu" #js {:value gpu :configurable true :writable true}))
  (let [proto (.. js/window -HTMLCanvasElement -prototype)
        prev (.-getContext proto)]
    (set! (.-getContext proto)
          (fn [kind]
            (this-as ^js c
              (if (= kind "webgpu")
                ;; realistic exclusivity: a canvas that already has a 2d
                ;; context (hammer.fake-canvas's fake sets __fake2d) can't
                ;; also get a webgpu one -- used to exercise configure!
                ;; failing for one component without breaking others.
                (cond
                  (.-__fake2d c) nil
                  (.-__fakegpu c) (.-__fakegpu c)
                  :else (let [o #js {:configure (fn [^js d] (swap! log conj [:configure (.-format d)]))
                                     :unconfigure (fn [] (swap! log conj [:unconfigure]))
                                     :getCurrentTexture (fn [] #js {:createView (fn [] #js {:view true})})}]
                          (set! (.-__fakegpu c) o) o))
                (.call prev c kind)))))))

(defn settle
  "Calls f after pending promise callbacks (device setup) have run."
  [f]
  (js/setTimeout f 0))
