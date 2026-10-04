(ns hammer.http-test
  (:require [cljs.test :refer [deftest is async use-fixtures]]
            [hammer.test-env]
            [hammer.state :as state]
            [hammer.events :as events]
            [hammer.http :as http]
            [hammer.log :as log]
            [hammer.testing :as t]))

(def calls (atom []))

(defn- response
  "A fetch Response stand-in: status, body text."
  [status body]
  #js {:ok (<= 200 status 299) :status status :statusText (str "S" status)
       :text (fn [] (js/Promise.resolve body))
       :blob (fn [] (js/Promise.resolve (str "blob:" body)))})

(defn- stub!
  "fetch answers every call with (respond url init): a Response, or a
  rejected promise for network errors."
  [respond]
  (http/set-fetch! (fn [url init]
                     (swap! calls conj [url init])
                     (respond url init))))

(use-fixtures :each {:before #(do (t/reset-app!) (reset! calls []))
                     :after #(http/set-fetch! nil)})

(events/reg-event ::go (fn [_ req] {:http req}))
(events/reg-event ::ok (fn [db & args] {:db (assoc db :ok (vec args))}))
(events/reg-event ::bad (fn [db & args] {:db (assoc db :bad (vec args))}))
(events/reg-event ::oks (fn [db body] {:db (update db :oks (fnil conj []) body)}))

(defn- settle
  "Calls f after pending promise callbacks and the event queue have run."
  [f]
  (js/setTimeout #(do (t/flush!) (f)) 5))

(deftest get-with-params-keywordized-json
  (async done
    (stub! (fn [_ _] (js/Promise.resolve (response 200 "{\"items\":[{\"id\":1}]}"))))
    (events/dispatch [::go {:uri "/api/items?x=1" :params {:q "milk" :tag ["a" "b"] :skip nil}
                            :on-success [::ok :extra]}])
    (settle
     (fn []
       (let [[url init] (first @calls)]
         (is (= "/api/items?x=1&q=milk&tag=a&tag=b" url) "params appended; vectors repeat; nil skipped")
         (is (= "GET" (.-method init))))
       (is (= [:extra {:items [{:id 1}]}] (:ok @state/app-db)))
       (done)))))

(deftest post-json-body-headers-and-fetch-options
  (async done
    (stub! (fn [_ _] (js/Promise.resolve (response 201 ""))))
    (events/dispatch [::go {:method :post :uri "/api/items" :body {:title "milk"}
                            :headers {"X-Token" "t"} :fetch-options {:credentials "include"}
                            :on-success [::ok]}])
    (settle
     (fn []
       (let [[_ init] (first @calls)]
         (is (= "POST" (.-method init)))
         (is (= "{\"title\":\"milk\"}" (.-body init)))
         (is (= "application/json" (.get (.-headers init) "content-type")))
         (is (= "t" (.get (.-headers init) "x-token")))
         (is (= "include" (.-credentials init))))
       (is (= [nil] (:ok @state/app-db)) "an empty body is nil")
       (done)))))

(deftest string-body-is-sent-as-is-and-text-format
  (async done
    (stub! (fn [_ _] (js/Promise.resolve (response 200 "plain"))))
    (events/dispatch [::go {:method :put :uri "/t" :body "raw" :response-format :text :on-success [::ok]}])
    (settle
     (fn []
       (let [[_ init] (first @calls)]
         (is (= "raw" (.-body init)))
         (is (nil? (.get (.-headers init) "content-type"))))
       (is (= ["plain"] (:ok @state/app-db)))
       (done)))))

(deftest non-2xx-goes-to-on-failure-with-parsed-body
  (async done
    (stub! (fn [_ _] (js/Promise.resolve (response 404 "{\"error\":\"nope\"}"))))
    (events/dispatch [::go {:uri "/missing" :on-success [::ok] :on-failure [::bad]}])
    (settle
     (fn []
       (is (nil? (:ok @state/app-db)))
       (is (= [{:uri "/missing" :status 404 :status-text "S404" :failure :error :response {:error "nope"}}]
              (:bad @state/app-db)))
       (done)))))

(deftest network-error-and-parse-error
  (async done
    (stub! (fn [url _] (if (= url "/down")
                         (js/Promise.reject (js/Error. "offline"))
                         (js/Promise.resolve (response 200 "{not json")))))
    (events/dispatch [::go [{:uri "/down" :on-failure [::bad]} {:uri "/garbled" :on-failure [::ok]}]])
    (settle
     (fn []
       (is (= [:network 0] ((juxt :failure :status) (first (:bad @state/app-db)))))
       (is (= [:parse 200] ((juxt :failure :status) (first (:ok @state/app-db)))) "a vector runs each request")
       (done)))))

(deftest failure-without-on-failure-is-reported
  (async done
    (stub! (fn [_ _] (js/Promise.resolve (response 500 ""))))
    (events/dispatch [::go {:uri "/boom" :on-success [::ok]}])
    (js/setTimeout
     (fn []
       (is (thrown-with-msg? js/Error #"hammer: http GET /boom failed: 500 S500" (t/flush!)))
       (done))
     5)))

(deftest timeout-aborts-and-fails-with-timeout
  (async done
    (stub! (fn [_ ^js init]
             (js/Promise. (fn [_ reject]
                            (.addEventListener (.-signal init) "abort"
                                               #(reject (js/Error. "aborted")))))))
    (events/dispatch [::go {:uri "/slow" :timeout 10 :on-failure [::bad]}])
    (js/setTimeout
     #(do (t/flush!)
          (is (= [:timeout 0] ((juxt :failure :status) (first (:bad @state/app-db)))))
          (done))
     40)))

(deftest abort-key-supersedes-silently
  (async done
    (let [resolvers (atom [])]
      (stub! (fn [url ^js init]
               (js/Promise. (fn [resolve reject]
                              (.addEventListener (.-signal init) "abort" #(reject (js/Error. "aborted")))
                              (swap! resolvers conj #(resolve (response 200 (str "\"" url "\""))))))))
      (events/dispatch [::go {:uri "/q1" :abort-key :search :on-success [::oks] :on-failure [::bad]}])
      (t/flush!)
      ;; a key built at runtime is a different object than the literal :search
      (events/dispatch [::go {:uri "/q2" :abort-key (keyword "search") :on-success [::oks] :on-failure [::bad]}])
      (t/flush!)
      (doseq [r @resolvers] (r))
      (settle
       (fn []
         (is (= ["/q2"] (:oks @state/app-db)) "only the latest request answers")
         (is (nil? (:bad @state/app-db)) "the superseded one does not fail")
         (done))))))

(deftest a-request-without-uri-is-reported
  (let [r (t/expect-errors #(events/dispatch-sync [::go {:url "/typo"}]))]
    (is (= ["hammer: :http needs a :uri string, got nil"] (mapv :message r)))))

;; ---- review: aborts during the body read, bad options, edge inputs

(defn- slow-body
  "A Response whose body read waits until the request's signal aborts (then
  rejects, like real fetch) or until (release!) is called."
  [^js init status body]
  (let [release (atom nil)
        r #js {:ok (<= 200 status 299) :status status :statusText "S"
               :text (fn [] (js/Promise. (fn [resolve reject]
                                           (reset! release #(resolve body))
                                           (.addEventListener (.-signal init) "abort"
                                                              #(reject (js/Error. "AbortError"))))))}]
    [r release]))

(deftest superseded-during-body-read-dispatches-nothing
  (async done
    (let [first? (atom true)]
      (stub! (fn [_ init]
               (if @first?
                 (do (reset! first? false) (js/Promise.resolve (first (slow-body init 200 "1"))))
                 (js/Promise.resolve (response 200 "2")))))
      (events/dispatch [::go {:uri "/a" :abort-key :k :on-success [::oks] :on-failure [::bad]}])
      (js/setTimeout
       (fn []
         (events/dispatch [::go {:uri "/b" :abort-key :k :on-success [::oks] :on-failure [::bad]}])
         (settle (fn []
                   (is (= [2] (:oks @state/app-db)))
                   (is (nil? (:bad @state/app-db)) "no :parse failure for the superseded one")
                   (done))))
       5))))

(deftest timeout-during-body-read-is-a-timeout
  (async done
    (stub! (fn [_ init] (js/Promise.resolve (first (slow-body init 200 "1")))))
    (events/dispatch [::go {:uri "/slowbody" :timeout 10 :on-failure [::bad]}])
    (js/setTimeout #(do (t/flush!)
                        (is (= :timeout (:failure (first (:bad @state/app-db)))))
                        (done))
                   40)))

(deftest timeout-while-reading-an-error-body-is-a-timeout
  (async done
    (stub! (fn [_ init] (js/Promise.resolve (first (slow-body init 500 "x")))))
    (events/dispatch [::go {:uri "/slowerr" :timeout 10 :on-failure [::bad]}])
    (js/setTimeout #(do (t/flush!)
                        (is (= :timeout (:failure (first (:bad @state/app-db)))))
                        (done))
                   40)))

(deftest a-fetch-ignoring-the-signal-still-drops-the-superseded-answer
  (async done
    (let [resolvers (atom [])]
      (stub! (fn [url _] (js/Promise. (fn [resolve _] (swap! resolvers conj #(resolve (response 200 (str "\"" url "\""))))))))
      (events/dispatch [::go {:uri "/x1" :abort-key :k2 :on-success [::oks]}])
      (t/flush!)
      (events/dispatch [::go {:uri "/x2" :abort-key :k2 :on-success [::oks]}])
      (t/flush!)
      (doseq [r @resolvers] (r))
      (settle (fn [] (is (= ["/x2"] (:oks @state/app-db))) (done))))))

(deftest bad-options-are-reported-and-nil-is-skipped
  (stub! (fn [_ _] (js/Promise.resolve (response 200 "1"))))
  (let [r (t/expect-errors #(do (events/dispatch-sync [::go {:uri "/a" :on-success :oops}])
                                (events/dispatch-sync [::go {:uri "/b" :response-format :jsn}])
                                (events/dispatch-sync [::go {:uri "/c" :body {:a 1}}])
                                (events/dispatch-sync [::go nil])
                                (events/dispatch-sync [::go [nil {:uri "/d" :on-success [::oks]}]])))]
    (is (= ["hammer: :http :on-success must be an event vector, got :oops"
            "hammer: :http :response-format must be :json, :text, :blob or :raw, got :jsn"
            "hammer: :http GET /c has a :body; use :params, or another :method"]
           (mapv :message r)))
    (is (= ["/d"] (mapv first @calls)) "only the valid request was sent")))

(deftest headers-instance-js-body-and-fragment
  (async done
    (stub! (fn [_ _] (js/Promise.resolve (response 200 ""))))
    (events/dispatch [::go {:method :post :uri "/f#frag" :params {:q 1}
                            :headers (js/Headers. #js {"X-A" "1"}) :body #js {:a 1}}])
    (settle
     (fn []
       (let [[url ^js init] (first @calls)]
         (is (= "/f?q=1#frag" url) "params go before the fragment")
         (is (= "application/json" (.get (.-headers init) "content-type")))
         (is (= "1" (.get (.-headers init) "x-a")))
         (is (= "{\"a\":1}" (.-body init)) "a #js object body is sent as JSON"))
       (done)))))

(deftest a-throwing-custom-fetch-fails-cleanly
  (async done
    (stub! (fn [_ _] (throw (js/Error. "sync"))))
    (events/dispatch [::go {:uri "/sync" :timeout 50 :on-failure [::bad]}])
    (settle (fn []
              (is (= :network (:failure (first (:bad @state/app-db)))))
              (done)))))
