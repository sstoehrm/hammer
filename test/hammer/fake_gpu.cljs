(ns hammer.fake-gpu
  "A stub navigator.gpu recording what hammer does with it."
  (:require [hammer.test-env]))

(defonce log (atom []))
(defonce lose! (atom nil)) ; resolves the newest device's .lost; optional arg: reason (default "unknown")
(defonce clears (atom [])) ; [r g b a] clearValue of every render pass
(defonce ^:private ids (atom 0))
(defonce adapter-request
  (atom nil)) ; :deferred mode: {:resolve (fn []) :reject (fn [e])} of the pending requestAdapter

(defn- device [mode]
  (let [lost (when-not (= mode :bad-device)
               (js/Promise. (fn [res _] (reset! lose! (fn [& [reason]] (res #js {:reason (or reason "unknown")}))))))]
    #js {:id (swap! ids inc)
         :queue #js {:submit (fn [cmds] (swap! log conj [:submit (alength cmds)]))}
         :lost lost
         :createCommandEncoder
         (fn []
           #js {:beginRenderPass (fn [^js d]
                                   (let [a (aget (.-colorAttachments d) 0)
                                         cv (.-clearValue a)]
                                     (swap! clears conj [(.-r cv) (.-g cv) (.-b cv) (.-a cv)])
                                     (swap! log conj [:pass (.-loadOp a) (.-a cv)]))
                                   #js {:end (fn [] (swap! log conj [:end]))
                                        :draw (fn [n] (swap! log conj [:draw n]))})
                :finish (fn [] #js {})})}))

(defonce ^:private saved (atom nil))

(defn restore!
  "Undoes install!: navigator.gpu and HTMLCanvasElement.prototype.getContext
  go back to what they were before it. No-op when nothing is installed."
  []
  (when-let [{:keys [desc get-context]} @saved]
    (if desc
      (js/Object.defineProperty js/navigator "gpu" desc)
      (js-delete js/navigator "gpu"))
    (set! (.. js/window -HTMLCanvasElement -prototype -getContext) get-context)
    (reset! saved nil)))

(defn install!
  "mode: :ok, :no-adapter, :missing, :deferred (requestAdapter stays
  pending until the test calls :resolve (an :ok adapter) or :reject on
  @adapter-request), :bad-format (getPreferredCanvasFormat throws),
  :bad-device (the device has no .lost promise) or :device-rejects
  (requestDevice rejects). Device ids restart at 1. Restores a previous install!
  first, so getContext is wrapped at most once; pair with restore! in an
  :after fixture."
  [mode]
  (restore!)
  (reset! log [])
  (reset! clears [])
  (reset! ids 0)
  (let [proto (.. js/window -HTMLCanvasElement -prototype)
        prev (.-getContext proto)]
    (reset! saved {:desc (js/Object.getOwnPropertyDescriptor js/navigator "gpu")
                   :get-context prev})
    (let [adapter #js {:requestDevice (fn [] (swap! log conj [:device])
                                        (if (= mode :device-rejects)
                                          (js/Promise.reject (js/Error. "device refused"))
                                          (js/Promise.resolve (device mode))))}
          gpu (case mode
                :missing js/undefined
                #js {:getPreferredCanvasFormat (fn []
                                                 (if (= mode :bad-format)
                                                   (throw (js/Error. "no format"))
                                                   "bgra8unorm"))
                     :requestAdapter (fn []
                                       (if (= mode :deferred)
                                         (js/Promise. (fn [res rej]
                                                        (reset! adapter-request
                                                                {:resolve #(res adapter) :reject rej})))
                                         (js/Promise.resolve (when-not (= mode :no-adapter) adapter))))})]
      (js/Object.defineProperty js/navigator "gpu" #js {:value gpu :configurable true :writable true}))
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
