(ns hammer.dom-update-test
  (:require [cljs.test :refer [deftest is use-fixtures]]
            [hammer.test-env]
            [hammer.state :as state]
            [hammer.events :as events]
            [hammer.dom :as dom]
            [hammer.testing :as t]
            [hammer.core :refer [defc]]))

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
        logs (atom [])
        orig js/console.error]
    (dom/mount! [fragile] el)
    (set! js/console.error (fn [& a] (swap! logs conj (vec (take 2 a)))))
    (try (events/dispatch-sync [:set :n 2])
         (finally (set! js/console.error orig)))
    (is (= "<p>1</p>" (.-innerHTML el)))
    (is (= [["hammer: render failed in" "fragile"]] @logs))
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
        logs (atom [])
        orig js/console.error]
    (set! js/console.error (fn [& a] (swap! logs conj (vec (take 2 a)))))
    (try
      (dom/mount! [two-kids] el)
      (finally (set! js/console.error orig)))
    (is (= "<div data-flag=\"false\"><i>ok1</i><b>bad2</b></div>" (.-innerHTML el)))
    (is (= [["hammer: render failed in" "bad-child"]] @logs))
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
