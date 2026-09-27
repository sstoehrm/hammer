(ns hammer.host-test
  (:require [cljs.test :refer [deftest is use-fixtures]]
            [hammer.test-env]
            [hammer.cells :as cells]
            [hammer.core :refer [defc]]
            [hammer.dom :as dom]
            [hammer.events :as events]
            [hammer.state :as state]
            [hammer.test-util :refer [capture-errors]]
            [hammer.testing :as t]))

(def log (atom []))

(def fake
  "A hosted component with one prop and one path binding [:v]."
  (cells/component
   "fake" 1 [{:kind :path :deps [] :f (fn [] [:v])}] [0 1] (fn [p v] [p v])
   (cells/Host.
    (fn [^cells/Instance inst] (set! (.-dirty inst) false) (cells/refresh! inst)
      (swap! log conj [:run (vec (.-vals inst))]))
    (fn [^cells/Instance inst _render _el]
      (swap! log conj [:create (vec (.-vals inst))])
      (let [n (js/document.createElement "i")]
        (set! (.-textContent n) (str (aget (.-vals inst) 0)))
        n))
    (fn [^cells/Instance inst] (swap! log conj [:destroy (aget (.-vals inst) 0)]) (cells/destroy! inst)))))

(events/reg-event ::set (fn [db k v] {:db (assoc db k v)}))
(events/reg-event ::set-ids-and-v (fn [db ids v] {:db (assoc db :ids ids :v v)}))
(events/reg-event ::set-ids-and-label (fn [db ids label] {:db (assoc db :ids ids :label label)}))

(defc holder [] [ids [:ids]]
  [:ul (for [id ids] ^{:key id} [fake id])])

(def log2 (atom []))

(def fake2
  "A hosted component with two props (id, label) and no bindings."
  (cells/component
   "fake2" 2 [] [0 1] (fn [id label] [id label])
   (cells/Host.
    (fn [^cells/Instance inst] (set! (.-dirty inst) false) (cells/refresh! inst)
      (swap! log2 conj [:run (vec (.-vals inst))]))
    (fn [^cells/Instance inst _render _el]
      (swap! log2 conj [:create (vec (.-vals inst))])
      (js/document.createElement "b"))
    (fn [^cells/Instance inst] (swap! log2 conj [:destroy (aget (.-vals inst) 0)]) (cells/destroy! inst)))))

(defc holder2 [] [ids [:ids] label [:label]]
  [:ul (for [id ids] ^{:key id} [fake2 id label])])

(use-fixtures :each {:before (fn [] (t/reset-app!) (reset! log []) (reset! log2 []))})

