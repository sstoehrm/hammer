(ns todomvc.core
  (:require [clojure.string :as str]
            [cljs.reader :refer [read-string]]
            [hammer.core :refer [defc reg-event reg-fx dispatch mount!]]))

(def store-key "todos-hammer")

(reg-fx :store #(.setItem js/localStorage store-key (pr-str %)))

(defn- with-todos [db f & args]
  (let [db (apply update db :todos f args)]
    {:db db :store (:todos db)}))

(reg-event :add (fn [db title]
                  (let [id ((fnil inc 0) (last (keys (:todos db))))]
                    (with-todos db assoc id {:id id :title title :done false}))))
(reg-event :toggle (fn [db id] (with-todos db update-in [id :done] not)))
(reg-event :save (fn [db id title] (with-todos db assoc-in [id :title] title)))
(reg-event :delete (fn [db id] (with-todos db dissoc id)))
(reg-event :clear-done (fn [db] (with-todos db #(into (sorted-map) (remove (comp :done val)) %))))
(reg-event :toggle-all (fn [db]
                         (let [d (not-every? :done (vals (:todos db)))]
                           (with-todos db (fn [ts] (reduce-kv #(assoc %1 %2 (assoc %3 :done d)) ts ts))))))
(reg-event :show (fn [db f] {:db (assoc db :showing f)}))

(defn- commit [id s]
  (dispatch (if (str/blank? s) [:delete id] [:save id (str/trim s)])))

(defc todo-item [id]
  [todo [:todos id]
   edit (atom nil)]
  [:li {:class [(when (:done todo) "completed") (when @edit "editing")]}
   [:div.view
    [:input.toggle {:type "checkbox" :checked (:done todo) :on-change [:toggle id]}]
    [:label {:on-dblclick #(reset! edit (:title todo))} (:title todo)]
    [:button.destroy {:on-click [:delete id]}]]
   (when @edit
     [:input.edit {:value @edit
                   :ref #(some-> % .focus)
                   :on-input #(reset! edit (.. ^js % -target -value))
                   :on-blur #(when @edit (commit id @edit) (reset! edit nil))
                   :on-keydown #(case (.-key ^js %)
                                  "Enter" (do (commit id @edit) (reset! edit nil))
                                  "Escape" (reset! edit nil)
                                  nil)}])])

(defc new-todo []
  [draft (atom "")]
  [:input.new-todo {:placeholder "What needs to be done?"
                    :value @draft
                    :ref #(some-> % .focus)
                    :on-input #(reset! draft (.. ^js % -target -value))
                    :on-keydown #(when (and (= "Enter" (.-key ^js %)) (not (str/blank? @draft)))
                                   (dispatch [:add (str/trim @draft)])
                                   (reset! draft ""))}])

(defn- shown? [showing t]
  (case showing :active (not (:done t)) :done (:done t) true))

(defc app []
  [todos   [:todos]
   showing [:showing]
   done    (count (filter :done (vals todos)))
   left    (- (count todos) done)
   shown   (filterv #(shown? showing %) (vals todos))]
  [:div
   [:section.todoapp
    [:header.header [:h1 "todos"] [new-todo]]
    (when (seq todos)
      [:section.main
       [:input#toggle-all.toggle-all {:type "checkbox" :checked (zero? left) :on-change [:toggle-all]}]
       [:label {:for "toggle-all"} "Mark all as complete"]
       [:ul.todo-list (for [t shown] ^{:key (:id t)} [todo-item (:id t)])]])
    [:footer.footer
     [:span.todo-count [:strong left] (if (= 1 left) " item left" " items left")]
     [:ul.filters
      (for [[k s] [[:all "All"] [:active "Active"] [:done "Completed"]]]
        ^{:key k} [:li [:a {:class (when (= k showing) "selected") :href (str "#/" (name k))} s]])]
     (when (pos? done)
       [:button.clear-completed {:on-click [:clear-done]} "Clear completed"])]]
   [:footer.info [:p "Double-click to edit a todo"]]])

(defn- load []
  (into (sorted-map) (some-> (.getItem js/localStorage store-key) read-string)))

(defn- route []
  (keyword (or (not-empty (subs (.. js/location -hash) 2)) "all")))

(defn ^:export main []
  (.addEventListener js/window "hashchange" #(dispatch [:show (route)]))
  (mount! [app] (.getElementById js/document "app") {:todos (load) :showing (route)}))
