(ns hammer.gl
  "WebGL2 facade: the event API, defdraw/defloop and mount!. Each component
  owns a WebGL2 context on its canvas. Without WebGL2: :fallback /
  :on-unsupported (logged once per page). On webglcontextlost: :dispose and
  no drawing; on webglcontextrestored: :init again and redraw. Unmount
  releases the context (WEBGL_lose_context) for a canvas hammer created; an
  adopted canvas (passed to mount!) keeps its context, since browsers return
  the same one on a later remount."
  (:require-macros [hammer.gl])
  (:require [hammer.app :as app]
            [hammer.draw :as draw]
            [hammer.log :as log]))

(def reg-event app/reg-event)
(def reg-fx app/reg-fx)
(def dispatch app/dispatch)
(def dispatch-sync app/dispatch-sync)
(def on-error! app/on-error!)
(def is? app/is?)
(def mount! draw/mount!)

(defonce ^:private logged (volatile! false))

(defn reset-log! "Test hook: the next unsupported context logs again." [] (vreset! logged false))

(defn- fallback! [^draw/State st reason]
  ;; a throwing :on-unsupported or :fallback render is the caller's bug: logged,
  ;; never aborts the mount
  (try
    (let [opts (.-opts st)
          ^js ext (.-ext st)]
      (when-let [f (:on-unsupported opts)] (f reason))
      (when-let [h (:fallback opts)]
        (when-let [render (some-> ext .-render)]
          (let [^js wrap (.-wrap ext)]
            (set! (.-textContent wrap) "")
            (.appendChild wrap (render h))))))
    (catch :default e (log/report! :error "hammer: gl fallback failed" e))))

(defn- unsupported! [st reason]
  (when-not @logged
    (vreset! logged true)
    (log/report! :error (str "hammer: WebGL2 unavailable: " reason) nil))
  (fallback! st reason))

(draw/register-backend!
 :gl
 (draw/Backend.
  (fn [^draw/State st render]
    (let [^js c (.-canvas st)
          wrap (when render
                 (let [s (js/document.createElement "span")]
                   (.setProperty (.-style s) "display" "contents")
                   (.appendChild s c)
                   s))
          ^js ext #js {:wrap wrap :render render :lost false :onlost nil :onrestored nil}
          ^js g (.getContext c "webgl2" (clj->js (or (:context-attrs (.-opts st)) {})))]
      (set! (.-ext st) ext)
      (if-not g
        (unsupported! st "no WebGL2 context (canvas already has another context type?)")
        (let [on-lost (fn [^js e]
                        (.preventDefault e)
                        (set! (.-lost ext) true)
                        (log/report! :warn (str "hammer: WebGL2 context lost in " (draw/component-name st)
                                                " -- it stays blank until the browser restores it")
                                     nil)
                        (draw/dispose! st))
              on-restored (fn [_]
                            (set! (.-lost ext) false)
                            (draw/queue! st))]
          (set! (.-onlost ext) on-lost)
          (set! (.-onrestored ext) on-restored)
          (.addEventListener c "webglcontextlost" on-lost)
          (.addEventListener c "webglcontextrestored" on-restored)
          (set! (.-ctx st) g)
          ;; an adopted canvas keeps its underlying context across remounts;
          ;; if it was already lost (e.g. a previous mount released it, or the
          ;; loss happened before this mount ever attached listeners), the
          ;; browser will not re-fire webglcontextlost for us -- start lost
          ;; and wait for webglcontextrestored like the running case does.
          (when (.isContextLost g) (set! (.-lost ext) true))))
      (or wrap c)))
  (fn [^draw/State st]
    (let [^js g (.-ctx st)]
      (when (and g (not (.-lost ^js (.-ext st))))
        (.viewport g 0 0 (.-drawingBufferWidth g) (.-drawingBufferHeight g))
        g)))
  ;; the viewport is set before every draw, so a resize needs nothing more
  (fn [_] nil)
  (fn [^draw/State st]
    (let [^js ext (.-ext st)
          ^js c (.-canvas st)
          ^js g (.-ctx st)]
      (when-let [f (some-> ext .-onlost)]
        (.removeEventListener c "webglcontextlost" f)
        (.removeEventListener c "webglcontextrestored" (.-onrestored ext)))
      ;; only release a context hammer created the canvas for: an adopted
      ;; canvas may be remounted, and getContext returns the same context for
      ;; the same canvas, so losing it here would leave the next mount with a
      ;; context that never recovers.
      (when (and g (not (.-adopted? st)))
        (some-> (.getExtension g "WEBGL_lose_context") (.loseContext)))
      (set! (.-ctx st) nil)))))
