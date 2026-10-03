(ns hammer.log
  "Every hammer: error and warning goes through report!: to the test
  collector (once hammer.testing turned it on), then to the reporter, the
  console unless on-error! replaced it. hammer logs and keeps going rather
  than throw, so this is where its mistakes show up.")

(defonce ^:private reporter (volatile! nil))
(defonce ^:private collected (volatile! nil))

(defn- console! [{:keys [level message error]}]
  (if (keyword-identical? level :warn)
    (if (some? error) (.warn js/console message error) (.warn js/console message))
    (if (some? error) (.error js/console message error) (.error js/console message))))

(defn on-error!
  "Replaces the reporter with (fn [{:keys [level message error]}]); level is
  :error or :warn, error the caught value or nil. nil restores the console."
  [f]
  (vreset! reporter f)
  nil)

(defn report!
  "Reports message (a \"hammer: ...\" string) at level :error or :warn, with
  the caught value error or nil."
  [level message error]
  (let [r {:level level :message message :error error}]
    (when-let [c @collected] (vreset! collected (conj c r)))
    (if-let [f @reporter]
      (try
        (f r)
        (catch :default e
          (console! r)
          (console! {:level :error :message "hammer: on-error! reporter failed" :error e})))
      (console! r))
    nil))

(defn collect!
  "Turns on the test collector (hammer.testing does this when loaded)."
  []
  (when-not @collected (vreset! collected [])))

(defn take!
  "The collected reports, oldest first; empties the collector."
  []
  (let [c (or @collected [])]
    (when @collected (vreset! collected []))
    c))

(defn isolated
  "Runs (f) with an empty collector; returns what was reported during it and
  puts back what was collected before."
  [f]
  (let [prev @collected]
    (vreset! collected [])
    (try
      (f)
      @collected
      (finally (vreset! collected prev)))))
