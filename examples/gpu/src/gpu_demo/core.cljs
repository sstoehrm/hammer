(ns gpu-demo.core
  (:require [hammer.core :refer [defc reg-event mount!]]
            [hammer.gpu :as gpu :refer [defdraw defloop]]))

(reg-event :inc (fn [db] {:db (update db :n inc)}))

(defdraw swatch [] [n [:n]]
  {:size [200 100] :fallback [:p "This demo needs WebGPU (Chrome, Edge, iOS Safari 26+)."]}
  (fn [g _] (gpu/pass g {:clear [(/ (mod n 10) 10) 0.2 0.6 1]} (fn [_]))))

(defloop pulse [] [] {:size [200 100] :fallback [:p "No WebGPU."]}
  (fn [g {:keys [t]}] (let [v (/ (inc (Math/sin (/ t 300))) 2)] (gpu/pass g {:clear [v v 0.2 1]} (fn [_])))))

(defc page [] [n [:n]]
  [:div [:button {:on-click [:inc]} (str "Colour " n)] [swatch] [pulse]])

(defn ^:export main [] (mount! [page] (js/document.getElementById "app") {:n 0}))
