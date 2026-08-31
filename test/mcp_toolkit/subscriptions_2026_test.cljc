(ns mcp-toolkit.subscriptions-2026-test
  "Tests for subscriptions/listen, the 2026-07-28 replacement for
   resources/subscribe and the HTTP GET endpoint.

   These exercise the library over a direct wire, which is the stdio model the
   spec describes: every subscription shares one channel, and the subscription
   id is what separates them."
  (:require
   [clojure.test :refer [#?(:cljs async) deftest is testing]]
   [mcp-toolkit.client :as client]
   [mcp-toolkit.impl.subscriptions :as subscriptions]
   [mcp-toolkit.json-rpc :as json-rpc]
   [mcp-toolkit.protocol :as protocol]
   [mcp-toolkit.server :as server]
   [mcp-toolkit.test.util :as util]
   [promesa.core :as p]))

(defn- async-test
  [timeout-ms p]
  #?(:cljs (async done
                  (-> (p/timeout p timeout-ms ::timeout)
                      (p/handle (fn [x error]
                                  (when (= x ::timeout)
                                    (is nil (str "Timed out after " timeout-ms "ms")))
                                  (or error x)))
                      (p/then (fn [_] (done)))))
     :clj (-> (p/timeout p timeout-ms ::timeout)
              (p/handle (fn [x error]
                          (when (= x ::timeout)
                            (is nil (str "Timed out after " timeout-ms "ms")))
                          (or error x)))
              deref)))

(def a-tool {:name "t"
             :description "d"
             :input-schema {}})
(def a-resource {:uri "file:///p/a.json"
                 :name "a"
                 :text "A"})

(defn- connect
  "Wires a client to a server and returns everything a test needs.

   :acknowledged is a promise per subscription id, resolved when that
   subscription's acknowledgement arrives. Subscribing is asynchronous, so
   waiting on it is how a test knows the server has registered the stream."
  [server-options client-options]
  (let [events (atom [])
        acknowledged (atom {})
        server-session (atom (server/create-session
                              (merge {:protocol-version "2026-07-28"
                                      :server-info {:name "test-server"
                                                    :version "1.0.0"}}
                                     server-options)))
        ack-promise (fn [subscription-id]
                      (get (swap! acknowledged update subscription-id
                                  (fn [existing] (or existing (p/deferred))))
                           subscription-id))
        record (fn [kind]
                 (fn [context]
                   (swap! events conj
                          [kind
                           (client/subscription-id context)
                           (-> context :message :params :uri)])))
        client-session
        (atom (client/create-session
               (merge {:protocol-version "2026-07-28"
                       :on-initialized nil
                       :on-subscription-acknowledged
                       (fn [context]
                         (let [params (-> context :message :params)
                               subscription-id (client/subscription-id context)]
                           (p/resolve! (ack-promise subscription-id) params)))
                       :on-server-tool-list-changed (record :tools)
                       :on-server-prompt-list-changed (record :prompts)
                       :on-server-resource-list-changed (record :resources)
                       :on-server-resource-changed (record :resource-updated)}
                      client-options)))
        server-context (atom nil)
        client-context (atom nil)]
    (reset! server-context
            {:session server-session
             :send-message (fn [message] (json-rpc/handle-message @client-context message))})
    (reset! client-context
            {:session client-session
             :send-message (fn [message] (json-rpc/handle-message @server-context message))})
    {:client @client-context
     :server @server-context
     :events events
     :acknowledged ack-promise}))

;; ---------------------------------------------------------------------------
;; Filter logic
;; ---------------------------------------------------------------------------

