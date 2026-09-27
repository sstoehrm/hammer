(ns hammer.app
  "The event API shared by every variant facade (hammer.core, hammer.canvas, hammer.gpu)."
  (:require [hammer.events :as events]))

(def reg-event events/reg-event)
(def reg-fx events/reg-fx)
(def dispatch events/dispatch)
(def dispatch-sync events/dispatch-sync)

(defn is?
  "Only valid as a whole binding init of defc/defdraw/defloop: (is? path v) is
  true iff the db value at path is = to v. The macros rewrite the form; calling
  it throws."
  [_path _v]
  (throw (js/Error. "hammer: is? is only valid as a whole binding init")))
