(ns hammer.draw
  "Runtime shared by draw components (hammer.canvas, hammer.gl): the draw
  runner, the frame queue and loop, and the canvas host (size, DPR, attrs,
  events). A Backend supplies the drawing context. A draw instance keeps its
  State in the instance's vnode field."
  (:require [clojure.string :as str]
            [hammer.cells :as cells]
            [hammer.events :as events]))

;; setup!: (fn [st render] → node) once the canvas exists; draw-arg: (fn [st])
;; → first draw-fn arg, nil to skip this draw; resized!: (fn [st]) after the
;; backing store changed; teardown!: (fn [st]) on unmount.
(deftype Backend [setup! draw-arg resized! teardown!])

(defonce ^:private backends #js {})

(defn register-backend! [kind ^Backend b] (aset backends (name kind) b))

;; broken: the last render (opts/draw-fn) threw. A defdraw (or a non-running
;; defloop) doesn't draw until a render succeeds. A running defloop ignores
;; broken and keeps animating with the previous opts/f -- "a loop keeps
;; running" -- so its clock keeps advancing too.
;; adopted?: canvas was passed to mount! (an existing <canvas> element), not
;; created by hammer. A backend should release resources tied to a canvas
;; (e.g. gl's WEBGL_lose_context) only when hammer created it -- an adopted
;; canvas may be remounted, and browsers return the same underlying context
;; for the same canvas, so releasing it would break the next mount.
(deftype State [inst backend kind loop? canvas order adopted?
                ^:mutable opts ^:mutable f ^:mutable broken ^:mutable ctx ^:mutable res ^:mutable inited
                ^:mutable w ^:mutable h ^:mutable dpr
                ^:mutable t ^:mutable last ^:mutable n ^:mutable running
                listeners ^:mutable observer ^:mutable attrs ^:mutable alive ^:mutable ext])

(defonce ^:private seq-no (volatile! 0))
(defonce ^:private all (js/Set.))      ; alive states
(defonce ^:private queued (js/Set.))   ; states to draw at the next frame
(defonce ^:private loops (js/Set.))    ; mounted defloop states

(deftype Clock [^:mutable pending ^:mutable raf])
(defonce ^:private clock (Clock. false nil))

(declare frame!)

(defn- cname [^State st] (.-cname ^cells/Comp (.-comp ^cells/Instance (.-inst st))))

(defn component-name
  "The component's name (as given to defdraw/defloop), for a backend's own
  logging (e.g. hammer.gl's webglcontextlost warning)."
  [^State st]
  (cname st))

(defn- request! []
  (when-not (.-pending clock)
    (set! (.-pending clock) true)
    (if-let [r (.-raf clock)] (r frame!) (js/requestAnimationFrame frame!))))

(defn set-raf!
  "Test hook: f replaces requestAnimationFrame (called with the frame fn); nil restores it."
  [f]
  (set! (.-raf clock) f)
  (set! (.-pending clock) false))

(defn queue! "Draws st at the next frame." [^State st] (.add queued st) (request!))

(defn states "Alive states of backend kind." [kind]
  (.filter (js/Array.from all) (fn [^State st] (keyword-identical? kind (.-kind st)))))

;; ---- canvas host: size, attrs, events

(defn- set-css! [^js el k v] (.setProperty (.-style el) k v))

(defn- auto-css+measure!
  "Fills the CSS box (display/width/height) and re-reads the live size."
  [^State st]
  (let [^js c (.-canvas st)]
    (set-css! c "display" "block")
    (set-css! c "width" "100%")
    (set-css! c "height" "100%")
    (set! (.-w st) (.-clientWidth c))
    (set! (.-h st) (.-clientHeight c))))

(defn- observe!
  "Auto size: the canvas fills its CSS box and follows it."
  [^State st]
  (auto-css+measure! st)
  (let [^js c (.-canvas st)]
    (set! (.-observer st)
          (if (exists? js/ResizeObserver)
            (doto (js/ResizeObserver.
                   (fn [^js entries]
                     (when-not (:size (.-opts st))
                       (let [r (.-contentRect (aget entries 0))]
                         (set! (.-w st) (.-width r))
                         (set! (.-h st) (.-height r))
                         (queue! st)))))
              (.observe c))
            #js {:disconnect (fn [])}))))

(defn- apply-size! [^State st]
  (if-let [[w h] (:size (.-opts st))]
    (do (set! (.-w st) w)
        (set! (.-h st) h)
        (set-css! (.-canvas st) "width" (str w "px"))
        (set-css! (.-canvas st) "height" (str h "px")))
    (if-not (.-observer st)
      (observe! st)
      ;; switching back from a fixed size: the observer is still watching the
      ;; box, but the CSS and w/h are stuck at the old fixed values until we
      ;; restore them here.
      (when (not= "100%" (.. (.-canvas st) -style -width))
        (auto-css+measure! st)))))

(defn- apply-attrs! [^State st]
  (let [^js c (.-canvas st)
        old (.-attrs st)
        nu (:attrs (.-opts st))]
    (when-not (identical? old nu)
      (doseq [[k v] nu :when (not= v (get old k))]
        (case k
          :class (set! (.-className c) (if (coll? v) (str/join " " (remove nil? v)) (str v)))
          :style (do (doseq [[sk _] (:style old) :when (not (contains? v sk))]
                       (.removeProperty (.-style c) (name sk)))
                     (doseq [[sk sv] v] (set-css! c (name sk) (str sv))))
          (if (nil? v) (.removeAttribute c (name k)) (.setAttribute c (name k) (str v)))))
      (doseq [[k v] old :when (not (contains? nu k))]
        (case k
          :class (set! (.-className c) "")
          :style (doseq [[sk _] v] (.removeProperty (.-style c) (name sk)))
          (.removeAttribute c (name k))))
      (set! (.-attrs st) nu))))

(defn- local-xy [^js c ^js e]
  (when (number? (.-clientX e))
    (let [r (.getBoundingClientRect c)]
      [(- (.-clientX e) (.-left r)) (- (.-clientY e) (.-top r))])))

(defn- handle! [^State st k ^js e]
  (when-let [h (get (.-opts st) k)]
    (let [xy (local-xy (.-canvas st) e)]
      (cond
        (vector? h) (events/dispatch (if xy (into h xy) h))
        (fn? h) (h e (when xy {:x (nth xy 0) :y (nth xy 1)}))))))

(def ^:private non-event-on-keys
  "Opt keys starting with \"on-\" that are not DOM events, so sync-listeners!
  must not register them: :on-unsupported is the gl option."
  #{:on-unsupported})

(defn- event-type
  "The DOM event type of opt key k (\"click\" for :on-click), or nil for
  other keys and the non-event :on- keys."
  [k]
  (when (and (keyword? k) (not (contains? non-event-on-keys k)))
    (let [n (name k)]
      (when (str/starts-with? n "on-") (subs n 3)))))

(defn- sync-listeners!
  "One listener per :on-<type> key of opts (excluding non-event :on- keys);
  listeners map keyed by type string."
  [^State st]
  (let [^js ls (.-listeners st)
        ^js c (.-canvas st)
        ;; type -> opt key, nil when opts have no event keys
        ^js want (reduce-kv (fn [^js acc k _]
                              (if-let [t (event-type k)]
                                (doto (or acc (js/Map.)) (.set t k))
                                acc))
                            nil (.-opts st))]
    (when want
      (.forEach want (fn [k t]
                       (when-not (.has ls t)
                         (let [f (fn [e] (handle! st k e))]
                           (.set ls t f)
                           (.addEventListener c t f))))))
    (when (pos? (.-size ls))
      (.forEach ls (fn [f t]
                     (when-not (and want (.has want t))
                       (.removeEventListener c t f)
                       (.delete ls t)))))))

;; ---- drawing

(defn- sync-size! [^State st]
  (let [dpr (or (.-devicePixelRatio js/globalThis) 1)
        ^js c (.-canvas st)
        bw (js/Math.ceil (* (.-w st) dpr))
        bh (js/Math.ceil (* (.-h st) dpr))]
    (set! (.-dpr st) dpr)
    (when (or (not= bw (.-width c)) (not= bh (.-height c)))
      (set! (.-width c) bw)
      (set! (.-height c) bh)
      ((.-resized! ^Backend (.-backend st)) st))))

(defn- advance!
  "Loop timing for a running loop at frame time ts; returns dt (0 otherwise).
  Called only for frames that draw."
  [^State st ts]
  (if (and (.-loop? st) (.-running st))
    (let [dt (if (nil? (.-last st)) 0 (min (get (.-opts st) :max-dt 100) (- ts (.-last st))))]
      (set! (.-last st) ts)
      (set! (.-t st) (+ (.-t st) dt))
      (set! (.-n st) (inc (.-n st)))
      dt)
    0))

(defn- info [^State st dt]
  (let [m {:w (.-w st) :h (.-h st) :dpr (.-dpr st)}]
    (if (.-loop? st) (assoc m :t (.-t st) :dt dt :n (.-n st)) m)))

(defn- init! [^State st arg i]
  (if-let [init (:init (.-opts st))]
    (try
      (set! (.-res st) (init arg i))
      (set! (.-inited st) true)
      (catch :default e
        (js/console.error "hammer: init failed in" (cname st) e)
        (set! (.-inited st) :failed)))
    (set! (.-inited st) true)))

(defn dispose!
  "Runs :dispose for an initialized state and marks it for a new :init."
  [^State st]
  (when (true? (.-inited st))
    (when-let [d (:dispose (.-opts st))]
      (try (d (.-res st))
           (catch :default e (js/console.error "hammer: dispose failed in" (cname st) e)))))
  (set! (.-res st) nil)
  (set! (.-inited st) false))

(defn- will-draw?
  "False while nothing can draw (no draw fn yet, :init failed, or the last
  opts/draw-fn render failed): the frame then leaves the canvas, and its last
  content, alone. Exception: a running defloop ignores a failed render and
  keeps drawing with its previous opts/f -- a loop keeps running."
  [^State st]
  (and (.-alive st) (some? (.-f st))
       (or (not (.-broken st)) (and (.-loop? st) (.-running st)))
       (not (keyword-identical? :failed (.-inited st)))))

(defn- draw! [^State st ts]
  (if-let [arg (when (and (will-draw? st) (pos? (.-w st)) (pos? (.-h st)))
                 (sync-size! st)
                 ((.-draw-arg ^Backend (.-backend st)) st))]
    (let [f (.-f st)
          i (info st (advance! st ts))]
      (when (false? (.-inited st)) (init! st arg i))
      (when (true? (.-inited st))
        (try
          (if (contains? (.-opts st) :init) (f arg i (.-res st)) (f arg i))
          (catch :default e
            (js/console.error "hammer: draw failed in" (cname st) e)))))
    ;; not drawable (zero size, no draw arg yet, e.g. gl context lost, or
    ;; see will-draw?): a loop's clock stands still, :t and :n don't
    ;; advance, and as after a pause the next drawn frame gets :dt 0.
    (set! (.-last st) nil)))

(defn- some-running? []
  (let [r (volatile! false)]
    (.forEach loops (fn [^State st] (when (.-running st) (vreset! r true))))
    @r))

(defn frame!
  "One animation frame at time ts (ms): draws every queued state and every
  running loop once, in mount order, then requests another frame while a loop
  runs."
  [ts]
  (set! (.-pending clock) false)
  (let [s (js/Set. queued)]
    (.clear queued)
    (.forEach loops (fn [^State st] (when (.-running st) (.add s st))))
    (.forEach (.sort (js/Array.from s) (fn [^State a ^State b] (- (.-order a) (.-order b))))
              (fn [st] (draw! st ts))))
  (when (or (pos? (.-size queued)) (some-running?)) (request!)))

;; ---- instances

(defn- rerender!
  "Re-evaluates opts and the draw fn from the instance's current bindings and
  queues a draw. If that throws, it is logged and the state is broken: the
  previous opts stay, and nothing is queued or drawn until a render succeeds."
  [^State st]
  (if-let [out (try (cells/render (.-inst st))
                    (catch :default e
                      (js/console.error "hammer: render failed in" (cname st) e)
                      nil))]
    (let [old (.-opts st)
          nu (or (aget out 0) {})]
      (set! (.-broken st) false)
      (set! (.-opts st) nu)
      (set! (.-f st) (aget out 1))
      ;; opts = the last applied ones (nil before the first render): size,
      ;; attrs and listeners are already in place.
      (when-not (and (some? old) (= old nu))
        (apply-size! st)
        (apply-attrs! st)
        (sync-listeners! st))
      (when (.-loop? st)
        (let [r (boolean (get nu :run? true))]
          (when-not r (set! (.-last st) nil))
          (set! (.-running st) r)))
      (queue! st))
    (set! (.-broken st) true)))

(defn- run-host!
  "Host run: recompute bindings; if the draw fn or opts depend on a change,
  re-evaluate them and queue a draw."
  [^cells/Instance inst]
  (set! (.-dirty inst) false)
  (when (.-mounted inst)
    (when (try (cells/refresh! inst)
               (catch :default e
                 (js/console.error "hammer: render failed in" (.-cname ^cells/Comp (.-comp inst)) e)
                 false))
      (rerender! (.-vnode inst)))))

(defonce ^:private resize-hooked (volatile! false))

(defn- hook-resize!
  "Window resize (also fired on zoom and DPR changes): redraw everything."
  []
  (when-not @resize-hooked
    (vreset! resize-hooked true)
    (.addEventListener js/window "resize" (fn [_] (.forEach all (fn [st] (queue! st)))))))

(defn- unwind!
  "Removes st from every registry and stops observing/listening. Used both
  by destroy! and by create-host to roll back after setup! throws, so a
  failed mount never leaves a state that keeps drawing or looping."
  [^State st]
  (let [^js c (.-canvas st)]
    (set! (.-alive st) false)
    (.delete all st)
    (.delete queued st)
    (.delete loops st)
    (.forEach (.-listeners st) (fn [f t] (.removeEventListener c t f)))
    (.clear (.-listeners st))
    (some-> ^js (.-observer st) (.disconnect))))

(defn- create-host [^cells/Instance inst kind loop? render el]
  (let [backend (aget backends (name kind))]
    (when-not backend
      (throw (js/Error. (str "hammer: no " (name kind) " backend loaded (require hammer." (name kind) ")"))))
    (let [st (State. inst backend kind loop? (or el (js/document.createElement "canvas")) (vswap! seq-no inc) (some? el)
                     nil nil false nil nil false
                     0 0 1
                     0 nil 0 false
                     (js/Map.) nil nil true nil)]
      (set! (.-vnode inst) st)
      (.add all st)
      (when loop? (.add loops st))
      (hook-resize!)
      (try
        (rerender! st)
        ((.-setup! ^Backend backend) st render)
        (catch :default e
          (unwind! st)
          (throw e))))))

(defn- destroy! [^cells/Instance inst]
  (let [^State st (.-vnode inst)]
    (unwind! st)
    (dispose! st)
    (try
      ((.-teardown! ^Backend (.-backend st)) st)
      (catch :default e
        (js/console.error "hammer: teardown failed in" (cname st) e)))
    (cells/destroy! inst)))

(defn component
  "Built by defdraw/defloop: a hammer component drawn by backend kind."
  [cname nprops specs body-deps body kind loop?]
  (cells/component cname nprops specs body-deps body
                   (cells/Host. run-host!
                                (fn [inst render el] (create-host inst kind loop? render el))
                                destroy!)))

;; ---- standalone mounting

(defn- destroy-inst! [^cells/Instance inst]
  ((.-destroy ^cells/Host (cells/host (.-comp inst))) inst))

(defn mount!
  "Mounts a draw component vector on el: an existing <canvas> is adopted,
  anything else gets a canvas inside. Whatever an earlier mount! (of any
  renderer: hammer.core/mount! too) put on el is unmounted first. With db,
  replaces app-db first."
  ([hiccup ^js el]
   (let [c (nth hiccup 0 nil)
         ^cells/Host h (when (cells/component? c) (cells/host c))]
     (when-not h (throw (js/Error. "hammer: mount! takes a draw component vector, e.g. [chart]")))
     (cells/unmount-root! el)
     (let [canvas? (= "CANVAS" (.-tagName el))
           inst (cells/create c hiccup 1 1)]
       (when-not canvas? (set! (.-textContent el) ""))
       (let [^js n (try
                     ((.-create h) inst nil (when canvas? el))
                     (catch :default e
                       ;; the instance's bindings already subscribed paths in
                       ;; cells/create above; without this, a failed create leaks
                       ;; that subscription forever.
                       (cells/destroy! inst)
                       (throw e)))]
         (when-not canvas? (.appendChild el n))
         (cells/set-root! el
                          (fn []
                            (destroy-inst! inst)
                            ;; a canvas mount! created goes too; an adopted one stays.
                            (when (and (not canvas?) (identical? el (.-parentNode n)))
                              (.removeChild el n))))))))
  ([hiccup el db]
   (events/set-db! db)
   (mount! hiccup el)))

(defn unmount-all!
  "Unmounts every root (the registry is shared with hammer.dom, so DOM roots
  too) and removes the canvases mount! created (adopted <canvas> elements
  stay)."
  []
  (cells/unmount-roots!))
