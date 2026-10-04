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
  (:require [cljs.tools.reader.edn :as edn]
            [cljs.tools.reader.reader-types :as rt]
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

(defn- param-str
  "A param key or value as text; keywords keep their namespace (:user/id → user/id)."
  [x]
  (if (keyword? x) (subs (str x) 1) (str x)))

(defn- with-params
  "url with params as a query string, before any #fragment."
  [url params]
  (let [sp (js/URLSearchParams.)]
    (doseq [[k v] params :when (some? v)]
      (.append sp (param-str k) (param-str v)))
    (let [q (.toString sp)]
      (if (str/blank? q)
        url
        (let [h (.indexOf url "#")
              [base frag] (if (neg? h) [url ""] [(subs url 0 h) (subs url h)])]
          (str base (if (str/includes? base "?") "&" "?") q frag))))))

(defn- hook? [h] (or (nil? h) (vector? h) (fn? h)))

(defn- clip
  "s cut to 200 characters for a message."
  [s]
  (let [s (str s)] (if (> (count s) 200) (str (subs s 0 200) "…") s)))

(defn- notify!
  "Runs :on-connect/:on-disconnect hook k of tube t: an event vector is
  dispatched, a fn is called (a throw is reported)."
  [^js t k]
  (let [h (get (.-opts t) k)]
    (cond
      (vector? h) (events/dispatch h)
      (fn? h) (try (h)
                   (catch :default e
                     (log/report! :error (str "hammer: tube " (pr-str (.-id t)) " " k " failed") e))))))

(defn- read-frame
  "The one EDN form in frame data, or ::bad after reporting why."
  [id data]
  (let [bad (fn [why] (log/report! :error (str "hammer: tube " id " received " why ": " (clip data)) nil) ::bad)]
    (if-not (string? data)
      (bad "a non-text frame")
      (try
        (let [r (rt/string-push-back-reader data)
              ev (edn/read {:eof ::eof} r)
              more (edn/read {:eof ::eof} r)]
          (cond
            (= ev ::eof) (bad "an empty frame")
            (not= more ::eof) (bad "more than one form in a frame")
            :else ev))
        (catch :default _ (bad "an unreadable frame"))))))

(defn- receive! [^js t data]
  (let [id (pr-str (.-id t))
        ev (read-frame id data)]
    (cond
      (= ev ::bad) nil
      (not (vector? ev)) (log/report! :error (str "hammer: tube " id " received a non-event: " (clip data)) nil)
      :else (if-let [f (:on-receive (.-opts t))]
              (try (f ev)
                   (catch :default e
                     (log/report! :error (str "hammer: tube " id " :on-receive failed") e)))
              (events/dispatch ev)))))

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

(declare destroy!)

(defn- connect!
  "Opens t's socket. Returns false (after reporting and removing the tube)
  when the socket can't even be constructed, e.g. a malformed url."
  [^js t]
  (set! (.-timer t) nil)
  (if (.-closed t)
    true
    (if-let [^js ws (try (open-socket (.-url t))
                         (catch :default e
                           (log/report! :error (str "hammer: tube " (pr-str (.-id t)) " could not open "
                                                    (.-url t))
                                        e)
                           nil))]
      (do (set! (.-ws t) ws)
          (set! (.-onopen ws) (fn [_]
                                (set! (.-connected t) true)
                                (set! (.-attempt t) 0)
                                (flush-queue! t)
                                (notify! t :on-connect)))
          (set! (.-onmessage ws) (fn [^js e] (receive! t (.-data e))))
          (set! (.-onerror ws) (fn [_] nil)) ; a close follows
          (set! (.-onclose ws) (fn [_]
                                 (let [was (.-connected t)]
                                   (set! (.-connected t) false)
                                   (set! (.-ws t) nil)
                                   (when-not (.-closed t)
                                     ;; schedule first: a throwing hook must not stop reconnecting
                                     (reconnect-later! t)
                                     (when was (notify! t :on-disconnect))))))
          true)
      (do (destroy! {:id (.-id t)}) false))))

(defn- close! [^js t]
  (set! (.-closed t) true)
  (when-let [tm (.-timer t)] (js/clearTimeout tm))
  (when-let [^js ws (.-ws t)]
    (set! (.-onopen ws) nil)
    (set! (.-onmessage ws) nil)
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
  (cond
    (not (string? url))
    (log/report! :error (str "hammer: tube " (pr-str id) " needs a :url string, got " (pr-str url)) nil)

    (some #(not (hook? (get opts %))) [:on-connect :on-disconnect])
    (let [k (first (filter #(not (hook? (get opts %))) [:on-connect :on-disconnect]))]
      (log/report! :error (str "hammer: tube " (pr-str id) " " k " must be an event vector or a fn, got "
                               (pr-str (get opts k)))
                   nil))

    (not (or (nil? (:on-receive opts)) (fn? (:on-receive opts))))
    (log/report! :error (str "hammer: tube " (pr-str id) " :on-receive must be a fn, got "
                             (pr-str (:on-receive opts)))
                 nil)

    :else
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
        (let [s (binding [*print-length* nil *print-level* nil] (pr-str ev))
              ^js ws (.-ws t)]
          ;; only an OPEN socket sends; while CLOSING, .send would drop it silently
          (if (and (.-connected t) ws (= 1 (.-readyState ws)))
            (.send ws s)
            (.push (.-queue t) s)))
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
