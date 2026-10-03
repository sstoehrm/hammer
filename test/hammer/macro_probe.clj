(ns hammer.macro-probe)

(defmacro expand-error
  "Macroexpands form at compile time; returns the root-cause message of the
  exception it throws, or nil if it expands cleanly."
  [form]
  (try
    (macroexpand-1 form)
    nil
    (catch Throwable e
      (loop [e e] (if-let [c (ex-cause e)] (recur c) (ex-message e))))))

(defmacro expand-warnings
  "Macroexpands form at compile time; returns the messages of the analyzer
  warnings it emits (via cljs.analyzer/warning), as a vector of strings."
  [form]
  (let [seen (atom [])]
    (binding [cljs.analyzer/*cljs-warning-handlers*
              [(fn [type _env extra] (swap! seen conj (cljs.analyzer/error-message type extra)))]]
      (cljs.analyzer/macroexpand-1 &env form))
    @seen))
