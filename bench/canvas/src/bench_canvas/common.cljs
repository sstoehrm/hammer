(ns bench-canvas.common
  "Counters and the window.bench hook the runner drives. Requires nothing
  from hammer, so every bench build can use it.")

(def ^js D js/BenchData)

(def draws (volatile! 0))
(def renders (volatile! 0))

(defn draw! [] (vswap! draws inc))
(defn render! [] (vswap! renders inc))

(defn params []
  (let [p (js/URLSearchParams. (.-search js/location))]
    {:app (.get p "app") :n (js/parseInt (.get p "n") 10)}))

(defn expose!
  "window.bench = {run(op, k), draws(), renders()}; run calls (trigger op k)."
  [trigger]
  (set! (.-bench js/window)
        #js {:run (fn [op k] (trigger op k))
             :draws (fn [] @draws)
             :renders (fn [] @renders)}))
