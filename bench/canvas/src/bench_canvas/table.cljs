(ns bench-canvas.table
  "table-1k: one defdraw, n rows of rect + fillText label."
  (:require [hammer.canvas :refer [defdraw reg-event dispatch mount!]]
            [bench-canvas.common :as c :refer [D]]))

(def ^js T (.-TABLE D))
(def W (.-W T))
(def H (.-H T))
(def RPC (.-RPC T))
(def CW (.-CW T))
(def CH (.-CH T))
(def FONT (.-FONT T))
(def ROW-A (.-ROW_A T))
(def ROW-B (.-ROW_B T))
(def TEXT (.-TEXT T))
(def HL (.-HL T))

(defn- build-rows [n]
  (mapv (fn [^js r] {:id (.-id r) :label (.-label r)}) (.tableRows D n (.-SEED D))))

(reg-event :table/create (fn [db _] {:db (assoc db :rows (build-rows (:n db)) :sel nil)}))

(reg-event :table/update
           (fn [db _]
             {:db (update db :rows
                          (fn [rows]
                            (reduce (fn [v i] (update-in v [i :label] str " !!!"))
                                    rows (range 0 (count rows) 10))))}))

(reg-event :table/select
           (fn [db k]
             (let [rows (:rows db)]
               {:db (assoc db :sel (:id (nth rows (.selectIndex D k (count rows)))))})))

(reg-event :table/swap
           (fn [db _]
             (let [rows (:rows db)
                   j (- (count rows) 2)]
               (if (> (count rows) j 1)
                 {:db (assoc db :rows (assoc rows 1 (nth rows j) j (nth rows 1)))}
                 nil))))

(reg-event :table/clear (fn [db _] {:db (assoc db :rows [] :sel nil)}))

(defdraw table [] [rows [:rows] sel [:sel]]
  {:size [W H]}
  (fn [^js ctx {:keys [w h]}]
    (c/draw!)
    (.clearRect ctx 0 0 w h)
    (set! (.-font ctx) FONT)
    (set! (.-textBaseline ctx) "middle")
    (dotimes [i (count rows)]
      (let [r (nth rows i)
            x (* (js/Math.floor (/ i RPC)) CW)
            y (* (rem i RPC) CH)]
        (set! (.-fillStyle ctx) (cond (identical? (:id r) sel) HL
                                      (even? i) ROW-A
                                      :else ROW-B))
        (.fillRect ctx x y (dec CW) (dec CH))
        (set! (.-fillStyle ctx) TEXT)
        (.fillText ctx (:label r) (+ x 3) (+ y (/ CH 2)))))))

(defn start! [n el]
  (mount! [table] el {:n n :rows [] :sel nil})
  (c/expose! (fn [op k] (dispatch [(keyword "table" op) k]))))
