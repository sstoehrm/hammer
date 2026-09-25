(ns cljs-ui.trie-test
  (:require [cljs.test :refer [deftest is]]
            [cljs-ui.trie :as trie]))

(defn- marks [root old nu]
  (let [seen (atom [])]
    (trie/notify! root old nu #(swap! seen conj %))
    @seen))

(deftest notify-marks-changed-paths-only
  (let [root (trie/node)]
    (trie/register! root [:a :x] :ax)
    (trie/register! root [:b] :b)
    (is (= [:ax] (marks root {:a {:x 1} :b 2} {:a {:x 2} :b 2})))
    (is (= [] (marks root {:a {:x 1} :b 2} {:a {:x 1 :y 5} :b 2})))
    (is (= [:b] (marks root {:b 1} {:b 2})))))

(deftest parent-path-sees-any-change-below
  (let [root (trie/node)]
    (trie/register! root [:a] :a)
    (trie/register! root [:a :x] :ax)
    (is (= [:a] (marks root {:a {:x 1 :y 1}} {:a {:x 1 :y 2}})))))

(deftest notify-prunes-unchanged-branches
  (let [root (trie/node)
        db {:todos (into {} (map (fn [i] [i {:done false}])) (range 1000))
            :other 1}]
    (dotimes [i 1000] (trie/register! root [:todos i] i))
    (trie/register! root [:other] :other)
    (is (= 2 (trie/notify! root db (assoc db :other 2) (fn [_]))))
    (let [seen (atom [])]
      (is (= 1002 (trie/notify! root db (assoc-in db [:todos 7 :done] true)
                                #(swap! seen conj %))))
      (is (= [7] @seen)))))

(deftest unregister-drops-empty-nodes
  (let [root (trie/node)]
    (trie/register! root [:a :x] :c1)
    (trie/register! root [:a :x] :c2)
    (trie/unregister! root [:a :x] :c1)
    (is (= [:c2] (marks root {:a {:x 1}} {:a {:x 2}})))
    (trie/unregister! root [:a :x] :c2)
    (is (= 0 (.-refs root)))
    (is (empty? (.-children root)))))

(deftest root-path-and-identical-db
  (let [root (trie/node)
        db {:a 1}]
    (trie/register! root [] :all)
    (is (= [:all] (marks root db {:a 2})))
    (is (= [] (marks root db db)))))
