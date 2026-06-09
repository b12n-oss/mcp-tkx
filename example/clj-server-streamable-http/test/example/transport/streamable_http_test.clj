(ns example.transport.streamable-http-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [example.transport.streamable-http :as t]
   [mcp-toolkit.server :as server]))

(deftest json-round-trip
  (testing "->json encodes kebab keys as camelCase; parse-message decodes camelCase to kebab"
    (is (= "{\"protocolVersion\":\"2025-11-25\"}"
           (t/->json {:protocol-version "2025-11-25"})))
    (is (= {:protocol-version "2025-11-25"}
           (t/parse-message {:req {:body "{\"protocolVersion\":\"2025-11-25\"}"}})))
    (is (nil? (t/parse-message {:req {:body "{not json"}})))))

(deftest host-and-origin-validation
  (testing "exact + wildcard host matching"
    (is (true?  (t/valid-host? "127.0.0.1:7926" #{"127.0.0.1:*"})))
    (is (true?  (t/valid-host? "127.0.0.1:1"    #{"127.0.0.1:*"})))
    (is (false? (t/valid-host? "evil.com:80"    #{"127.0.0.1:*"})))
    (is (false? (t/valid-host? ""               #{"127.0.0.1:*"}))))
  (testing "origin: blank is allowed, otherwise must match"
    (is (true?  (t/valid-origin? ""                       #{"http://localhost:7926"})))
    (is (true?  (t/valid-origin? "http://localhost:7926"  #{"http://localhost:7926"})))
    (is (false? (t/valid-origin? "http://evil.com"        #{"http://localhost:7926"})))))

(deftest session-id-shape
  (testing "new-session-id returns a fresh UUID string each call"
    (let [a (t/new-session-id) b (t/new-session-id)]
      (is (string? a))
      (is (not= a b))
      (is (re-matches #"[0-9a-f-]{36}" a)))))

(deftest session-pool-helpers
  (let [ctx (t/ctx-start {})
        rec (t/assoc-session! ctx "sid-1" :fake-data)]
    (testing "assoc-session! stores a namespaced record and returns it"
      (is (= "sid-1" (:session/id rec)))
      (is (= :fake-data (:session/data rec)))
      (is (instance? clojure.lang.Atom (:session/get-channel rec))))
    (testing "fetch-session! returns it by id, nil for unknown"
      (is (= rec (t/fetch-session! ctx "sid-1")))
      (is (nil? (t/fetch-session! ctx "nope"))))
    (testing "dissoc-session! removes it"
      (t/dissoc-session! ctx "sid-1")
      (is (nil? (t/fetch-session! ctx "sid-1"))))))

(defn- test-ctx []
  (-> (t/ctx-start {:settings {:allowed-hosts #{"127.0.0.1:*"}}})
      (assoc :create-session-fn
             (fn [_ctx _sid]
               (atom (server/create-session
                      {:server-info {:name "test-srv" :version "9.9"}}))))))

(defn- json-req [method-map & {:keys [session-id]}]
  {:request-method :post
   :headers (cond-> {"content-type" "application/json" "host" "127.0.0.1:7926"}
              session-id (assoc "mcp-session-id" session-id))
   :body (t/->json method-map)})

(defn- seed-session! [ctx]
  (let [sid (t/new-session-id)
        data ((:create-session-fn ctx) ctx sid)]
    (t/assoc-session! ctx sid data)
    sid))

(deftest post-unknown-session-404
  (let [ctx (test-ctx)
        resp (t/handle-post ctx (json-req {:jsonrpc "2.0" :id 1 :method "tools/list"} :session-id "ghost"))]
    (is (= 404 (:status resp)))))

(deftest post-notification-returns-202
  (let [ctx (test-ctx)
        sid (seed-session! ctx)
        resp (t/handle-post ctx (json-req {:jsonrpc "2.0" :method "notifications/cancelled"
                                           :params {:request-id 999}} :session-id sid))]
    (is (= 202 (:status resp)))
    (is (= "Accepted" (:body resp)))))

;; ── P3.T1: flip-decision tests ───────────────────────────────────────────────

(defn- recording-sink []
  (let [calls (atom [])]
    {:calls calls
     :sink {:open-sse! (fn [] (swap! calls conj [:open-sse]))
            :frame!    (fn [m] (swap! calls conj [:frame m]))}}))

(deftest flip-pure-json-when-only-response
  (let [{:keys [calls sink]} (recording-sink)
        [send! state] (t/make-request-send-message 7 sink)]
    (send! {:jsonrpc "2.0" :id 7 :result {:ok true}})
    (is (= [] @calls) "no streaming for a lone response")
    (is (false? (:sse? @state)))
    (is (= {:jsonrpc "2.0" :id 7 :result {:ok true}} (:buffered @state)))))

(deftest flip-to-sse-on-progress-then-response
  (let [{:keys [calls sink]} (recording-sink)
        [send! state] (t/make-request-send-message 7 sink)
        progress {:jsonrpc "2.0" :method "notifications/progress" :params {:progress 1}}
        response {:jsonrpc "2.0" :id 7 :result {:ok true}}]
    (send! progress)   ; first non-response → flip
    (send! response)   ; now in SSE mode → framed, not buffered
    (is (= [[:open-sse] [:frame progress] [:frame response]] @calls))
    (is (true? (:sse? @state)))
    (is (nil? (:buffered @state)))))

(deftest flip-server-request-during-handling-streams
  (let [{:keys [calls sink]} (recording-sink)
        [send! _state] (t/make-request-send-message 7 sink)
        sampling-req {:jsonrpc "2.0" :id 0 :method "sampling/createMessage" :params {}}]
    (send! sampling-req)  ; a server->client REQUEST (different id, has :method) → flip
    (is (= [[:open-sse] [:frame sampling-req]] @calls))))

;; ── P3.T4: DELETE /mcp teardown ─────────────────────────────────────────────

(deftest delete-removes-session
  (let [ctx (test-ctx)
        sid (seed-session! ctx)]
    (is (= 404 (:status (t/handle-delete ctx {:headers {"mcp-session-id" "ghost"}}))))
    (let [resp (t/handle-delete ctx {:headers {"mcp-session-id" sid}})]
      (is (= 204 (:status resp)))
      (is (nil? (t/fetch-session! ctx sid))))))

;; ── P3.T3: GET /mcp stream guard ────────────────────────────────────────────

(deftest get-stream-conflicts-when-already-open
  (let [ctx (test-ctx)
        sid (seed-session! ctx)
        session (t/fetch-session! ctx sid)]
    (testing "unknown session → 404"
      (is (= 404 (:status (t/handle-get ctx {:headers {"mcp-session-id" "ghost"}})))))
    (testing "a second open is 405 while a channel is registered"
      (reset! (:session/get-channel session) ::fake-channel)
      (is (= 405 (:status (t/handle-get ctx {:headers {"mcp-session-id" sid}})))))))

;; ── P4.T1: bounded per-session event ring ────────────────────────────────────

(defn- bare-session []
  {:session/id "s" :session/event-log (atom {:next-id 0 :events []})})

(deftest record-event-allocates-monotonic-ids
  (let [s (bare-session)
        a (t/record-event! s {:jsonrpc "2.0" :method "x"} 1000)
        b (t/record-event! s {:jsonrpc "2.0" :method "y"} 1001)]
    (is (= 1 (:id a)))
    (is (= 2 (:id b)))
    (is (re-find #"^id: 1\nevent: message\ndata: " (:frame a)))
    (is (= 2 (count (:events @(:session/event-log s)))))))

(deftest record-event-evicts-by-age
  (let [s (bare-session)]
    (t/record-event! s {:jsonrpc "2.0" :method "old"} 0)            ; ts 0
    (t/record-event! s {:jsonrpc "2.0" :method "new"} 400000)       ; 400s later → old pruned
    (let [ids (mapv :id (:events @(:session/event-log s)))]
      (is (= [2] ids) "the >5min-old event was pruned"))))
