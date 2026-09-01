(ns mcp-toolkit.dual-era-test
  "Tests for a server that answers both eras on one session.

   The specification allows this, choosing per request: a protocol version in
   _meta means stateless, an initialize means the handshake."
  (:require
   [clojure.test :refer [#?(:cljs async) deftest is testing]]
   [mcp-toolkit.client :as client]
   [mcp-toolkit.impl.server.handler :as handler]
   [mcp-toolkit.impl.server.handler-2026 :as handler-2026]
   [mcp-toolkit.impl.server.handler-dual :as handler-dual]
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

(def a-tool {:name "t"
             :description "d"
             :input-schema {}})

(def modern-meta
  {protocol/meta-protocol-version "2026-07-28"
   protocol/meta-client-capabilities {}})

(defn- dual-session [opts]
  (atom (server/create-session (merge {:dual-era? true
                                       :on-initialized nil
                                       :server-info {:name "both"
                                                     :version "1.0.0"}
                                       :tools [a-tool]}
                                      opts))))

(defn- drive
  "Feeds messages in order and returns a promise of everything sent back."
  [session messages]
  (let [sent (atom [])
        context {:session session
                 :send-message (fn [message] (swap! sent conj message) nil)}]
    (-> (reduce (fn [pending message]
                  (p/then pending (fn [_] (json-rpc/handle-message context message))))
                (p/resolved nil)
                messages)
        (p/then (fn [_] @sent)))))

(def handshake-opening
  [{:jsonrpc "2.0"
    :id 100
    :method "initialize"
    :params {:protocol-version "2025-11-25"
             :capabilities {}
             :client-info {:name "old"
                           :version "1"}}}
   {:jsonrpc "2.0"
    :method "notifications/initialized"}])

;; ---------------------------------------------------------------------------
;; Session wiring
;; ---------------------------------------------------------------------------

(deftest dual-session-wiring-test
  (let [session (server/create-session {:dual-era? true})]
    (testing "a dual session starts un-initialized, since a handshake may still arrive"
      (is (true? (:dual-era? session)))
      (is (false? (:initialized session)))
      (is (nil? (:protocol-version session))
          "no single negotiated version: each request brings its own or handshakes"))

    (testing "it serves both eras but only answers one of them statelessly"
      (is (= ["2024-11-05" "2025-03-26" "2025-06-18" "2025-11-25" "2026-07-28"]
             (:server-supported-protocol-versions session)))
      (is (= ["2026-07-28"] (:modern-protocol-versions session))))

    (testing "the handshake table lives beside the dual one, not inside it"
      (is (= handler-dual/handler-by-method (:handler-by-method session)))
      (is (some? (:legacy-handler-by-method session))
          "so completing a handshake can move it without touching the dual table")))

  (testing "single-era sessions are unchanged"
    (let [handshake (server/create-session {})
          stateless (server/create-session {:protocol-version "2026-07-28"})]
      (is (false? (:dual-era? handshake)))
      (is (nil? (:modern-protocol-versions handshake)))
      (is (nil? (:legacy-handler-by-method handshake)))
      (is (false? (:dual-era? stateless)))
      (is (= ["2026-07-28"] (:modern-protocol-versions stateless))))))

(deftest dual-table-test
  (testing "the table is the union of both eras"
    (doseq [method (keys handler-2026/handler-by-method)]
      (is (contains? handler-dual/handler-by-method method)))
    (doseq [method (concat (keys handler/handler-by-method-pre-initialization)
                           (keys handler/handler-by-method-post-initialization))]
      (is (contains? handler-dual/handler-by-method method)))))

(deftest era-detection-test
  (testing "a declared protocol version marks a request as stateless"
    (is (true? (handler-dual/modern-request?
                {:method "tools/list"
                 :params {:_meta modern-meta}})))
    (is (false? (handler-dual/modern-request?
                 {:method "tools/list"
                  :params {}}))))

  (testing "discovery counts as stateless even without a version"
    ;; A stateless client may probe with it before it knows anything, and the
    ;; handshake era has no such method to confuse it with.
    (is (true? (handler-dual/modern-request? {:method "server/discover"}))))

  (testing "an initialize is a handshake request"
    (is (false? (handler-dual/modern-request?
                 {:method "initialize"
                  :params {:protocol-version "2025-11-25"}})))))

;; ---------------------------------------------------------------------------
;; Serving both
;; ---------------------------------------------------------------------------

(deftest serves-each-era-in-its-own-shape-test
  (testing "a stateless request gets stateless results"
    (async-test
     3000
     (-> (drive (dual-session {})
                [{:jsonrpc "2.0"
                  :id 1
                  :method "tools/list"
                  :params {:_meta modern-meta}}])
         (p/then (fn [sent]
                   (let [result (-> sent first :result)]
                     (is (= "complete" (:result-type result)))
                     (is (= 60000 (:ttl-ms result)))
                     (is (some? (get-in result [:_meta protocol/meta-server-info])))))))))

  (testing "a handshake request gets handshake results"
    (async-test
     3000
     (-> (drive (dual-session {})
                (conj (vec handshake-opening)
                      {:jsonrpc "2.0"
                       :id 1
                       :method "tools/list"
                       :params {}}))
         (p/then (fn [sent]
                   (let [result (-> sent last :result)]
                     (is (= 1 (count (:tools result))))
                     (is (nil? (:result-type result))
                         "resultType belongs to the stateless era only")
                     (is (nil? (:ttl-ms result)))))))))

  (testing "an initialize gets a real handshake, not the diagnostic"
    (async-test
     3000
     (-> (drive (dual-session {}) [(first handshake-opening)])
         (p/then (fn [sent]
                   (let [response (first sent)]
                     (is (nil? (:error response))
                         "a dual server can handshake, so it must not refuse one")
                     (is (= "2025-11-25" (-> response :result :protocol-version))))))))))

(deftest handshake-does-not-strand-stateless-clients-test
  ;; The trap this design exists to avoid. Merging the tables and letting
  ;; notifications/initialized swap in the post-handshake table would serve the
  ;; first handshake client and break every stateless one, because that swap
  ;; replaces the dispatch table on shared session state.
  (testing "a stateless client is still served after a handshake completes"
    (async-test
     5000
     (let [session (dual-session {})]
       (-> (drive session
                  (concat [{:jsonrpc "2.0"
                            :id 1
                            :method "tools/list"
                            :params {:_meta modern-meta}}]
                          handshake-opening
                          [{:jsonrpc "2.0"
                            :id 2
                            :method "tools/list"
                            :params {:_meta modern-meta}}]))
           (p/then (fn [sent]
                     (let [before (first (filter (fn [m] (= 1 (:id m))) sent))
                           after (first (filter (fn [m] (= 2 (:id m))) sent))]
                       (is (= "complete" (-> before :result :result-type)))
                       (is (= "complete" (-> after :result :result-type))
                           "the stateless client must not lose its era mid-session")
                       (is (= (-> before :result :ttl-ms)
                              (-> after :result :ttl-ms)))))))))))

(deftest methods-are-scoped-to-their-era-test
  (testing "a method this revision removed still answers a handshake client"
    (async-test
     3000
     (-> (drive (dual-session {}) [{:jsonrpc "2.0"
                                    :id 1
                                    :method "ping"
                                    :params {}}])
         (p/then (fn [sent]
                   (is (= {} (-> sent first :result))))))))

  (testing "and is gone for a stateless one"
    (async-test
     3000
     (-> (drive (dual-session {})
                [{:jsonrpc "2.0"
                  :id 1
                  :method "ping"
                  :params {:_meta modern-meta}}])
         (p/then (fn [sent]
                   (is (= -32601 (-> sent first :error :code)))))))))

;; ---------------------------------------------------------------------------
;; Versions
;; ---------------------------------------------------------------------------

(deftest version-reporting-test
  (testing "discovery names every revision the session serves"
    (async-test
     3000
     (-> (drive (dual-session {}) [{:jsonrpc "2.0"
                                    :id 1
                                    :method "server/discover"}])
         (p/then (fn [sent]
                   (is (= ["2024-11-05" "2025-03-26" "2025-06-18" "2025-11-25" "2026-07-28"]
                          (-> sent first :result :supported-versions))))))))

  (testing "but the stateless path accepts only the revision it implements"
    ;; Serving a handshake version with stateless semantics would be answering
    ;; a question that was not asked.
    (async-test
     3000
     (-> (drive (dual-session {})
                [{:jsonrpc "2.0"
                  :id 1
                  :method "tools/list"
                  :params {:_meta {protocol/meta-protocol-version "2025-11-25"}}}])
         (p/then (fn [sent]
                   (let [error (-> sent first :error)]
                     (is (= -32022 (:code error)))
                     (is (= ["2026-07-28"] (-> error :data :supported)))
                     (is (= "2025-11-25" (-> error :data :requested))))))))))

;; ---------------------------------------------------------------------------
;; Notifications
;; ---------------------------------------------------------------------------

(deftest notifications-reach-both-audiences-test
  (testing "one event, a tagged copy per subscription and one for the handshake client"
    (async-test
     5000
     (let [session (dual-session {})
           sent (atom [])
           context {:session session
                    :send-message (fn [message] (swap! sent conj message) nil)}
           call (fn [message] (json-rpc/handle-message context message))]
       (-> (reduce (fn [pending message] (p/then pending (fn [_] (call message))))
                   (p/resolved nil)
                   (concat handshake-opening
                           [{:jsonrpc "2.0"
                             :id 7
                             :method "subscriptions/listen"
                             :params {:notifications {:tools-list-changed true}
                                      :_meta modern-meta}}]))
           (p/then (fn [_]
                     (reset! sent [])
                     (server/notify-tool-list-changed context)
                     (let [delivered (mapv (fn [m]
                                             [(:method m)
                                              (-> m :params :_meta
                                                  (get protocol/meta-subscription-id))])
                                           @sent)]
                       (is (= [["notifications/tools/list_changed" 7]
                               ["notifications/tools/list_changed" nil]]
                              delivered)
                           "tagged for the subscriber, untagged down the handshake connection")))))))))

(testing "with no handshake client, only the subscribers hear about it"
  (async-test
   5000
   (let [session (dual-session {})
         sent (atom [])
         context {:session session
                  :send-message (fn [message] (swap! sent conj message) nil)}]
     (-> (json-rpc/handle-message context
                                  {:jsonrpc "2.0"
                                   :id 7
                                   :method "subscriptions/listen"
                                   :params {:notifications {:tools-list-changed true}
                                            :_meta modern-meta}})
         (p/then (fn [_]
                   (reset! sent [])
                   (server/notify-tool-list-changed context)
                   (is (= [7] (mapv (fn [m] (-> m :params :_meta
                                                (get protocol/meta-subscription-id)))
                                    @sent))
                       "nothing goes down a connection that never handshook"))))))

  (testing "a resource update respects each era's idea of interest"
    (async-test
     5000
     (let [session (dual-session {:resources [{:uri "file:///p/a.json"
                                               :name "a"
                                               :text "A"}]})
           sent (atom [])
           context {:session session
                    :send-message (fn [message] (swap! sent conj message) nil)}]
       (-> (reduce (fn [pending message] (p/then pending (fn [_] (json-rpc/handle-message context message))))
                   (p/resolved nil)
                   (concat handshake-opening
                           ;; the handshake client subscribes its way
                           [{:jsonrpc "2.0"
                             :id 5
                             :method "resources/subscribe"
                             :params {:uri "file:///p/a.json"}}
                            ;; the stateless one subscribes to the subtree
                            {:jsonrpc "2.0"
                             :id 8
                             :method "subscriptions/listen"
                             :params {:notifications {:resource-subscriptions ["file:///p/"]}
                                      :_meta modern-meta}}]))
           (p/then (fn [_]
                     (reset! sent [])
                     (server/notify-resource-updated context {:uri "file:///p/a.json"})
                     (is (= [8 nil]
                            (mapv (fn [m] (-> m :params :_meta
                                              (get protocol/meta-subscription-id)))
                                  @sent))
                         "both, each because of its own subscription")
                     (reset! sent [])
                     ;; only the stateless subtree covers this one
                     (server/notify-resource-updated context {:uri "file:///p/other.json"})
                     (is (= [8] (mapv (fn [m] (-> m :params :_meta
                                                  (get protocol/meta-subscription-id)))
                                      @sent))
                         "the handshake client never subscribed to this URI"))))))))

;; ---------------------------------------------------------------------------
;; Unsubscribing across the era boundary
;; ---------------------------------------------------------------------------

(deftest unsubscribe-reaches-the-modern-table-test
  ;; A dual-era server picks the era from _meta. notify-unsubscribe used to
  ;; send notifications/cancelled with no _meta at all, so it was read as a
  ;; handshake message and routed to the legacy table, where it cancelled
  ;; nothing. The subscription stayed open and kept delivering, and the client
  ;; had no way to tell.
  (testing "a cancelled notification carrying _meta is a stateless request"
    (is (true? (handler-dual/modern-request?
                {:method "notifications/cancelled"
                 :params {:request-id 1
                          :_meta modern-meta}}))))

  (testing "and without _meta it is not, which is what made unsubscribe a no-op"
    (is (false? (handler-dual/modern-request?
                 {:method "notifications/cancelled"
                  :params {:request-id 1}}))))

  (testing "the client stamps _meta on a stateless session, so it routes modern"
    (let [sent (atom [])
          client-session (atom (client/create-session {:protocol-version "2026-07-28"
                                                       :on-initialized nil}))
          context {:session client-session
                   :send-message (fn [m] (swap! sent conj m) nil)}]
      (client/notify-unsubscribe context 7)
      (let [message (first @sent)]
        (is (= "notifications/cancelled" (:method message)))
        (is (= 7 (-> message :params :request-id)))
        (is (= "2026-07-28" (-> message :params :_meta (get protocol/meta-protocol-version)))
            "the protocol version must be present or a dual-era server mis-routes it")
        (is (true? (handler-dual/modern-request? message))
            "and the dual dispatch must agree it is a stateless request"))))

  (testing "end to end: the cancel actually ends the subscription on a dual session"
    ;; The blocks above check modern-request? in isolation, and that predicate
    ;; is not what the fix changed. This drives the client's own message
    ;; through the real dual dispatch table and asserts the subscription is
    ;; gone, which is the behaviour that was broken.
    ;; The message shape the block above proved the client produces. Built
    ;; here rather than driven through the client, so this asserts the dispatch
    ;; routing and nothing else.
    ;; Awaited. The 2026 table's entries go through wrap-handler, which returns
    ;; a promise, so the swap! lands on a later tick. ClojureScript defers where
    ;; the JVM happened not to, and a synchronous assertion passed on one host
    ;; and failed on the other.
    (async-test
     5000
     (let [server-session (dual-session {})
           handler (get handler-dual/handler-by-method "notifications/cancelled")]
       (swap! server-session assoc :subscription-by-id {7 {:tools-list-changed true}})
       (p/let [_ (handler {:session server-session
                           :send-message (fn [_] nil)
                           :message {:jsonrpc "2.0"
                                     :method "notifications/cancelled"
                                     :params {:request-id 7
                                              :_meta modern-meta}}})]
         (is (= {} (:subscription-by-id @server-session))
             "the subscription is gone, which is what routing to the modern table does")))))

  (testing "end to end: the same message without _meta leaves it in place"
    ;; The failure this fix addressed. A cancel with no _meta is read as a
    ;; handshake message, routed to the legacy table, and cancels nothing.
    (async-test
     5000
     (let [server-session (dual-session {})
           handler (get handler-dual/handler-by-method "notifications/cancelled")]
       (swap! server-session assoc :subscription-by-id {7 {:tools-list-changed true}})
       (p/let [_ (handler {:session server-session
                           :message {:jsonrpc "2.0"
                                     :method "notifications/cancelled"
                                     :params {:request-id 7}}
                           :send-message (fn [_] nil)})]
         (is (= {7 {:tools-list-changed true}} (:subscription-by-id @server-session))
             "the legacy table does not touch subscriptions, so it survives"))))))
