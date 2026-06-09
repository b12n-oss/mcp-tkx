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

(def ^:private init-msg
  {:jsonrpc "2.0" :id 0 :method "initialize"
   :params {:protocol-version "2025-11-25"
            :client-info {:name "test-client" :version "1.0"}
            :capabilities {}}})

(deftest post-initialize-assigns-session
  (let [ctx (test-ctx)
        resp (t/handle-post ctx (json-req init-msg))
        sid  (get-in resp [:headers "mcp-session-id"])
        body (t/parse-message {:req {:body (:body resp)}})]
    (is (= 200 (:status resp)))
    (is (string? sid))
    (testing "the session is now in the pool"
      (is (some? (t/fetch-session! ctx sid))))
    (testing "the body is the initialize result echoing the negotiated version"
      (is (= "2025-11-25" (get-in body [:result :protocol-version])))
      (is (= "test-srv" (get-in body [:result :server-info :name]))))))

(def ^:private initialized-notif
  {:jsonrpc "2.0" :method "notifications/initialized"})

(defn- handshake! [ctx]
  (let [resp (t/handle-post ctx (json-req init-msg))
        sid  (get-in resp [:headers "mcp-session-id"])]
    (t/handle-post ctx (json-req initialized-notif :session-id sid))
    sid))

(deftest post-request-returns-json-result
  (let [ctx (test-ctx)
        sid (handshake! ctx)
        resp (t/handle-post ctx (json-req {:jsonrpc "2.0" :id 1 :method "tools/list"} :session-id sid))
        body (t/parse-message {:req {:body (:body resp)}})]
    (is (= 200 (:status resp)))
    (is (= sid (get-in resp [:headers "mcp-session-id"])))
    (is (vector? (get-in body [:result :tools])))))

(deftest post-unknown-session-404
  (let [ctx (test-ctx)
        resp (t/handle-post ctx (json-req {:jsonrpc "2.0" :id 1 :method "tools/list"} :session-id "ghost"))]
    (is (= 404 (:status resp)))))
