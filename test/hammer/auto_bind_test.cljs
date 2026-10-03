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
            [hammer.cells :as cells]
            [hammer.core :refer [defc]])
  (:require-macros [hammer.macro-probe :refer [expand-warnings]]
                   [hammer.tpl-macros :refer [with-local]]))

(use-fixtures :each {:before t/reset-app!})

(defn- container []
  (let [el (.createElement js/document "div")]
    (.appendChild (.-body js/document) el)
    el))

(defn- nspecs "Bindings a component has, hidden ones included." [^cells/Comp c] (count (.-specs c)))

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
  (is (= 1 (nspecs both)))
  (reset! cart [7])
  (let [el (container)]
    (dom/mount! [both] el)
    (swap! cart conj 8)
    (t/flush!)
    (is (= "<i>2/7</i>" (.-innerHTML el)))))

(deftest once-opts-out
  (is (= 0 (nspecs once)))
  (reset! config "v1")
  (let [el (container)]
    (dom/mount! [once] el)
    (reset! config "v2")
    (t/flush!)
    (is (= "<em>v1</em>" (.-innerHTML el)) "read once, not tracked")))

(deftest derefs-inside-fns-and-of-locals-are-left-alone
  (is (= 0 (nspecs in-handler)) "a handler runs at event time")
  (is (= 0 (nspecs shadowed)) "a let-bound cart is a local")
  (let [el (container)]
    (dom/mount! [shadowed] el)
    (is (= "<i>5</i>" (.-innerHTML el)))))

(deftest a-non-watchable-ref-still-derefs
  (let [el (container)]
    (dom/mount! [delayed] el)
    (is (= "<i>3</i>" (.-innerHTML el)))))

(deftest an-explicit-slot-is-unchanged
  (is (= 1 (nspecs via-slot))))

(deftest app-db-deref-warns-instead
  (is (= ["defc peek: @state/app-db re-renders only by chance; read the db through a path binding, e.g. [todos [:todos]]"]
         (expand-warnings (hammer.core/defc peek [] [] [:i (count @state/app-db)]))))
  (is (= [] (expand-warnings (hammer.core/defc fine [] [] [:i (count @cart)])))))

(def w (atom 10))
(def drawn (atom []))
(defdraw sized [] [] {:size [@w 10]} (fn [_ _] (swap! drawn conj @w)))

(deftest defdraw-opts-deref-is-tracked
  (is (= 1 (nspecs sized)) "only the opts deref; the draw fn's is at draw time")
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

;; ---- locals and non-atom vars are never auto-bound (review findings)

(def bag (atom 100))
(def ^:dynamic *dyn* (atom :outer))
(set! (.-hammerStore js/globalThis) (atom 1))

(defc as-arrow [] [] [:i (as-> (atom 5) bag @bag)])
(defc user-macro [] [] [:i (with-local [bag (atom 6)] @bag)])
(defc keys-kw [] [m [:m]] (let [{:keys [:bag]} m] [:i @bag]))
(defc keys-ns [] [m [:m]] (let [{:keys [foo/bag]} m] [:i @bag]))
(defc caught [] [] [:i (try (throw (atom 7)) (catch :default bag @bag))])
(defc dyn [] [] [:i (binding [*dyn* (atom :inner)] (str @*dyn*))])
(defc core-name [] [] [:i (as-> (atom 9) val @val)])
(defc core-areduce [] [] [:i (areduce (to-array [1]) i val (atom 8) @val)])
(defc js-global [] [] [:i @js/globalThis.hammerStore])
(defc in-letfn [] [] (letfn [(f [] 1)] [:i (+ (f) (count @cart))]))

(deftest locals-under-any-binder-are-left-alone
  (reset! state/app-db {:m {:bag (atom 3) :foo/bag (atom 4)}})
  (doseq [[c out n] [[as-arrow "<i>5</i>" 0] [user-macro "<i>6</i>" 0] [keys-kw "<i>3</i>" 1]
                     [caught "<i>7</i>" 0] [dyn "<i>:inner</i>" 0] [core-name "<i>9</i>" 0]
                     [core-areduce "<i>8</i>" 0]]]
    (let [el (container)]
      (dom/mount! [c] el)
      (is (= out (.-innerHTML el)) (.-cname ^cells/Comp c))
      (is (= n (nspecs c)) (str (.-cname ^cells/Comp c) ": nothing auto-bound"))))
  (let [el (container)]
    (reset! state/app-db {:m {:foo/bag (atom 4)}})
    (dom/mount! [keys-ns] el)
    (is (= "<i>4</i>" (.-innerHTML el)))))

(deftest js-globals-are-not-auto-bound
  (is (= 0 (nspecs js-global)))
  (let [el (container)]
    (dom/mount! [js-global] el)
    (is (= "<i>1</i>" (.-innerHTML el)))))

(deftest letfn-body-is-tracked
  (is (= 1 (nspecs in-letfn)))
  (reset! cart [1])
  (let [el (container)]
    (dom/mount! [in-letfn] el)
    (swap! cart conj 2)
    (t/flush!)
    (is (= "<i>3</i>" (.-innerHTML el)))))