(deftest honoured-filter-test
  (testing "a type the server cannot support is dropped, not refused"
    ;; The acknowledgement reports the subset the server agreed to honour, so
    ;; a client can tell a quiet stream from one it never had.
    (is (= {:tools-list-changed true}
           (subscriptions/honoured-filter {:tools {:list-changed true}}
                                          {:tools-list-changed true
                                           :prompts-list-changed true}))))

  (testing "resource subscriptions need the resources capability"
    (is (= {} (subscriptions/honoured-filter {:tools {}}
                                             {:resource-subscriptions ["file:///a"]})))
    (is (= {:resource-subscriptions ["file:///a"]}
           (subscriptions/honoured-filter {:resources {}}
                                          {:resource-subscriptions ["file:///a"]}))))

  (testing "an empty request honours nothing"
    (is (= {} (subscriptions/honoured-filter {:tools {}
                                              :prompts {}
                                              :resources {}} {})))))

(deftest uri-matching-test
  (testing "an exact URI matches"
    (is (true? (subscriptions/uri-covered-by? "file:///p/a.json" "file:///p/a.json"))))

  (testing "a URI ending in a slash covers everything beneath it"
    (is (true? (subscriptions/uri-covered-by? "file:///p/" "file:///p/a.json")))
    (is (true? (subscriptions/uri-covered-by? "file:///p/" "file:///p/deep/b.json"))))

  (testing "a prefix without a slash does not silently capture a sibling"
    (is (false? (subscriptions/uri-covered-by? "file:///proj" "file:///project/a.json")))
    (is (false? (subscriptions/uri-covered-by? "file:///p/" "file:///other/a.json")))))

(deftest wants-test
  (testing "a filter opts in only to what it named"
    (is (true? (subscriptions/wants? {:tools-list-changed true} "tools/list_changed" nil)))
    (is (false? (subscriptions/wants? {:tools-list-changed true} "prompts/list_changed" nil)))
    (is (false? (subscriptions/wants? {} "tools/list_changed" nil))))

  (testing "an unknown topic belongs on no stream"
    (is (false? (subscriptions/wants? {:tools-list-changed true} "something/else" nil)))))

(deftest subscriber-ids-test
  (let [session-value {:subscription-by-id
                       {1 {:tools-list-changed true}
                        2 {:prompts-list-changed true}
                        3 {:tools-list-changed true
                           :resource-subscriptions ["file:///p/"]}}}]
    (testing "only the subscriptions that opted in are returned"
      (is (= [1 3] (subscriptions/subscriber-ids session-value "tools/list_changed" nil)))
      (is (= [2] (subscriptions/subscriber-ids session-value "prompts/list_changed" nil))))

    (testing "resource updates are matched against each subscription's URIs"
      (is (= [3] (subscriptions/subscriber-ids session-value "resources/updated"
                                               "file:///p/a.json")))
      (is (= [] (subscriptions/subscriber-ids session-value "resources/updated"
                                              "file:///other/a.json"))))))

(deftest subscriber-ids-mixed-id-types-test
  ;; JSON-RPC lets a client pick a string id or a numeric one, so a server with
  ;; two clients can hold both at once. A bare `sort` throws ClassCastException
  ;; on that mixture, which took out every notification fan-out and
  ;; close-all-subscriptions! along with it.
  (let [session-value {:subscription-by-id
                       {1     {:tools-list-changed true}
                        "abc" {:tools-list-changed true}
                        2     {:tools-list-changed true}
                        "zzz" {:prompts-list-changed true}}}]
    (testing "a mixture of string and numeric ids does not throw"
      (is (= [1 2 "abc"]
             (subscriptions/subscriber-ids session-value "tools/list_changed" nil))))

    (testing "the order is deterministic, with numbers before strings"
      (is (= (subscriptions/subscriber-ids session-value "tools/list_changed" nil)
             (subscriptions/subscriber-ids session-value "tools/list_changed" nil))))

    (testing "an all-string subscriber set still works"
      (is (= ["zzz"]
             (subscriptions/subscriber-ids session-value "prompts/list_changed" nil))))))

;; ---------------------------------------------------------------------------
;; Holding a request open
;; ---------------------------------------------------------------------------

