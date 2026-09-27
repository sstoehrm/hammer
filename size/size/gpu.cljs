(ns size.gpu
  (:require [hammer.gpu :as gpu :refer [defdraw reg-event mount!]]))
(reg-event :inc (fn [db] {:db (update db :n inc)}))
(defdraw clear [] [n [:n]] {:size [100 100] :on-click [:inc]}
  (fn [g _] (gpu/pass g {:clear [(/ (mod n 10) 10) 0 0 1]} (fn [_]))))
(defn main [] (mount! [clear] (js/document.getElementById "a") {:n 0}))
