(ns hammer.test-util
  "Shared test helpers."
  (:require [hammer.testing :as t]))

(defn capture-errors
  "Runs (f logs) with js/console.error replaced by a stub that conj-es each
  call's args (as a vector) onto logs, an atom starting at []. Restores
  js/console.error afterwards, even if f throws. f takes logs so a test can
  inspect calls made so far partway through (e.g. before/after some action);
  the return value is the final captured vector. Runs inside
  hammer.testing/expect-errors, so the errors never fail a flush!."
  [f]
  (let [orig js/console.error
        logs (atom [])]
    (set! js/console.error (fn [& args] (swap! logs conj (vec args))))
    (try (t/expect-errors #(f logs)) (finally (set! js/console.error orig)))
    @logs))

(defn capture-warnings
  "Like capture-errors, but for js/console.warn."
  [f]
  (let [orig js/console.warn
        logs (atom [])]
    (set! js/console.warn (fn [& args] (swap! logs conj (vec args))))
    (try (t/expect-errors #(f logs)) (finally (set! js/console.warn orig)))
    @logs))
