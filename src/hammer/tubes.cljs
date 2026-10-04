(ns hammer.tubes
  "Tubes: event vectors between the app and a server over a WebSocket, as
  EDN. Inspired by pneumatic-tubes (Artūr Girenko), client side only and
  written for hammer. Requiring this namespace registers the effects:

    {:hammer.tubes/create {:url \"ws://host/ws\" :params {:token \"abc\"}
                           :on-connect [:online] :on-disconnect [:offline]}}
    {:hammer.tubes/send [:say-hello \"x\"]}         ; or {:id :chat :event [...]}
    {:hammer.tubes/destroy {}}                      ; or {:id :chat}

  Every frame from the server is read as EDN and dispatched (or given to
  :on-receive). Events sent while disconnected are queued and go out, in
  order, on the next connect. A dropped connection reconnects after a random
  backoff whose maximum grows linearly to 30 s; destroy closes it for good,
  without :on-disconnect. :id (default :default) names a tube; creating an id
  again replaces it."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [hammer.events :as events]
            [hammer.log :as log]))

(defonce ^:private ws-ctor (volatile! nil))

;; id → #js {:id :opts :url :ws :queue :attempt :timer :connected :closed}, by value
(defonce ^:private tubes (volatile! {}))

(defn set-websocket!
  "Replaces js/WebSocket with (fn [url] socket), e.g. a fake in tests; nil
  restores it."
  [f]
  (vreset! ws-ctor f)
  nil)

(defn- open-socket [url]
  (if-let [f @ws-ctor] (f url) (js/WebSocket. url)))

(defn- default-backoff
  "Random ms before reconnect attempt n (0-based): up to 1 s more per
  attempt, at most 30 s."
  [n]
  (js/Math.floor (* (js/Math.random) (min 30000 (* 1000 (inc n))))))

(defn- with-params [url params]
  (let [sp (js/URLSearchParams.)]
    (doseq [[k v] params :when (some? v)]
      (.append sp (if (keyword? k) (name k) (str k)) (if (keyword? v) (name v) (str v))))
    (let [q (.toString sp)]
      (if (str/blank? q) url (str url (if (str/includes? url "?") "&" "?") q)))))

(defn- notify!
  "Runs an :on-connect/:on-disconnect hook: an event vector is dispatched, a
  fn is called."
  [h]
  (cond
    (vector? h) (events/dispatch h)
    (fn? h) (h)))

(defn- receive! [^js t data]
  (let [id (pr-str (.-id t))
        ev (try (reader/read-string data)
                (catch :default _
                  (log/report! :error (str "hammer: tube " id " received an unreadable frame: " data) nil)
                  ::unreadable))]
    (cond
      (= ev ::unreadable) nil
      (vector? ev) (if-let [f (:on-receive (.-opts t))] (f ev) (events/dispatch ev))
      :else (log/report! :error (str "hammer: tube " id " received a non-event: " data) nil))))

(defn- flush-queue! [^js t]
  (let [q (.-queue t)]
    (set! (.-queue t) #js [])
    (.forEach q (fn [s] (.send (.-ws t) s)))))

(declare connect!)

(defn- reconnect-later! [^js t]
  (let [backoff (or (:backoff (.-opts t)) default-backoff)
        n (.-attempt t)]
    (set! (.-attempt t) (inc n))
    (set! (.-timer t) (js/setTimeout #(connect! t) (backoff n)))))

(defn- connect! [^js t]
  (set! (.-timer t) nil)
  (when-not (.-closed t)
    (let [^js ws (open-socket (.-url t))
          opts (.-opts t)]
      (set! (.-ws t) ws)
      (set! (.-onopen ws) (fn [_]
                            (set! (.-connected t) true)
                            (set! (.-attempt t) 0)
                            (flush-queue! t)
                            (notify! (:on-connect opts))))
      (set! (.-onmessage ws) (fn [^js e] (receive! t (.-data e))))
      (set! (.-onerror ws) (fn [_] nil)) ; a close follows
      (set! (.-onclose ws) (fn [_]
                             (let [was (.-connected t)]
                               (set! (.-connected t) false)
                               (set! (.-ws t) nil)
                               (when-not (.-closed t)
                                 (when was (notify! (:on-disconnect opts)))
                                 (reconnect-later! t))))))))

(defn- close! [^js t]
  (set! (.-closed t) true)
  (when-let [tm (.-timer t)] (js/clearTimeout tm))
  (when-let [^js ws (.-ws t)]
    (set! (.-onclose ws) nil)
    (.close ws)))

(defn destroy!
  "Closes tube {:id id} (default :default) for good: no reconnect, no
  :on-disconnect."
  [{:keys [id] :or {id :default}}]
  (when-let [t (get @tubes id)]
    (close! t)
    (vswap! tubes dissoc id))
  nil)

(defn create!
  "Opens a tube: {:id :url :params :on-connect :on-disconnect :on-receive
  :backoff}. An id already open is replaced."
  [{:keys [id url params] :or {id :default} :as opts}]
  (if-not (string? url)
    (log/report! :error (str "hammer: tube " (pr-str id) " needs a :url string, got " (pr-str url)) nil)
    (do (destroy! {:id id})
        (let [t #js {:id id :opts opts :url (with-params url params) :ws nil :queue #js []
                     :attempt 0 :timer nil :connected false :closed false}]
          (vswap! tubes assoc id t)
          (connect! t))))
  nil)

(defn send!
  "Sends an event vector to the :default tube, or {:id :event} to tube id.
  While disconnected it is queued."
  [x]
  (let [[id ev] (cond
                  (vector? x) [:default x]
                  (and (map? x) (vector? (:event x))) [(:id x :default) (:event x)])]
    (cond
      (nil? ev)
      (log/report! :error (str "hammer: tube send needs an event vector or {:id :event}, got " (pr-str x)) nil)

      :else
      (if-let [^js t (get @tubes id)]
        (let [s (pr-str ev)]
          (if (.-connected t) (.send (.-ws t) s) (.push (.-queue t) s)))
        (log/report! :error (str "hammer: no tube " (pr-str id) " to send to") nil))))
  nil)

(defn destroy-all!
  "Destroys every tube (hammer.testing/reset-app! calls it)."
  []
  (run! close! (vals @tubes))
  (vreset! tubes {})
  nil)

(events/reg-fx ::create create!)
(events/reg-fx ::send send!)
(events/reg-fx ::destroy #(destroy! (or % {})))
