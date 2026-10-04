(ns hammer.svg-test
  "SVG elements are created in the SVG namespace, below :svg and until a
  foreignObject, in plain hiccup, compiled templates and every update path."
  (:require [cljs.test :refer [deftest is use-fixtures]]
            [hammer.test-env]
            [hammer.state :as state]
            [hammer.events :as events]
            [hammer.dom :as dom]
            [hammer.testing :as t]
            [hammer.core :refer [defc]]))

(use-fixtures :each {:before t/reset-app!})

(def svg-ns "http://www.w3.org/2000/svg")
(def html-ns "http://www.w3.org/1999/xhtml")

(defn- container []
  (let [el (.createElement js/document "div")]
    (.appendChild (.-body js/document) el)
    el))

(defn- ns-of [el sel] (.-namespaceURI (.querySelector el sel)))

(events/reg-event ::set (fn [db k v] {:db (assoc db k v)}))

(deftest plain-hiccup-svg
  (let [el (container)]
    (dom/mount! [:div [:svg {:viewBox "0 0 10 10"} [:circle#c {:r 5 :class "dot"}]
                       [:foreignObject [:p#p "x"]]]
                 [:span#s "y"]]
                el)
    (is (= svg-ns (ns-of el "svg")))
    (is (= svg-ns (ns-of el "#c")))
    (is (= html-ns (ns-of el "#p")) "foreignObject content is HTML again")
    (is (= html-ns (ns-of el "#s")))
    (is (= "0 0 10 10" (.getAttribute (.querySelector el "svg") "viewBox")) "case kept")
    (is (= "dot" (.getAttribute (.querySelector el "#c") "class")))))

(defc dot [id] [r [:r id]] [:circle {:id (str "d" id) :r r}])
(defc chart [] [ids [:ids] on? [:on?]]
  [:svg {:width 100}
   (for [id ids] ^{:key id} [dot id])
   (when on? [:rect#extra {:width 3}])
   [:g#g [:line {:x1 0}]]])

(deftest compiled-svg-components-and-updates
  (reset! state/app-db {:ids [1 2] :r {1 3 2 4 3 5} :on? false})
  (let [el (container)]
    (dom/mount! [chart] el)
    (is (= svg-ns (ns-of el "svg")))
    (is (= [svg-ns svg-ns] (mapv #(.-namespaceURI %) (.querySelectorAll el "circle")))
        "a component whose root is :circle is an SVG circle inside svg")
    (is (= svg-ns (ns-of el "#g line")))
    (events/dispatch-sync [::set :ids [3 1 2]])
    (is (= svg-ns (ns-of el "#d3")) "a keyed insert is created in the SVG namespace")
    (events/dispatch-sync [::set :on? true])
    (is (= svg-ns (ns-of el "#extra")) "a conditional hole inside svg")
    (events/dispatch-sync [::set :r {1 3 2 4 3 9}])
    (is (= "9" (.getAttribute (.querySelector el "#d3") "r")))))

(defc same-root [] [] [:circle.same {:r 1}])

(deftest one-template-in-both-namespaces
  (let [el (container)]
    (dom/mount! [:div [:svg [same-root]] [:div [same-root]]] el)
    (let [[a b] (vec (.querySelectorAll el ".same"))]
      (is (= [svg-ns html-ns] [(.-namespaceURI a) (.-namespaceURI b)])
          "the template keeps a prototype per namespace"))))

(defc swap-root [] [s? [:s?]] (if s? [:svg#sv [:circle]] [:div#dv "x"]))

(deftest replacing-a-root-with-svg
  (reset! state/app-db {:s? false})
  (let [el (container)]
    (dom/mount! [swap-root] el)
    (events/dispatch-sync [::set :s? true])
    (is (= svg-ns (ns-of el "#sv circle")))))

;; ---- the update paths the first tests don't reach (review)

(defc rings [] [ids [:ids]] [:svg (for [id ids] ^{:key id} [:circle {:id (str "k" id)}])])

(deftest keyed-middle-creates-are-svg
  (reset! state/app-db {:ids [3 4]})
  (let [el (container)]
    (dom/mount! [rings] el)
    (events/dispatch-sync [::set :ids [4 5 3]])
    (is (= svg-ns (ns-of el "#k5")) "created in the keyed diff's middle")
    (events/dispatch-sync [::set :ids [6 7 8 9]])
    (is (= [svg-ns] (distinct (mapv #(.-namespaceURI %) (.querySelectorAll el "circle")))) "full rebuild")))

(defc mixed [] [x [:x]] [:svg [:title "t"] x [:g#after]])

(deftest kid-hole-switching-text-and-hiccup-inside-svg
  (reset! state/app-db {:x "text"})
  (let [el (container)]
    (dom/mount! [mixed] el)
    (events/dispatch-sync [::set :x [:rect#r]])
    (is (= svg-ns (ns-of el "#r")))
    (events/dispatch-sync [::set :x "again"])
    (events/dispatch-sync [::set :x (list [:line#l1] [:line#l2])])
    (is (= [svg-ns svg-ns] [(ns-of el "#l1") (ns-of el "#l2")]))))

(defc fo [] [x [:x]] [:svg [:foreignObject x]])

(deftest foreign-object-sole-kid-and-replace-stay-html
  (reset! state/app-db {:x [:p#p "a"]})
  (let [el (container)]
    (dom/mount! [fo] el)
    (is (= svg-ns (ns-of el "foreignObject")))
    (is (= html-ns (ns-of el "#p")))
    (events/dispatch-sync [::set :x [:div#d [:span#s "b"]]])
    (is (= [html-ns html-ns] [(ns-of el "#d") (ns-of el "#s")]))))
