(ns bench-canvas.gpu
  "gpu-points (bench-gpu build, hammer.gpu only): one defdraw drawing n points
  (point-list) from a vertex buffer, coloured by a uniform."
  (:require [hammer.gpu :as gpu :refer [defdraw reg-event dispatch mount!]]
            [bench-canvas.common :as c :refer [D]]))

(def ^js G (.-GPU D))
(def W (.-W G))
(def H (.-H G))

(def empty-pts (js/Float32Array. 0))

(reg-event :gpu/create (fn [db _] {:db (assoc db :pts (.gpuPoints D (:n db) (.-SEED D)))}))
(reg-event :gpu/update (fn [db k] {:db (assoc db :color (.gpuColor D k))}))
(reg-event :gpu/clear (fn [db _] {:db (assoc db :pts empty-pts)}))

(defn- init [{:keys [device format]} _ n]
  (let [^js device device
        module (.createShaderModule device #js {:code (.-GPU_SHADER D)})
        pipeline (.createRenderPipeline
                  device
                  #js {:layout "auto"
                       :vertex #js {:module module :entryPoint "vs"
                                    :buffers #js [#js {:arrayStride 8
                                                       :attributes #js [#js {:shaderLocation 0 :offset 0
                                                                             :format "float32x2"}]}]}
                       :fragment #js {:module module :entryPoint "fs" :targets #js [#js {:format format}]}
                       :primitive #js {:topology "point-list"}})
        vbuf (.createBuffer device #js {:size (* n 8)
                                        :usage (bit-or js/GPUBufferUsage.VERTEX js/GPUBufferUsage.COPY_DST)})
        ubuf (.createBuffer device #js {:size 16
                                        :usage (bit-or js/GPUBufferUsage.UNIFORM js/GPUBufferUsage.COPY_DST)})
        bind (.createBindGroup device #js {:layout (.getBindGroupLayout ^js pipeline 0)
                                           :entries #js [#js {:binding 0 :resource #js {:buffer ubuf}}]})]
    #js {:pipeline pipeline :vbuf vbuf :ubuf ubuf :bind bind :uploaded nil}))

(defdraw points [n] [pts [:pts] color [:color]]
  {:size [W H]
   :init (fn [g i] (init g i n))
   :dispose (fn [^js res] (.destroy ^js (.-vbuf res)) (.destroy ^js (.-ubuf res)))}
  (fn [{:keys [queue] :as g} _ ^js res]
    (c/draw!)
    (let [^js queue queue]
      (when-not (identical? pts (.-uploaded res))
        (when (pos? (.-length pts)) (.writeBuffer queue (.-vbuf res) 0 pts))
        (set! (.-uploaded res) pts))
      (.writeBuffer queue (.-ubuf res) 0 color)
      (gpu/pass g {:clear [0 0 0 1]}
                (fn [^js pass]
                  (.setPipeline pass (.-pipeline res))
                  (.setBindGroup pass 0 (.-bind res))
                  (.setVertexBuffer pass 0 (.-vbuf res))
                  (.draw pass (/ (.-length pts) 2)))))))

(defn main []
  (let [{:keys [n]} (c/params)]
    (mount! [points n] (js/document.getElementById "app")
            {:n n :pts empty-pts :color (.gpuColor D 0)})
    (c/expose! (fn [op k] (dispatch [(keyword "gpu" op) k])))))