(deftest hold-open-test
  (testing "a handler that takes ownership of its request sends no response"
    (async-test
     3000
     (let [sent (atom [])
           session (atom (assoc (server/create-session {:protocol-version "2026-07-28"})
                                :handler-by-method
                                {"open" (fn [_context] json-rpc/hold-open)
                                 "normal" (fn [_context] {:ok true})}))
           context {:session session
                    :send-message (fn [message] (swap! sent conj message) nil)}]
       (p/let [_ (json-rpc/handle-message context {:jsonrpc "2.0"
                                                   :id 1
                                                   :method "open"})
               _ (json-rpc/handle-message context {:jsonrpc "2.0"
                                                   :id 2
                                                   :method "normal"})]
         (is (= [2] (mapv :id @sent))
             "nil would have been sent as a result, which is why hold-open exists"))))))

;; ---------------------------------------------------------------------------
;; Opening a stream
;; ---------------------------------------------------------------------------

(deftest acknowledgement-test
  (testing "the acknowledgement reports the honoured subset and carries the id"
    (async-test
     5000
     (let [{:keys [client acknowledged]} (connect {:tools [a-tool]} {})]
       (client/request-subscribe client {:tools-list-changed true
                                         :prompts-list-changed true})
       (p/let [params (acknowledged 0)]
         (is (= {:tools-list-changed true} (:notifications params))
             "the server has no prompts, so that type is omitted")
         (is (= 0 (get-in params [:_meta protocol/meta-subscription-id])))))))

  (testing "the server records the subscription under the request id"
    (async-test
     5000
     (let [{:keys [client server acknowledged]} (connect {:tools [a-tool]} {})]
       (client/request-subscribe client {:tools-list-changed true})
       (p/let [_ (acknowledged 0)]
         (is (= {0 {:tools-list-changed true}}
                (server/active-subscriptions server))))))))

;; ---------------------------------------------------------------------------
;; Delivery and gating
;; ---------------------------------------------------------------------------

(deftest notification-delivery-test
  (testing "notifications reach the stream, tagged with its subscription id"
    (async-test
     5000
     (let [{:keys [client server events acknowledged]}
           (connect {:tools [a-tool]
                     :resources [a-resource]} {})]
       (client/request-subscribe client {:tools-list-changed true
                                         :resource-subscriptions ["file:///p/"]})
       (p/let [_ (acknowledged 0)]
         (server/add-tool server {:name "new"
                                  :description "d"
                                  :input-schema {}})
         (server/notify-resource-updated server {:uri "file:///p/a.json"})
         (is (= [[:tools 0 nil]
                 [:resource-updated 0 "file:///p/a.json"]]
                @events))))))

  (testing "a type the client did not subscribe to is never sent"
    (async-test
     5000
     (let [{:keys [client server events acknowledged]}
           (connect {:tools [a-tool]
                     :prompts [{:name "p"
                                :description "d"}]
                     :resources [a-resource]}
                    {})]
       ;; tools only
       (client/request-subscribe client {:tools-list-changed true})
       (p/let [_ (acknowledged 0)]
         (server/notify-prompt-list-changed server)
         (server/notify-resource-list-changed server)
         (server/notify-resource-updated server {:uri "file:///p/a.json"})
         (is (= [] @events)
             "the server must not send notification types the client did not request")))))

  (testing "a resource outside the subscribed subtree is not delivered"
    (async-test
     5000
     (let [{:keys [client server events acknowledged]}
           (connect {:resources [a-resource]} {})]
       (client/request-subscribe client {:resource-subscriptions ["file:///p/"]})
       (p/let [_ (acknowledged 0)]
         (server/notify-resource-updated server {:uri "file:///p/a.json"})
         (server/notify-resource-updated server {:uri "file:///elsewhere/z.json"})
         (is (= [[:resource-updated 0 "file:///p/a.json"]] @events)))))))

