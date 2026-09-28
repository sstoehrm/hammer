(ns hammer.test-util
  "Shared test helpers.")

(defn capture-errors
  "Runs (f logs) with js/console.error replaced by a stub that conj-es each
  call's args (as a vector) onto logs, an atom starting at []. Restores
  js/console.error afterwards, even if f throws. f takes logs so a test can
  inspect calls made so far partway through (e.g. before/after some action);
  the return value is the final captured vector."
  [f]
  (let [orig js/console.error
        logs (atom [])]
    (set! js/console.error (fn [& args] (swap! logs conj (vec args))))
    (try (f logs) (finally (set! js/console.error orig)))
    @logs))

(defn capture-warnings
  "Like capture-errors, but for js/console.warn."
  [f]
  (let [orig js/console.warn
        logs (atom [])]
    (set! js/console.warn (fn [& args] (swap! logs conj (vec args))))
    (try (f logs) (finally (set! js/console.warn orig)))
    @logs))
