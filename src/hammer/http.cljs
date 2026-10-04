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
  "uri with params appended as a query string, before any #fragment; a
  sequential value repeats the key, nil values are skipped."
  [uri params]
  (let [sp (js/URLSearchParams.)]
    (doseq [[k v] params
            x (if (sequential? v) v [v])
            :when (some? x)]
      (.append sp (if (keyword? k) (name k) (str k)) (if (keyword? x) (name x) (str x))))
    (let [q (.toString sp)]
      (if (str/blank? q)
        uri
        (let [h (.indexOf uri "#")
              [base frag] (if (neg? h) [uri ""] [(subs uri 0 h) (subs uri h)])]
          (str base (if (str/includes? base "?") "&" "?") q frag))))))

(defn- json-body?
  "clj data, or a plain #js object (fetch would send it as \"[object Object]\")."
  [b]
  (or (map? b) (vector? b) (seq? b) (set? b) (object? b)))

(defn- init
  "The fetch init: :fetch-options, then method, headers, body and signal.
  :headers may be a map, a js/Headers or anything its constructor takes."
  [{:keys [body headers fetch-options]} method signal]
  (let [o (clj->js (or fetch-options {}))
        hs (js/Headers. (if (map? headers) (clj->js headers) (or headers #js {})))
        json? (json-body? body)]
    (when (and json? (not (.has hs "content-type")))
      (.set hs "Content-Type" "application/json"))
    (gobj/set o "method" method)
    (gobj/set o "headers" hs)
    (when (some? body)
      (gobj/set o "body" (if json? (js/JSON.stringify (if (object? body) body (clj->js body))) body)))
    (gobj/set o "signal" signal)
    o))

(defn- parse-json [t keywords?]
  (when-not (str/blank? t)
    (js->clj (js/JSON.parse t) :keywordize-keys keywords?)))

(defn- outcome
  "Promise of the response's outcome: [:ok body] or [:fail failure-map]. It
  rejects only when reading the body fails (an abort, a dropped connection)."
  [^js res fmt keywords?]
  (let [status {:status (.-status res) :status-text (.-statusText res)}]
    (if (.-ok res)
      (case fmt
        :raw (js/Promise.resolve [:ok res])
        :blob (.then (.blob res) (fn [b] [:ok b]))
        :text (.then (.text res) (fn [t] [:ok t]))
        (.then (.text res)
               (fn [t]
                 (try [:ok (parse-json t keywords?)]
                      (catch :default _ [:fail (assoc status :failure :parse)])))))
      (.then (.text res)
             (fn [t]
               [:fail (assoc status :failure :error
                             :response (if (= fmt :json)
                                         (try (parse-json t keywords?) (catch :default _ t))
                                         t))])))))

(defn- request!
  [{:keys [uri params timeout abort-key response-format keywords? on-success on-failure]
    :or {response-format :json keywords? true}
    :as req}]
  (let [method (str/upper-case (name (or (:method req) :get)))
        ctl (js/AbortController.)
        st #js {:ctl ctl :why nil}
        timer (when timeout
                (js/setTimeout #(do (set! (.-why st) "timeout") (.abort ctl)) timeout))
        fail! (fn [m]
                (let [m (merge {:uri uri :status 0 :status-text ""} m)]
                  (if on-failure
                    (events/dispatch (conj on-failure m))
                    (log/report! :error (str "hammer: http " method " " uri " failed: "
                                             (if (pos? (:status m))
                                               (str (:status m) " " (:status-text m))
                                               (name (:failure m))))
                                 nil))))
        ;; the one exit of every path: why the request was aborted wins over
        ;; what the response or a body read did meanwhile
        settle! (fn [[kind x]]
                  (when timer (js/clearTimeout timer))
                  (when (and abort-key (identical? st (get @in-flight abort-key)))
                    (vswap! in-flight dissoc abort-key))
                  (case (.-why st)
                    "superseded" nil
                    "timeout" (fail! {:failure :timeout})
                    (case kind
                      :ok (when on-success (events/dispatch (conj on-success x)))
                      :fail (fail! x)
                      (fail! {:failure :network}))))]
    (when abort-key
      (when-let [^js prev (get @in-flight abort-key)]
        (set! (.-why prev) "superseded")
        (.abort (.-ctl prev)))
      (vswap! in-flight assoc abort-key st))
    (-> (try (fetch* (with-params uri params) (init req method (.-signal ctl)))
             (catch :default e (js/Promise.reject e)))  ; a custom fetch that throws
        (.then #(outcome % response-format keywords?))
        (.then settle! (fn [_] (settle! [:network])))
        (.catch #(log/report! :error (str "hammer: http " method " " uri " failed") %)))
    nil))

(def ^:private formats #{:json :text :blob :raw})

(defn- problem
  "Why request req can't be sent, or nil."
  [{:keys [uri method body response-format on-success on-failure]}]
  (let [m (str/upper-case (name (or method :get)))]
    (cond
      (not (string? uri)) (str ":http needs a :uri string, got " (pr-str uri))
      (not (or (nil? on-success) (vector? on-success)))
      (str ":http :on-success must be an event vector, got " (pr-str on-success))
      (not (or (nil? on-failure) (vector? on-failure)))
      (str ":http :on-failure must be an event vector, got " (pr-str on-failure))
      (not (or (nil? response-format) (contains? formats response-format)))
      (str ":http :response-format must be :json, :text, :blob or :raw, got " (pr-str response-format))
      (and (some? body) (contains? #{"GET" "HEAD"} m))
      (str ":http " m " " uri " has a :body; use :params, or another :method"))))

(defn- checked-request! [req]
  (when (some? req)  ; like :dispatch nil, a nil request is skipped
    (if-let [p (problem req)]
      (log/report! :error (str "hammer: " p) nil)
      (request! req))))

(events/reg-fx :http
  (fn [v]
    (if (sequential? v) (run! checked-request! v) (checked-request! v))))