(deftest concurrent-subscriptions-test
  (testing "two streams are demultiplexed by their subscription ids"
    (async-test
     5000
     (let [{:keys [client server events acknowledged]}
           (connect {:tools [a-tool]
                     :resources [a-resource]} {})]
       (client/request-subscribe client {:tools-list-changed true})
       (p/let [_ (acknowledged 0)
               _ (do (client/request-subscribe client
                                               {:resource-subscriptions ["file:///p/"]})
                     (acknowledged 1))]
         (is (= {0 {:tools-list-changed true}
                 1 {:resource-subscriptions ["file:///p/"]}}
                (server/active-subscriptions server)))
         (server/notify-tool-list-changed server)
         (server/notify-resource-updated server {:uri "file:///p/a.json"})
         (is (= [[:tools 0 nil]
                 [:resource-updated 1 "file:///p/a.json"]]
                @events)
             "each notification carries the id of the stream that asked for it"))))))

;; ---------------------------------------------------------------------------
;; Ending a stream
;; ---------------------------------------------------------------------------

(deftest cancellation-test
  (testing "a client cancel against the listen id ends the stream"
    (async-test
     5000
     (let [{:keys [client server events acknowledged]} (connect {:tools [a-tool]} {})]
       (client/request-subscribe client {:tools-list-changed true})
       (p/let [_ (acknowledged 0)]
         (client/notify-unsubscribe client 0)
         (is (= {} (server/active-subscriptions server)))
         (server/notify-tool-list-changed server)
         (is (= [] @events) "nothing is delivered after a cancel"))))))

(deftest graceful-closure-test
  (testing "closing answers the still-open request, which is how a client knows"
    (async-test
     5000
     (let [{:keys [client server acknowledged]} (connect {:tools [a-tool]} {})
           closed (client/request-subscribe client {:tools-list-changed true})]
       (p/let [_ (acknowledged 0)
               _ (server/close-all-subscriptions! server)
               result closed]
         (is (= "complete" (:result-type result)))
         (is (= 0 (get-in result [:_meta protocol/meta-subscription-id]))
             "the response names the subscription it closes")
         (is (= {} (server/active-subscriptions server)))))))

  (testing "closing an unknown subscription is a no-op"
    (async-test
     3000
     (let [{:keys [server]} (connect {:tools [a-tool]} {})]
       (is (nil? (server/close-subscription! server 99)))
       (is (= {} (server/active-subscriptions server)))))))

;; ---------------------------------------------------------------------------
;; The handshake revisions
;; ---------------------------------------------------------------------------

