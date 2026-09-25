(ns hammer.criteria-test
  (:require [cljs.test :refer [deftest is use-fixtures]]
            [hammer.test-env]
            [hammer.testing :as t]
            [hammer.core :refer [defc reg-event dispatch-sync mount!]]))

(use-fixtures :each {:before t/reset-app!})

(defc row [id] [todo [:todos id]]
  [:li {:class (when (:done todo) "done")} (:title todo)])

(defc table [] [ids [:ids]]
  [:ul (for [id ids] ^{:key id} [row id])])

(defc val-view [] [v [:v]] [:p v])

(deftest two-arity-mount-keeps-current-db
  (let [el (.createElement js/document "div")]
    (mount! [val-view] el {:v "first"})
    (reg-event :test/set-v (fn [db v] {:db (assoc db :v v)}))
    (dispatch-sync [:test/set-v "second"])
    (mount! [val-view] el)
    (is (= "<p>second</p>" (.-innerHTML el)))))

(deftest toggling-one-of-1000-renders-one
  (let [el (.createElement js/document "div")
        ids (vec (range 1000))]
    (mount! [table] el {:ids ids
                        :todos (into {} (map (fn [i] [i {:title (str "t" i) :done false}])) ids)})
    (reg-event :test/toggle (fn [db id] {:db (update-in db [:todos id :done] not)}))
    (t/reset-renders! row table)
    (dispatch-sync [:test/toggle 500])
    (is (= 1 (t/renders row)))
    (is (= 0 (t/renders table)))
    (is (= "done" (.. el -firstChild (querySelectorAll "li") (item 500) (getAttribute "class"))))))
