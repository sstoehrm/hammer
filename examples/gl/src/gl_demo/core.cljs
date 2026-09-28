(ns gl-demo.core
  (:require [hammer.core :refer [defc reg-event mount!]]
            [hammer.gl :refer [defdraw defloop]]))

(reg-event :inc (fn [db] {:db (update db :n inc)}))
(reg-event :toggle-tri (fn [db] {:db (update db :tri-on not)}))

(defdraw swatch [] [n [:n]]
  {:size [200 100] :fallback [:p "This demo needs WebGL2."]}
  (fn [g _] (.clearColor g (/ (mod n 10) 10) 0.2 0.6 1) (.clear g (.-COLOR_BUFFER_BIT g))))

(defloop pulse [] [] {:size [200 100] :fallback [:p "No WebGL2."]}
  (fn [g {:keys [t]}]
    (let [v (/ (inc (Math/sin (/ t 300))) 2)]
      (.clearColor g v v 0.2 1)
      (.clear g (.-COLOR_BUFFER_BIT g)))))

(def ^:private tri-vs
  "#version 300 es
layout(location=0) in vec2 pos;
void main() { gl_Position = vec4(pos, 0.0, 1.0); }
")

(def ^:private tri-fs
  "#version 300 es
precision mediump float;
uniform vec4 u_color;
out vec4 outColor;
void main() { outColor = u_color; }
")

(defn- shader! [^js g type src]
  (let [s (.createShader g type)]
    (.shaderSource g s src)
    (.compileShader g s)
    s))

(defn- init-triangle [^js g _info]
  (let [prog (.createProgram g)
        vs (shader! g (.-VERTEX_SHADER g) tri-vs)
        fs (shader! g (.-FRAGMENT_SHADER g) tri-fs)
        buf (.createBuffer g)]
    (.attachShader g prog vs)
    (.attachShader g prog fs)
    (.linkProgram g prog)
    (.deleteShader g vs)
    (.deleteShader g fs)
    (.bindBuffer g (.-ARRAY_BUFFER g) buf)
    (.bufferData g (.-ARRAY_BUFFER g) (js/Float32Array. #js [0 0.7 -0.7 -0.6 0.7 -0.6]) (.-STATIC_DRAW g))
    #js {:gl g :prog prog :buf buf :uloc (.getUniformLocation g prog "u_color")}))

(defn- dispose-triangle [^js res]
  (.deleteProgram ^js (.-gl res) (.-prog res))
  (.deleteBuffer ^js (.-gl res) (.-buf res)))

(defdraw triangle [] [on [:tri-on]]
  {:size [200 150]
   :init init-triangle
   :dispose dispose-triangle
   :fallback [:p "Triangle needs WebGL2."]}
  (fn [g _ ^js res]
    (.clearColor g 0.1 0.1 0.1 1)
    (.clear g (.-COLOR_BUFFER_BIT g))
    (.useProgram g (.-prog res))
    (.bindBuffer g (.-ARRAY_BUFFER g) (.-buf res))
    (.enableVertexAttribArray g 0)
    (.vertexAttribPointer g 0 2 (.-FLOAT g) false 0 0)
    (if on
      (.uniform4f g (.-uloc res) 0.2 0.85 0.35 1)
      (.uniform4f g (.-uloc res) 0.85 0.25 0.55 1))
    (.drawArrays g (.-TRIANGLES g) 0 3)))

(defc page [] [n [:n] on [:tri-on]]
  [:div
   [:h2 "Swatch (defdraw): redraws on each click"]
   [:button {:on-click [:inc]} (str "Colour " n)]
   [:div [swatch]]
   [:h2 "Pulse (defloop): animates every frame"]
   [:div [pulse]]
   [:h2 "Triangle (defdraw + :init)"]
   [:button {:on-click [:toggle-tri]} (if on "Toggle (green)" "Toggle (pink)")]
   [:div [triangle]]])

(defn ^:export main [] (mount! [page] (js/document.getElementById "app") {:n 0 :tri-on false}))
