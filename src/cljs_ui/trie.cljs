(ns cljs-ui.trie
  "Path subscriptions. A node holds the cells subscribed to exactly its path
  and counts every registration at or below it.
  Cells are removed by identity: pass the same object to unregister! that
  register! got.")

(deftype Node [^:mutable children cells ^:mutable refs])

(defn node [] (Node. {} (js/Set.) 0))

(defn register!
  "Subscribes cell to path below node n."
  [^Node n path cell]
  (set! (.-refs n) (inc (.-refs n)))
  (if-let [ks (seq path)]
    (let [k (first ks)
          c (or (get (.-children n) k)
                (let [c (node)]
                  (set! (.-children n) (assoc (.-children n) k c))
                  c))]
      (register! c (rest ks) cell))
    (.add (.-cells n) cell)))

(defn unregister!
  "Removes cell from path below node n; drops nodes nobody subscribes to."
  [^Node n path cell]
  (set! (.-refs n) (dec (.-refs n)))
  (if-let [ks (seq path)]
    (let [k (first ks)
          ^Node c (get (.-children n) k)]
      (unregister! c (rest ks) cell)
      (when (zero? (.-refs c))
        (set! (.-children n) (dissoc (.-children n) k))))
    (.delete (.-cells n) cell)))

(defn notify!
  "Calls (mark cell) for every cell whose path value is not identical?
  between old and nu. Unchanged branches are skipped. Returns the number of
  child nodes visited."
  [n old nu mark]
  (let [visits (volatile! 0)]
    (letfn [(walk [n o v]
              (.forEach (.-cells n) (fn [c] (mark c)))
              (reduce-kv (fn [_ k c]
                           (vswap! visits inc)
                           (let [co (get o k)
                                 cv (get v k)]
                             (when-not (identical? co cv) (walk c co cv))))
                         nil
                         (.-children n)))]
      (when-not (identical? old nu) (walk n old nu)))
    @visits))
