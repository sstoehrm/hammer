(ns cljs-ui.scheduler
  "Dirty instances, flushed once per animation frame, parents first.")

(deftype State [^:mutable pending ^:mutable run])

(defonce ^:private dirty #js [])
(defonce ^:private st (State. false nil))

(defn set-runner!
  "f is called with each dirty instance during a flush."
  [f]
  (set! (.-run st) f))

(defn runner [] (.-run st))

(defn pending? [] (pos? (.-length dirty)))

(defn flush!
  "Runs the instances queued so far, lowest depth first. An instance whose run
  throws is logged and skipped; the rest of the batch still runs."
  []
  (set! (.-pending st) false)
  (let [xs (.sort (.splice dirty 0) (fn [^js a ^js b] (- (.-depth a) (.-depth b))))]
    (when-let [run (.-run st)]
      (.forEach xs (fn [^js x]
                     (when (.-dirty x)
                       (try
                         (run x)
                         (catch :default e
                           (js/console.error "cljs-ui: update failed" e)))))))))

(defn schedule! [^js inst]
  (when-not (.-dirty inst)
    (set! (.-dirty inst) true)
    (.push dirty inst)
    (when-not (.-pending st)
      (set! (.-pending st) true)
      (js/requestAnimationFrame flush!))))
