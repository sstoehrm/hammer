(ns ^:figwheel-hooks counter.core
  (:require [hammer.core :refer [defc reg-event dispatch mount!]]))

(reg-event :add (fn [db n]
                  (let [c (+ (:count db) n)]
                    {:db (-> db (assoc :count c) (update :log conj c))})))
(reg-event :reset (fn [db] {:db (assoc db :count 0 :log [])}))

(defc counter []
  [n    [:count]
   step (atom "1")
   sign (cond (pos? n) "positive" (neg? n) "negative" :else "zero")]
  [:div.counter
   [:h1 {:class sign} n]
   [:button.dec {:on-click #(dispatch [:add (- (or (parse-long @step) 0))])} "−"]
   [:button.inc {:on-click #(dispatch [:add (or (parse-long @step) 0)])} "+"]
   [:label " step "
    [:input {:type "number" :value @step :on-input #(reset! step (.. ^js % -target -value))}]]
   [:button.reset {:on-click [:reset]} "reset"]])

(defc history []
  [log   [:log]
   total (count log)]
  [:div.history
   [:p total " changes"]
   [:ol (for [[i v] (map-indexed vector log)] ^{:key i} [:li v])]])

(defc app [] []
  [:div [counter] [history]])

(defn- el [] (js/document.getElementById "app"))

(defonce started (do (mount! [app] (el) {:count 0 :log []}) true))

(defn ^:after-load reload
  "Re-renders after a hot reload, keeping the current db."
  []
  (mount! [app] (el)))
