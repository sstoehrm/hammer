(ns hammer.eq-test
  "is? equality subscriptions: trie index, defc detection, refresh fan-out."
  (:require [cljs.test :refer [deftest is testing use-fixtures]]
            [hammer.test-env]
            [hammer.state :as state]
            [hammer.trie :as trie]
            [hammer.cells :as cells]
            [hammer.events :as events]
            [hammer.scheduler :as sched]
            [hammer.dom :as dom]
            [hammer.testing :as t]
            [hammer.core :as h :refer [defc is?]])
  (:require-macros [hammer.macro-probe :refer [expand-error]]))

(use-fixtures :each {:before t/reset-app!})

(defn- marks [root old nu]
  (let [seen (atom [])]
    (trie/notify! root old nu #(swap! seen conj %))
    (set @seen)))

(defn- container []
  (let [el (.createElement js/document "div")]
    (.appendChild (.-body js/document) el)
    el))

(defn- runs
  "Calls f; returns how many instances the scheduler ran (refreshed) meanwhile."
  [f]
  (let [n (atom 0)
        prev (sched/runner)]
    (sched/set-runner! (fn [x] (swap! n inc) (prev x)))
    (try (f) (finally (sched/set-runner! prev)))
    @n))

(events/reg-event :put (fn [db k v] {:db (assoc db k v)}))

;; trie

(deftest trie-marks-only-old-and-new-value
  (let [root (trie/node)]
    (dotimes [i 100] (trie/register-eq! root [:sel] i [:c i]))
    (trie/register! root [:sel] :plain)
    (is (= #{:plain [:c 3]} (marks root {} {:sel 3})))
    (is (= #{:plain [:c 3] [:c 7]} (marks root {:sel 3} {:sel 7})))
    (is (= #{:plain [:c 7]} (marks root {:sel 7} {:sel 1000})))
    (is (= #{} (marks root {:sel 7 :x 1} {:sel 7 :x 2})))
    (testing "= but not identical values mark only plain cells"
      (trie/register-eq! root [:v] [1 2] :v12)
      (trie/register! root [:v] :vplain)
      (is (= #{:vplain} (marks root {:v [1 2]} {:v (list 1 2)}))))))

(deftest trie-keys-by-value-equality
  (let [root (trie/node)]
    (trie/register-eq! root [:m] (keyword (str "a" "b")) :kw)
    (trie/register-eq! root [:m] [:x (str "y" 1)] :vec)
    (trie/register-eq! root [:m] nil :nil)
    (trie/register-eq! root [:m] {:a 1} :map)
    (is (= #{:kw :nil} (marks root {} {:m :ab})))
    (is (= #{:kw :vec} (marks root {:m :ab} {:m [:x "y1"]})))
    (is (= #{:vec :map} (marks root {:m [:x "y1"]} {:m (hash-map :a 1)})))
    (is (= #{:map :nil} (marks root {:m {:a 1}} {:m nil})))))

(deftest trie-hash-collisions-stay-separate
  ;; nil and 0 both hash to 0; cells are removed by identity, so use one object
  (let [root (trie/node)
        nil-cell #js {:id "nil"}]
    (trie/register-eq! root [:m] 0 :zero)
    (trie/register-eq! root [:m] nil nil-cell)
    (trie/register-eq! root [:m] 5 :five)
    (is (= (hash 0) (hash nil)))
    (is (= #{:zero} (marks root {:m 0} {:m 5.5})))
    (is (= #{:five nil-cell} (marks root {:m nil} {:m 5})))
    (is (= #{:zero nil-cell} (marks root {:m nil} {:m 0})))
    (trie/unregister-eq! root [:m] nil nil-cell)
    (is (= #{:zero} (marks root {:m nil} {:m 0})))))

(deftest trie-unregister-eq-drops-nodes
  ;; cells are removed by identity: keep one object per cell
  (let [root (trie/node)
        [c1 c2 p nan] [:c1 :c2 :p :nan]]
    (trie/register-eq! root [:a :b] 1 c1)
    (trie/register-eq! root [:a :b] 1 c2)
    (trie/register! root [:a] p)
    (trie/unregister-eq! root [:a :b] 1 c1)
    (is (= #{p c2} (marks root {} {:a {:b 1}})))
    (trie/unregister-eq! root [:a :b] 1 c2)
    (is (nil? (get (.-children (get (.-children root) :a)) :b)))
    (trie/unregister! root [:a] p)
    (is (empty? (.-children root)))
    (is (zero? (.-refs root)))
    (testing "NaN never equals itself but still unregisters"
      (trie/register-eq! root [:n] js/NaN nan)
      (trie/unregister-eq! root [:n] js/NaN nan)
      (is (empty? (.-children root))))))

;; defc

(defc sel-row [id]
  [sel? (is? [:selected] id)
   cls  (when sel? "danger")]
  [:tr {:class cls} [:td id]])

(defc sel-table [] [ids [:ids]]
  [:table (for [id ids] ^{:key id} [sel-row id])])

(defc path-row [id]
  [sel [:selected]
   cls (when (= sel id) "danger")]
  [:tr {:class cls} [:td id]])

(defc path-table [] [ids [:ids]]
  [:table (for [id ids] ^{:key id} [path-row id])])

(defc qualified [k] [a (hammer.core/is? [:m] k) b (h/is? [:m] :z)] [:i (str a b)])

(deftest defc-detects-is?
  (is (= [:eq :expr] (map #(.-kind ^cells/Spec %) (.-specs sel-row))))
  (is (= [[0] [1]] (map #(vec (.-deps ^cells/Spec %)) (.-specs sel-row))))
  (is (= [:eq :eq] (map #(.-kind ^cells/Spec %) (.-specs qualified))))
  (is (= "defc: is? takes a path and a value"
         (expand-error (hammer.core/defc bad [] [a (is? [:x])] nil))))
  (is (thrown? js/Error (is? [:x] 1))))

(defn- classes [el]
  (vec (keep-indexed (fn [i ^js tr] (when (= "danger" (.-className tr)) i))
                     (js/Array.from (.querySelectorAll el "tr")))))

(deftest selecting-among-1000-refreshes-only-flipped-rows
  (let [ids (vec (range 1000))
        el (container)]
    (dom/mount! [sel-table] el)
    (events/dispatch-sync [:put :ids ids])
    (t/reset-renders! sel-row sel-table)
    (is (= 1 (runs #(events/dispatch-sync [:put :selected 5]))))
    (is (= 1 (t/renders sel-row)))
    (is (= [5] (classes el)))
    (t/reset-renders! sel-row)
    (is (= 2 (runs #(events/dispatch-sync [:put :selected 7]))))
    (is (= 2 (t/renders sel-row)))
    (is (= [7] (classes el)))
    (is (= 1 (runs #(events/dispatch-sync [:put :selected nil]))))
    (is (= [] (classes el)))
    (is (= 0 (t/renders sel-table)))
    (testing "a plain path binding refreshes every row (the fan-out is? avoids)"
      (t/reset-app!)
      (let [el (container)]
        (dom/mount! [path-table] el)
        (events/dispatch-sync [:put :ids ids])
        (is (= 1000 (runs #(events/dispatch-sync [:put :selected 5]))))
        (is (= [5] (classes el)))))))

(defc cmp [v] [on? (is? [:mode] v)] [:b (str on?)])
(defc cmp-parent [] [v [:want]] [:p [cmp v]])

(deftest compared-value-follows-props
  (let [el (container)]
    (h/mount! [cmp-parent] el {:mode :a :want :a})
    (is (= "<p><b>true</b></p>" (.-innerHTML el)))
    (events/dispatch-sync [:put :want (keyword (str "b"))])
    (is (= "<p><b>false</b></p>" (.-innerHTML el)))
    (events/dispatch-sync [:put :mode (keyword "b")])
    (is (= "<p><b>true</b></p>" (.-innerHTML el)))
    (testing "the old value's registration is gone"
      (t/reset-renders! cmp)
      (is (= 1 (runs #(events/dispatch-sync [:put :mode :a]))))
      (is (= "<p><b>false</b></p>" (.-innerHTML el)))
      (is (= 0 (runs #(events/dispatch-sync [:put :mode :c])))))))

(defc vec-cmp [a b] [on? (is? [:pair] [a b])] [:b (str on?)])
(defc nil-cmp [] [on? (is? [:missing] nil)] [:b (str on?)])

(deftest vector-and-nil-values
  (let [el (container)]
    (h/mount! [:div [vec-cmp 1 "x"] [nil-cmp]] el {:pair (list 1 "x")})
    (is (= "<div><b>true</b><b>true</b></div>" (.-innerHTML el)))
    (events/dispatch-sync [:put :pair [1 (str "y")]])
    (events/dispatch-sync [:put :missing 0])
    (is (= "<div><b>false</b><b>false</b></div>" (.-innerHTML el)))
    (events/dispatch-sync [:put :pair [1 "x"]])
    (events/dispatch-sync [:put :missing nil])
    (is (= "<div><b>true</b><b>true</b></div>" (.-innerHTML el)))))

(defc path-cmp [k] [on? (is? [:sel k] 1)] [:b (str on?)])
(defc path-parent [] [k [:k]] [:p [path-cmp k]])

(deftest path-change-re-registers
  (let [el (container)]
    (h/mount! [path-parent] el {:k :a :sel {:a 1 :b 2}})
    (is (= "<p><b>true</b></p>" (.-innerHTML el)))
    (events/dispatch-sync [:put :k :b])
    (is (= "<p><b>false</b></p>" (.-innerHTML el)))
    (is (= 0 (runs #(events/dispatch-sync [:put :sel {:a 5 :b 2}]))))
    (is (nil? (get (.-children (get (.-children state/paths) :sel)) :a)))
    (events/dispatch-sync [:put :sel {:a 5 :b 1}])
    (is (= "<p><b>true</b></p>" (.-innerHTML el)))))

(defc maybe [] [show [:show]] [:div (when show [sel-row 3])])

(deftest unmount-unregisters
  (let [el (container)]
    (h/mount! [maybe] el {:show true :selected 3})
    (is (= 1 (count (classes el))))
    (events/dispatch-sync [:put :show false])
    (is (nil? (get (.-children state/paths) :selected)))
    (is (= 0 (runs #(events/dispatch-sync [:put :selected 4]))))
    (t/reset-app!)
    (is (empty? (.-children state/paths)))
    (is (zero? (.-refs state/paths)))))
