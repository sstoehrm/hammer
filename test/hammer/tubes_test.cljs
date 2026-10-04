(ns hammer.tubes-test
  "Tubes: event vectors as EDN over a WebSocket, both ways."
  (:require [cljs.test :refer [deftest is async use-fixtures]]
            [hammer.test-env]
            [hammer.state :as state]
            [hammer.events :as events]
            [hammer.tubes :as tubes]
            [hammer.testing :as t]))

(def sockets (atom []))

(defn- fake-ws
  "A WebSocket stand-in: records url and sent frames; the test opens it,
  pushes messages and drops it. close() fires onclose asynchronously."
  [url]
  (let [ws #js {:url url :sent #js [] :closed false :readyState 0}]
    (set! (.-send ws) (fn [s] (when (= 1 (.-readyState ws)) (.push (.-sent ws) s))))
    (set! (.-close ws) (fn [] (set! (.-closed ws) true) (set! (.-readyState ws) 2)
                         (js/queueMicrotask #(do (set! (.-readyState ws) 3)
                                                 (when-let [f (.-onclose ws)] (f #js {}))))))
    (swap! sockets conj ws)
    ws))

(defn- open! [^js ws] (set! (.-readyState ws) 1) ((.-onopen ws) #js {}))
(defn- push! [^js ws s] ((.-onmessage ws) #js {:data s}))
(defn- drop! [^js ws] (set! (.-readyState ws) 3) ((.-onclose ws) #js {}))
(defn- sent [^js ws] (vec (.-sent ws)))

(use-fixtures :each {:before #(do (t/reset-app!) (reset! sockets []) (tubes/set-websocket! fake-ws))
                     :after #(do (t/reset-app!) (tubes/set-websocket! nil))})

(events/reg-event ::fx (fn [_ k v] {k v}))
(events/reg-event ::got (fn [db & args] {:db (update db :got (fnil conj []) (vec args))}))

(def no-wait (fn [_] 0))

(deftest create-connect-send-and-receive
  (events/dispatch-sync [::fx ::tubes/create {:url "ws://h/ws" :params {:token "abc"}
                                              :on-connect [::got :up] :backoff no-wait}])
  (let [ws (first @sockets)]
    (is (= "ws://h/ws?token=abc" (.-url ws)))
    (events/dispatch-sync [::fx ::tubes/send [:say-hello "early"]])
    (is (= [] (sent ws)) "queued until the socket is open")
    (open! ws)
    (is (= ["[:say-hello \"early\"]"] (sent ws)) "the queue goes out on open, as EDN")
    (events/dispatch-sync [::fx ::tubes/send [:say-hello "now" {:n 1}]])
    (is (= "[:say-hello \"now\" {:n 1}]" (last (sent ws))))
    (push! ws "[:hammer.tubes-test/got :from-server #{1}]")
    (t/flush!)
    (is (= [[:up] [:from-server #{1}]] (:got @state/app-db)) "on-connect, then the server's event")))

(deftest on-receive-replaces-dispatch-and-bad-frames-are-reported
  (let [seen (atom [])]
    (tubes/create! {:url "ws://h" :on-receive #(swap! seen conj %) :backoff no-wait})
    (let [ws (first @sockets)]
      (open! ws)
      (let [r (t/expect-errors #(do (push! ws "[:ok 1]")
                                    (push! ws "[:unclosed")
                                    (push! ws ":not-an-event")))]
        (is (= [[:ok 1]] @seen))
        (is (= ["hammer: tube :default received an unreadable frame: [:unclosed"
                "hammer: tube :default received a non-event: :not-an-event"]
               (mapv :message r)))))))

(deftest reconnects-after-a-drop-and-keeps-the-queue
  (async done
    (tubes/create! {:url "ws://h" :on-connect [::got :up] :on-disconnect [::got :down] :backoff no-wait})
    (let [ws1 (first @sockets)]
      (open! ws1)
      (drop! ws1)
      (tubes/send! [:while-down 1])
      (js/setTimeout
       (fn []
         (let [ws2 (second @sockets)]
           (is (some? ws2) "a new socket after the backoff")
           (open! ws2)
           (is (= ["[:while-down 1]"] (sent ws2)))
           (t/flush!)
           (is (= [[:up] [:down] [:up]] (:got @state/app-db)))
           (done)))
       10))))

(deftest destroy-closes-silently-and-stops
  (async done
    (tubes/create! {:id :chat :url "ws://h" :on-disconnect [::got :down] :backoff no-wait})
    (let [ws (first @sockets)]
      (open! ws)
      (events/dispatch-sync [::fx ::tubes/destroy {:id :chat}])
      (is (.-closed ws))
      (js/setTimeout
       (fn []
         (t/flush!)
         (is (= 1 (count @sockets)) "no reconnect")
         (is (nil? (:got @state/app-db)) "no on-disconnect for a deliberate destroy")
         (let [r (t/expect-errors #(tubes/send! {:id :chat :event [:x]}))]
           (is (= ["hammer: no tube :chat to send to"] (mapv :message r))))
         (done))
       10))))

(deftest several-tubes-and-replacing-an-id
  (tubes/create! {:id :a :url "ws://a" :backoff no-wait})
  (tubes/create! {:id :b :url "ws://b" :backoff no-wait})
  (let [[wa wb] @sockets]
    (open! wa) (open! wb)
    (tubes/send! {:id :b :event [:to-b]})
    (is (= [] (sent wa)))
    (is (= ["[:to-b]"] (sent wb)))
    (tubes/create! {:id :a :url "ws://a2" :backoff no-wait})
    (is (.-closed wa) "the old :a socket is closed")
    (is (= "ws://a2" (.-url (nth @sockets 2))))))

(deftest bad-sends-are-reported
  (tubes/create! {:url "ws://h" :backoff no-wait})
  (let [r (t/expect-errors #(do (tubes/send! :oops)
                                (tubes/send! {:id :nope :event [:x]})
                                (tubes/create! {:url 7})))]
    (is (= ["hammer: tube send needs an event vector or {:id :event}, got :oops"
            "hammer: no tube :nope to send to"
            "hammer: tube :default needs a :url string, got 7"]
           (mapv :message r)))))

(deftest default-backoff-grows-linearly-to-a-cap
  (dotimes [n 50]
    (let [ms (#'tubes/default-backoff n)]
      (is (<= 0 ms (min 30000 (* 1000 (inc n))))))))

(deftest reset-app-destroys-tubes
  (tubes/create! {:url "ws://h" :backoff no-wait})
  (let [ws (first @sockets)]
    (open! ws)
    (t/reset-app!)
    (is (.-closed ws))
    (is (= ["hammer: no tube :default to send to"]
           (mapv :message (t/expect-errors #(tubes/send! [:x])))))))

;; ---- review: hooks that throw, closing sockets, bad urls, framing

(deftest a-throwing-on-disconnect-still-reconnects-and-is-reported
  (async done
    (tubes/create! {:url "ws://h" :on-disconnect (fn [] (throw (js/Error. "hook"))) :backoff no-wait})
    (let [ws1 (first @sockets)]
      (open! ws1)
      (let [r (t/expect-errors #(drop! ws1))]
        (is (= ["hammer: tube :default :on-disconnect failed"] (mapv :message r))))
      (js/setTimeout (fn [] (is (= 2 (count @sockets)) "reconnected anyway") (done)) 10))))

(deftest a-throwing-on-receive-is-reported
  (tubes/create! {:url "ws://h" :on-receive (fn [_] (throw (js/Error. "recv"))) :backoff no-wait})
  (let [ws (first @sockets)]
    (open! ws)
    (is (= ["hammer: tube :default :on-receive failed"]
           (mapv :message (t/expect-errors #(push! ws "[:x]")))))))

(deftest sends-while-closing-are-queued
  (tubes/create! {:url "ws://h" :backoff no-wait})
  (let [ws (first @sockets)]
    (open! ws)
    (set! (.-readyState ws) 2) ; the server started closing; onclose not yet fired
    (tubes/send! [:late 1])
    (is (= [] (sent ws)))
    (drop! ws)))

(deftest a-socket-that-cannot-open-is-reported-and-removed
  (tubes/set-websocket! (fn [_] (throw (js/Error. "SyntaxError"))))
  (let [r (t/expect-errors #(do (tubes/create! {:url "ws://bad" :backoff no-wait})
                                (tubes/send! [:x])))]
    (is (= ["hammer: tube :default could not open ws://bad"
            "hammer: no tube :default to send to"]
           (mapv :message r)))))

(deftest params-go-before-a-fragment-and-keep-namespaces
  (tubes/create! {:url "ws://h/ws#frag" :params {:user/id 1 :q "a"} :backoff no-wait})
  (is (= "ws://h/ws?user%2Fid=1&q=a#frag" (.-url (first @sockets)))))

(deftest print-length-does-not-truncate-sent-events
  (tubes/create! {:url "ws://h" :backoff no-wait})
  (let [ws (first @sockets)]
    (open! ws)
    (binding [*print-length* 2] (tubes/send! [:big [1 2 3 4]]))
    (is (= "[:big [1 2 3 4]]" (last (sent ws))))))

(deftest extra-forms-in-a-frame-are-reported-and-frames-truncated
  (tubes/create! {:url "ws://h" :backoff no-wait})
  (let [ws (first @sockets)
        long-bad (apply str "[" (repeat 500 "x "))]
    (open! ws)
    (let [r (t/expect-errors #(do (push! ws "[:a] [:b]") (push! ws long-bad)))]
      (is (= "hammer: tube :default received more than one form in a frame: [:a] [:b]" (:message (first r))))
      (is (< (count (:message (second r))) 300) "a long frame is truncated in the message"))))

(deftest handlers-are-detached-on-destroy
  (tubes/create! {:url "ws://h" :on-connect [::got :up] :backoff no-wait})
  (let [ws (first @sockets)]
    (tubes/destroy! {})
    (is (nil? (.-onopen ws)))
    (is (nil? (.-onmessage ws)))))

(deftest malformed-hooks-are-reported
  (is (= ["hammer: tube :default :on-connect must be an event vector or a fn, got :up"]
         (mapv :message (t/expect-errors #(tubes/create! {:url "ws://h" :on-connect :up}))))))
