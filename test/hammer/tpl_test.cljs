(ns hammer.tpl-test
  "Compiled templates (defc) against the same hiccup mounted uncompiled."
  (:require [cljs.test :refer [deftest is use-fixtures]]
            [hammer.test-env]
            [hammer.state :as state]
            [hammer.events :as events]
            [hammer.dom :as dom]
            [hammer.testing :as t]
            [hammer.core :refer [defc]]
            [hammer.test-util :refer [capture-errors]])
  (:require-macros [hammer.tpl-macros :refer [defboth]]))

(use-fixtures :each {:before t/reset-app!})

(defn- container []
  (let [el (.createElement js/document "div")]
    (.appendChild (.-body js/document) el)
    el))

(declare shape)

(defn- shape-kids
  "Child nodes as data; adjacent text merged and empty text dropped, since
  both are invisible (a template keeps an empty text node at a nil hole)."
  [^js n]
  (->> (js/Array.from (.-childNodes n))
       (map shape)
       (partition-by string?)
       (mapcat (fn [xs] (if (string? (first xs)) [(apply str xs)] xs)))
       (remove #{""})
       vec))

(defn- shape
  "A node as data: tag, attributes as a map (order-insensitive), live form
  properties, children."
  [^js n]
  (if (= 3 (.-nodeType n))
    (.-data n)
    (into [(.-tagName n)
           (into (sorted-map)
                 (comp (map (fn [^js a] [(.-name a) (.-value a)]))
                       ;; an emptied style leaves style="" after any patch, compiled or not
                       (remove #(= % ["style" ""])))
                 (js/Array.from (.-attributes n)))
           (case (.-tagName n)
             "INPUT" {:value (.-value n) :checked (.-checked n)}
             "SELECT" {:value (.-value n)}
             "OPTION" {:selected (.-selected n)}
             nil)]
          (shape-kids n))))

(defn- check!
  "Mounts [comp] once and, for each db state, compares its DOM with a fresh
  uncompiled mount of (plain vals-of-ks). stable?: the compiled root element
  must be the same node throughout. Returns the compiled container."
  ([comp plain ks states] (check! comp plain ks states true))
  ([comp plain ks states stable?]
   (let [el (container)
         ref (container)]
     (events/set-db! (first states))
     (dom/mount! [comp] el)
     (let [root (.-firstChild el)]
       (doseq [[i st] (map-indexed vector states)]
         (when (pos? i)
           (events/set-db! st)
           (t/flush!))
         (dom/mount! (apply plain (map #(get st %) ks)) ref)
         (is (= (shape-kids ref) (shape-kids el)) (str "state " i " " (pr-str st)))
         (when stable? (is (identical? root (.-firstChild el)) (str "root kept, state " i)))))
     el)))

;; ---- compiled output equals uncompiled output

(defboth static-body [a]
  [:div.a#x {:title "t" :data-n 1 :hidden true :style {:color "red"} :class ["k" nil "l"]}
   [:span "hi"] "text" 12 nil false true [:b.c] [:i {:disabled false}]])

(deftest static-body-matches
  (check! static-body static-body-plain [:a] [{:a 1} {:a 2}]))

(defboth text-holes [a b]
  [:p "hi " a " " b [:em a]])

(deftest text-holes-match
  (check! text-holes text-holes-plain [:a :b]
          [{:a "x" :b 1} {:a 3 :b "y"} {:a nil :b ""} {:a "" :b false} {:a true :b 0}
           {:a :kw :b 1.5} {:a "x" :b 1}]))

(defboth maybe-attrs [a b]
  [:div a [:span b]])

(deftest maybe-attrs-second-item-matches
  (check! maybe-attrs maybe-attrs-plain [:a :b]
          [{:a "t" :b 1} {:a {:id "q" :class "c"} :b 2} {:a nil :b 3} {:a [:b "x"] :b 4}
           {:a {:title "z"} :b 5} {:a "back" :b 6}]
          false))

(defboth class-holes [a b]
  [:ul [:li.base {:class a} "x"] [:li {:class a} "y"] [:li#i.p.q {:class b}]])

(deftest class-holes-match
  (check! class-holes class-holes-plain [:a :b]
          [{:a nil :b "on"} {:a "on" :b nil} {:a ["p" nil "q"] :b [nil nil]} {:a "" :b ""}
           {:a [nil nil] :b ["z"]} {:a nil :b nil}]))

(defboth style-and-attrs [a b c]
  [:div {:style a :href b :data-x c :disabled c} [:a {:href b :title "static"} "l"]])

(deftest style-and-attr-holes-match
  (check! style-and-attrs style-and-attrs-plain [:a :b :c]
          [{:a {:color "red"} :b "/x" :c true} {:a {:color "blue" :width "1px"} :b nil :c false}
           {:a nil :b "/y" :c 7} {:a {} :b "/y" :c nil} {:a {:width "2px"} :b :k :c "s"}]))

(defboth form-props [a b c]
  [:form
   [:input {:value a :type "text"}]
   [:input {:type "checkbox" :checked b}]
   [:select {:value c} [:option {:value "x"} "X"] [:option {:value "y"} "Y"]]
   [:select {:value c} (for [o ["x" "y"]] ^{:key o} [:option {:value o :selected (= o c)} o])]])

(deftest value-checked-selected-match
  (check! form-props form-props-plain [:a :b :c]
          [{:a "v" :b true :c "y"} {:a nil :b false :c "x"} {:a 3 :b nil :c "y"} {:a "w" :b 1 :c "x"}]))

(defboth branches [a b]
  [:div "a" (when a [:b a]) (if b [:i "yes"] "no") [:span "z"]
   (case a 1 [:em "one"] 2 "two" nil) (cond (= b 1) [:u b] b (str b "!")) (let [c (str a b)] [:s c])])

(deftest when-if-case-cond-let-match
  (check! branches branches-plain [:a :b]
          [{:a 1 :b 1} {:a nil :b nil} {:a 2 :b true} {:a 1 :b "q"} {:a 3 :b nil} {:a 1 :b 1}]))

(defboth lists [a b]
  [:div
   [:ul (for [x a] ^{:key x} [:li {:class (when (odd? x) "odd")} x])]
   [:ol (for [x a] [:li x (when (= x b) [:b "!"])])]
   [:section [:h1 "t"] (for [x a] ^{:key x} [:p x]) [:footer "f"] (map (fn [x] [:i x]) a)]])

(deftest keyed-and-unkeyed-lists-match
  (check! lists lists-plain [:a :b]
          [{:a [1 2 3] :b 2} {:a [3 1 2 4] :b 4} {:a [] :b nil} {:a [5] :b 5}
           {:a [2 5 1 7 3] :b 1} {:a (range 10 0 -1) :b 3} {:a [1 2 3] :b 2} {:a [8 9] :b 8} {:a [1 2 3] :b 2}]))

(defc child [id] [v [:vals id]] [:b {:data-id id} "c" id ":" v])

(defboth nested [a b]
  [:div [:header b] (for [id a] ^{:key id} [child id]) [child 99] (when b [child 7])])

(deftest nested-components-match
  (check! nested nested-plain [:a :b]
          [{:a [1 2] :b "h" :vals {1 "x" 2 "y" 7 "s" 99 "n"}}
           {:a [2 1 3] :b nil :vals {1 "x" 2 "Y" 3 "z" 99 "n"}}
           {:a [] :b "h2" :vals {99 "N" 7 "S"}}
           {:a [3] :b "h3" :vals {3 "z3" 99 "N" 7 "S"}}]))

(defboth switching [a]
  [:div [:p "pre" a "post"] [:p a] [:ul a]])

(deftest hole-switches-between-text-and-hiccup
  (check! switching switching-plain [:a]
          [{:a "t"} {:a [:b "x"]} {:a (list [:i 1] [:i 2])} {:a nil} {:a 5} {:a [:b "y"]}
           {:a (list)} {:a "t2"} {:a (for [i (range 3)] ^{:key i} [:li i])} {:a false}]))

(defboth root-switch [a]
  (if a [:p {:title a} a] [:span "none"]))

(deftest body-branches-are-separate-templates
  (check! root-switch root-switch-plain [:a] [{:a "x"} {:a nil} {:a "y"} {:a "z"}] false))

;; ---- reuse, refs, handlers, render counts

(events/reg-event :set (fn [db k v] {:db (assoc db k v)}))
(events/reg-event :relabel (fn [db id s] {:db (assoc-in db [:rows id :label] s)}))
(events/reg-event :tpl/select (fn [db id] {:db (assoc db :selected id)}))
(events/reg-event :tpl/delete (fn [db id] {:db (update db :ids #(filterv (partial not= id) %))}))

(defc row [id]
  [r   [:rows id]
   sel [:selected]
   cls (when (= sel id) "danger")]
  [:tr {:class cls}
   [:td.col-md-1 (:id r)]
   [:td.col-md-4 [:a {:on-click [:tpl/select id]} (:label r)]]
   [:td.col-md-1 [:a {:on-click [:tpl/delete id]} [:span.glyphicon.glyphicon-remove {:aria-hidden "true"}]]]
   [:td.col-md-6]])

(defc table [] [ids [:ids]]
  [:table.table.table-hover.table-striped.test-data
   [:tbody (for [id ids] ^{:key id} [row id])]])

(defn- rows-db [n]
  {:ids (vec (range 1 (inc n)))
   :rows (into {} (for [i (range 1 (inc n))] [i {:id i :label (str "row " i)}]))})

(defn- click! [^js el]
  (.dispatchEvent el (new (.-MouseEvent js/window) "click" #js {:bubbles true})))

(defn- all-nodes [^js el]
  (let [w (.createTreeWalker js/document el 0xFFFFFFFF)]
    (loop [acc []]
      (if-let [n (.nextNode w)] (recur (conj acc n)) acc))))

(deftest bench-row-renders-reuses-nodes-and-dispatches
  (reset! state/app-db (rows-db 3))
  (let [el (container)]
    (dom/mount! [table] el)
    (is (= (str "<table class=\"table table-hover table-striped test-data\"><tbody>"
                (apply str (for [i [1 2 3]]
                             (str "<tr><td class=\"col-md-1\">" i "</td>"
                                  "<td class=\"col-md-4\"><a>row " i "</a></td>"
                                  "<td class=\"col-md-1\"><a><span aria-hidden=\"true\" class=\"glyphicon glyphicon-remove\"></span></a></td>"
                                  "<td class=\"col-md-6\"></td></tr>")))
                "</tbody></table>")
           (.-innerHTML el)))
    (let [before (all-nodes el)
          tbody (.querySelector el "tbody")]
      (t/reset-renders! row table)
      (click! (.querySelector (.-firstChild tbody) "td.col-md-4 a"))
      (t/flush!)
      (is (= 1 (:selected @state/app-db)))
      (is (= 1 (t/renders row)))
      (is (= "danger" (.-className (.-firstChild tbody))))
      (events/dispatch-sync [:relabel 2 "two"])
      (is (= 2 (t/renders row)))
      (is (= 0 (t/renders table)))
      (is (= "two" (.-textContent (.querySelector (aget (.-children tbody) 1) "a"))))
      (events/dispatch-sync [:tpl/select 3])
      (is (= 4 (t/renders row)))
      (is (= ["" "" "danger"] (mapv #(.-className %) (.-children tbody))))
      (is (= before (all-nodes el)) "every element and text node reused")
      (click! (.querySelector (aget (.-children tbody) 1) "span"))
      (t/flush!)
      (is (= [1 3] (:ids @state/app-db)))
      (is (= ["1" "3"] (mapv #(.-textContent (.-firstChild %)) (.-children tbody)))))))

(deftest rows-are-cloned-not-built
  (reset! state/app-db (rows-db 1))
  (let [el (container)
        doc js/document
        orig (.-createElement doc)
        n (atom 0)]
    (dom/mount! [table] el)
    (set! (.-createElement doc) (fn [tag] (swap! n inc) (.call orig doc tag)))
    (try
      (events/dispatch-sync [:set :ids (vec (range 1 21))])
      (finally (js-delete doc "createElement")))
    (is (= 20 (.-length (.querySelectorAll el "tr"))))
    (is (= 0 @n))))

(deftest keyed-rows-move-with-their-nodes
  (reset! state/app-db (rows-db 5))
  (let [el (container)]
    (dom/mount! [table] el)
    (let [tbody (.querySelector el "tbody")
          [a b c d e] (vec (.-children tbody))]
      (t/reset-renders! row)
      (events/dispatch-sync [:set :ids [5 2 3 4 1]])
      (is (= [e b c d a] (vec (.-children tbody))))
      (is (= 0 (t/renders row)))
      (events/dispatch-sync [:set :ids [6 7]])
      (is (= ["" ""] (mapv #(.-textContent (.-firstChild %)) (.-children tbody))))
      (is (not-any? #{a b c d e} (vec (.-children tbody)))))))

(def ref-log (atom []))
(defn- logger [k] (fn [el] (swap! ref-log conj [k (some-> el .-tagName) (some-> el .-isConnected)])))
(def ref-in (logger :in))
(def ref-sp (logger :sp))

(defc reffy [] [a [:a]]
  [:div [:input {:ref ref-in}] (when a [:p [:span {:ref ref-sp} a]])])

(deftest refs-after-insertion-and-nil-on-unmount
  (reset! ref-log [])
  (reset! state/app-db {:a "x"})
  (let [el (container)]
    (dom/mount! [reffy] el)
    (is (= [[:in "INPUT" true] [:sp "SPAN" true]] @ref-log))
    (reset! ref-log [])
    (events/dispatch-sync [:set :a "y"])
    (is (= [] @ref-log) "a changed hole does not re-call refs")
    (events/dispatch-sync [:set :a nil])
    (is (= [[:sp nil nil]] @ref-log))
    (events/dispatch-sync [:set :a "z"])
    (reset! ref-log [])
    (dom/mount! [:p "other"] el)
    (is (= [[:in nil nil] [:sp nil nil]] @ref-log))))

(defc handlers [] [on? [:on?] n [:n]]
  [:div
   [:button#v {:on-click (when on? [:set :hit n])} "v"]
   [:button#f {:on-click #(events/dispatch [:set :fn-hit n])} "f"]])

(deftest handlers-delegate-and-follow-updates
  (reset! state/app-db {:on? false :n 1})
  (let [el (container)
        v #(.querySelector el "#v")
        f #(.querySelector el "#f")]
    (dom/mount! [handlers] el)
    (is (= #{"click"} (set (js/Array.from (.-__cuiT el)))))
    (click! (v))
    (click! (f))
    (t/flush!)
    (is (nil? (:hit @state/app-db)))
    (is (= 1 (:fn-hit @state/app-db)))
    (events/dispatch-sync [:set :on? true])
    (events/dispatch-sync [:set :n 2])
    (click! (v))
    (click! (f))
    (t/flush!)
    (is (= 2 (:hit @state/app-db)))
    (is (= 2 (:fn-hit @state/app-db)))
    (events/dispatch-sync [:set :on? false])
    (events/dispatch-sync [:set :hit nil])
    (click! (v))
    (t/flush!)
    (is (nil? (:hit @state/app-db)))))

(defc input-only [] [text [:text] other [:other]]
  [:label other [:input {:value text}]])

(deftest value-hole-compares-with-live-element
  (reset! state/app-db {:text "ab" :other 1})
  (let [el (container)]
    (dom/mount! [input-only] el)
    (let [i (.querySelector el "input")]
      (set! (.-value i) "typed")
      (events/dispatch-sync [:set :other 2])
      (is (= "ab" (.-value i)) "re-render resets to the db value, as uncompiled")
      (is (identical? i (.querySelector el "input"))))))

(defc boom [] [n [:n]] [:p {:title (str n)} (if (= n 2) (throw (js/Error. "nope")) n)])

(deftest compiled-body-throw-keeps-dom
  (reset! state/app-db {:n 1})
  (let [el (container)
        _ (dom/mount! [boom] el)
        logs (capture-errors (fn [_] (events/dispatch-sync [:set :n 2])))]
    (is (= "<p title=\"1\">1</p>" (.-innerHTML el)))
    (is (= ["hammer: render failed in boom"] (mapv first logs)))
    (events/dispatch-sync [:set :n 3])
    (is (= "<p title=\"3\">3</p>" (.-innerHTML el)))))

(defc counted [] [a [:a] b [:b]] [:p a])

(deftest compiled-body-render-gating
  (reset! state/app-db {:a 1 :b 1})
  (let [el (container)]
    (dom/mount! [counted] el)
    (t/reset-renders! counted)
    (events/dispatch-sync [:set :b 2])
    (is (= 0 (t/renders counted)))
    (events/dispatch-sync [:set :a 2])
    (is (= 1 (t/renders counted)))
    (is (= "<p>2</p>" (.-innerHTML el)))))

(defc keyed-root [k] [] ^{:key k} [:hr {:data-k k}])
(defc keyed-roots [] [ks [:ks]] [:div (for [k ks] ^{:key k} [:i k]) (for [k ks] [keyed-root k])])

(deftest key-on-compiled-root
  (reset! state/app-db {:ks [1 2 3]})
  (let [el (container)]
    (dom/mount! [keyed-roots] el)
    (let [is* (vec (.querySelectorAll el "i"))]
      (events/dispatch-sync [:set :ks [3 1 2]])
      (is (= [(is* 2) (is* 0) (is* 1)] (vec (.querySelectorAll el "i"))))
      (is (= ["3" "1" "2"] (mapv #(.getAttribute % "data-k") (.querySelectorAll el "hr")))))))

(deftest unmount-unsubscribes-regions
  (reset! state/app-db {:a [1 2] :b "h" :vals {1 "x" 2 "y" 7 "s" 99 "n"}})
  (let [el (container)
        before (.-refs state/paths)]
    (dom/mount! [nested] el)
    (is (= (+ before 2 4) (.-refs state/paths)))
    (events/dispatch-sync [:set :b nil])
    (is (= (+ before 2 3) (.-refs state/paths)))
    (dom/mount! [:p] el)
    (is (= before (.-refs state/paths)))))

;; component vectors in hiccup position compile to :comp vnodes

(events/reg-event :set-tag (fn [db t] {:db (assoc db :tag t)}))

(defc cc-leaf [x] [] [:b x])
(defc cc-dyn [t] [tag [:tag]]
  [:div [tag "x"] [cc-leaf t] (let [f cc-leaf] ^{:key 1} [f "y"])])
(defc cc-list [] [ks [:ks]] [:ul (for [k ks] ^{:key k} [cc-leaf k])])

(deftest component-vectors-compile-with-runtime-fallback
  (let [el (container)]
    (reset! state/app-db {:tag :i})
    (dom/mount! [cc-dyn "z"] el)
    (is (= "<div><i>x</i><b>z</b><b>y</b></div>" (.-innerHTML el)) "a symbol bound to a keyword stays a tag")
    (events/dispatch-sync [:set-tag :span])
    (is (= "<div><span>x</span><b>z</b><b>y</b></div>" (.-innerHTML el)))))

(deftest compiled-component-vnodes-keep-keys
  (let [el (container)]
    (reset! state/app-db {:ks ["a" "b" "c"]})
    (dom/mount! [cc-list] el)
    (let [[a b c] (vec (.. el -firstChild -children))]
      (events/dispatch-sync [:set :ks ["c" "a" "b"]])
      (is (= [c a b] (vec (.. el -firstChild -children))))
      (is (= "<ul><b>c</b><b>a</b><b>b</b></ul>" (.-innerHTML el))))))

;; ---- :key in the attrs map is the element's key, not an attribute

(defn- attr-keyed-hiccup [ks] [:ul (for [k ks] [:li {:key k :class "row"} k])])
(defc attr-keyed [] [ks [:ks]] [:ul (for [k ks] [:li {:key k :class "row"} k])])
;; a call in body position is not compiled: plain hiccup through normalize
(defc attr-keyed-runtime [] [ks [:ks]] (attr-keyed-hiccup ks))
(defc both-keys [] [ks [:ks]] [:ul (for [k ks] ^{:key (- k)} [:li {:key 0} k])])

(deftest attrs-key-moves-nodes-compiled-and-plain
  (doseq [[label view] [["compiled" attr-keyed] ["plain" attr-keyed-runtime]]]
    (reset! state/app-db {:ks [1 2 3]})
    (let [el (container)]
      (dom/mount! [view] el)
      (let [[a b c] (vec (.querySelectorAll el "li"))
            warns (t/expect-errors #(events/dispatch-sync [:set :ks [3 1 2]]))]
        (is (= [c a b] (vec (.querySelectorAll el "li"))) (str label ": moved, not re-rendered in place"))
        (is (= [] warns) (str label ": no duplicate/partial key warning"))
        (is (= "<ul><li class=\"row\">3</li><li class=\"row\">1</li><li class=\"row\">2</li></ul>"
               (.-innerHTML el))
            (str label ": no key attribute"))))))

(deftest metadata-key-wins-over-attrs-key
  (reset! state/app-db {:ks [1 2]})
  (let [el (container)]
    (dom/mount! [both-keys] el)
    (let [warns (t/expect-errors #(events/dispatch-sync [:set :ks [2 1]]))]
      (is (= [] warns) "every attrs :key is 0, so using it would warn about duplicates"))
    (is (= "<ul><li>2</li><li>1</li></ul>" (.-innerHTML el)))))

(defc string-attr-keyed [] [ks [:ks]] [:ul (for [k ks] [:li {:key k "data-x" "1"} k])])

(deftest attrs-key-in-a-map-with-string-keys
  (reset! state/app-db {:ks [1 2]})
  (let [el (container)]
    (dom/mount! [string-attr-keyed] el)
    (let [[a b] (vec (.querySelectorAll el "li"))]
      (is (= [] (t/expect-errors #(events/dispatch-sync [:set :ks [2 1]]))))
      (is (= [b a] (vec (.querySelectorAll el "li"))))
      (is (= "<ul><li data-x=\"1\">2</li><li data-x=\"1\">1</li></ul>" (.-innerHTML el))))))

;; ---- :style as a string; camelCase keys warn

(defboth string-style [s] [:p {:style s} "x"])

(deftest string-style-and-switching-to-a-map
  (let [el (check! string-style string-style-plain [:s]
                   [{:s "color: red"} {:s {:color "blue"}} {:s "margin: 1px"}
                    {:s nil} {:s "color: red"} {:s {:margin "2px"}}])]
    (is (= "<p style=\"margin: 2px;\">x</p>" (.-innerHTML el)) "the string's color is gone after the map")))

(deftest string-style-is-applied
  (reset! state/app-db {:s "color: red"})
  (let [el (container)]
    (dom/mount! [string-style] el)
    (is (= "red" (.. el -firstChild -style -color)))
    (events/dispatch-sync [:set :s {:margin "1px"}])
    (is (= ["" "1px"] [(.. el -firstChild -style -color) (.. el -firstChild -style -margin)]))
    (events/dispatch-sync [:set :s "padding: 3px"])
    (is (= ["" "3px"] [(.. el -firstChild -style -margin) (.. el -firstChild -style -padding)]))))

(defc camel-style [] [c [:c]] [:p {:style {:backgroundColor c :--myVar "1"}} "x"])

(deftest camel-case-style-keys-warn
  (reset! state/app-db {:c "red"})
  (let [warns (t/expect-errors #(dom/mount! [camel-style] (container)))]
    (is (= [{:level :warn :message "hammer: :style keys are CSS names, got :backgroundColor" :error nil}] warns)
        "custom properties (--x) keep their case")))

;; ---- <option> :value is always in the markup

(defboth options [c v] [:select {:value c} (for [o ["a" "b"]] ^{:key o} [:option {:value (if (= o "b") v o)} o])])

(deftest option-value-is-an-attribute
  (let [el (check! options options-plain [:c :v]
                   [{:c "a" :v "b"} {:c "b" :v "b"} {:c "b" :v "z"} {:c "a" :v nil}])]
    (is (= ["a" nil] (mapv #(.getAttribute % "value") (.querySelectorAll el "option")))
        "nil removes the attribute; the option's value is its text again"))
  (reset! state/app-db {:c "b" :v "b"})
  (let [el (container)]
    (dom/mount! [options] el)
    (is (= "<select><option value=\"a\">a</option><option value=\"b\">b</option></select>" (.-innerHTML el))
        "written even when the value equals the text")
    (is (= "b" (.. el -firstChild -value)) "the select still picks it")))
