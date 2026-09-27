(ns size.dom
  (:require [hammer.core :refer [defc reg-event mount!]]))
(reg-event :inc (fn [db] {:db (update db :n inc)}))
(defc app [] [n [:n]] [:button {:on-click [:inc]} n])
(defn main [] (mount! [app] (js/document.getElementById "app") {:n 0}))
