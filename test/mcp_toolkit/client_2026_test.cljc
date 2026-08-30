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
                     (is (= protocol/supported-protocol-versions
                            (:server-supported-protocol-versions @client-session)))
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
