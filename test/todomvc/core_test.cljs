(ns todomvc.core-test
  (:require [cljs.test :refer [deftest is]]
            [cljs.reader :refer [read-string]]
            [hammer.test-env]
            [hammer.testing :as t]
            [hammer.core :refer [dispatch]]
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

(defn- fresh-app! []
  (when-let [old (q "#app")] (.remove old))
  (let [root (.createElement js/document "div")]
    (set! (.-id root) "app")
    (.appendChild (.-body js/document) root)
    root))

(defn- dblclick! [el]
  (.dispatchEvent el (new (.-MouseEvent js/window) "dblclick" #js {:bubbles true}))
  (t/flush!))

(defn- label-el [s]
  (some #(when (= s (.-textContent %)) %) (qa ".todo-list label")))

(defn- item-el [s]
  (some #(when (= s (.-textContent (.querySelector % "label"))) %) (qa ".todo-list li")))

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
    (is (true? (get-in (read-string (.getItem js/localStorage "todos-hammer")) [1 :done])))

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

(deftest toggle-all-destroy-escape-blur-empty-save-and-routing
  (t/reset-app!)
  (.clear js/localStorage)
  (set! (.-hash js/location) "")
  (fresh-app!)
  (app/main)
  (doseq [s ["a" "b" "c"]]
    (input! (q ".new-todo") s)
    (key! (q ".new-todo") "Enter"))
  (is (= ["a" "b" "c"] (texts ".todo-list label")))

  (.click (q ".toggle-all"))
  (t/flush!)
  (is (= "0 items left" (.-textContent (q ".todo-count"))))
  (.click (q ".toggle-all"))
  (t/flush!)
  (is (= "3 items left" (.-textContent (q ".todo-count"))))

  (.click (.querySelector (item-el "b") ".destroy"))
  (t/flush!)
  (is (= ["a" "c"] (texts ".todo-list label")))

  (dblclick! (label-el "a"))
  (input! (q ".edit") "zzz")
  (key! (q ".edit") "Escape")
  (is (= ["a" "c"] (texts ".todo-list label")))
  (is (nil? (q ".edit")))

  (dblclick! (label-el "c"))
  (input! (q ".edit") "C2")
  (.dispatchEvent (q ".edit") (new (.-FocusEvent js/window) "blur"))
  (t/flush!)
  (is (= ["a" "C2"] (texts ".todo-list label")))

  (dblclick! (label-el "a"))
  (input! (q ".edit") "   ")
  (key! (q ".edit") "Enter")
  (is (= ["C2"] (texts ".todo-list label")))

  (.click (q ".toggle"))
  (t/flush!)
  (set! (.-hash js/location) "#/active")
  (.dispatchEvent js/window (new (.-HashChangeEvent js/window) "hashchange"))
  (t/flush!)
  (is (= [] (texts ".todo-list label")))
  (set! (.-hash js/location) "#/all")
  (.dispatchEvent js/window (new (.-HashChangeEvent js/window) "hashchange"))
  (t/flush!)
  (is (= ["C2"] (texts ".todo-list label")))

  (t/reset-app!)
  (fresh-app!)
  (app/main)
  (is (= ["C2"] (texts ".todo-list label")))
  (is (= "0 items left" (.-textContent (q ".todo-count")))))
