(ns cljs-ui.criteria-test
  (:require [cljs.test :refer [deftest is use-fixtures]]
            [cljs-ui.test-env]
            [cljs-ui.testing :as t]
            [cljs-ui.core :refer [defc reg-event dispatch-sync mount!]]))

(use-fixtures :each {:before t/reset-app!})

(defc row [id] [todo [:todos id]]
  [:li {:class (when (:done todo) "done")} (:title todo)])

(defc table [] [ids [:ids]]
  [:ul (for [id ids] ^{:key id} [row id])])

(deftest toggling-one-of-1000-renders-one
  (let [el (.createElement js/document "div")
        ids (vec (range 1000))]
    (mount! [table] el {:ids ids
                        :todos (into {} (map (fn [i] [i {:title (str "t" i) :done false}])) ids)})
    (reg-event :toggle (fn [db id] {:db (update-in db [:todos id :done] not)}))
    (t/reset-renders! row table)
    (dispatch-sync [:toggle 500])
    (is (= 1 (t/renders row)))
    (is (= 0 (t/renders table)))
    (is (= "done" (.. el -firstChild (querySelectorAll "li") (item 500) (getAttribute "class"))))))