(deftest hosted-component-lifecycle
  (reset! state/app-db {:ids [1 2 3] :v :a})
  (let [el (js/document.createElement "div")]
    (dom/mount! [holder] el)
    (is (= [[:create [1 :a]] [:create [2 :a]] [:create [3 :a]]] @log))
    (is (= "<ul><i>1</i><i>2</i><i>3</i></ul>" (.-innerHTML el)))
    (let [[n1 _ n3] (js/Array.from (.. el -firstChild -children))]
      (reset! log [])
      (events/dispatch-sync [::set :v :b])
      (is (= #{[:run [1 :b]] [:run [2 :b]] [:run [3 :b]]} (set @log)) "path change runs the host")
      (reset! log [])
      (events/dispatch-sync [::set :ids [3 1]])
      (is (= [[:destroy 2]] @log))
      (is (= [n3 n1] (vec (js/Array.from (.. el -firstChild -children)))) "keyed move keeps host nodes"))))

(deftest destroyed-instance-does-not-run
  "A hosted instance removed by its parent's re-render in the same flush as a
  path change it subscribes to must not have its host's run called: its
  destroy runs (lower depth, parent first) before its own scheduled run, and
  the dispatcher must see it unmounted and skip it rather than run it anyway."
  (reset! state/app-db {:ids [1 2 3] :v :a})
  (let [el (js/document.createElement "div")]
    (dom/mount! [holder] el)
    (reset! log [])
    (events/dispatch-sync [::set-ids-and-v [1 3] :b])
    (is (= #{[:destroy 2] [:run [1 :b]] [:run [3 :b]]} (set @log))
        "no [:run [2 :b]] for the removed instance")))

;; ---- a Host whose create throws must not leak the instance's subscription

(def log3 (atom []))

(def boom-host
  "A hosted component with one path binding whose Host's create always
  throws, to check dom.cljs's create! destroys the instance (and unsubscribes
  its cell) instead of leaving a half-created, still-subscribed instance."
  (cells/component
   "boom-host" 0 [{:kind :path :deps [] :f (fn [] [:boom])}] [0] (fn [v] [v])
   (cells/Host.
    (fn [^cells/Instance inst] (set! (.-dirty inst) false) (cells/refresh! inst)
      (swap! log3 conj [:run (aget (.-vals inst) 0)]))
    (fn [_inst _render _el] (throw (js/Error. "create boom")))
    (fn [^cells/Instance inst] (swap! log3 conj [:destroy]) (cells/destroy! inst)))))

(defc holder3 [] [] [:div [boom-host]])

(deftest hosted-create-throw-destroys-instance-and-does-not-leak-subscription
  (reset! state/app-db {:boom 1})
  (reset! log3 [])
  (let [el (js/document.createElement "div")
        before (.-refs state/paths)]
    (is (thrown? js/Error (dom/mount! [holder3] el)) "create boom propagates")
    (is (= before (.-refs state/paths)) "the failed instance's path subscription was cleaned up")
    (events/dispatch-sync [::set :boom 2])
    (is (= [] @log3) "a later db change on its path must not run the destroyed instance")))

(deftest hosted-component-prop-change-keeps-node
  "A non-key prop change on a hosted component (its key/id is unchanged) must
  run the host, with the new prop values, and keep the host's DOM node."
  (reset! state/app-db {:ids [1 2] :label "a"})
  (let [el (js/document.createElement "div")]
    (dom/mount! [holder2] el)
    (is (= [[:create [1 "a"]] [:create [2 "a"]]] @log2))
    (let [before (vec (js/Array.from (.. el -firstChild -children)))]
      (reset! log2 [])
      (events/dispatch-sync [::set :label "b"])
      (is (= #{[:run [1 "b"]] [:run [2 "b"]]} (set @log2)) "prop change runs the host")
      (is (= before (vec (js/Array.from (.. el -firstChild -children)))) "host node identity kept"))))

;; ---- #16: a throwing Host destroy or run is isolated like other errors

(def log4 (atom []))

(def grumpy
  "Hosted, one prop (id) and a path binding [:g]; destroy throws before
  calling cells/destroy!."
  (cells/component
   "grumpy" 1 [{:kind :path :deps [] :f (fn [] [:g])}] [0 1] (fn [id g] [id g])
   (cells/Host.
    (fn [^cells/Instance inst] (set! (.-dirty inst) false) (cells/refresh! inst)
      (swap! log4 conj [:run (aget (.-vals inst) 0)]))
    (fn [^cells/Instance _inst _render _el] (js/document.createElement "i"))
    (fn [^cells/Instance inst]
      (swap! log4 conj [:destroy (aget (.-vals inst) 0)])
      (throw (js/Error. "destroy boom"))))))

(defc holder4 [] [ids [:ids] label [:label]]
  [:div [:ul (for [id ids] ^{:key id} [grumpy id])] [:b label]])

(def runny
  "Hosted, props (id, label); run throws."
  (cells/component
   "runny" 2 [] [0 1] (fn [id label] [id label])
   (cells/Host.
    (fn [^cells/Instance inst] (swap! log4 conj [:run (aget (.-vals inst) 0)]) (throw (js/Error. "run boom")))
    (fn [^cells/Instance _inst _render _el] (js/document.createElement "i"))
    (fn [^cells/Instance inst] (cells/destroy! inst)))))

(defc holder5 [] [ids [:ids] label [:label]]
  [:div [:ul (for [id ids] ^{:key id} [runny id label])] [:b label]])

(defn- first-two [logs] (mapv #(vec (take 2 %)) logs))

(deftest throwing-host-destroy-is-isolated
  (reset! state/app-db {:ids [1 2 3] :label "a" :g 0})
  (reset! log4 [])
  (let [el (js/document.createElement "div")]
    (dom/mount! [holder4] el)
    (let [errs (first-two (capture-errors (fn [_] (events/dispatch-sync [::set-ids-and-label [] "b"]))))]
      (is (= [[:destroy 1] [:destroy 2] [:destroy 3]] (sort @log4)) "every removed instance is destroyed")
      (is (= "<div><ul></ul><b>b</b></div>" (.-innerHTML el)) "the parent's patch completes")
      (is (= (repeat 3 ["hammer: destroy failed in" "grumpy"]) errs) "each throw is logged by component name"))
    (reset! log4 [])
    (events/dispatch-sync [::set :g 1])
    (is (= [] @log4) "their [:g] subscriptions are gone")))

(deftest throwing-host-run-from-a-prop-change-is-isolated
  (reset! state/app-db {:ids [1 2] :label "a"})
  (reset! log4 [])
  (let [el (js/document.createElement "div")]
    (dom/mount! [holder5] el)
    (let [errs (first-two (capture-errors (fn [_] (events/dispatch-sync [::set :label "b"]))))]
      (is (= [[:run 1] [:run 2]] @log4) "both hosted instances run")
      (is (= "<div><ul><i></i><i></i></ul><b>b</b></div>" (.-innerHTML el)) "the parent's patch completes")
      (is (= (repeat 2 ["hammer: update failed in" "runny"]) errs)))))

;; ---- #17: a Host create that returns nil fails fast with a clear message

(def log6 (atom []))

(def hollow
  (cells/component
   "hollow" 0 [{:kind :path :deps [] :f (fn [] [:h])}] [0] (fn [v] [v])
   (cells/Host.
    (fn [^cells/Instance inst] (set! (.-dirty inst) false) (swap! log6 conj :run))
    (fn [^cells/Instance _inst _render _el] nil)
    (fn [^cells/Instance inst] (swap! log6 conj :destroy) (cells/destroy! inst)))))

(defc holder6 [] [] [:div [hollow]])

(deftest host-create-returning-nil-fails-fast
  (reset! state/app-db {:h 1})
  (reset! log6 [])
  (let [el (js/document.createElement "div")
        before (.-refs state/paths)
        msg (try (dom/mount! [holder6] el) nil
                 (catch :default e (.-message e)))]
    (is (= "hammer: Host create of hollow returned nil; it must return a DOM node" msg))
    (is (= [:destroy] @log6) "the host's own destroy cleans up the half-created instance")
    (is (= before (.-refs state/paths)) "no leaked subscription")))

;; a Host whose destroy is a no-op (returns without calling cells/destroy!
;; itself): create! must still run cells/destroy! after calling it, not just
;; when destroy throws, or the instance's path subscription leaks forever.

(def hollow-noop
  (cells/component
   "hollow-noop" 0 [{:kind :path :deps [] :f (fn [] [:h])}] [0] (fn [v] [v])
   (cells/Host.
    (fn [^cells/Instance inst] (set! (.-dirty inst) false))
    (fn [^cells/Instance _inst _render _el] nil)
    (fn [^cells/Instance _inst] nil))))

(defc holder7 [] [] [:div [hollow-noop]])

(deftest host-create-returning-nil-with-a-no-op-destroy-does-not-leak
  (reset! state/app-db {:h 1})
  (let [el (js/document.createElement "div")
        before (.-refs state/paths)
        msg (try (dom/mount! [holder7] el) nil
                 (catch :default e (.-message e)))]
    (is (= "hammer: Host create of hollow-noop returned nil; it must return a DOM node" msg))
    (is (= before (.-refs state/paths))
        "no leaked subscription even though the host's own destroy never called cells/destroy!")))
