(ns cljs-ui.dom-mount-test
  (:require [cljs.test :refer [deftest is use-fixtures]]
            [cljs-ui.test-env]
            [cljs-ui.state :as state]
            [cljs-ui.events :as events]
            [cljs-ui.dom :as dom]
            [cljs-ui.testing :as t]
            [cljs-ui.core :refer [defc]]))

(use-fixtures :each {:before t/reset-app!})

(defn- container []
  (let [el (.createElement js/document "div")]
    (.appendChild (.-body js/document) el)
    el))

(defc greeting [who] [n [:count]] [:p "hi " who " " n])

(deftest normalizes-tags-classes-and-children
  (let [el (container)]
    (dom/mount! [:div.a.b#x {:class ["c" nil "d"] :title "t"}
                 "hi" nil false (list [:i 1] (list [:b 2]))]
                el)
    (let [d (.-firstChild el)]
      (is (= "DIV" (.-tagName d)))
      (is (= "x" (.-id d)))
      (is (= "a b c d" (.getAttribute d "class")))
      (is (= "t" (.getAttribute d "title")))
      (is (= "hi<i>1</i><b>2</b>" (.-innerHTML d))))))

(deftest sets-props-attrs-and-style
  (let [el (container)]
    (dom/mount! [:input {:value "v" :checked true :disabled false :hidden true
                         :style {:color "red"}}]
                el)
    (let [i (.-firstChild el)]
      (is (= "v" (.-value i)))
      (is (true? (.-checked i)))
      (is (not (.hasAttribute i "disabled")))
      (is (= "" (.getAttribute i "hidden")))
      (is (= "red" (.. i -style -color))))))

(deftest event-vectors-dispatch-and-fns-run
  (let [el (container)
        got (atom nil)]
    (events/reg-event :clicked (fn [db x] {:db (assoc db :clicked x)}))
    (dom/mount! [:div
                 [:button#a {:on-click [:clicked 1]}]
                 [:button#b {:on-click #(reset! got (.-type %))}]]
                el)
    (.click (.querySelector el "#a"))
    (.click (.querySelector el "#b"))
    (t/flush!)
    (is (= 1 (:clicked @state/app-db)))
    (is (= "click" @got))))

(deftest ref-gets-element-after-insertion
  (let [el (container)
        seen (atom nil)]
    (dom/mount! [:div [:input {:ref #(when % (reset! seen [(.-tagName %) (.contains js/document %)]))}]] el)
    (is (= ["INPUT" true] @seen))))

(deftest mounts-components-with-db-values
  (reset! state/app-db {:count 3})
  (t/reset-renders! greeting)
  (let [el (container)]
    (dom/mount! [:div [greeting "ann"]] el)
    (is (= "<div><p>hi ann 3</p></div>" (.-innerHTML el)))
    (is (= 1 (t/renders greeting)))))

(deftest remount-replaces-and-unsubscribes
  (reset! state/app-db {:count 1})
  (let [el (container)
        before (.-refs state/paths)]
    (dom/mount! [greeting "a"] el)
    (is (= (inc before) (.-refs state/paths)))
    (dom/mount! [:p "plain"] el)
    (is (= "<p>plain</p>" (.-innerHTML el)))
    (is (= before (.-refs state/paths)))))
