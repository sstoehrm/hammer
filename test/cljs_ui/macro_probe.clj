(ns cljs-ui.macro-probe)

(defmacro expand-error
  "Macroexpands form at compile time; returns the root-cause message of the
  exception it throws, or nil if it expands cleanly."
  [form]
  (try
    (macroexpand-1 form)
    nil
    (catch Throwable e
      (loop [e e] (if-let [c (ex-cause e)] (recur c) (ex-message e))))))
