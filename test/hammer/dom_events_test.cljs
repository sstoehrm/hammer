(ns hammer.dom-events-test
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

(defn- click! [el]
  (.dispatchEvent el (new (.-MouseEvent js/window) "click" #js {:bubbles true})))

(deftest delegated-click-reaches-nested-handler
  (let [el (container)
        got (atom nil)]
    (events/reg-event :hit (fn [db x] {:db (assoc db :hit x)}))
    (dom/mount! [:div [:ul [:li [:button#b {:on-click [:hit 7]} [:span#s "x"]]]]
                 [:i#f {:on-click #(reset! got (.-id (.-target %)))}]]
                el)
    (click! (.querySelector el "#s"))
    (click! (.querySelector el "#f"))
    (t/flush!)
    (is (= 7 (:hit @state/app-db)))
    (is (= "f" @got))
    (is (= #{"click"} (set (.from js/Array (.-__cuiT el)))))
    (is (nil? (.-__cuiT (.querySelector el "#b"))))))

(deftest bubbles-target-first-and-stop-propagation-halts
  (let [el (container)
        log (atom [])
        h (fn [k] (fn [_] (swap! log conj k)))]
    (dom/mount! [:div {:on-click (h :outer)}
                 [:p {:on-click (h :mid)}
                  [:b#t {:on-click (h :target)}]
                  [:b#s {:on-click (fn [e] (swap! log conj :stop) (.stopPropagation e))}]]]
                el)
    (click! (.querySelector el "#t"))
    (is (= [:target :mid :outer] @log))
    (reset! log [])
    (click! (.querySelector el "#s"))
    (is (= [:stop] @log))))

(deftest non-bubbling-blur-runs-only-on-target
  (let [el (container)
        log (atom [])]
    (dom/mount! [:div {:on-blur #(swap! log conj :div)}
                 [:input#i {:on-blur #(swap! log conj (.-type %))}]]
                el)
    (.dispatchEvent (.querySelector el "#i") (new (.-FocusEvent js/window) "blur"))
    (is (= ["blur"] @log))))

(defc toggler [] [on? [:on?]]
  [:div [:button#b {:on-click (when on? [:clicked])} "b"]])

(deftest handler-added-by-patch-and-removed-when-nil
  (events/reg-event :clicked (fn [db] {:db (update db :n (fnil inc 0))}))
  (reset! state/app-db {:on? false})
  (let [el (container)
        b #(.querySelector el "#b")]
    (dom/mount! [toggler] el)
    (is (= 0 (.-size (.-__cuiT el))))
    (events/set-db! (assoc @state/app-db :on? true))
    (t/flush!)
    (click! (b))
    (t/flush!)
    (is (= 1 (:n @state/app-db)))
    (events/set-db! (assoc @state/app-db :on? false))
    (t/flush!)
    (click! (b))
    (t/flush!)
    (is (= 1 (:n @state/app-db)))))

(deftest separate-roots-work-independently
  (let [a (container)
        b (container)
        log (atom [])]
    (dom/mount! [:div {:on-click #(swap! log conj :a)} [:span#a "a"]] a)
    (dom/mount! [:div {:on-click #(swap! log conj :b-click)
                       :on-keydown #(swap! log conj :b-key)}
                 [:span#b "b"]]
                b)
    (click! (.querySelector b "#b"))
    (.dispatchEvent (.querySelector b "#b")
                    (new (.-KeyboardEvent js/window) "keydown" #js {:bubbles true}))
    (is (= [:b-click :b-key] @log))
    (reset! log [])
    (click! (.querySelector a "#a"))
    (is (= [:a] @log))
    (is (= #{"click"} (set (.from js/Array (.-__cuiT a)))))))

(deftest fn-handlers-get-their-element
  (let [el (container)
        got (atom [])]
    (dom/mount! [:div [:button#b {:on-click (fn [e owner] (swap! got conj [(.-id (.-target e)) (.-id owner)]))}
                       [:span#s "x"]]
                 [:i#one {:on-click #(swap! got conj [:one-arg (.-id (.-target %))])}]]
                el)
    (click! (.querySelector el "#s"))
    (click! (.querySelector el "#one"))
    (is (= [["s" "b"] [:one-arg "one"]] @got)
        "the second arg is the element the handler is on; one-arg fns still work")))

(deftest non-fn-non-vector-handlers-warn
  (let [el (container)
        warns (hammer.testing/expect-errors
               #(dom/mount! [:div [:button {:on-click :save}] [:i {:on-input #{:x}}]
                             [:b {:on-click [:ok]}] [:u {:on-click (fn [_])}] [:s {:on-click nil}]
                             [:q {:on-click false}]]
                            el))]
    (is (= ["hammer: :on-click must be an event vector or a fn, got :save (wrap a multimethod or other callable in #(...))"
            "hammer: :on-input must be an event vector or a fn, got #{:x} (wrap a multimethod or other callable in #(...))"]
           (mapv :message warns))
        "nil and false mean no handler")))
