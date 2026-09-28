(ns bench-canvas.gl
  "gl-points (bench-gl build, hammer.gl only): one defdraw drawing n points
  (gl.POINTS) from a vertex buffer, coloured by a uniform."
  (:require [hammer.gl :refer [defdraw reg-event dispatch mount!]]
            [bench-canvas.common :as c :refer [D]]))

(def ^js G (.-GL D))
(def W (.-W G))
(def H (.-H G))

(def empty-pts (js/Float32Array. 0))

(reg-event :gl/create (fn [db _] {:db (assoc db :pts (.glPoints D (:n db) (.-SEED D)))}))
(reg-event :gl/update (fn [db k] {:db (assoc db :color (.glColor D k))}))
(reg-event :gl/clear (fn [db _] {:db (assoc db :pts empty-pts)}))

(defn- shader! [^js g type src]
  (let [s (.createShader g type)]
    (.shaderSource g s src)
    (.compileShader g s)
    s))

(defn- init [^js g _info n]
  (let [prog (.createProgram g)
        vs (shader! g (.-VERTEX_SHADER g) (.-GL_VS D))
        fs (shader! g (.-FRAGMENT_SHADER g) (.-GL_FS D))
        buf (.createBuffer g)]
    (.attachShader g prog vs)
    (.attachShader g prog fs)
    (.linkProgram g prog)
    (.deleteShader g vs)
    (.deleteShader g fs)
    (.bindBuffer g (.-ARRAY_BUFFER g) buf)
    (.bufferData g (.-ARRAY_BUFFER g) (* n 8) (.-DYNAMIC_DRAW g))
    #js {:gl g :prog prog :buf buf :uloc (.getUniformLocation g prog "u_color") :uploaded nil}))

(defn- dispose [^js res]
  (.deleteProgram ^js (.-gl res) (.-prog res))
  (.deleteBuffer ^js (.-gl res) (.-buf res)))

(defdraw points [n] [pts [:pts] color [:color]]
  {:size [W H]
   ;; readPixels (pixel parity in run.mjs) needs the drawing buffer kept
   ;; around after the browser would otherwise clear it on composite; the
   ;; vanilla variant requests the same attr.
   :context-attrs {:preserveDrawingBuffer true}
   :init (fn [g i] (init g i n))
   :dispose dispose
   ;; run.mjs waits on window.bench.unsupported (like vanilla's gl.js) to skip
   ;; a page with no WebGL2 instead of timing out waiting for a draw.
   :on-unsupported (fn [_reason] (set! (.-unsupported ^js (.-bench js/window)) true))}
  (fn [g _ ^js res]
    (c/draw!)
    (.bindBuffer g (.-ARRAY_BUFFER g) (.-buf res))
    (when-not (identical? pts (.-uploaded res))
      (when (pos? (.-length pts)) (.bufferSubData g (.-ARRAY_BUFFER g) 0 pts))
      (set! (.-uploaded res) pts))
    (.useProgram g (.-prog res))
    (.enableVertexAttribArray g 0)
    (.vertexAttribPointer g 0 2 (.-FLOAT g) false 0 0)
    (.uniform4fv g (.-uloc res) color)
    (.clearColor g 0 0 0 1)
    (.clear g (.-COLOR_BUFFER_BIT g))
    (.drawArrays g (.-POINTS g) 0 (/ (.-length pts) 2))))

(defn main []
  (let [{:keys [n]} (c/params)]
    (c/expose! (fn [op k] (dispatch [(keyword "gl" op) k])))
    (mount! [points n] (js/document.getElementById "app")
            {:n n :pts empty-pts :color (.glColor D 0)})))
