(ns size.gl
  (:require [hammer.gl :refer [defdraw reg-event mount!]]))
(reg-event :inc (fn [db] {:db (update db :n inc)}))
(defdraw clear [] [n [:n]] {:size [100 100] :on-click [:inc]}
  (fn [g _] (.clearColor g (/ (mod n 10) 10) 0 0 1) (.clear g (.-COLOR_BUFFER_BIT g))))
(defn main [] (mount! [clear] (js/document.getElementById "a") {:n 0}))
