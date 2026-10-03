(ns hammer.dom-update-test
  (:require [cljs.test :refer [deftest is use-fixtures]]
            [hammer.test-env]
            [hammer.state :as state]
            [hammer.events :as events]
            [hammer.dom :as dom]
            [hammer.testing :as t]
            [hammer.core :refer [defc]]
            [hammer.test-util :refer [capture-errors capture-warnings]]))

(use-fixtures :each {:before t/reset-app!})

(defn- container []
  (let [el (.createElement js/document "div")]
    (.appendChild (.-body js/document) el)
    el))

(defn- kids [el] (vec (js/Array.from (.-children el))))

(events/reg-event :set (fn [db k v] {:db (assoc db k v)}))
(events/reg-event :retitle (fn [db id s] {:db (assoc-in db [:todos id :title] s)}))

(defc item [id] [todo [:todos id]] [:li (:title todo)])
(defc items [] [ids [:ids]] [:ul (for [id ids] ^{:key id} [item id])])

(deftest db-change-patches-only-dirty-component
  (reset! state/app-db {:ids [1 2] :todos {1 {:title "a"} 2 {:title "b"}}})
  (let [el (container)]
    (dom/mount! [items] el)
    (let [li2 (.. el -firstChild -lastChild)]
      (t/reset-renders! item items)
      (events/dispatch-sync [:retitle 2 "B"])
      (is (= "<ul><li>a</li><li>B</li></ul>" (.-innerHTML el)))
      (is (identical? li2 (.. el -firstChild -lastChild)))
      (is (= 1 (t/renders item)))
      (is (= 0 (t/renders items))))))

(deftest keyed-children-move-not-recreate
  (reset! state/app-db {:ids [1 2 3] :todos {1 {:title "a"} 2 {:title "b"} 3 {:title "c"}}})
  (let [el (container)]
    (dom/mount! [items] el)
    (let [[a _ c] (kids (.-firstChild el))]
      (t/reset-renders! item)
      (events/dispatch-sync [:set :ids [3 1]])
      (is (= [c a] (kids (.-firstChild el))))
      (is (= "<ul><li>c</li><li>a</li></ul>" (.-innerHTML el)))
      (is (= 0 (t/renders item))))))

(defc kitem [k] [] [:li (pr-str k)])
(defc kitems [] [ks [:ks]] [:ul (for [k ks] ^{:key k} [kitem k])])

(deftest keyed-diff-keeps-value-semantics-for-keys
  (let [el (container)
        warns (atom 0)
        orig js/console.warn]
    (set! js/console.warn (fn [& _] (swap! warns inc)))
    (try
      (reset! state/app-db {:ks [1 "1" 2]})
      (dom/mount! [kitems] el)
      (let [[one one-s two] (kids (.-firstChild el))]
        (is (= 0 @warns) "1 and \"1\" are different keys")
        (events/dispatch-sync [:set :ks ["1" 2 1.0]])
        (is (= [one-s two one] (kids (.-firstChild el))) "moved, not recreated"))
      (events/dispatch-sync [:set :ks [:a (keyword "b") 3]])
      (let [[a b three] (kids (.-firstChild el))]
        (events/dispatch-sync [:set :ks [3 (keyword "a") :b]])
        (is (= [three a b] (kids (.-firstChild el))) "runtime keywords match literal ones")
        (is (= "<ul><li>3</li><li>:a</li><li>:b</li></ul>" (.-innerHTML el))))
      (events/dispatch-sync [:set :ks [[:v 1] "x"]])
      (let [[v x] (kids (.-firstChild el))]
        (events/dispatch-sync [:set :ks ["x" [:v (inc 0)]]])
        (is (= [x v] (kids (.-firstChild el))) "vector keys by value"))
      (is (= 0 @warns))
      (events/dispatch-sync [:set :ks [1 1.0]])
      (is (= 1 @warns) "1 and 1.0 are duplicates")
      (finally (set! js/console.warn orig)))))

(defc label [s] [] [:span s])
(defc pair [] [a [:a] b [:b]] [:div [label a] [label b]])

