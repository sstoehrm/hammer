(ns bench-canvas.loops
  "loop-N: one defloop, N moving rects. Per-frame state lives in typed arrays
  (the same ones the vanilla loop uses); `step` mutates them and returns a
  fresh wrapper each frame, in both variants. `particles` keeps the wrapper in
  a volatile! (the documented pattern); `particles-atom` in a watched atom
  with swap! (the pattern docs/develop-with-a-hammer.md warns against)."
  (:require [hammer.canvas :refer [defloop mount!]]
            [bench-canvas.common :as c :refer [D]]))

(def ^js L (.-LOOP D))
(def W (.-W L))
(def H (.-H L))
(def SIZE (.-SIZE L))
(def COLOR (.-COLOR L))

(defn- make [n] (.particles D n (.-SEED D)))

(defn- step [^js p dt w h]
  (let [^js xs (.-xs p) ^js ys (.-ys p) ^js vxs (.-vxs p) ^js vys (.-vys p) n (.-n p)]
    (dotimes [i n]
      (let [x (+ (aget xs i) (* (aget vxs i) dt))
            y (+ (aget ys i) (* (aget vys i) dt))]
        (aset xs i x)
        (aset ys i y)
        (when (or (< x 0) (> x w)) (aset vxs i (- (aget vxs i))))
        (when (or (< y 0) (> y h)) (aset vys i (- (aget vys i))))))
    #js {:xs xs :ys ys :vxs vxs :vys vys :n n}))

(defn- paint! [^js ctx ^js p w h]
  (c/draw!)
  (.clearRect ctx 0 0 w h)
  (set! (.-fillStyle ctx) COLOR)
  (let [^js xs (.-xs p) ^js ys (.-ys p)]
    (dotimes [i (.-n p)]
      (.fillRect ctx (aget xs i) (aget ys i) SIZE SIZE))))

(defloop particles [n] [world (volatile! (make n))]
  {:size [W H]}
  (do (c/render!)
      (fn [ctx {:keys [w h dt]}]
        (paint! ctx (vswap! world step dt w h) w h))))

(defloop particles-atom [n] [world (atom (make n))]
  {:size [W H]}
  (do (c/render!)
      (fn [ctx {:keys [w h dt]}]
        (paint! ctx (swap! world step dt w h) w h))))

(defn start! [n atom? el]
  (mount! [(if atom? particles-atom particles) n] el)
  (c/expose! (fn [_ _] nil)))
