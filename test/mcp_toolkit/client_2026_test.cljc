(ns mcp-toolkit.client-2026-test
  "Tests for the 2026-07-28 client: per-request _meta, server/discover, and
   the multi round-trip loop that answers a server's requests for input."
  (:require
   [clojure.test :refer [#?(:cljs async) deftest is testing]]
   [mcp-toolkit.client :as client]
   [mcp-toolkit.impl.client.handler-2026 :as client-handler-2026]
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

(defn- connect
  "Wires a client session directly to a server session and returns the client
   context, plus an atom logging every message that crossed."
  [server-options client-options]
  (let [wire (atom [])
        server-session (atom (server/create-session
                              (merge {:protocol-version "2026-07-28"
                                      :server-info {:name "test-server"
                                                    :version "1.0.0"}}
                                     server-options)))
        client-session (atom (client/create-session
                              (merge {:protocol-version "2026-07-28"
                                      :on-initialized nil}
                                     client-options)))
        server-context (atom nil)
        client-context (atom nil)]
    (reset! server-context
            {:session server-session
             :send-message (fn [message]
                             (swap! wire conj [:server-> message])
                             (json-rpc/handle-message @client-context message))})
    (reset! client-context
            {:session client-session
             :send-message (fn [message]
                             (swap! wire conj [:client-> message])
                             (json-rpc/handle-message @server-context message))})
    {:context @client-context
     :client-session client-session
     :server-session server-session
     :wire wire}))

(defn- client-requests [wire]
  (into [] (comp (filter (fn [[direction _]] (= :client-> direction)))
                 (map second))
        @wire))

(def greet-tool
  "Cannot answer until the client says who is asking."
  {:name "greet"
   :description "Greets you by name"
   :input-schema {:type "object"
                  :properties {}}
   :tool-fn (fn [context _arguments]
              (if-some [answer (server/input-response context :who)]
                {:content [{:type "text"
                            :text (str "hello " (-> answer :content :name))}]}
                (server/input-required
                 {:input-requests {:who (server/elicit-form-request
                                         {:message "Who are you?"
                                          :requested-schema {:type "object"}})}
                  :request-state "asked-for-name"})))})

(def roots-tool
  {:name "where"
   :description "Reports the client's roots"
   :input-schema {:type "object"
                  :properties {}}
   :tool-fn (fn [context _arguments]
              (if-some [answer (server/input-response context :roots)]
                {:content [{:type "text"
                            :text (pr-str (:roots answer))}]}
                (server/input-required
                 {:input-requests {:roots (server/roots-request)}})))})

(def insatiable-tool
  "Never accepts an answer. Exists to prove the round-trip cap works."
  {:name "insatiable"
   :description "Always asks for more"
   :input-schema {:type "object"
                  :properties {}}
   :tool-fn (fn [_context _arguments]
              (server/input-required {:input-requests {:again (server/roots-request)}}))})

;; ---------------------------------------------------------------------------
;; Session wiring
;; ---------------------------------------------------------------------------

(deftest client-session-protocol-selection-test
  (testing "a handshake client is unchanged"
    (let [session (client/create-session {})]
      (is (false? (:initialized session)))
      (is (= "2025-11-25" (:protocol-version session)))
      (is (contains? (:handler-by-method session) "ping"))))

  (testing "a stateless client has no handshake and is live immediately"
    (let [session (client/create-session {:protocol-version "2026-07-28"})]
      (is (true? (:initialized session)))
      (is (= client-handler-2026/handler-by-method (:handler-by-method session))))))

(deftest client-dispatch-table-test
  (testing "the server can no longer initiate a request"
    (doseq [method ["ping" "roots/list" "sampling/createMessage"]]
      (is (not (contains? client-handler-2026/handler-by-method method))
          (str method " is not an inbound request on 2026-07-28"))))

  (testing "notifications are still routed"
    (doseq [method ["notifications/progress" "notifications/message"
                    "notifications/tools/list_changed"]]
      (is (contains? client-handler-2026/handler-by-method method)))))

;; ---------------------------------------------------------------------------
;; Per-request _meta
;; ---------------------------------------------------------------------------

(def echo-tool
  {:name "echo"
   :description "Echoes"
   :input-schema {:type "object"
                  :properties {}}
   :tool-fn (fn [_context _arguments] {:content [{:type "text"
                                                  :text "hi"}]})})

(deftest request-meta-test
  ;; These call a tool rather than listing them. request-tool-list is guarded
  ;; on the server capabilities, which are only known after discovery, and the
  ;; guard is not what is under test here.
  (testing "every request carries the client's version, capabilities and identity"
    (async-test
     3000
     (let [{:keys [context wire]} (connect {:tools [echo-tool]}
                                           {:client-info {:name "probe"
                                                          :version "9"}
                                            :client-capabilities {:elicitation {:form {}}}})]
       (-> (client/request-tool-invocation context "echo" {})
           (p/then (fn [_]
                     (let [request-meta (-> (client-requests wire) first :params :_meta)]
                       (is (= "2026-07-28" (get request-meta protocol/meta-protocol-version)))
                       (is (= {:elicitation {:form {}}}
                              (get request-meta protocol/meta-client-capabilities)))
                       (is (= {:name "probe"
                               :version "9"}
                              (get request-meta protocol/meta-client-info))))))))))

  (testing "capabilities are sent as an empty map rather than omitted"
    ;; An empty map says the client supports nothing optional. Omitting the
    ;; field says something different, and the spec requires it be present.
    (async-test
     3000
     (let [{:keys [context wire]} (connect {:tools [echo-tool]}
                                           {:client-capabilities {}})]
       (-> (client/request-tool-invocation context "echo" {})
           (p/then (fn [_]
                     (let [request-meta (-> (client-requests wire) first :params :_meta)]
                       (is (= {} (get request-meta protocol/meta-client-capabilities))))))))))

  (testing "a log level is sent only when the client opted in"
    (async-test
     3000
     (let [opted-in (connect {:tools [echo-tool]} {:log-level "debug"})
           silent (connect {:tools [echo-tool]} {})]
       (p/let [_ (client/request-tool-invocation (:context opted-in) "echo" {})
               _ (client/request-tool-invocation (:context silent) "echo" {})]
         (is (= "debug" (-> (client-requests (:wire opted-in)) first :params :_meta
                            (get protocol/meta-log-level))))
         (is (nil? (-> (client-requests (:wire silent)) first :params :_meta
                       (get protocol/meta-log-level)))))))))

;; ---------------------------------------------------------------------------
;; Discovery
;; ---------------------------------------------------------------------------

(deftest discover-test
  (testing "discovery stores what the server reported"
    (async-test
     3000
     (let [{:keys [context client-session]}
           (connect {:server-instructions "be nice"
                     :tools [{:name "t"
                              :description "d"
                              :input-schema {}}]}
                    {})]
       (-> (client/request-discover context)
           (p/then (fn [_]
                     (is (= ["2026-07-28"]
                            (:server-supported-protocol-versions @client-session))
                         "the server reports only what it can serve, not what the
                          library implements")
                     (is (= "be nice" (:server-instructions @client-session)))
                     (is (= {:list-changed true}
                            (:tools (:server-capabilities @client-session))))
                     (is (true? (client/server-supports-protocol-version? context)))))))))

  (testing "the version check reports nil before discovery has run, not false"
    (let [{:keys [context]} (connect {} {})]
      (is (nil? (client/server-supports-protocol-version? context)))))

  (testing "discovery drives the default fetch, for a server with tools only"
    ;; This is a regression test. request-tool-list used to guard on the
    ;; :prompts capability rather than :tools, which stayed hidden while
    ;; initialize advertised a fixed map that always included prompts. Now that
    ;; server/discover reports only what a server really has, a tools-only
    ;; server would have had its tools silently never fetched.
    (async-test
     3000
     (let [{:keys [context client-session]}
           (connect {:tools [{:name "alpha"
                              :description "d"
                              :input-schema {}}
                             {:name "beta"
                              :description "d"
                              :input-schema {}}]}
                    {:on-initialized nil})]
       (-> (client/request-discover context)
           (p/then (fn [_] (client/request-tool-list context)))
           (p/then (fn [_]
                     (is (= ["alpha" "beta"]
                            (sort (keys (:server-tool-by-name @client-session))))
                         "a tools-only server must still get its tools listed"))))))))

;; ---------------------------------------------------------------------------
;; Multi round-trip requests
;; ---------------------------------------------------------------------------

(deftest multi-round-trip-fulfilment-test
  (testing "an elicitation is answered and the call retried, transparently"
    (async-test
     5000
     (let [asked (atom [])
           {:keys [context wire]}
           (connect {:tools [greet-tool]}
                    {:client-capabilities {:elicitation {:form {}}}
                     :on-elicitation-requested
                     (fn [_context params]
                       (swap! asked conj (:message params))
                       {:action "accept"
                        :content {:name "octocat"}})})]
       (-> (client/request-tool-invocation context "greet" {})
           (p/then (fn [result]
                     (is (= "hello octocat" (-> result :content first :text))
                         "the caller sees a finished result, not an interim one")
                     (is (= "complete" (:result-type result)))
                     (is (= ["Who are you?"] @asked))
                     (is (= 2 (count (client-requests wire)))
                         "one original call plus one retry")))))))

  (testing "the retry echoes the server's own keys and request state"
    (async-test
     5000
     (let [{:keys [context wire]}
           (connect {:tools [greet-tool]}
                    {:on-elicitation-requested
                     (fn [_ _] {:action "accept"
                                :content {:name "octocat"}})})]
       (-> (client/request-tool-invocation context "greet" {})
           (p/then (fn [_]
                     (let [retry (second (client-requests wire))]
                       (is (= "asked-for-name" (-> retry :params :request-state)))
                       (is (= ["mcp-toolkit/who"]
                              (keys (-> retry :params :input-responses)))
                           "keys go back exactly as the server minted them"))))))))

  (testing "roots are answered from the session, with no callback needed"
    (async-test
     5000
     (let [{:keys [context]} (connect {:tools [roots-tool]}
                                      {:roots [{:uri "file:///proj"
                                                :name "proj"}]})]
       (-> (client/request-tool-invocation context "where" {})
           (p/then (fn [result]
                     (is (= (pr-str [{:uri "file:///proj"
                                      :name "proj"}])
                            (-> result :content first :text)))))))))

  (testing "an ordinary call makes exactly one round trip"
    (async-test
     3000
     (let [{:keys [context wire]}
           (connect {:tools [{:name "echo"
                              :description "d"
                              :input-schema {}
                              :tool-fn (fn [_ _] {:content [{:type "text"
                                                             :text "hi"}]})}]}
                    {})]
       (-> (client/request-tool-invocation context "echo" {})
           (p/then (fn [result]
                     (is (= "hi" (-> result :content first :text)))
                     (is (= 1 (count (client-requests wire)))))))))))

(deftest multi-round-trip-guard-rails-test
  (testing "a server that never stops asking is cut off"
    (async-test
     5000
     (let [{:keys [context]} (connect {:tools [insatiable-tool]}
                                      {:max-round-trips 3})]
       (-> (client/request-tool-invocation context "insatiable" {})
           (p/then (fn [_] (is false "should have been rejected")))
           (p/catch (fn [error]
                      (let [data (ex-data error)]
                        (is (= :too-many-round-trips (:type data)))
                        (is (= "tools/call" (:method data)))
                        (is (= 3 (:max-round-trips data))))))))))

  (testing "a request the client cannot answer fails with a clear reason"
    (async-test
     5000
     (let [{:keys [context]} (connect {:tools [greet-tool]} {})]
       ;; No :on-elicitation-requested was configured.
       (-> (client/request-tool-invocation context "greet" {})
           (p/then (fn [_] (is false "should have been rejected")))
           (p/catch (fn [error]
                      (let [data (ex-data error)]
                        (is (= :missing-input-request-handler (:type data)))
                        (is (= "elicitation/create" (:method data)))
                        (is (= :on-elicitation-requested (:callback data)))))))))))

;; ---------------------------------------------------------------------------
;; The handshake client must not change
;; ---------------------------------------------------------------------------

(deftest handshake-client-unchanged-test
  (testing "a handshake client sends no _meta and expects no round trips"
    (async-test
     3000
     (let [wire (atom [])
           server-session (atom (server/create-session {:on-initialized nil
                                                        :tools [{:name "t"
                                                                 :description "d"
                                                                 :input-schema {}}]}))
           client-session (atom (client/create-session {:on-initialized nil}))
           server-context (atom nil)
           client-context (atom nil)]
       (reset! server-context
               {:session server-session
                :send-message (fn [m] (json-rpc/handle-message @client-context m))})
       (reset! client-context
               {:session client-session
                :send-message (fn [m]
                                (swap! wire conj m)
                                (json-rpc/handle-message @server-context m))})
       (-> (client/send-first-handshake-message @client-context)
           (p/then (fn [_] (client/request-tool-list @client-context)))
           (p/then (fn [_]
                     (let [initialize (first (filter (fn [m] (= "initialize" (:method m))) @wire))
                           tools-list (first (filter (fn [m] (= "tools/list" (:method m))) @wire))]
                       (is (= "2025-11-25" (-> initialize :params :protocol-version)))
                       (is (nil? (-> initialize :params :_meta)))
                       (is (nil? (-> tools-list :params :_meta))
                           "_meta belongs to 2026-07-28 and must not leak backwards")))))))))

(deftest list-functions-work-on-a-stateless-session-test
  ;; All three gated on :server-capabilities, which initialize fills in. A
  ;; stateless session has no handshake, and request-discover is explicitly
  ;; optional, so all three sent nothing and returned nil on a session
  ;; create-session marks :initialized and the docs call usable immediately.
  (testing "prompts, resources and tools list without a prior discover"
    (let [sent (atom [])
          session (atom (client/create-session {:protocol-version "2026-07-28"
                                                :on-initialized nil}))
          context {:session session
                   :send-message (fn [m] (swap! sent conj m) nil)}]
      (is (not (contains? @session :server-capabilities))
          "nothing has seeded capabilities, which is the whole point")
      (client/request-prompt-list context)
      (client/request-resource-list context)
      (client/request-tool-list context)
      ;; The stateless path goes through call-with-round-trips, which is a
      ;; p/loop, so the send lands on a later tick. Wait on it rather than
      ;; reading the atom straight after the call.
      (async-test
       3000
       (p/let [_ (util/assert-atom sent
                                   (fn [msgs] (= 3 (count msgs)))
                                   2000
                                   "all three list calls reach the wire")]
         (is (= #{"prompts/list" "resources/list" "tools/list"}
                (set (map :method @sent)))
             "and they are the three we asked for"))))))

(deftest list-functions-respect-discovered-capabilities-test
  ;; Ungating these for stateless sessions over-corrected: they then ignored
  ;; :server-capabilities even after request-discover had populated them, so a
  ;; client called prompts/list at a server that had just said it has none.
  (testing "an un-discovered stateless session goes ahead"
    (let [sent (atom [])
          session (atom (client/create-session {:protocol-version "2026-07-28"
                                                :on-initialized nil}))
          context {:session session
                   :send-message (fn [m] (swap! sent conj m) nil)}]
      (client/request-tool-list context)
      (async-test
       3000
       (p/let [_ (util/assert-atom sent (fn [m] (seq m)) 2000 "the call goes out")]
         (is (= ["tools/list"] (map :method @sent)))))))

  (testing "once discovery says a capability is absent, the call is not made"
    (let [sent (atom [])
          session (atom (client/create-session {:protocol-version "2026-07-28"
                                                :on-initialized nil}))
          context {:session session
                   :send-message (fn [m] (swap! sent conj m) nil)}]
      ;; What request-discover stores: a server with prompts and no tools.
      (swap! session assoc :server-capabilities {:prompts {}})
      (client/request-tool-list context)
      (client/request-prompt-list context)
      (async-test
       3000
       (p/let [_ (util/assert-atom sent (fn [m] (seq m)) 2000 "the honoured call goes out")]
         (is (= ["prompts/list"] (map :method @sent))
             "only the capability the server actually declared"))))))

(deftest roots-list-changed-is-not-sent-on-a-stateless-session-test
  ;; 2026-07-28 removed notifications/roots/list_changed along with the
  ;; server-initiated roots/list it answered. Verified against both
  ;; specification sources: 2025-11-25 names it, 2026-07-28 does not name it
  ;; anywhere. Sending it there puts a method on the wire the revision does
  ;; not define, and stamping _meta so a dual-era server routed it "properly"
  ;; would only route it to a table with no handler for it either.
  (testing "a stateless session sends nothing"
    (let [sent (atom [])
          session (atom (client/create-session {:protocol-version "2026-07-28"
                                                :on-initialized nil}))
          context {:session session
                   :send-message (fn [m] (swap! sent conj m) nil)}]
      (client/notify-root-list-changed context)
      (is (= [] @sent))))

  (testing "a handshake session still sends it, since that revision has it"
    (let [sent (atom [])
          session (atom (client/create-session {:on-initialized nil}))
          context {:session session
                   :send-message (fn [m] (swap! sent conj m) nil)}]
      (client/notify-root-list-changed context)
      (is (= ["notifications/roots/list_changed"] (map :method @sent))))))

(deftest unsubscribe-settles-the-listen-promise-test
  ;; request-subscribe's promise resolves when the subscription closes, and a
  ;; server told to cancel does not send the closing response. Without this the
  ;; promise never settled and its registry entry sat there for the life of the
  ;; session.
  (testing "ending a subscription settles the request that opened it"
    (async-test
     3000
     (let [sent (atom [])
           session (atom (client/create-session {:protocol-version "2026-07-28"
                                                 :on-initialized nil}))
           context {:session session
                    :send-message (fn [m] (swap! sent conj m) nil)}
           listening (client/request-subscribe context {:tools-list-changed true})]
       (p/let [_ (util/assert-atom session
                                   (fn [s] (= 1 (count (:handler-by-called-method-id s))))
                                   2000
                                   "the listen request is registered")]
         (let [subscription-id (first (keys (:handler-by-called-method-id @session)))]
           (client/notify-unsubscribe context subscription-id)
           (is (= {} (:handler-by-called-method-id @session))
               "the registry entry is released rather than leaked")
           (p/then listening
                   (fn [result]
                     (is (nil? result)
                         "and the promise resolves, as request-subscribe documents")))))))))