(deftest child-rerenders-only-when-props-change
  (reset! state/app-db {:a "x" :b "y"})
  (let [el (container)]
    (dom/mount! [pair] el)
    (t/reset-renders! label pair)
    (events/dispatch-sync [:set :a "X"])
    (is (= "<div><span>X</span><span>y</span></div>" (.-innerHTML el)))
    (is (= 1 (t/renders pair)))
    (is (= 1 (t/renders label)))))

(defc counter [] [n (atom 0)] [:button {:on-click #(swap! n inc)} @n])

(deftest local-atom-rerenders
  (let [el (container)]
    (dom/mount! [counter] el)
    (.click (.-firstChild el))
    (t/flush!)
    (is (= "<button>1</button>" (.-innerHTML el)))))

(events/reg-event :type (fn [db s] {:db (assoc db :text s)}))
(defc field [] [text [:text]]
  [:input {:value text :on-input #(events/dispatch-sync [:type (.. % -target -value)])}])

(deftest controlled-input-keeps-node-focus-and-cursor
  (reset! state/app-db {:text "ab"})
  (let [el (container)]
    (dom/mount! [field] el)
    (let [i (.-firstChild el)
          E (.-Event js/window)]
      (.focus i)
      (set! (.-value i) "abc")
      (.setSelectionRange i 1 1)
      (.dispatchEvent i (E. "input" #js {:bubbles true}))
      (is (= "abc" (:text @state/app-db)))
      (is (identical? i (.-firstChild el)))
      (is (identical? i (.-activeElement js/document)))
      (is (= 1 (.-selectionStart i))))))

(def ref-log (atom []))
(defc toggled [] [on [:on]]
  (if on
    [:span {:ref #(swap! ref-log conj (some-> % .-tagName))} [item 1]]
    [:b "off"]))

(deftest replacing-a-branch-unmounts-it
  (reset! state/app-db {:on true :todos {1 {:title "a"}}})
  (reset! ref-log [])
  (let [el (container)
        before (.-refs state/paths)]
    (dom/mount! [toggled] el)
    (is (= (+ before 2) (.-refs state/paths)))
    (events/dispatch-sync [:set :on false])
    (is (= "<b>off</b>" (.-innerHTML el)))
    (is (= ["SPAN" nil] @ref-log))
    (is (= (+ before 1) (.-refs state/paths)))))

(defc fragile [] [n [:n]] (if (= n 2) (throw (js/Error. "nope")) [:p n]))

(deftest render-error-keeps-previous-dom
  (reset! state/app-db {:n 1})
  (let [el (container)
        _ (dom/mount! [fragile] el)
        logs (capture-errors (fn [_] (events/dispatch-sync [:set :n 2])))]
    (is (= "<p>1</p>" (.-innerHTML el)))
    (is (= ["hammer: render failed in fragile"] (mapv first logs)))
    (events/dispatch-sync [:set :n 3])
    (is (= "<p>3</p>" (.-innerHTML el)))))

(defc maybe [] [on [:on]] (when on [:em "yes"]))

(deftest nil-body-and-back
  (reset! state/app-db {:on false})
  (let [el (container)]
    (dom/mount! [maybe] el)
    (is (= "" (.-innerHTML el)))
    (events/dispatch-sync [:set :on true])
    (is (= "<em>yes</em>" (.-innerHTML el)))
    (events/dispatch-sync [:set :on false])
    (is (= "" (.-innerHTML el)))))

(defc dup [] [xs [:xs]] [:ul (for [x xs] ^{:key x} [:li x])])

(deftest duplicate-keys-fall-back-to-index-diff
  (reset! state/app-db {:xs [1 1 2]})
  (let [el (container)
        warns (atom 0)
        orig js/console.warn]
    (set! js/console.warn (fn [& _] (swap! warns inc)))
    (try
      (dom/mount! [dup] el)
      (events/dispatch-sync [:set :xs [2]])
      (finally (set! js/console.warn orig)))
    (is (= "<ul><li>2</li></ul>" (.-innerHTML el)))
    (is (pos? @warns))))

(defc idlist [] [ids [:ids]] [:ul (for [id ids] ^{:key id} [:li [:input {:id (str "in" id)}]])])

(deftest keyed-removal-preserves-focus-in-remaining-rows
  (reset! state/app-db {:ids [1 2 3]})
  (let [el (container)]
    (dom/mount! [idlist] el)
    (let [lis (kids (.-firstChild el))
          li2 (nth lis 1)
          li3 (nth lis 2)
          in3 (.-firstChild li3)]
      (.focus in3)
      (events/dispatch-sync [:set :ids [2 3]])
      (is (identical? in3 (.-activeElement js/document)))
      (let [lis2 (kids (.-firstChild el))]
        (is (identical? li2 (first lis2)))
        (is (identical? li3 (second lis2)))))))

(defc reffed [id] [todo [:todos id]]
  [:li {:ref #(.focus %)} (:title todo)])
(defc reffed-list [] [ids [:ids]] [:ul (for [id ids] ^{:key id} [reffed id])])

(deftest ref-throw-during-unmount-does-not-abort-keyed-removal
  (reset! state/app-db {:ids [1 2 3]
                        :todos {1 {:title "a"} 2 {:title "b"} 3 {:title "c"}}})
  (let [el (container)
        before (.-refs state/paths)
        logs (atom [])
        orig js/console.error]
    (dom/mount! [reffed-list] el)
    (let [after-mount (.-refs state/paths)]
      (set! js/console.error (fn [& a] (swap! logs conj (first a))))
      (try
        (events/dispatch-sync [:set :ids [3]])
        (is (= "<ul><li>c</li></ul>" (.-innerHTML el)))
        (is (= (- after-mount 2) (.-refs state/paths)))
        (is (= ["hammer: :ref failed" "hammer: :ref failed"] @logs))
        (t/reset-app!)
        (finally (set! js/console.error orig)))
      (is (= before (.-refs state/paths))))))

(events/reg-event :toggle-and-bump (fn [db] {:db (-> db (update :on not) (update :n inc))}))

(defc thrower [] [on [:on]]
  (if on
    [:span {:ref (fn [el] (when (nil? el) (throw (js/Error. "boom"))))}]
    [:i "gone"]))

(defc leaf [] [n [:n]] [:span (str n)])
(defc wrapper [] [] [:div [leaf]])
(defc root2 [] [] [:div [thrower] [wrapper]])

(deftest flush-isolates-instance-errors
  (reset! state/app-db {:on true :n 1})
  (let [el (container)
        logs (atom [])
        orig js/console.error]
    (dom/mount! [root2] el)
    (set! js/console.error (fn [& a] (swap! logs conj (first a))))
    (try
      (events/dispatch-sync [:toggle-and-bump])
      (finally (set! js/console.error orig)))
    (is (= "<div><i>gone</i><div><span>2</span></div></div>" (.-innerHTML el)))
    (is (= ["hammer: :ref failed"] @logs))))

(defc ok-child [id] [] [:i (str "ok" id)])
(defc bad-child [id] [x (throw (js/Error. "boom"))] [:b (str "bad" id)])
(defc two-kids [] [flag [:flag]] [:div {:data-flag (str flag)} [ok-child 1] [bad-child 2]])

(deftest child-binding-init-throw-is-isolated
  (reset! state/app-db {:flag false})
  (let [el (container)
        logs (capture-errors (fn [_] (dom/mount! [two-kids] el)))]
    (is (= "<div data-flag=\"false\"><i>ok1</i><b>bad2</b></div>" (.-innerHTML el)))
    (is (= ["hammer: render failed in bad-child"] (mapv first logs)))
    (events/dispatch-sync [:set :flag true])
    (is (= "<div data-flag=\"true\"><i>ok1</i><b>bad2</b></div>" (.-innerHTML el)))))

(defc good-root [] [n [:n]] [:p (str n)])
(defc bad-root [] [x (throw (js/Error. "root boom"))] [:p (str x)])

(deftest mount-good-bad-good-keeps-refs-consistent
  (reset! state/app-db {:n 1})
  (let [el (container)
        logs (atom [])
        orig js/console.error]
    (dom/mount! [good-root] el)
    (let [after-good (.-refs state/paths)]
      (set! js/console.error (fn [& a] (swap! logs conj (first a))))
      (try
        (dom/mount! [bad-root] el)
        (dom/mount! [good-root] el)
        (finally (set! js/console.error orig)))
      (is (= after-good (.-refs state/paths)))
      (is (= "<p>1</p>" (.-innerHTML el)))
      (is (pos? (count @logs))))))

(defc pick [id] [sel [:selected] cls (when (= sel id) "on")] [:li {:class cls} id])
(defc picks [] [] [:ul [pick 1] [pick 2] [pick 3]])

(deftest intermediate-only-change-does-not-render
  (reset! state/app-db {:selected 0})
  (let [el (container)]
    (dom/mount! [picks] el)
    (t/reset-renders! pick)
    (events/dispatch-sync [:set :selected 9])
    (is (= 0 (t/renders pick)))
    (events/dispatch-sync [:set :selected 2])
    (is (= 1 (t/renders pick)))
    (events/dispatch-sync [:set :selected 1])
    (is (= 3 (t/renders pick)))
    (is (= "<ul><li class=\"on\">1</li><li>2</li><li>3</li></ul>" (.-innerHTML el)))))

(defc quiet [_a b] [] [:span b])
(defc loud [] [a [:a]] [:div [quiet a "b"]])

(deftest unreferenced-prop-change-does-not-render
  (reset! state/app-db {:a 1})
  (let [el (container)]
    (dom/mount! [loud] el)
    (t/reset-renders! quiet loud)
    (events/dispatch-sync [:set :a 2])
    (is (= 1 (t/renders loud)))
    (is (= 0 (t/renders quiet)))))

(def row-log (atom []))
(defc row [id] [todo [:todos id]]
  [:li {:ref #(swap! row-log conj (when % [(.-textContent %) (.-isConnected %)]))} (:title todo)])
(defc rows [] [ids [:ids]] [:ul (for [id ids] ^{:key id} [row id])])

(defn- todos [& ids] (into {} (map (fn [i] [i {:title (str "t" i)}])) ids))

(deftest clear-to-empty-unmounts-every-row
  (reset! state/app-db {:ids [1 2 3] :todos (todos 1 2 3)})
  (reset! row-log [])
  (let [el (container)
        before (.-refs state/paths)]
    (dom/mount! [rows] el)
    (is (= [["t1" true] ["t2" true] ["t3" true]] @row-log))
    (is (= (+ before 4) (.-refs state/paths)))
    (reset! row-log [])
    (t/reset-renders! row)
    (events/dispatch-sync [:set :ids []])
    (is (= "<ul></ul>" (.-innerHTML el)))
    (is (= [nil nil nil] @row-log))
    (is (= (+ before 1) (.-refs state/paths)))
    (events/dispatch-sync [:retitle 1 "z"])
    (is (= 0 (t/renders row)))
    (events/dispatch-sync [:set :ids [2]])
    (is (= "<ul><li>t2</li></ul>" (.-innerHTML el)))))

(deftest clear-to-empty-index-diff
  (reset! state/app-db {:xs [1 2 3]})
  (let [el (container)]
    (dom/mount! [dup] el)
    (events/dispatch-sync [:set :xs []])
    (is (= "<ul></ul>" (.-innerHTML el)))
    (events/dispatch-sync [:set :xs [4 5]])
    (is (= "<ul><li>4</li><li>5</li></ul>" (.-innerHTML el)))))

(deftest full-keyed-replace-recreates-all-rows
  (reset! state/app-db {:ids [1 2 3] :todos (todos 1 2 3 4 5 6 7)})
  (reset! row-log [])
  (let [el (container)
        before (.-refs state/paths)]
    (dom/mount! [rows] el)
    (let [old-lis (kids (.-firstChild el))]
      (reset! row-log [])
      (t/reset-renders! row)
      (events/dispatch-sync [:set :ids [4 5 6 7]])
      (is (= "<ul><li>t4</li><li>t5</li><li>t6</li><li>t7</li></ul>" (.-innerHTML el)))
      (is (= [nil nil nil ["t4" true] ["t5" true] ["t6" true] ["t7" true]] @row-log))
      (is (= 4 (t/renders row)))
      (is (= (+ before 5) (.-refs state/paths)))
      (is (not-any? (set old-lis) (kids (.-firstChild el))))
      (t/reset-renders! row)
      (events/dispatch-sync [:retitle 1 "z"])
      (is (= 0 (t/renders row)))
      (events/dispatch-sync [:retitle 5 "five"])
      (is (= 1 (t/renders row)))
      (is (= "<ul><li>t4</li><li>five</li><li>t6</li><li>t7</li></ul>" (.-innerHTML el))))))

(deftest keyed-append-tail-keeps-existing-rows
  (reset! state/app-db {:ids [1 2] :todos (todos 1 2 3 4 5)})
  (reset! row-log [])
  (let [el (container)]
    (dom/mount! [rows] el)
    (let [[a b] (kids (.-firstChild el))]
      (reset! row-log [])
      (t/reset-renders! row)
      (events/dispatch-sync [:set :ids [2 1 3 4 5]])
      (is (= "<ul><li>t2</li><li>t1</li><li>t3</li><li>t4</li><li>t5</li></ul>" (.-innerHTML el)))
      (is (= [b a] (take 2 (kids (.-firstChild el)))))
      (is (= 3 (t/renders row)))
      (is (= [["t3" true] ["t4" true] ["t5" true]] @row-log)))))

(deftest indexed-append-tail-from-empty
  (reset! state/app-db {:xs []})
  (let [el (container)]
    (dom/mount! [dup] el)
    (is (= "<ul></ul>" (.-innerHTML el)))
    (events/dispatch-sync [:set :xs [1 2 3]])
    (is (= "<ul><li>1</li><li>2</li><li>3</li></ul>" (.-innerHTML el)))
    (let [[l1] (kids (.-firstChild el))]
      (events/dispatch-sync [:set :xs [1 2 3 4]])
      (is (identical? l1 (first (kids (.-firstChild el)))))
      (is (= "<ul><li>1</li><li>2</li><li>3</li><li>4</li></ul>" (.-innerHTML el))))))

;; ---- keyed reconciliation (prefix/suffix + LIS)

(defc klist [] [xs [:xs]] [:ul (for [x xs] ^{:key x} [:li (str x)])])

(defn- count-moves!
  "Counts insertBefore calls on el; returns the counter atom."
  [^js el]
  (let [n (atom 0)
        orig (.-insertBefore el)]
    (set! (.-insertBefore el) (fn [a b] (swap! n inc) (.call orig el a b)))
    n))

(defn- texts [el] (mapv #(.-textContent %) (kids el)))

(defn- keyed-step!
  "Dispatches :xs → xs on a mounted klist ul; checks order and that every
  surviving key kept its node. Returns the number of insertBefore calls."
  [ul xs]
  (let [before (zipmap (texts ul) (kids ul))
        moves (count-moves! ul)]
    (events/dispatch-sync [:set :xs xs])
    (js-delete ul "insertBefore")
    (is (= (mapv str xs) (texts ul)))
    (doseq [[k node] (map vector (texts ul) (kids ul))
            :when (before k)]
      (is (identical? (before k) node) (str "key " k " kept its node")))
    @moves))

(defn- mount-klist [xs]
  (reset! state/app-db {:xs xs})
  (let [el (container)]
    (dom/mount! [klist] el)
    (.-firstChild el)))

(deftest keyed-swap-moves-two-nodes
  (let [xs (vec (range 1000))
        ys (assoc xs 1 998 998 1)
        ul (mount-klist xs)]
    (is (= 2 (keyed-step! ul ys)))
    (is (= 1 (keyed-step! ul (assoc ys 2 3 3 2))))
    (is (= 1 (keyed-step! (mount-klist [:a :b]) [:b :a])))))

(deftest keyed-reverse
  (let [ul (mount-klist [1 2 3 4 5 6])]
    (is (= 5 (keyed-step! ul [6 5 4 3 2 1])))))

(deftest keyed-prefix-suffix-insert-remove
  (let [ul (mount-klist [1 2 3 4 5])]
    (is (= 1 (keyed-step! ul [1 2 9 3 4 5])))
    (is (= 0 (keyed-step! ul [1 2 3 4 5])))
    (is (= 1 (keyed-step! ul [0 1 2 3 4 5])))
    (is (= 1 (keyed-step! ul [0 1 2 3 4 5 6])))
    (is (= 0 (keyed-step! ul [1 2 3 4 5])))
    (is (= 0 (keyed-step! ul [3])))
    (is (= 2 (keyed-step! ul [1 3 5])))
    (is (= 0 (keyed-step! ul [])))
    (is (= 1 (keyed-step! ul [7 8])) "one fragment insert")))

(deftest keyed-vector-keys-use-value-equality
  (let [ul (mount-klist [[:a 1] [:b 2] [:c 3]])]
    (is (= 1 (keyed-step! ul [[:c 3] [:a 1] [:b 2]])))))

(deftest keyed-component-rows-move-with-component-anchors
  (reset! state/app-db {:ids [1 2 3 4] :todos (into {} (for [i [1 2 3 4 5]] [i {:title (str i)}]))})
  (let [el (container)]
    (dom/mount! [items] el)
    (let [ul (.-firstChild el)
          [a b c d] (kids ul)
          moves (count-moves! ul)]
      (t/reset-renders! item)
      (events/dispatch-sync [:set :ids [4 5 2 3 1]])
      (is (= "<ul><li>4</li><li>5</li><li>2</li><li>3</li><li>1</li></ul>" (.-innerHTML el)))
      (is (= [d b c a] (keep (set [a b c d]) (kids ul))))
      (is (= 1 (t/renders item)))
      (is (= 3 @moves)))))

(defc tlist [] [xs [:xs]] [:ul (for [[k tag] xs] ^{:key k} [tag (str k)])])

(deftest keyed-tag-change-under-same-key-while-moving
  (reset! state/app-db {:xs [[1 :li] [2 :li] [3 :li]]})
  (let [el (container)]
    (dom/mount! [tlist] el)
    (events/dispatch-sync [:set :xs [[3 :li] [2 :p] [1 :li]]])
    (is (= "<ul><li>3</li><p>2</p><li>1</li></ul>" (.-innerHTML el)))))

(deftest keyed-random-shuffles-inserts-removals
  (let [seed (atom 42)
        rnd (fn [n] (swap! seed #(mod (+ (* % 1103515245) 12345) 2147483648)) (mod (quot @seed 65536) n))
        shuffle* (fn [xs] (reduce (fn [v i] (let [j (rnd (inc i))] (assoc v i (v j) j (v i))))
                                  (vec xs) (range (dec (count xs)) 0 -1)))
        ul (mount-klist [])]
    (dotimes [_ 200]
      (let [xs (->> (range 20) (filter (fn [_] (< (rnd 10) 6))) shuffle*)]
        (keyed-step! ul xs)))))

;; ---- a list with keyed and unkeyed items warns (it falls back to index diff)

(defc mixed-keys [] [ks [:ks]]
  [:ul (for [k ks] (if (= k 2) [:li k] ^{:key k} [:li k]))])

(defc head-and-keyed [] [ks [:ks]]
  [:ul [:li "head"] (for [k ks] ^{:key k} [:li k])])

(deftest partly-keyed-list-warns
  (reset! state/app-db {:ks [1 2 3]})
  (let [el (container)
        _ (dom/mount! [mixed-keys] el)
        warns (capture-warnings (fn [_] (events/dispatch-sync [:set :ks [3 2 1]])))]
    (is (= ["hammer: some list items have no key, falling back to index diff"] (mapv first warns)))
    (is (= "<ul><li>3</li><li>2</li><li>1</li></ul>" (.-innerHTML el)))))

(deftest partly-keyed-list-warns-when-the-first-item-has-no-key
  (reset! state/app-db {:ks [2 1 3]})
  (let [el (container)
        _ (dom/mount! [mixed-keys] el)
        warns (capture-warnings (fn [_] (events/dispatch-sync [:set :ks [2 3 1]])))]
    (is (= 1 (count warns)))))

(deftest static-head-before-a-keyed-for-does-not-warn
  (reset! state/app-db {:ks [1 2]})
  (let [el (container)
        _ (dom/mount! [head-and-keyed] el)
        warns (capture-warnings (fn [_] (events/dispatch-sync [:set :ks [2 1]])))]
    (is (= [] warns) "the for is its own list in the compiled template")
    (is (= "<ul><li>head</li><li>2</li><li>1</li></ul>" (.-innerHTML el)))))

(defc plain-list [] [ks [:ks]] [:ul (for [k ks] [:li k])])

(deftest unkeyed-list-stays-silent
  (reset! state/app-db {:ks [1 2]})
  (let [el (container)
        _ (dom/mount! [plain-list] el)
        warns (capture-warnings (fn [_] (events/dispatch-sync [:set :ks [2 1 3]])))]
    (is (= [] warns))
    (is (= "<ul><li>2</li><li>1</li><li>3</li></ul>" (.-innerHTML el)))))
