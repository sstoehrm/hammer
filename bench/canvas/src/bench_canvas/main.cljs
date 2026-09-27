(ns bench-canvas.main
  "bench-canvas build entry: hammer.canvas only (no hammer.core / hammer.dom).
  ?app= picks the scenario, ?n= its size."
  (:require [bench-canvas.common :as c]
            [bench-canvas.table :as table]
            [bench-canvas.rects :as rects]
            [bench-canvas.loops :as loops]))

(defn main []
  (let [{:keys [app n]} (c/params)
        el (js/document.getElementById "app")]
    (case app
      "table" (table/start! n el)
      "rects" (rects/start! n el)
      "loop" (loops/start! n false el)
      "loop-atom" (loops/start! n true el)
      (throw (js/Error. (str "bench: unknown app " app))))))
