(ns hammer.trie
  "Path subscriptions. A node holds the cells subscribed to exactly its path,
  an equality index (compared value -> cells) for is? subscriptions, and
  counts every registration at or below it.
  Keys (path items, is? values) keep value semantics: numbers, strings,
  booleans and nil live in a js/Map by value, keywords in a js/Map by their
  fqn (so a runtime-built keyword finds the literal one, and \"a\" never
  finds :a), anything else in a persistent map (children) or a hash-bucketed
  js/Map (is? values).
  A cell set is nil, one cell, or a js/Set of cells. Cells are removed by
  identity: pass the same object to unregister! that register! got.")

(deftype Node [key ^:mutable cells ^:mutable refs ^:mutable eq
               ^:mutable prim ^:mutable kws ^:mutable other])
(deftype Index [^:mutable prim ^:mutable kws ^:mutable other])
(deftype Entry [v ^:mutable cells])

(defn node [] (Node. nil nil 0 nil nil nil nil))

;; ---- cell sets: nil | cell | js/Set

(defn- cells+ [cs c]
  (cond
    (nil? cs) c
    (instance? js/Set cs) (doto ^js cs (.add c))
    (identical? cs c) cs
    :else (doto (js/Set.) (.add cs) (.add c))))

(defn- cells- [cs c]
  (cond
    (instance? js/Set cs) (do (.delete ^js cs c) (when (pos? (.-size ^js cs)) cs))
    (identical? cs c) nil
    :else cs))

(defn- each-cell! [cs mark]
  (cond
    (nil? cs) nil
    (instance? js/Set cs) (.forEach ^js cs (fn [c] (mark c)))
    :else (mark cs)))

(defn- has-cell? [cs c]
  (if (instance? js/Set cs) (.has ^js cs c) (identical? cs c)))

(defn- prim? [k] (or (number? k) (string? k) (nil? k) (boolean? k)))

;; ---- children

(defn child
  "The child node of n at key k, or nil."
  [^Node n k]
  (let [c (cond
            (keyword? k) (when-let [^js m (.-kws n)] (.get m (.-fqn k)))
            (prim? k) (when-let [^js m (.-prim n)] (.get m k))
            :else (when-let [m (.-other n)] (get m k)))]
    (when-not (undefined? c) c)))

(defn- add-child! [^Node n k]
  (let [c (Node. k nil 0 nil nil nil nil)]
    (cond
      (keyword? k) (.set (or (.-kws n) (set! (.-kws n) (js/Map.))) (.-fqn k) c)
      (prim? k) (.set (or (.-prim n) (set! (.-prim n) (js/Map.))) k c)
      :else (set! (.-other n) (assoc (.-other n) k c)))
    c))

(defn- remove-child! [^Node n k]
  (cond
    (keyword? k) (let [^js m (.-kws n)]
                   (.delete m (.-fqn k))
                   (when (zero? (.-size m)) (set! (.-kws n) nil)))
    (prim? k) (let [^js m (.-prim n)]
                (.delete m k)
                (when (zero? (.-size m)) (set! (.-prim n) nil)))
    :else (let [m (dissoc (.-other n) k)]
            (set! (.-other n) (when (seq m) m)))))

(defn- each-child!
  "Calls (f child) for every child node; the child's key is (.-key child)."
  [^Node n f]
  (when-let [^js m (.-prim n)] (.forEach m (fn [c] (f c))))
  (when-let [^js m (.-kws n)] (.forEach m (fn [c] (f c))))
  (when-let [m (.-other n)] (reduce-kv (fn [_ _ c] (f c)) nil m)))

(defn child-keys
  "The keys of n's children (for tests and debugging)."
  [n]
  (let [ks #js []]
    (each-child! n (fn [^Node c] (.push ks (.-key c))))
    (vec ks)))

;; ---- walking

(defn- path-vec [path] (if (vector? path) path (vec path)))

(defn- acquire!
  "Adds 1 to refs from n down path, creating nodes; returns the leaf."
  [^Node n path]
  (let [p (path-vec path)
        len (count p)]
    (loop [n n i 0]
      (let [^Node n n]
        (set! (.-refs n) (inc (.-refs n)))
        (if (< i len)
          (let [k (nth p i)]
            (recur (or (child n k) (add-child! n k)) (inc i)))
          n)))))

(defn- release!
  "Subtracts 1 from refs from n down path p (from index i); drops children
  whose refs reach zero. Returns the leaf, or nil if the path is missing."
  [^Node n p i len]
  (set! (.-refs n) (dec (.-refs n)))
  (if (< i len)
    (let [k (nth p i)]
      (when-let [c (child n k)]
        (let [leaf (release! c p (inc i) len)]
          (when (zero? (.-refs ^Node c)) (remove-child! n k))
          leaf)))
    n))

(defn register!
  "Subscribes cell to path below node n."
  [n path cell]
  (let [^Node l (acquire! n path)]
    (set! (.-cells l) (cells+ (.-cells l) cell))))

(defn unregister!
  "Removes cell from path below node n; drops nodes nobody subscribes to."
  [n path cell]
  (let [p (path-vec path)]
    (when-let [^Node l (release! n p 0 (count p))]
      (set! (.-cells l) (cells- (.-cells l) cell)))))

;; ---- is? index

(defn- bucket-entry
  "The entry of hash-bucketed js/Map m whose value is = to v."
  [^js m v]
  (when-let [^js b (.get m (hash v))]
    (.find b (fn [^Entry e] (= (.-v e) v)))))

(defn- eq-cells
  "The cell set compared to v in index x."
  [^Index x v]
  (let [cs (cond
             (keyword? v) (when-let [^js m (.-kws x)] (.get m (.-fqn v)))
             (prim? v) (when-let [^js m (.-prim x)] (.get m v))
             :else (when-let [^js m (.-other x)]
                     (when-let [^Entry e (bucket-entry m v)] (.-cells e))))]
    (when-not (undefined? cs) cs)))

(defn register-eq!
  "Subscribes cell to changes of (= value-at-path v) below node n."
  [n path v cell]
  (let [^Node l (acquire! n path)
        ^Index x (or (.-eq l) (set! (.-eq l) (Index. nil nil nil)))]
    (cond
      (keyword? v) (let [^js m (or (.-kws x) (set! (.-kws x) (js/Map.)))
                         k (.-fqn v)]
                     (.set m k (cells+ (.get m k) cell)))
      (prim? v) (let [^js m (or (.-prim x) (set! (.-prim x) (js/Map.)))]
                  (.set m v (cells+ (.get m v) cell)))
      :else (let [^js m (or (.-other x) (set! (.-other x) (js/Map.)))]
              (if-let [^Entry e (bucket-entry m v)]
                (set! (.-cells e) (cells+ (.-cells e) cell))
                (let [h (hash v)
                      b (or (.get m h) (let [b #js []] (.set m h b) b))]
                  (.push b (Entry. v cell))))))))

(defn- map-remove!
  "Removes cell from the cell set at key k of js/Map m; true if m is now empty."
  [^js m k cell]
  (let [cs (.get m k)]
    (when-not (undefined? cs)
      (if-some [cs (cells- cs cell)]
        (.set m k cs)
        (.delete m k))))
  (zero? (.-size m)))

(defn unregister-eq!
  "Removes an is? subscription made by register-eq! with the same path, v and cell."
  [n path v cell]
  (let [p (path-vec path)]
    (when-let [^Node l (release! n p 0 (count p))]
      (when-let [^Index x (.-eq l)]
        (cond
          (keyword? v) (when-let [m (.-kws x)]
                         (when (map-remove! m (.-fqn v) cell) (set! (.-kws x) nil)))
          (prim? v) (when-let [m (.-prim x)]
                      (when (map-remove! m v cell) (set! (.-prim x) nil)))
          :else
          (when-let [^js m (.-other x)]
            (let [h (hash v)]
              (when-let [^js b (.get m h)]
                ;; by cell, not by =: a value that is not = to itself ([NaN]) still unregisters
                (when-let [^Entry e (.find b (fn [^Entry e] (has-cell? (.-cells e) cell)))]
                  (let [cs (cells- (.-cells e) cell)]
                    (set! (.-cells e) cs)
                    (when (nil? cs)
                      (.splice b (.indexOf b e) 1)
                      (when (zero? (.-length b)) (.delete m h))
                      (when (zero? (.-size m)) (set! (.-other x) nil)))))))))
        (when (and (nil? (.-prim x)) (nil? (.-kws x)) (nil? (.-other x)))
          (set! (.-eq l) nil))))))

;; ---- notify

(deftype Walk [mark ^:mutable visits])

(defn- walk! [^Walk w ^Node n o v]
  (let [mark (.-mark w)]
    (each-cell! (.-cells n) mark)
    (when-let [x (.-eq n)]
      (let [eo (eq-cells x o)
            ev (eq-cells x v)]
        ;; found for both and = means the same entry: nothing flips
        (when (and (or eo ev) (not (and eo ev (= o v))))
          (each-cell! eo mark)
          (each-cell! ev mark))))
    (each-child! n (fn [^Node c]
                     (set! (.-visits w) (inc (.-visits w)))
                     (let [k (.-key c)
                           co (get o k)
                           cv (get v k)]
                       (when-not (identical? co cv) (walk! w c co cv)))))))

(defn notify!
  "Calls (mark cell) for every cell whose path value is not identical?
  between old and nu, and for every is? cell compared to the old or the new
  value when they are not =. Unchanged branches are skipped. Returns the
  number of child nodes visited."
  [n old nu mark]
  (let [w (Walk. mark 0)]
    (when-not (identical? old nu) (walk! w n old nu))
    (.-visits w)))
