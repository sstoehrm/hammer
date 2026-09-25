(ns todomvc.core-test
  (:require [cljs.test :refer [deftest is]]
            [cljs.reader :refer [read-string]]
            [cljs-ui.test-env]
            [cljs-ui.testing :as t]
            [cljs-ui.core :refer [dispatch]]
            [todomvc.core :as app]))

(defn- q [s] (.querySelector js/document s))
(defn- qa [s] (vec (js/Array.from (.querySelectorAll js/document s))))
(defn- texts [s] (mapv #(.-textContent %) (qa s)))

(defn- input! [el v]
  (set! (.-value el) v)
  (.dispatchEvent el (new (.-Event js/window) "input" #js {:bubbles true}))
  (t/flush!))

(defn- key! [el k]
  (.dispatchEvent el (new (.-KeyboardEvent js/window) "keydown" #js {:key k :bubbles true}))
  (t/flush!))

(deftest add-toggle-edit-filter-clear
  (t/reset-app!)
  (.clear js/localStorage)
  (let [root (.createElement js/document "div")]
    (set! (.-id root) "app")
    (.appendChild (.-body js/document) root)
    (app/main)
    (doseq [s ["milk" "eggs"]]
      (input! (q ".new-todo") s)
      (key! (q ".new-todo") "Enter"))
    (is (= ["milk" "eggs"] (texts ".todo-list label")))
    (is (= "" (.-value (q ".new-todo"))))
    (is (= "2 items left" (.-textContent (q ".todo-count"))))

    (.click (first (qa ".toggle")))
    (t/flush!)
    (is (= "1 item left" (.-textContent (q ".todo-count"))))
    (is (true? (get-in (read-string (.getItem js/localStorage "todos-cljs-ui")) [1 :done])))

    (.dispatchEvent (second (qa ".todo-list label"))
                    (new (.-MouseEvent js/window) "dblclick" #js {:bubbles true}))
    (t/flush!)
    (is (identical? (q ".edit") (.-activeElement js/document)))
    (input! (q ".edit") "EGGS")
    (key! (q ".edit") "Enter")
    (is (= ["milk" "EGGS"] (texts ".todo-list label")))

    (dispatch [:show :active])
    (t/flush!)
    (is (= ["EGGS"] (texts ".todo-list label")))

    (dispatch [:show :all])
    (t/flush!)
    (.click (q ".clear-completed"))
    (t/flush!)
    (is (= ["EGGS"] (texts ".todo-list label")))))
