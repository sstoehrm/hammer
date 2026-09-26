(ns hammer.trie-test
  (:require [cljs.test :refer [deftest is testing]]
            [hammer.trie :as trie]))

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
      ;; root: 2 children; [:todos]: only the changed key is visited (hash map diff)
      (is (= 3 (trie/notify! root db (assoc-in db [:todos 7 :done] true)
                                #(swap! seen conj %))))
      (is (= [7] @seen)))))

(deftest unregister-drops-empty-nodes
  (let [root (trie/node)
        c1 :c1
        c2 :c2]
    (trie/register! root [:a :x] c1)
    (trie/register! root [:a :x] c2)
    (trie/unregister! root [:a :x] c1)
    (is (= [c2] (marks root {:a {:x 1}} {:a {:x 2}})))
    (trie/unregister! root [:a :x] c2)
    (is (= 0 (.-refs root)))
    (is (empty? (trie/child-keys root)))))

(deftest root-path-and-identical-db
  (let [root (trie/node)
        db {:a 1}]
    (trie/register! root [] :all)
    (is (= [:all] (marks root db {:a 2})))
    (is (= [] (marks root db db)))))

(deftest child-keys-keep-value-semantics
  (let [root (trie/node)
        kw (keyword (str "a" "b"))]
    (trie/register! root [kw] :kw)
    (trie/register! root [[:v (str "x" 1)]] :vec)
    (trie/register! root ["ab"] :str)
    (trie/register! root [1] :one)
    (trie/register! root ["1"] :one-str)
    (trie/register! root [nil] :nil)
    (trie/register! root [true] :true)
    (trie/register! root [false] :false)
    (trie/register! root [:ns/ab] :ns-kw)
    (trie/register! root ['ab] :sym)
    (testing "runtime keyword finds the literal key and vice versa"
      (is (= [:kw] (marks root {:ab 1} {:ab 2})))
      (is (= [:kw] (marks root {kw 1} {(keyword "ab") 2}))))
    (testing "vector keys by value"
      (is (= [:vec] (marks root {[:v "x1"] 1} {[:v "x1"] 2}))))
    (testing "a string and a keyword with the same name are different keys"
      (is (= [:str] (marks root {"ab" 1} {"ab" 2})))
      (is (not= (trie/child root "ab") (trie/child root :ab))))
    (testing "a number and a numeric string are different keys"
      (is (= [:one] (marks root {1 :a} {1 :b})))
      (is (= [:one-str] (marks root {"1" :a} {"1" :b})))
      (is (= [:one] (marks root [0 :a] [0 :b]))))
    (testing "nil and booleans"
      (is (= [:nil] (marks root {nil 1} {nil 2})))
      (is (= #{:true :false} (set (marks root {true 1 false 1} {true 2 false 2})))))
    (testing "namespaced keyword and symbol stay apart from :ab"
      (is (= [:ns-kw] (marks root {:ns/ab 1} {:ns/ab 2})))
      (is (= [:sym] (marks root {'ab 1} {'ab 2}))))
    (is (= 10 (count (trie/child-keys root))))
    (testing "unregister by an equal key built at runtime drops the node"
      (doseq [[p c] [[[(keyword "ab")] :kw] [[[:v "x1"]] :vec] [["ab"] :str] [[1] :one]
                     [["1"] :one-str] [[nil] :nil] [[true] :true] [[false] :false]
                     [[(keyword "ns" "ab")] :ns-kw] [[(symbol "ab")] :sym]]]
        (trie/unregister! root p c))
      (is (empty? (trie/child-keys root)))
      (is (zero? (.-refs root))))))

(deftest cell-sets-grow-and-shrink
  ;; cells are removed by identity: keep one object per cell
  (let [root (trie/node)
        [c1 c2 c3] [:c1 :c2 :c3]]
    (trie/register! root [:a] c1)
    (trie/register! root [:a] c2)
    (trie/register! root [:a] c3)
    (is (= [c1 c2 c3] (marks root {:a 1} {:a 2})))
    (trie/unregister! root [:a] c2)
    (is (= [c1 c3] (marks root {:a 1} {:a 2})))
    (trie/unregister! root [:a] c1)
    (is (= [c3] (marks root {:a 1} {:a 2})))
    (trie/unregister! root [:a] c3)
    (is (empty? (trie/child-keys root)))))

(deftest non-vector-paths
  (let [root (trie/node)]
    (trie/register! root (list :a 0) :l)
    (is (= [:l] (marks root {:a [1]} {:a [2]})))
    (trie/unregister! root (list :a 0) :l)
    (is (empty? (trie/child-keys root)))))

(defn- lcg [seed]
  (let [s (volatile! seed)]
    (fn [n] (vswap! s #(mod (+ (* % 1103515245) 12345) 2147483648)) (mod @s n))))

(deftest hash-map-diff-marks-exactly-the-changed-keys
  ;; many children under a changed hash map take the structural diff; the
  ;; marks must equal brute force: every registered key whose value is not identical
  (let [rnd (lcg 7)
        pool (vec (concat (range 300) (map str (range 100)) (map #(keyword (str "k" %)) (range 100))
                          (map (fn [i] [:v i]) (range 50)) [nil true false 1.5 "" :a/b]))
        pick #(nth pool (rnd (count pool)))
        root (trie/node)
        cells (into {} (map (fn [k] [k #js {:k k}])) pool)]
    (doseq [k pool] (trie/register! root [k] (cells k)))
    (dotimes [round 200]
      (let [size (if (odd? round) (+ 9 (rnd 8)) (rnd 400)) ; small: a bitmap root
            o (if (even? round)
                (into {} (map (fn [_] [(pick) (rnd 5)])) (range size))
                (reduce (fn [m _] (assoc m (pick) (rnd 5))) {} (range size)))
            val #(let [x (rnd 6)] (when (pos? x) x)) ; nil values too
            o (if (zero? (mod round 3)) (reduce (fn [m _] (assoc m (pick) (val))) o (range 20)) o)
            edit (fn [m assoc dissoc]
                   (reduce (fn [m _]
                             (case (rnd 4)
                               0 (dissoc m (pick))
                               1 (assoc m (pick) (val))
                               2 (assoc m (pick) [(rnd 3)]) ; = but never identical
                               3 m))
                           m (range (rnd 40))))
            v (if (zero? (mod round 4))
                (persistent! (edit (transient o) assoc! dissoc!))
                (edit o assoc dissoc))
            expected (set (keep (fn [k] (when-not (identical? (get o k) (get v k)) (cells k))) pool))
            seen (atom #{})]
        (trie/notify! root o v #(swap! seen conj %))
        (is (= expected @seen) (str "round " round))))))

(deftest hash-map-diff-sees-a-key-swap-with-an-identical-value
  ;; k1 -> 1 replaced by k2 -> 1 in the same bitmap slot (numbers hash to
  ;; themselves: k and k+32 share the root slot of a map of <= 16 entries):
  ;; the values are identical but the keys differ, so both must be marked
  (let [root (trie/node)
        ks (range 64)
        cells (into {} (map (fn [k] [k #js {:k k}])) ks)]
    (doseq [k ks] (trie/register! root [k] (cells k)))
    (doseq [k1 (range 1 11)]
      (let [k2 (+ k1 32)
            o (into {} (map (fn [k] [k 1])) (range 1 11))
            v (-> o (dissoc k1) (assoc k2 1))
            seen (atom #{})]
        (is (instance? PersistentHashMap o))
        (trie/notify! root o v #(swap! seen conj %))
        (is (= #{(cells k1) (cells k2)} @seen) (str k1 " -> " k2))))))
