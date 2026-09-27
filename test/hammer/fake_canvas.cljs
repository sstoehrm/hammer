(ns hammer.fake-canvas
  "jsdom has no canvas contexts: getContext(\"2d\") returns a recording fake."
  (:require [hammer.test-env]))

(def ops-2d ["clearRect" "fillRect" "setTransform" "beginPath" "arc" "fill" "stroke"])

(defonce log (atom []))

(defn- fake-2d [^js canvas]
  (let [o #js {:canvas canvas}]
    (doseq [m ops-2d]
      (aset o m (fn [& args] (swap! log conj (into [(keyword m) (.-id canvas)] args)))))
    o))

(defonce installed
  (let [proto (.. js/window -HTMLCanvasElement -prototype)
        orig (.-getContext proto)]
    (set! (.-getContext proto)
          (fn [kind]
            (this-as ^js canvas
              (if (= kind "2d")
                (or (.-__fake2d canvas)
                    (let [o (fake-2d canvas)] (set! (.-__fake2d canvas) o) o))
                (.call orig canvas kind)))))
    true))

(defn ops
  "Recorded calls on the canvas with id, as [op & args]."
  [id]
  (into [] (comp (filter #(= id (second %))) (map #(into [(first %)] (drop 2 %)))) @log))
