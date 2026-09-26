(ns hammer.trie
  "Path subscriptions. A node holds the cells subscribed to exactly its path,
  an equality index (compared value -> cells) for is? subscriptions, and
  counts every registration at or below it.
  Cells are removed by identity: pass the same object to unregister! that
  register! got.")

(deftype Node [^:mutable children cells ^:mutable refs ^:mutable eq])
(deftype Entry [v cells])

(defn node [] (Node. {} (js/Set.) 0 nil))

(defn- descend!
  "Walks path below n, adjusting refs by d, and calls (f leaf). Creates nodes
  when d is positive; drops nodes whose refs reach zero."
  [^Node n path d f]
  (set! (.-refs n) (+ (.-refs n) d))
  (if-let [ks (seq path)]
    (let [k (first ks)
          ^Node c (or (get (.-children n) k)
                      (when (pos? d)
                        (let [c (node)]
                          (set! (.-children n) (assoc (.-children n) k c))
                          c)))]
      (when c
        (descend! c (rest ks) d f)
        (when (zero? (.-refs c))
          (set! (.-children n) (dissoc (.-children n) k)))))
    (f n)))

(defn register!
  "Subscribes cell to path below node n."
  [n path cell]
  (descend! n path 1 (fn [^Node l] (.add (.-cells l) cell))))

(defn unregister!
  "Removes cell from path below node n; drops nodes nobody subscribes to."
  [n path cell]
  (descend! n path -1 (fn [^Node l] (.delete (.-cells l) cell))))

(defn- entry
  "The index entry whose value is = to v. The index buckets by (hash v)."
  [^js m v]
  (when-let [^js b (.get m (hash v))]
    (.find b (fn [^Entry e] (= (.-v e) v)))))

(defn register-eq!
  "Subscribes cell to changes of (= value-at-path v) below node n."
  [n path v cell]
  (descend! n path 1
            (fn [^Node l]
              (when-not (.-eq l) (set! (.-eq l) (js/Map.)))
              (let [m (.-eq l)
                    h (hash v)]
                (if-let [^Entry e (entry m v)]
                  (.add (.-cells e) cell)
                  (let [b (or (.get m h) (let [b #js []] (.set m h b) b))]
                    (.push b (Entry. v (doto (js/Set.) (.add cell))))))))))

(defn unregister-eq!
  "Removes an is? subscription made by register-eq! with the same path, v and cell."
  [n path v cell]
  (descend! n path -1
            (fn [^Node l]
              (when-let [^js m (.-eq l)]
                (let [h (hash v)]
                  (when-let [^js b (.get m h)]
                    ;; by cell, not by =: a value that is not = to itself (NaN) still unregisters
                    (when-let [^Entry e (.find b (fn [^Entry e] (.has (.-cells e) cell)))]
                      (.delete (.-cells e) cell)
                      (when (zero? (.-size (.-cells e)))
                        (.splice b (.indexOf b e) 1)
                        (when (zero? (.-length b)) (.delete m h))
                        (when (zero? (.-size m)) (set! (.-eq l) nil))))))))))

(defn notify!
  "Calls (mark cell) for every cell whose path value is not identical?
  between old and nu, and for every is? cell compared to the old or the new
  value when they are not =. Unchanged branches are skipped. Returns the
  number of child nodes visited."
  [n old nu mark]
  (let [visits (volatile! 0)
        mark-all (fn [^Entry e] (when e (.forEach (.-cells e) (fn [c] (mark c)))))]
    (letfn [(walk [^Node n o v]
              (.forEach (.-cells n) (fn [c] (mark c)))
              (when-let [m (.-eq n)]
                (let [eo (entry m o)
                      ev (entry m v)]
                  (when-not (identical? eo ev)
                    (mark-all eo)
                    (mark-all ev))))
              (reduce-kv (fn [_ k c]
                           (vswap! visits inc)
                           (let [co (get o k)
                                 cv (get v k)]
                             (when-not (identical? co cv) (walk c co cv))))
                         nil
                         (.-children n)))]
      (when-not (identical? old nu) (walk n old nu)))
    @visits))
