(ns bench-canvas.many
  "many-canvases-1k (bench-dom build): n tiny defdraw components in a keyed
  hammer.core DOM list. Each cell's colour comes from a path into a map keyed
  by id ([:colors id]) and its highlight from (is? [:sel] id)."
  (:require [hammer.core :refer [defc reg-event dispatch mount! is?]]
            [hammer.canvas :refer [defdraw]]
            [bench-canvas.common :as c :refer [D]]))

(def ^js M (.-MANY D))
(def S (.-S M))
(def HL (.-HL M))
(def UPD (.-UPD M))

(defn- id-at [k n] (inc (.selectIndex D k n)))

(reg-event :many/create
           (fn [db _]
             (let [n (:n db)
                   ^js cs (.cellColors D n (.-SEED D))]
               {:db (assoc db
                           :ids (vec (range 1 (inc n)))
                           :colors (persistent! (reduce (fn [m i] (assoc! m (inc i) (aget cs i)))
                                                        (transient {}) (range n)))
                           :sel nil)})))

;; recolours one cell; a different id every k, so the colour always changes
(reg-event :many/update (fn [db k] {:db (assoc-in db [:colors (id-at k (:n db))] UPD)}))

(reg-event :many/select (fn [db k] {:db (assoc db :sel (id-at k (:n db)))}))

(reg-event :many/clear (fn [db _] {:db (assoc db :ids [] :colors {} :sel nil)}))

(defdraw cell [id] [color [:colors id] sel (is? [:sel] id)]
  {:size [S S]}
  (fn [^js ctx {:keys [w h]}]
    (c/draw!)
    (.clearRect ctx 0 0 w h)
    (set! (.-fillStyle ctx) (if sel HL color))
    (.fillRect ctx 2 2 (- w 4) (- h 4))))

(defc grid [] [ids [:ids]]
  [:div {:class "grid"} (for [id ids] ^{:key id} [cell id])])

(defn main []
  (let [{:keys [n]} (c/params)]
    (mount! [grid] (js/document.getElementById "app") {:n n :ids [] :colors {} :sel nil})
    ;; the grid has no canvas until create; count the empty mount as ready
    (c/draw!)
    (c/expose! (fn [op k] (dispatch [(keyword "many" op) k])))))
