(ns hammer.core
  "Public API: reg-event, reg-fx, dispatch, dispatch-sync, defc, is?, mount!."
  (:require-macros [hammer.core])
  (:require [hammer.cells]
            [hammer.dom :as dom]
            [hammer.events :as events]))

(def reg-event events/reg-event)
(def reg-fx events/reg-fx)
(def dispatch events/dispatch)
(def dispatch-sync events/dispatch-sync)

(defn is?
  "Only valid as a whole defc binding init: (is? path v) is true iff the db
  value at path is = to v. defc rewrites the form; calling it throws."
  [_path _v]
  (throw (js/Error. "hammer: is? is only valid as a whole defc binding init")))

(defn mount!
  "Renders hiccup into el. With db, replaces app-db first; the 2-arity form
  keeps the current db, e.g. in a ^:dev/after-load hook for hot reload."
  ([hiccup el] (dom/mount! hiccup el))
  ([hiccup el db] (events/set-db! db) (dom/mount! hiccup el)))
