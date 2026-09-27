(ns canvas-demo.core
  (:require [hammer.core :refer [defc reg-event mount!]]
            [hammer.canvas :refer [defdraw defloop]]))

(defn- rand-pts [n] (into {} (map (fn [i] [i {:x (rand-int 600) :y (rand-int 300)}])) (range n)))

(reg-event :pick (fn [db x y]
                   (let [[id] (apply min-key (fn [[_ p]] (+ (Math/abs (- x (:x p))) (Math/abs (- y (:y p)))))
                                     (:pts db))]
                     {:db (assoc db :sel id)})))
(reg-event :toggle (fn [db] {:db (update db :paused? not)}))

(defdraw scatter [] [pts [:pts] sel [:sel]]
  {:size [600 300] :on-click [:pick] :attrs {:style {:border "1px solid #ccc"}}}
  (fn [ctx {:keys [w h]}]
    (.clearRect ctx 0 0 w h)
    (doseq [[id {:keys [x y]}] pts]
      (set! (.-fillStyle ctx) (if (= id sel) "crimson" "steelblue"))
      (.fillRect ctx (- x 2) (- y 2) 5 5))))

(defn- step [balls dt w h]
  (mapv (fn [{:keys [x y vx vy] :as b}]
          (let [x (+ x (* vx dt)) y (+ y (* vy dt))]
            (assoc b :x x :y y
                   :vx (if (or (< x 0) (> x w)) (- vx) vx)
                   :vy (if (or (< y 0) (> y h)) (- vy) vy))))
        balls))

(defloop balls [] [world (atom (vec (repeatedly 200 #(hash-map :x (rand 600) :y (rand 200)
                                                                 :vx (- (rand 0.4) 0.2) :vy (- (rand 0.4) 0.2)))))
                   paused? [:paused?]]
  {:size [600 200] :run? (not paused?)}
  (fn [ctx {:keys [w h dt]}]
    (swap! world step dt w h)
    (.clearRect ctx 0 0 w h)
    (set! (.-fillStyle ctx) "darkorange")
    (doseq [{:keys [x y]} @world] (.fillRect ctx x y 3 3))))

(defc page [] [paused? [:paused?]]
  [:div
   [:h2 "Scatter (click a point)"] [scatter]
   [:h2 "Balls"] [:button {:on-click [:toggle]} (if paused? "Resume" "Pause")] [balls]])

(defn ^:export main []
  (mount! [page] (js/document.getElementById "app") {:pts (rand-pts 500) :sel nil :paused? false}))
