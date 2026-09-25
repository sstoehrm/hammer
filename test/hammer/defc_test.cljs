(ns hammer.defc-test
  (:require [cljs.test :refer [deftest is]]
            [hammer.test-env]
            [hammer.state :as state]
            [hammer.cells :as cells]
            [hammer.core :refer [defc]])
  (:require-macros [hammer.macro-probe :refer [expand-error]]))

(defc row [id]
  [todo  [:todos id]
   edit  (atom false)
   label (str (:title todo) (when @edit "*"))
   n     (count label)
   pair  (vector id n)]
  [:li {:data-n n} label])

(defc plain [] [] [:hr])

(deftest defc-infers-kinds-and-deps
  (is (cells/component? row))
  (is (= "row" (.-cname row)))
  (is (= 1 (.-nprops row)))
  (is (= [:path :expr :expr :expr :expr] (map :kind (.-specs row))))
  (is (= [[0] [] [1 2] [3] [0 4]] (map :deps (.-specs row)))))

(deftest defc-instances-render
  (reset! state/app-db {:todos {7 {:title "x"}}})
  (let [inst (cells/create row [7] 1)]
    (is (= [:li {:data-n 1} "x"] (cells/render inst)))
    (is (= [7 1] (aget (.-vals inst) 5)))
    (cells/destroy! inst))
  (let [inst (cells/create plain [] 1)]
    (is (= [:hr] (cells/render inst)))
    (cells/destroy! inst)))

(deftest defc-rejects-bad-names-at-compile-time
  (is (= "defc: bindings need an even number of forms"
         (expand-error (hammer.core/defc bad [] [a] nil))))
  (is (= "defc: props and binding names must be plain symbols"
         (expand-error (hammer.core/defc bad [{:keys [x]}] [] nil))))
  (is (= "defc: duplicate prop or binding name"
         (expand-error (hammer.core/defc bad [a] [a 1] nil))))
  (is (nil? (expand-error (hammer.core/defc ok [a] [b 1] nil)))))
