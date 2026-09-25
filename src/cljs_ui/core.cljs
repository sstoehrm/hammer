(ns cljs-ui.core
  "Public API: reg-event, reg-fx, dispatch, dispatch-sync, defc, mount!."
  (:require-macros [cljs-ui.core])
  (:require [cljs-ui.cells]
            [cljs-ui.dom :as dom]
            [cljs-ui.events :as events]))

(def reg-event events/reg-event)
(def reg-fx events/reg-fx)
(def dispatch events/dispatch)
(def dispatch-sync events/dispatch-sync)

(defn mount!
  "Sets app-db to db and renders hiccup into el."
  [hiccup el db]
  (events/set-db! db)
  (dom/mount! hiccup el))