(deftest handshake-notifications-unchanged-test
  (testing "notifications still go straight down the connection, untagged"
    (async-test
     5000
     (let [sent (atom [])
           session (atom (server/create-session {:on-initialized nil
                                                 :tools [a-tool]}))
           context {:session session
                    :send-message (fn [message] (swap! sent conj message) nil)}]
       (server/notify-tool-list-changed context)
       (is (= ["notifications/tools/list_changed"] (mapv :method @sent)))
       (is (nil? (-> @sent first :params))
           "no subscription id, because there is no subscription")
       (p/resolved true))))

  (testing "resource updates still use the handshake subscription set"
    (async-test
     5000
     (let [sent (atom [])
           session (atom (assoc (server/create-session {:on-initialized nil
                                                        :resources [a-resource]})
                                :client-subscribed-resource-uris #{"file:///p/a.json"}))
           context {:session session
                    :send-message (fn [message] (swap! sent conj message) nil)}]
       (server/notify-resource-updated context {:uri "file:///p/a.json"})
       (server/notify-resource-updated context {:uri "file:///p/unsubscribed.json"})
       (is (= 1 (count @sent)))
       (is (= "file:///p/a.json" (-> @sent first :params :uri)))
       (p/resolved true)))))

(deftest prompt-list-changed-method-name-test
  ;; Regression. The server used to send notifications/prompt/list_changed,
  ;; singular, while the spec and both client tables use the plural. The
  ;; notification existed but could never be delivered to anyone.
  (testing "the prompt list-changed notification uses the name clients listen for"
    (async-test
     5000
     (let [sent (atom [])
           session (atom (server/create-session {:on-initialized nil
                                                 :prompts [{:name "p"
                                                            :description "d"}]}))
           context {:session session
                    :send-message (fn [message] (swap! sent conj message) nil)}]
       (server/notify-prompt-list-changed context)
       (is (= ["notifications/prompts/list_changed"] (mapv :method @sent)))
       (p/resolved true))))

  (testing "and it now actually reaches a client"
    (async-test
     5000
     (let [fired (atom [])
           server-session (atom (server/create-session {:on-initialized nil
                                                        :prompts [{:name "p"
                                                                   :description "d"}]}))
           client-session (atom (client/create-session
                                 {:on-initialized nil
                                  :on-server-prompt-list-changed
                                  (fn [_] (swap! fired conj :prompts))}))
           server-context (atom nil)
           client-context (atom nil)]
       (reset! server-context
               {:session server-session
                :send-message (fn [m] (json-rpc/handle-message @client-context m))})
       (reset! client-context
               {:session client-session
                :send-message (fn [m] (json-rpc/handle-message @server-context m))})
       (client/send-first-handshake-message @client-context)
       ;; Wait on the handshake actually completing, not on a fixed number of
       ;; microtask ticks. `send-first-handshake-message` returns nil rather
       ;; than its promise, so there is nothing to await directly, and a bare
       ;; (p/let [_ (p/resolved true)] ...) silently depended on the exact
       ;; length of the server's response promise chain. Any correctness fix
       ;; that added a link to that chain broke this test rather than the code.
       (p/let [_ (util/assert-atom client-session
                                   (fn [s] (true? (:initialized s)))
                                   3000
                                   "client handshake completes")]
         (server/notify-prompt-list-changed @server-context)
         (util/assert-atom fired
                           (fn [f] (= [:prompts] f))
                           3000
                           "the prompt list-changed notification reaches the client"))))))

(deftest subscribing-before-registration-still-delivers-test
  ;; The stored filter used to be narrowed to what the server could serve at
  ;; subscribe time, and capability is derived from the very registries
  ;; add-tool / add-prompt / add-resource exist to mutate. A client that
  ;; subscribed before those ran held a permanently dead stream: the key was
  ;; already dropped and nothing later could put it back. Plausible whenever a
  ;; server registers after auth or a DB connect, or registers in
  ;; :on-initialized.
  (testing "a tool registered after the subscription still reaches the subscriber"
    (async-test
     5000
     (let [sent (atom [])
           session (atom (server/create-session {:protocol-version "2026-07-28"
                                                 :on-initialized nil}))
           context {:session session
                    :send-message (fn [m] (swap! sent conj m) nil)}]
       ;; Subscribe first, while the server has no tools at all.
       (json-rpc/handle-message context
                                {:jsonrpc "2.0"
                                 :id 1
                                 :method "subscriptions/listen"
                                 :params {:notifications {:tools-list-changed true}
                                          :_meta {protocol/meta-protocol-version "2026-07-28"}}})
       (p/let [_ (util/assert-atom sent
                                   (fn [msgs] (seq msgs))
                                   2000
                                   "the subscription is acknowledged")]
         (reset! sent [])
         ;; Now register a tool and announce it.
         (server/add-tool context {:name "late"
                                   :description "registered after the subscribe"
                                   :input-schema {:type "object"}
                                   :tool-fn (fn [_ _] {:content []})})
         (util/assert-atom sent
                           (fn [msgs]
                             (some (fn [m] (= "notifications/tools/list_changed" (:method m))) msgs))
                           2000
                           "a tool registered later still notifies the subscriber"))))))
