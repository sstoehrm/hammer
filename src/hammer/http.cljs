(ns hammer.http
  "The :http effect: fetch, then dispatch :on-success or :on-failure.
  Requiring this namespace registers it; apps that don't require it pay
  nothing. Key names follow re-frame's http-fx where they mean the same.

    {:http {:method :get :uri \"/api/items\" :params {:q \"milk\"}
            :on-success [:loaded] :on-failure [:load-failed]}}

  :on-success gets the body appended; :on-failure gets
  {:uri :status :status-text :failure :response}, :failure being :error
  (non-2xx), :network, :timeout or :parse. A vector of request maps runs each."
  (:require [clojure.string :as str]
            [goog.object :as gobj]
            [hammer.events :as events]
            [hammer.log :as log]))

(defonce ^:private fetch-fn (volatile! nil))

;; abort-key → #js {:ctl AbortController :why nil|"superseded"|"timeout"}, by
;; value: a key built at runtime is = to a literal one but not identical
(defonce ^:private in-flight (volatile! {}))

(defn set-fetch!
  "Replaces js/fetch for :http requests with (fn [url init] promise-of-Response),
  e.g. to add auth or to stub requests in tests; nil restores js/fetch."
  [f]
  (vreset! fetch-fn f)
  nil)

(defn- fetch* [url init]
  (if-let [f @fetch-fn] (f url init) (js/fetch url init)))

(defn- with-params
  "uri with params appended as a query string; a sequential value repeats the
  key, nil values are skipped."
  [uri params]
  (let [sp (js/URLSearchParams.)]
    (doseq [[k v] params
            x (if (sequential? v) v [v])
            :when (some? x)]
      (.append sp (if (keyword? k) (name k) (str k)) (if (keyword? x) (name x) (str x))))
    (let [q (.toString sp)]
      (if (str/blank? q) uri (str uri (if (str/includes? uri "?") "&" "?") q)))))

(defn- json-body? [b] (or (map? b) (vector? b) (seq? b) (set? b)))

(defn- init
  "The fetch init: :fetch-options, then method, headers, body and signal."
  [{:keys [body headers fetch-options]} method signal]
  (let [o (clj->js (or fetch-options {}))
        hs (clj->js (or headers {}))
        json? (json-body? body)]
    (when (and json? (not (some #(= "content-type" (str/lower-case %)) (js-keys hs))))
      (gobj/set hs "Content-Type" "application/json"))
    (gobj/set o "method" method)
    (gobj/set o "headers" hs)
    (when (some? body)
      (gobj/set o "body" (if json? (js/JSON.stringify (clj->js body)) body)))
    (gobj/set o "signal" signal)
    o))

(defn- parse-json [t keywords?]
  (when-not (str/blank? t)
    (js->clj (js/JSON.parse t) :keywordize-keys keywords?)))

(defn- read-body
  "Promise of the success body in format fmt; rejects when JSON doesn't parse."
  [^js res fmt keywords?]
  (case fmt
    :raw (js/Promise.resolve res)
    :blob (.blob res)
    :text (.text res)
    (.then (.text res) #(parse-json % keywords?))))

(defn- error-body
  "An error response's body: parsed JSON when fmt is :json and it parses, else text."
  [t fmt keywords?]
  (if (= fmt :json)
    (try (parse-json t keywords?) (catch :default _ t))
    t))

(defn- request!
  [{:keys [uri params timeout abort-key response-format keywords? on-success on-failure]
    :or {response-format :json keywords? true}
    :as req}]
  (let [method (str/upper-case (name (or (:method req) :get)))
        ctl (js/AbortController.)
        st #js {:ctl ctl :why nil}
        timer (when timeout
                (js/setTimeout #(do (set! (.-why st) "timeout") (.abort ctl)) timeout))
        done! (fn []
                (when timer (js/clearTimeout timer))
                (when (and abort-key (identical? st (get @in-flight abort-key)))
                  (vswap! in-flight dissoc abort-key)))
        fail! (fn [m]
                (done!)
                (let [m (merge {:uri uri :status 0 :status-text ""} m)]
                  (if on-failure
                    (events/dispatch (conj on-failure m))
                    (log/report! :error (str "hammer: http " method " " uri " failed: "
                                             (if (pos? (:status m))
                                               (str (:status m) " " (:status-text m))
                                               (name (:failure m))))
                                 nil))))]
    (when abort-key
      (when-let [^js prev (get @in-flight abort-key)]
        (set! (.-why prev) "superseded")
        (.abort (.-ctl prev)))
      (vswap! in-flight assoc abort-key st))
    (-> (fetch* (with-params uri params) (init req method (.-signal ctl)))
        (.then
         (fn [^js res]
           (let [status {:status (.-status res) :status-text (.-statusText res)}]
             (if (.-ok res)
               (.then (read-body res response-format keywords?)
                      (fn [body]
                        (done!)
                        (when on-success (events/dispatch (conj on-success body))))
                      (fn [_] (fail! (assoc status :failure :parse))))
               (.then (.text res)
                      #(fail! (assoc status :failure :error
                                     :response (error-body % response-format keywords?)))))))
         (fn [_]
           (case (.-why st)
             "superseded" (done!)
             "timeout" (fail! {:failure :timeout})
             (fail! {:failure :network})))))
    nil))

(defn- checked-request! [req]
  (if (string? (:uri req))
    (request! req)
    (log/report! :error (str "hammer: :http needs a :uri string, got " (pr-str (:uri req))) nil)))

(events/reg-fx :http
  (fn [v]
    (if (sequential? v) (run! checked-request! v) (checked-request! v))))
