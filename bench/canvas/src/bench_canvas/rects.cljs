(ns bench-canvas.rects
  "rects-10k: one defdraw, n filled rects (no text)."
  (:require [hammer.canvas :refer [defdraw reg-event dispatch mount!]]
            [bench-canvas.common :as c :refer [D]]))

(def ^js R (.-RECTS D))
(def W (.-W R))
(def H (.-H R))
(def HL (.-HL R))
(def ^js PALETTE (.-PALETTE D))
(def NP (.-length PALETTE))

(defn- build-rects [n]
  (mapv (fn [^js r] {:id (.-id r) :x (.-x r) :y (.-y r) :w (.-w r) :h (.-h r) :c (.-c r)})
        (.rects D n (.-SEED D))))

(reg-event :rects/create (fn [db _] {:db (assoc db :rects (build-rects (:n db)) :sel nil)}))

(reg-event :rects/update
           (fn [db _]
             {:db (update db :rects
                          (fn [rs]
                            (reduce (fn [v i] (update-in v [i :c] (fn [c] (rem (inc c) NP))))
                                    rs (range 0 (count rs) 10))))}))

(reg-event :rects/select
           (fn [db k]
             (let [rs (:rects db)]
               {:db (assoc db :sel (:id (nth rs (.selectIndex D k (count rs)))))})))

(reg-event :rects/swap
           (fn [db _]
             (let [rs (:rects db)
                   j (- (count rs) 2)]
               (if (> (count rs) j 1)
                 {:db (assoc db :rects (assoc rs 1 (nth rs j) j (nth rs 1)))}
                 nil))))

(reg-event :rects/clear (fn [db _] {:db (assoc db :rects [] :sel nil)}))

(defdraw rects [] [rs [:rects] sel [:sel]]
  {:size [W H]}
  (fn [^js ctx {:keys [w h]}]
    (c/draw!)
    (.clearRect ctx 0 0 w h)
    (dotimes [i (count rs)]
      (let [r (nth rs i)]
        (set! (.-fillStyle ctx) (if (identical? (:id r) sel) HL (aget PALETTE (:c r))))
        (.fillRect ctx (:x r) (:y r) (:w r) (:h r))))))

(defn start! [n el]
  (mount! [rects] el {:n n :rects [] :sel nil})
  (c/expose! (fn [op k] (dispatch [(keyword "rects" op) k]))))
