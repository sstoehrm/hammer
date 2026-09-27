(ns hammer.app-test
  (:require [cljs.test :refer [deftest is]]
            [hammer.test-env]
            [hammer.app :as app]
            [hammer.core :as core :refer [defc]]
            [hammer.cells :as cells]))

(deftest core-reexports-app
  (is (identical? app/reg-event core/reg-event))
  (is (identical? app/reg-fx core/reg-fx))
  (is (identical? app/dispatch core/dispatch))
  (is (identical? app/dispatch-sync core/dispatch-sync))
  (is (identical? app/is? core/is?)))

(defc via-app-alias [id] [on? (app/is? [:sel] id)] [:i (str on?)])

(deftest is?-through-app-alias
  (is (= [:eq] (map #(.-kind ^cells/Spec %) (.-specs via-app-alias)))))
