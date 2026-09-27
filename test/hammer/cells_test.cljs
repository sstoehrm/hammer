(ns hammer.cells-test
  (:require [cljs.test :refer [deftest is]]
            [hammer.test-env]
            [hammer.state :as state]
            [hammer.trie :as trie]
            [hammer.cells :as cells]
            [hammer.scheduler :as sched]))

(defn- set-db! [db]
  (let [old @state/app-db]
    (reset! state/app-db db)
    (trie/notify! state/paths old db cells/mark!)))

(def runs (atom 0))

;; hand-built equivalent of
;; (defc row [id] [todo [:todos id] edit (atom false) label (str ...)] [:li label])
(def row
  (cells/component
   "row" 1
   [{:kind :path :deps [0] :f (fn [id] [:todos id])}
    {:kind :expr :deps [] :f (fn [] (atom false))}
    {:kind :expr :deps [1 2] :f (fn [todo edit]
                                  (swap! runs inc)
                                  (str (:title todo) (when @edit "*")))}]
   [3]
   (fn [_id _todo _edit label] [:li label])))

(deftest create-evaluates-bindings
  (reset! state/app-db {:todos {1 {:title "a"}}})
  (reset! runs 0)
  (let [inst (cells/create row [1] 1)]
    (is (= [:li "a"] (cells/render inst)))
    (is (= 1 @runs))
    (is (= 1 (.-renders row)))
    (cells/destroy! inst)))

(deftest path-change-recomputes-dependents-only
  (reset! state/app-db {:todos {1 {:title "a"} 2 {:title "b"}}})
  (let [inst (cells/create row [1] 1)]
    (reset! runs 0)
    (set-db! (assoc-in @state/app-db [:todos 2 :title] "B"))
    (is (not (.-dirty inst)))
    (set-db! (assoc-in @state/app-db [:todos 1 :title] "A"))
    (is (.-dirty inst))
    (is (true? (cells/refresh! inst)))
    (is (= 1 @runs))
    (is (= [:li "A"] (cells/render inst)))
    (cells/destroy! inst)))

(deftest equal-value-is-not-a-change
  (reset! state/app-db {:todos {1 {:title "a"}}})
  (let [inst (cells/create row [1] 1)]
    (set-db! (assoc-in @state/app-db [:todos 1] {:title "a"}))
    (is (.-dirty inst))
    (is (false? (cells/refresh! inst)))
    (cells/destroy! inst)))

(deftest local-atom-change-marks-and-rerenders
  (reset! state/app-db {:todos {1 {:title "a"}}})
  (let [inst (cells/create row [1] 1)
        edit (aget (.-vals inst) 2)]
    (reset! edit true)
    (is (.-dirty inst))
    (is (true? (cells/refresh! inst)))
    (is (= [:li "a*"] (cells/render inst)))
    (cells/destroy! inst)))

(deftest prop-change-re-resolves-path
  (reset! state/app-db {:todos {1 {:title "a"} 2 {:title "b"}}})
  (let [inst (cells/create row [1] 1)]
    (is (false? (cells/set-props! inst [1])))
    (is (true? (cells/set-props! inst [2])))
    (is (true? (cells/refresh! inst)))
    (is (= [:li "b"] (cells/render inst)))
    (set-db! (assoc-in @state/app-db [:todos 1 :title] "X"))
    (is (false? (cells/refresh! inst)))
    (set-db! (assoc-in @state/app-db [:todos 2 :title] "Y"))
    (is (true? (cells/refresh! inst)))
    (cells/destroy! inst)))

(deftest destroy-unsubscribes
  (reset! state/app-db {:todos {1 {:title "a"}}})
  (let [before (.-refs state/paths)
        inst (cells/create row [1] 1)
        edit (aget (.-vals inst) 2)]
    (is (= (inc before) (.-refs state/paths)))
    (cells/destroy! inst)
    (set-db! (assoc-in @state/app-db [:todos 1 :title] "b"))
    (reset! edit true)
    (is (not (.-dirty inst)))
    (is (= before (.-refs state/paths)))))

(deftest unmount-roots-runs-every-root-even-if-one-throws
  (let [el1 (js/document.createElement "div")
        el2 (js/document.createElement "div")
        unmounted (atom [])
        orig js/console.error errs (atom 0)]
    (cells/set-root! el1 (fn [] (throw (js/Error. "boom"))))
    (cells/set-root! el2 (fn [] (swap! unmounted conj el2)))
    (set! js/console.error (fn [& _] (swap! errs inc)))
    (try
      (cells/unmount-roots!)
      (is (= [el2] @unmounted) "the second root's unmount fn still runs after the first throws")
      (is (= 1 @errs) "the throwing unmount fn is logged, not left to crash the rest")
      (finally (set! js/console.error orig)))))

(deftype Fake [depth ^:mutable dirty id])

(deftest scheduler-runs-parents-first-once
  (let [order (atom [])
        prev (sched/runner)
        a (Fake. 3 false :a)
        b (Fake. 1 false :b)
        c (Fake. 2 false :c)]
    (sched/flush!) ; drop instances queued by earlier tests
    (sched/set-runner! (fn [^Fake x] (set! (.-dirty x) false) (swap! order conj (.-id x))))
    (sched/schedule! a)
    (sched/schedule! b)
    (sched/schedule! c)
    (sched/schedule! a)
    (sched/flush!)
    (sched/set-runner! prev)
    (is (= [:b :c :a] @order))
    (is (not (sched/pending?)))))
