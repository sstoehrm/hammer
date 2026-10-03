(ns hammer.auto-bind-test
  "A global atom deref'd in a component body or binding init is bound
  automatically, so the component re-renders when it changes."
  (:require [cljs.test :refer [deftest is use-fixtures]]
            [hammer.test-env]
            [hammer.state :as state]
            [hammer.events :as events]
            [hammer.dom :as dom]
            [hammer.testing :as t]
            [hammer.canvas :as cv :refer [defdraw]]
            [hammer.draw :as draw]
            [hammer.core :refer [defc]])
  (:require-macros [hammer.macro-probe :refer [expand-warnings]]))

(use-fixtures :each {:before t/reset-app!})

(defn- container []
  (let [el (.createElement js/document "div")]
    (.appendChild (.-body js/document) el)
    el))

(def cart (atom [1]))
(def config (atom "v1"))
(def later (delay 3))

(defc badge [] [] [:span (count @cart)])
(defc total [] [n (count @cart) twice (* 2 n)] [:b twice])
(defc both [] [] [:i (count @cart) "/" (first @cart)])
(defc once [] [] [:em @^:once config])
(defc in-handler [] [] [:button {:on-click #(reset! config @cart)} "x"])
(defc shadowed [] [] (let [cart (atom 5)] [:i @cart]))
(defc delayed [] [] [:i @later])
(defc via-slot [] [c cart] [:i (count @c)])

(deftest body-deref-of-a-global-atom-re-renders
  (reset! cart [1])
  (let [el (container)]
    (dom/mount! [badge] el)
    (is (= "<span>1</span>" (.-innerHTML el)))
    (swap! cart conj 2)
    (t/flush!)
    (is (= "<span>2</span>" (.-innerHTML el)))))

(deftest binding-init-deref-recomputes
  (reset! cart [1])
  (let [el (container)]
    (dom/mount! [total] el)
    (is (= "<b>2</b>" (.-innerHTML el)))
    (swap! cart conj 2 3)
    (t/flush!)
    (is (= "<b>6</b>" (.-innerHTML el)))))

(deftest one-hidden-binding-per-atom
  (is (= 1 (count (.-specs ^js both))))
  (reset! cart [7])
  (let [el (container)]
    (dom/mount! [both] el)
    (swap! cart conj 8)
    (t/flush!)
    (is (= "<i>2/7</i>" (.-innerHTML el)))))

(deftest once-opts-out
  (is (= 0 (count (.-specs ^js once))))
  (reset! config "v1")
  (let [el (container)]
    (dom/mount! [once] el)
    (reset! config "v2")
    (t/flush!)
    (is (= "<em>v1</em>" (.-innerHTML el)) "read once, not tracked")))

(deftest derefs-inside-fns-and-of-locals-are-left-alone
  (is (= 0 (count (.-specs ^js in-handler))) "a handler runs at event time")
  (is (= 0 (count (.-specs ^js shadowed))) "a let-bound cart is a local")
  (let [el (container)]
    (dom/mount! [shadowed] el)
    (is (= "<i>5</i>" (.-innerHTML el)))))

(deftest a-non-watchable-ref-still-derefs
  (let [el (container)]
    (dom/mount! [delayed] el)
    (is (= "<i>3</i>" (.-innerHTML el)))))

(deftest an-explicit-slot-is-unchanged
  (is (= 1 (count (.-specs ^js via-slot)))))

(deftest app-db-deref-warns-instead
  (is (= ["defc peek: @state/app-db re-renders only by chance; read the db through a path binding, e.g. [todos [:todos]]"]
         (expand-warnings (hammer.core/defc peek [] [] [:i (count @state/app-db)]))))
  (is (= [] (expand-warnings (hammer.core/defc fine [] [] [:i (count @cart)])))))

(def w (atom 10))
(def drawn (atom []))
(defdraw sized [] [] {:size [@w 10]} (fn [_ _] (swap! drawn conj @w)))

(deftest defdraw-opts-deref-is-tracked
  (is (= 1 (count (.-specs ^js sized))) "only the opts deref; the draw fn's is at draw time")
  (reset! w 10)
  (t/use-fake-frames!)
  (try
    (let [el (container)]
      (cv/mount! [sized] el)
      (t/frame! 0)
      (reset! w 20)
      (t/frame! 16)
      (is (= "20px" (.. el -firstChild -style -width))))
    (finally (t/reset-app!) (draw/set-raf! nil))))
