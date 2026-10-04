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
(defc tagged [k] [] ^{:key k} [:hr])

(deftest defc-infers-kinds-and-deps
  (is (cells/component? row))
  (is (= "row" (.-cname row)))
  (is (= 1 (.-nprops row)))
  (is (= [:path :expr :expr :expr :expr] (map #(.-kind ^cells/Spec %) (.-specs row))))
  (is (= [[0] [] [1 2] [3] [0 4]] (map #(vec (.-deps ^cells/Spec %)) (.-specs row))))
  (is (= [3 4] (vec (.-body-deps row))))
  (is (= [0] (vec (.-body-deps tagged)))))

(deftest defc-instances-render
  (reset! state/app-db {:todos {7 {:title "x"}}})
  (let [inst (cells/create row [7] 1)
        ^js v (cells/render inst)]
    ;; a compiled template: hole values in post-order (kids, then attrs)
    (is (= :tpl (.-t v)))
    (is (= ["x" 1] (vec (.-attrs v))))
    (is (= [7 1] (aget (.-vals inst) 5)))
    (cells/destroy! inst))
  (let [inst (cells/create plain [] 1)
        ^js v (cells/render inst)]
    (is (= :tpl (.-t v)))
    (is (= [] (vec (.-attrs v))))
    (cells/destroy! inst)))

(deftest defc-rejects-bad-names-at-compile-time
  (is (= "defc bad: bindings need an even number of forms, got [a]"
         (expand-error (hammer.core/defc bad [] [a] nil))))
  (is (= (str "defc bad: props and binding names must be plain symbols, got {:keys [x]};"
              " bind the value to a symbol and derive the parts: [m] [x (:x m)]")
         (expand-error (hammer.core/defc bad [{:keys [x]}] [] nil))))
  (is (= (str "defc bad: props and binding names must be plain symbols, got [a b];"
              " bind the value to a symbol and derive the parts: [m] [x (:x m)]")
         (expand-error (hammer.core/defc bad [] [[a b] [:pair]] nil)))
      "a destructured binding name")
  (is (= "defc bad: duplicate prop or binding name a"
         (expand-error (hammer.core/defc bad [a] [a 1] nil))))
  (is (nil? (expand-error (hammer.core/defc ok [a] [b 1] nil)))))

(deftest missing-bindings-vector-is-named
  (is (= "defc page: missing bindings vector, write (defc page [] [] body)"
         (expand-error (hammer.core/defc page [] [:ul [:li "a"]]))))
  (is (= "defc row: missing bindings vector, write (defc row [id] [] body)"
         (expand-error (hammer.core/defc row [id] (str id)))))
  (is (= "defdraw chart: missing bindings vector, write (defdraw chart [] [] body)"
         (expand-error (hammer.canvas/defdraw chart [] {:size [1 1]} (fn [_ _])))))
  (is (= "defc row: missing bindings vector, write (defc row [id] [] body)"
         (expand-error (hammer.core/defc row [id] [child id])))
      "a component vector with an even count would parse as bindings and leave no body")
  (is (= "defc page: missing props and bindings vectors, write (defc page [] [] body)"
         (expand-error (hammer.core/defc page [:ul [:li "a"]])))))
