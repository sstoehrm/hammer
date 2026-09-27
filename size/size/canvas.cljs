(ns size.canvas
  (:require [hammer.canvas :refer [defdraw defloop reg-event mount!]]))
(reg-event :inc (fn [db] {:db (update db :n inc)}))
(defdraw bar [] [n [:n]] {:size [100 20] :on-click [:inc]}
  (fn [ctx {:keys [w h]}] (.clearRect ctx 0 0 w h) (.fillRect ctx 0 0 n h)))
(defloop spin [] [] {:size [20 20]}
  (fn [ctx {:keys [t]}] (.clearRect ctx 0 0 20 20) (.fillRect ctx 0 0 (mod (/ t 10) 20) 20)))
(defn main []
  (mount! [bar] (js/document.getElementById "a") {:n 1})
  (mount! [spin] (js/document.getElementById "b")))
