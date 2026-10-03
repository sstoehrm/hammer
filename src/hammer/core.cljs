(ns hammer.core
  "Public API: reg-event, reg-fx, dispatch, dispatch-sync, on-error!, defc, is?, mount!."
  (:require-macros [hammer.core])
  (:require [hammer.app :as app]
            [hammer.cells]
            [hammer.dom :as dom]
            [hammer.events :as events]))

(def reg-event app/reg-event)
(def reg-fx app/reg-fx)
(def dispatch app/dispatch)
(def dispatch-sync app/dispatch-sync)
(def on-error! app/on-error!)
(def is? app/is?)

(defn mount!
  "Renders hiccup into el. With db, replaces app-db first; the 2-arity form
  keeps the current db, e.g. in a ^:dev/after-load hook for hot reload."
  ([hiccup el] (dom/mount! hiccup el))
  ([hiccup el db] (events/set-db! db) (dom/mount! hiccup el)))
