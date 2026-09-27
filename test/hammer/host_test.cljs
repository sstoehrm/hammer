(ns hammer.host-test
  (:require [cljs.test :refer [deftest is use-fixtures]]
            [hammer.test-env]
            [hammer.cells :as cells]
            [hammer.core :refer [defc]]
            [hammer.dom :as dom]
            [hammer.events :as events]
            [hammer.state :as state]
            [hammer.testing :as t]))

(def log (atom []))

(def fake
  "A hosted component with one prop and one path binding [:v]."
  (cells/component
   "fake" 1 [{:kind :path :deps [] :f (fn [] [:v])}] [0 1] (fn [p v] [p v])
   (cells/Host.
    (fn [inst] (set! (.-dirty inst) false) (cells/refresh! inst)
      (swap! log conj [:run (vec (.-vals inst))]))
    (fn [inst _render _el]
      (swap! log conj [:create (vec (.-vals inst))])
      (let [n (js/document.createElement "i")]
        (set! (.-textContent n) (str (aget (.-vals inst) 0)))
        n))
    (fn [inst] (swap! log conj [:destroy (aget (.-vals inst) 0)]) (cells/destroy! inst)))))

(events/reg-event ::set (fn [db k v] {:db (assoc db k v)}))

(defc holder [] [ids [:ids]]
  [:ul (for [id ids] ^{:key id} [fake id])])

(use-fixtures :each {:before (fn [] (t/reset-app!) (reset! log []))})

(deftest hosted-component-lifecycle
  (reset! state/app-db {:ids [1 2 3] :v :a})
  (let [el (js/document.createElement "div")]
    (dom/mount! [holder] el)
    (is (= [[:create [1 :a]] [:create [2 :a]] [:create [3 :a]]] @log))
    (is (= "<ul><i>1</i><i>2</i><i>3</i></ul>" (.-innerHTML el)))
    (let [[n1 _ n3] (js/Array.from (.. el -firstChild -children))]
      (reset! log [])
      (events/dispatch-sync [::set :v :b])
      (is (= #{[:run [1 :b]] [:run [2 :b]] [:run [3 :b]]} (set @log)) "path change runs the host")
      (reset! log [])
      (events/dispatch-sync [::set :ids [3 1]])
      (is (= [[:destroy 2]] @log))
      (is (= [n3 n1] (vec (js/Array.from (.. el -firstChild -children)))) "keyed move keeps host nodes"))))
