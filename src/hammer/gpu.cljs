(ns hammer.gpu
  "WebGPU facade: the event API, defdraw/defloop, mount!, pass. One device per
  page, requested on first mount; unsupported browsers get :fallback /
  :on-unsupported; after device loss every component is disposed, re-inited
  and redrawn."
  (:require-macros [hammer.gpu])
  (:require [hammer.app :as app]
            [hammer.draw :as draw]))

(def reg-event app/reg-event)
(def reg-fx app/reg-fx)
(def dispatch app/dispatch)
(def dispatch-sync app/dispatch-sync)
(def is? app/is?)
(def mount! draw/mount!)

;; status: :idle :pending :ready :unsupported
(deftype Dev [^:mutable status ^:mutable device ^:mutable format ^:mutable reason waiting])
(defonce ^:private dev (Dev. :idle nil nil nil (js/Set.)))

(defn reset-device!
  "Test hook: forget the device and any waiting components."
  []
  (set! (.-status dev) :idle)
  (set! (.-device dev) nil)
  (.clear (.-waiting dev)))

(defn- configure! [^draw/State st]
  (let [^js ctx (.getContext ^js (.-canvas st) "webgpu")]
    (.configure ctx #js {:device (.-device dev) :format (.-format dev) :alphaMode "premultiplied"})
    (set! (.-ctx st) ctx)
    (draw/queue! st)))

(defn- fallback! [^draw/State st]
  ;; a throwing :on-unsupported or :fallback render is a bug in the caller's
  ;; component, not ours -- caught and logged so it can't abort a forEach over
  ;; other waiting states or leave `waiting` uncleared.
  (try
    (let [opts (.-opts st)
          ^js ext (.-ext st)]
      (when-let [f (:on-unsupported opts)] (f (.-reason dev)))
      (when-let [h (:fallback opts)]
        (when-let [render (some-> ext .-render)]
          (let [^js wrap (.-wrap ext)]
            (set! (.-textContent wrap) "")
            (.appendChild wrap (render h))))))
    (catch :default e (js/console.error "hammer: gpu fallback failed" e))))

(defn- unsupported! [reason]
  (set! (.-status dev) :unsupported)
  (set! (.-reason dev) reason)
  (js/console.error "hammer: WebGPU unavailable:" reason)
  (.forEach (.-waiting dev) fallback!)
  (.clear (.-waiting dev)))

(declare acquire!)

(defn- lost! []
  (doseq [st (draw/states :gpu)]
    (draw/dispose! st)
    (set! (.-ctx ^draw/State st) nil)
    (.add (.-waiting dev) st))
  (set! (.-status dev) :idle)
  (set! (.-device dev) nil)
  (acquire!))

(defn- ready! [^js device format]
  (set! (.-status dev) :ready)
  (set! (.-device dev) device)
  (set! (.-format dev) format)
  (.then (.-lost device) (fn [^js info]
                           (when (and (identical? device (.-device dev)) (not= "destroyed" (.-reason info)))
                             (lost!))))
  ;; one state's configure! failing (e.g. an adopted canvas that already has
  ;; a 2d context, so getContext "webgpu" is nil) must not stop the others
  ;; from configuring, and must not be mistaken for a page-wide device/adapter
  ;; failure by whatever called ready!.
  (.forEach (.-waiting dev)
            (fn [st]
              (try (configure! st)
                   (catch :default e (js/console.error "hammer: gpu configure failed" e)))))
  (.clear (.-waiting dev)))

(defn- acquire! []
  (when (keyword-identical? (.-status dev) :idle)
    (set! (.-status dev) :pending)
    (let [^js gpu (.-gpu js/navigator)]
      (if-not gpu
        (unsupported! "navigator.gpu is missing")
        ;; the failure callback of each `.then` is passed as its second arg
        ;; (not chained on afterwards via `.catch`) so it only fires for an
        ;; actual requestAdapter/requestDevice rejection -- an exception
        ;; thrown by ready! itself (already guarded above) or by unsupported!
        ;; (guarded in fallback!) can never be mistaken for one and re-log or
        ;; re-run the unsupported path.
        (.then (.requestAdapter gpu)
               (fn [^js a]
                 (if-not a
                   (unsupported! "no WebGPU adapter")
                   (.then (.requestDevice a)
                          (fn [d] (ready! d (.getPreferredCanvasFormat gpu)))
                          (fn [e] (unsupported! (str e))))))
               (fn [e] (unsupported! (str e))))))))

(draw/register-backend!
 :gpu
 (draw/Backend.
  (fn [^draw/State st render]
    (let [wrap (when render
                 (let [s (js/document.createElement "span")]
                   (.setProperty (.-style s) "display" "contents")
                   (.appendChild s (.-canvas st))
                   s))]
      (set! (.-ext st) #js {:wrap wrap :render render})
      (acquire!)
      (case (.-status dev)
        ;; same guard as ready!'s forEach below: a mount that lands here after
        ;; the device is already :ready must not let its own configure!
        ;; failure (e.g. an adopted canvas already in 2d mode) escape setup!.
        :ready (try (configure! st) (catch :default e (js/console.error "hammer: gpu configure failed" e)))
        :unsupported (fallback! st)
        (.add (.-waiting dev) st))
      (or wrap (.-canvas st))))
  (fn [^draw/State st]
    (when-let [^js ctx (.-ctx st)]
      (when (keyword-identical? (.-status dev) :ready)
        (let [^js d (.-device dev)]
          {:device d :queue (.-queue d) :context ctx :format (.-format dev)
           :view (.createView (.getCurrentTexture ctx))}))))
  ;; resized!: a no-op. getCurrentTexture always follows the canvas's current
  ;; backing-store size, and the context's configure call persists across a
  ;; resize -- only device loss needs a reconfigure.
  (fn [_] nil)
  (fn [^draw/State st]
    (.delete (.-waiting dev) st)
    (some-> ^js (.-ctx st) (.unconfigure)))))

(defn pass
  "One render pass on (:view gpu) that clears to clear ([r g b a], default
  transparent black): calls (f pass), ends it and submits."
  [{:keys [device queue view]} {:keys [clear]} f]
  (let [[r g b a] (or clear [0 0 0 0])
        ^js enc (.createCommandEncoder ^js device)
        ^js p (.beginRenderPass enc #js {:colorAttachments
                                         #js [#js {:view view :loadOp "clear" :storeOp "store"
                                                   :clearValue #js {:r r :g g :b b :a a}}]})]
    (f p)
    (.end p)
    (.submit ^js queue #js [(.finish enc)])))
