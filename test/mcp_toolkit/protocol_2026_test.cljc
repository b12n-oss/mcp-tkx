(ns mcp-toolkit.protocol-2026-test
  "Tests for protocol revision 2026-07-28: the stateless core and multi
   round-trip requests."
  (:require
   [camel-snake-kebab.core :as csk]
   [camel-snake-kebab.extras :as cske]
   [clojure.test :refer [#?(:cljs async) deftest is testing]]
   [mcp-toolkit.impl.mrtr :as mrtr]
   [mcp-toolkit.impl.server.handler-2026 :as handler-2026]
   [mcp-toolkit.json-rpc :as json-rpc]
   [mcp-toolkit.protocol :as protocol]
   [mcp-toolkit.server :as server]
   [promesa.core :as p]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- async-test
  "Runs a promise to completion on the JVM, and defers to clojure.test's async
   support under ClojureScript."
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

(def request-meta
  {protocol/meta-protocol-version "2026-07-28"
   protocol/meta-client-capabilities {:elicitation {:form {}}}
   protocol/meta-client-info {:name "test-client"
                              :version "1"}})

(defn- drive
  "Feeds messages to a session in order. Returns a promise of every message the
   server sent back."
  [session messages]
  (let [sent (atom [])
        context {:session session
                 :send-message (fn [message] (swap! sent conj message) nil)}]
    (-> (reduce (fn [pending message]
                  (p/then pending (fn [_] (json-rpc/handle-message context message))))
                (p/resolved nil)
                messages)
        (p/then (fn [_] @sent)))))

(def echo-tool
  {:name "echo"
   :description "Echoes its argument"
   :input-schema {:type "object"
                  :properties {:msg {:type "string"}}}
   :tool-fn (fn [_context {:keys [msg]}]
              {:content [{:type "text"
                          :text (str "echo " msg)}]})})

(def greet-tool
  "A tool that cannot answer until the client tells it a name."
  {:name "greet"
   :description "Greets you by name"
   :input-schema {:type "object"
                  :properties {}}
   :tool-fn (fn [context _arguments]
              (if-some [answer (server/input-response context :who)]
                {:content [{:type "text"
                            :text (str "hello " (-> answer :content :name)
                                       " after " (server/request-state context))}]}
                (server/input-required
                 {:input-requests {:who (server/elicit-form-request
                                         {:message "Who are you?"
                                          :requested-schema {:type "object"
                                                             :properties {:name {:type "string"}}
                                                             :required ["name"]}})}
                  :request-state "asked-for-name"})))})

(def test-resource
  {:uri "ipfs:///world/hello.md"
   :name "hello.md"
   :mime-type "text/markdown"
   :text "Hello world"})

(defn- session-2026 [opts]
  (atom (server/create-session (merge {:protocol-version "2026-07-28"
                                       :server-info {:name "test-server"
                                                     :version "1.0.0"}}
                                      opts))))

;; ---------------------------------------------------------------------------
;; Protocol constants
;; ---------------------------------------------------------------------------

(deftest stateless-version-test
  (testing "only 2026-07-28 drops the handshake"
    (is (true? (protocol/stateless? "2026-07-28")))
    (is (false? (protocol/stateless? "2025-11-25")))
    (is (false? (protocol/stateless? "2025-06-18")))
    (is (false? (protocol/stateless? nil))))

  (testing "the latest version is advertised and supported"
    (is (= "2026-07-28" protocol/latest-protocol-version))
    (is (contains? (set protocol/supported-protocol-versions) "2026-07-28"))
    (is (= protocol/latest-protocol-version (last protocol/supported-protocol-versions))
        "the newest revision sorts last, which is what negotiation falls back to")))

(deftest opaque-wire-key-test
  (testing "protocol-defined and namespaced keys must survive the JSON boundary"
    (is (true? (protocol/opaque-wire-key? "_meta")))
    (is (true? (protocol/opaque-wire-key? protocol/meta-protocol-version)))
    (is (true? (protocol/opaque-wire-key? protocol/meta-server-info)))
    (is (true? (protocol/opaque-wire-key? "mcp-toolkit/who"))))

  (testing "ordinary protocol fields are converted as usual"
    (is (false? (protocol/opaque-wire-key? "ttlMs")))
    (is (false? (protocol/opaque-wire-key? "cacheScope")))
    (is (false? (protocol/opaque-wire-key? :result-type)))))

(deftest renumbered-error-codes-test
  (testing "the codes this revision moved into the specification block"
    (is (= -32020 protocol/header-mismatch-code))
    (is (= -32021 protocol/missing-required-client-capability-code))
    (is (= -32022 protocol/unsupported-protocol-version-code)))

  (testing "a missing resource became Invalid Params"
    (is (= -32602 protocol/invalid-params-code))
    (is (= -32002 protocol/legacy-resource-not-found-code))))

;; ---------------------------------------------------------------------------
;; JSON key conversion
;; ---------------------------------------------------------------------------

(deftest wire-key-conversion-test
  (testing "ordinary protocol fields convert between camelCase and kebab-case"
    (is (= :result-type (protocol/decode-key "resultType")))
    (is (= :ttl-ms (protocol/decode-key "ttlMs")))
    (is (= :cache-scope (protocol/decode-key "cacheScope")))
    (is (= "resultType" (protocol/encode-key :result-type)))
    (is (= "ttlMs" (protocol/encode-key :ttl-ms))))

  (testing "_meta keeps its underscore in both directions"
    (is (= :_meta (protocol/decode-key "_meta")))
    (is (= "_meta" (protocol/encode-key :_meta))))

  (testing "namespaced keys stay strings, since a keyword would lose the namespace"
    (is (= protocol/meta-protocol-version (protocol/decode-key protocol/meta-protocol-version)))
    (is (= protocol/meta-server-info (protocol/encode-key protocol/meta-server-info)))
    (is (= "mcp-toolkit/who" (protocol/decode-key "mcp-toolkit/who"))))

  (testing "the whole 2026-07-28 wire vocabulary round-trips losslessly"
    (doseq [k [protocol/meta-protocol-version
               protocol/meta-client-capabilities
               protocol/meta-client-info
               protocol/meta-log-level
               protocol/meta-server-info
               protocol/meta-subscription-id
               "_meta" "progressToken" "resultType" "ttlMs" "cacheScope"
               "supportedVersions" "inputRequests" "requestState"]]
      (is (= k (protocol/encode-key (protocol/decode-key k)))
          (str k " must survive a round trip unchanged"))))

  (testing "a namespaced keyword keeps its namespace"
    ;; js->clj with :keywordize-keys hands over namespaced keywords, and `name`
    ;; alone would silently drop the namespace here.
    (is (= "io.modelcontextprotocol/protocolVersion"
           (protocol/encode-key :io.modelcontextprotocol/protocolVersion))))

  (testing "the transport wiring the guides document survives a whole-message walk"
    ;; docs/guide/kebab-case-transformation.md and getting-started.md tell a
    ;; transport author to use protocol/encode-key and protocol/decode-key.
    ;; Those guides previously taught raw camel-snake-kebab, which silently
    ;; corrupts the two keys asserted here. This pins the documented wiring so
    ;; the guides and the code cannot drift apart again.
    (let [message  {:max-tokens 10
                    :input-schema {:type "object"}
                    :_meta {protocol/meta-protocol-version "2026-07-28"}}
          on-wire  (cske/transform-keys protocol/encode-key message)
          returned (cske/transform-keys protocol/decode-key on-wire)]
      (is (= {"maxTokens"   10
              "inputSchema" {"type" "object"}
              "_meta"       {protocol/meta-protocol-version "2026-07-28"}}
             on-wire)
          "outbound: ordinary keys camelise, _meta and namespaced keys are verbatim")
      (is (= message returned)
          "inbound: the message round-trips back to exactly what we sent")))

  (testing "raw camel-snake-kebab corrupts the two keys protocol/* protects"
    ;; The reason protocol/encode-key and protocol/decode-key exist at all. If
    ;; this ever starts passing, csk gained the exceptions and the guides'
    ;; warning can be revisited.
    (is (not= :_meta (csk/->kebab-case-keyword "_meta"))
        "csk drops the underscore, so _meta stops matching")
    (is (not= protocol/meta-protocol-version
              (csk/->kebab-case-keyword protocol/meta-protocol-version))
        "csk mangles a namespaced key, which then cannot round-trip"))

  (testing "correlation keys survive the boundary, which is why they are namespaced"
    (doseq [k [:who :step1 :foo_bar :github-login]]
      (let [wire (mrtr/->wire-key k)]
        (is (= wire (protocol/encode-key (protocol/decode-key wire))))
        (is (= k (mrtr/<-wire-key (protocol/decode-key wire))))))))

;; ---------------------------------------------------------------------------
;; Multi round-trip correlation keys
;; ---------------------------------------------------------------------------

(deftest correlation-key-round-trip-test
  (testing "keys survive a full round trip unchanged"
    (doseq [k [:who :step1 :foo_bar :github-login :capital-of-france]]
      (is (= k (-> k mrtr/->wire-key mrtr/<-wire-key))
          (str "key " k " must come back as itself"))))

  (testing "keys are namespaced so transports treat them as opaque"
    (is (true? (protocol/opaque-wire-key? (mrtr/->wire-key :step1)))
        "a bare key would be rewritten by kebab conversion and come back wrong")
    (is (= "mcp-toolkit/who" (mrtr/->wire-key :who))))

  (testing "keys that differ only in separator stay distinct"
    (let [ks [:foo-bar :foo_bar]]
      (is (= 2 (count (distinct (map mrtr/->wire-key ks))))
          "kebab conversion would collapse these onto one wire key"))))

(deftest input-required-shape-test
  (testing "an interim result names itself and carries its requests"
    (let [result (mrtr/input-required
                  {:input-requests {:who (mrtr/elicit-form-request
                                          {:message "Who?"
                                           :requested-schema {:type "object"}})}
                   :request-state "s1"})]
      (is (= "input_required" (:result-type result)))
      (is (= "s1" (:request-state result)))
      (is (= ["mcp-toolkit/who"] (keys (:input-requests result))))
      (is (= "elicitation/create" (get-in result [:input-requests "mcp-toolkit/who" :method])))
      (is (= "form" (get-in result [:input-requests "mcp-toolkit/who" :params :mode])))))

  (testing "request-state is optional"
    (is (not (contains? (mrtr/input-required {:input-requests {}}) :request-state))))

  (testing "input-required? recognises only interim results"
    (is (true? (mrtr/input-required? (mrtr/input-required {:input-requests {}}))))
    (is (false? (mrtr/input-required? {:content []})))
    (is (false? (mrtr/input-required? {:result-type "complete"})))
    (is (false? (mrtr/input-required? nil)))))

(deftest request-builders-test
  (testing "each builder names the method the spec defines"
    (is (= "sampling/createMessage" (:method (mrtr/sampling-request {:messages []
                                                                     :max-tokens 1}))))
    (is (= "roots/list" (:method (mrtr/roots-request))))
    (is (= "elicitation/create" (:method (mrtr/elicit-form-request {:message "m"}))))
    (is (= "elicitation/create" (:method (mrtr/elicit-url-request {:message "m"
                                                                   :url "https://x"})))))

  (testing "URL elicitation carries no elicitation-id, which this revision removed"
    (let [params (:params (mrtr/elicit-url-request {:message "m"
                                                    :url "https://x"}))]
      (is (= "url" (:mode params)))
      (is (not (contains? params :elicitation-id))))))

(deftest input-response-reading-test
  (let [context {:message {:params {:request-state "s1"
                                    :input-responses {"mcp-toolkit/who" {:action "accept"}}}}}]
    (testing "a single answer is read back under the key it was asked for"
      (is (= {:action "accept"} (server/input-response context :who)))
      (is (nil? (server/input-response context :missing))))

    (testing "all answers are re-keyed back to the handler's own keys"
      (is (= {:who {:action "accept"}} (server/input-responses context))))

    (testing "request state and retry detection"
      (is (= "s1" (server/request-state context)))
      (is (true? (server/retry? context)))
      (is (false? (server/retry? {:message {:params {:name "greet"}}}))))))

;; ---------------------------------------------------------------------------
;; Session wiring
;; ---------------------------------------------------------------------------

(deftest create-session-protocol-selection-test
  (testing "a handshake session is unchanged"
    (let [session (server/create-session {})]
      (is (false? (:initialized session)))
      (is (nil? (:protocol-version session)))
      (is (contains? (:handler-by-method session) "initialize"))))

  (testing "a stateless session has no handshake and is live immediately"
    (let [session (server/create-session {:protocol-version "2026-07-28"})]
      (is (true? (:initialized session)))
      (is (= "2026-07-28" (:protocol-version session)))
      (is (contains? (:handler-by-method session) "server/discover"))
      (is (contains? (:handler-by-method session) "initialize")
          "routed on purpose, to answer a handshake client with a diagnostic
           rather than a bare Method not found")))

  (testing "a session reports only the versions its own dispatch table serves"
    (is (= ["2026-07-28"]
           (:server-supported-protocol-versions
            (server/create-session {:protocol-version "2026-07-28"})))
        "a stateless session cannot serve a handshake client and must not say it can")
    (is (= ["2024-11-05" "2025-03-26" "2025-06-18" "2025-11-25"]
           (:server-supported-protocol-versions (server/create-session {}))))))

(deftest removed-methods-test
  (testing "the dispatch table drops what this revision deleted"
    (doseq [method ["ping" "logging/setLevel" "resources/subscribe"
                    "resources/unsubscribe" "notifications/roots/list_changed"]]
      (is (not (contains? handler-2026/handler-by-method method))
          (str method " was removed in 2026-07-28"))))

  (testing "the surviving feature methods are still routed"
    (doseq [method ["tools/list" "tools/call" "prompts/list" "prompts/get"
                    "resources/list" "resources/read" "resources/templates/list"
                    "completion/complete" "notifications/cancelled" "server/discover"]]
      (is (contains? handler-2026/handler-by-method method)))))

;; ---------------------------------------------------------------------------
;; Result decoration
;; ---------------------------------------------------------------------------

(deftest decorate-result-test
  (let [session (session-2026 {})]
    (testing "every result states its type and identifies the server"
      (let [result (handler-2026/decorate-result session "tools/call" {:content []})]
        (is (= "complete" (:result-type result)))
        (is (= {:name "test-server"
                :version "1.0.0"}
               (get-in result [:_meta protocol/meta-server-info])))))

    (testing "an interim result keeps the type its handler chose"
      (is (= "input_required"
             (:result-type (handler-2026/decorate-result
                            session "tools/call" {:result-type "input_required"})))))

    (testing "only cacheable results carry freshness hints"
      (is (= 60000 (:ttl-ms (handler-2026/decorate-result session "tools/list" {:tools []}))))
      (is (= "public" (:cache-scope (handler-2026/decorate-result session "tools/list" {:tools []}))))
      (is (nil? (:ttl-ms (handler-2026/decorate-result session "tools/call" {:content []})))
          "tools/call is not a CacheableResult"))

    (testing "all six cacheable results are covered"
      (doseq [method ["server/discover" "tools/list" "prompts/list"
                      "resources/list" "resources/templates/list" "resources/read"]]
        (let [result (handler-2026/decorate-result session method {})]
          (is (number? (:ttl-ms result)) (str method " needs a ttl"))
          (is (contains? #{"public" "private"} (:cache-scope result))
              (str method " needs a cache scope")))))

    (testing "a handler's own freshness values win over the defaults"
      (let [result (handler-2026/decorate-result session "tools/list"
                                                 {:tools []
                                                  :ttl-ms 123
                                                  :cache-scope "private"})]
        (is (= 123 (:ttl-ms result)))
        (is (= "private" (:cache-scope result)))))

    (testing "existing _meta entries are preserved alongside the server identity"
      (let [result (handler-2026/decorate-result session "tools/call"
                                                 {:content []
                                                  :_meta {"x/y" 1}})]
        (is (= 1 (get-in result [:_meta "x/y"])))
        (is (some? (get-in result [:_meta protocol/meta-server-info])))))

    (testing "a full JSON-RPC response passes through untouched"
      (let [response {:jsonrpc "2.0"
                      :id 1
                      :error {:code -32601
                              :message "nope"}}]
        (is (= response (handler-2026/decorate-result session "tools/call" response))))))

  (testing "a session may override the freshness policy"
    (let [session (session-2026 {:cache-policy {"tools/list" {:ttl-ms 999
                                                              :cache-scope "private"}}})
          result (handler-2026/decorate-result session "tools/list" {:tools []})]
      (is (= 999 (:ttl-ms result)))
      (is (= "private" (:cache-scope result))))))

;; ---------------------------------------------------------------------------
;; Discovery
;; ---------------------------------------------------------------------------

(deftest discover-test
  (testing "discovery reports versions, capabilities and instructions"
    (async-test
     3000
     (-> (drive (session-2026 {:tools [echo-tool]
                               :server-instructions "be nice"})
                [{:jsonrpc "2.0"
                  :id 1
                  :method "server/discover"}])
         (p/then (fn [sent]
                   (let [result (-> sent first :result)]
                     (is (= ["2026-07-28"] (:supported-versions result))
                         "only what this session can actually serve")
                     (is (= "be nice" (:instructions result)))
                     (is (= "complete" (:result-type result)))
                     (is (= 3600000 (:ttl-ms result)))
                     (is (= {:name "test-server"
                             :version "1.0.0"}
                            (get-in result [:_meta protocol/meta-server-info])))))))))

  (testing "capabilities are derived from what the server actually holds"
    (async-test
     3000
     (-> (drive (session-2026 {:tools [echo-tool]})
                [{:jsonrpc "2.0"
                  :id 1
                  :method "server/discover"}])
         (p/then (fn [sent]
                   (let [capabilities (-> sent first :result :capabilities)]
                     (is (= {:list-changed true} (:tools capabilities)))
                     (is (not (contains? capabilities :prompts))
                         "a server with no prompts must not advertise prompts")
                     (is (not (contains? capabilities :resources))
                         "a server with no resources must not advertise resources")))))))

  (testing "an empty server advertises no feature capabilities at all"
    (async-test
     3000
     (-> (drive (session-2026 {}) [{:jsonrpc "2.0"
                                    :id 1
                                    :method "server/discover"}])
         (p/then (fn [sent]
                   (let [capabilities (-> sent first :result :capabilities)]
                     (is (not (contains? capabilities :tools)))
                     (is (not (contains? capabilities :prompts)))
                     (is (not (contains? capabilities :resources))))))))))

;; ---------------------------------------------------------------------------
;; Per-request _meta
;; ---------------------------------------------------------------------------

(deftest protocol-version-check-test
  (testing "a version the server does not speak is rejected"
    (async-test
     3000
     (-> (drive (session-2026 {:tools [echo-tool]})
                [{:jsonrpc "2.0"
                  :id 1
                  :method "tools/list"
                  :params {:_meta (assoc request-meta protocol/meta-protocol-version "2030-01-01")}}])
         (p/then (fn [sent]
                   (let [error (-> sent first :error)]
                     (is (= -32022 (:code error)))
                     (is (= "2030-01-01" (-> error :data :requested)))
                     (is (= ["2026-07-28"] (-> error :data :supported)))))))))

  (testing "a supported version is served normally"
    (async-test
     3000
     (-> (drive (session-2026 {:tools [echo-tool]})
                [{:jsonrpc "2.0"
                  :id 1
                  :method "tools/list"
                  :params {:_meta request-meta}}])
         (p/then (fn [sent]
                   (is (= 1 (count (-> sent first :result :tools))))
                   (is (nil? (-> sent first :error))))))))

  (testing "a request without a declared version is still served, which STDIO probing needs"
    (async-test
     3000
     (-> (drive (session-2026 {:tools [echo-tool]})
                [{:jsonrpc "2.0"
                  :id 1
                  :method "tools/list"
                  :params {}}])
         (p/then (fn [sent]
                   (is (nil? (-> sent first :error)))
                   (is (some? (-> sent first :result :tools)))))))))

(deftest removed-method-is-not-found-test
  (testing "calling a deleted method returns Method not found"
    (async-test
     3000
     (-> (drive (session-2026 {})
                [{:jsonrpc "2.0"
                  :id 1
                  :method "ping"
                  :params {:_meta request-meta}}
                 {:jsonrpc "2.0"
                  :id 2
                  :method "resources/subscribe"
                  :params {:uri "ipfs:///world/hello.md"
                           :_meta request-meta}}])
         (p/then (fn [sent]
                   (is (= 2 (count sent)))
                   (is (every? (fn [m] (= -32601 (-> m :error :code))) sent))))))))

(deftest resource-not-found-code-test
  (testing "2026-07-28 reports a missing resource as Invalid Params"
    (async-test
     3000
     (-> (drive (session-2026 {:resources [test-resource]})
                [{:jsonrpc "2.0"
                  :id 1
                  :method "resources/read"
                  :params {:uri "ipfs:///nope.md"
                           :_meta request-meta}}])
         (p/then (fn [sent]
                   (is (= -32602 (-> sent first :error :code)))
                   (is (= "ipfs:///nope.md" (-> sent first :error :data :uri))))))))

  (testing "a resource that exists is still read"
    (async-test
     3000
     (-> (drive (session-2026 {:resources [test-resource]})
                [{:jsonrpc "2.0"
                  :id 1
                  :method "resources/read"
                  :params {:uri "ipfs:///world/hello.md"
                           :_meta request-meta}}])
         (p/then (fn [sent]
                   (let [result (-> sent first :result)]
                     (is (= "Hello world" (-> result :contents first :text)))
                     (is (= "complete" (:result-type result)))
                     (is (= "private" (:cache-scope result))))))))))

(deftest deterministic-list-order-test
  (let [tools (mapv (fn [i] {:name (str "tool_" i)
                             :description "d"
                             :input-schema {}})
                    (range 12))
        expected (mapv :name (sort-by :name tools))
        list-tools (fn [declared]
                     (-> (drive (session-2026 {:tools declared})
                                [{:jsonrpc "2.0"
                                  :id 1
                                  :method "tools/list"
                                  :params {:_meta request-meta}}])
                         (p/then (fn [sent] (mapv :name (-> sent first :result :tools))))))]

    (testing "tools are listed in a stable order regardless of declaration order"
      ;; The revision asks for a deterministic order so clients can cache list
      ;; results. Twelve tools matter here: below nine the underlying map is an
      ;; array map and keeps insertion order by accident.
      (async-test
       3000
       (p/let [as-declared (list-tools tools)
               reversed (list-tools (reverse tools))
               shuffled (list-tools (shuffle tools))]
         (is (= expected as-declared))
         (is (= expected reversed))
         (is (= expected shuffled)))))

    (testing "resources are ordered by uri"
      (async-test
       3000
       (-> (drive (session-2026 {:resources [{:uri "ipfs:///c"
                                              :name "c"}
                                             {:uri "ipfs:///a"
                                              :name "a"}
                                             {:uri "ipfs:///b"
                                              :name "b"}]})
                  [{:jsonrpc "2.0"
                    :id 1
                    :method "resources/list"
                    :params {:_meta request-meta}}])
           (p/then (fn [sent]
                     (is (= ["ipfs:///a" "ipfs:///b" "ipfs:///c"]
                            (mapv :uri (-> sent first :result :resources)))))))))))

(deftest missing-client-capability-error-test
  (testing "a handler can refuse rather than ask for input the client cannot give"
    (async-test
     3000
     (let [needy-tool {:name "needy"
                       :description "Needs elicitation"
                       :input-schema {:type "object"}
                       :tool-fn (fn [context _arguments]
                                  (if (get-in (server/request-client-capabilities context)
                                              [:elicitation :form])
                                    {:content [{:type "text"
                                                :text "ok"}]}
                                    (server/missing-client-capability-error
                                     context {:elicitation {:form {}}})))}]
       (-> (drive (session-2026 {:tools [needy-tool]})
                  [{:jsonrpc "2.0"
                    :id 1
                    :method "tools/call"
                    :params {:name "needy"
                             :arguments {}
                             ;; declares no elicitation support
                             :_meta {protocol/meta-protocol-version "2026-07-28"
                                     protocol/meta-client-capabilities {}}}}])
           (p/then (fn [sent]
                     (let [error (-> sent first :error)]
                       (is (= -32021 (:code error)))
                       (is (= {:elicitation {:form {}}}
                              (-> error :data :required-capabilities)))))))))))

(deftest cross-era-handling-test
  (testing "a handshake client gets a diagnostic naming the versions on offer"
    ;; A handshake client has no way to move forward to a newer revision, so
    ;; this error may be the only thing it can show a user. A bare Method not
    ;; found would tell it nothing.
    (async-test
     3000
     (-> (drive (session-2026 {})
                [{:jsonrpc "2.0"
                  :id 1
                  :method "initialize"
                  :params {:protocol-version "2025-11-25"
                           :capabilities {}}}])
         (p/then (fn [sent]
                   (let [error (-> sent first :error)]
                     (is (= -32022 (:code error)))
                     (is (= ["2026-07-28"] (-> error :data :supported)))
                     (is (= "2025-11-25" (-> error :data :requested)))
                     (is (re-find #"initialize" (:message error))
                         "the message has to be readable by a person")))))))

  (testing "a stateless session refuses a request declaring a handshake version"
    ;; It has no table for that revision, so serving it with 2026 semantics
    ;; would be answering a question that was not asked.
    (async-test
     3000
     (-> (drive (session-2026 {:tools [echo-tool]})
                [{:jsonrpc "2.0"
                  :id 1
                  :method "tools/list"
                  :params {:_meta {protocol/meta-protocol-version "2025-11-25"
                                   protocol/meta-client-capabilities {}}}}])
         (p/then (fn [sent]
                   (let [error (-> sent first :error)]
                     (is (= -32022 (:code error)))
                     (is (= "2025-11-25" (-> error :data :requested)))))))))

  (testing "a handshake session still negotiates as it always did"
    (async-test
     3000
     (-> (drive (atom (server/create-session {:on-initialized nil}))
                [{:jsonrpc "2.0"
                  :id 1
                  :method "initialize"
                  :params {:protocol-version "2025-11-25"
                           :capabilities {}
                           :client-info {:name "c"
                                         :version "1"}}}])
         (p/then (fn [sent]
                   (is (nil? (-> sent first :error)))
                   (is (= "2025-11-25" (-> sent first :result :protocol-version)))))))))

;; ---------------------------------------------------------------------------
;; Multi round-trip requests, end to end
;; ---------------------------------------------------------------------------

(deftest multi-round-trip-test
  (testing "a tool asks for input, then completes on the retry"
    (async-test
     5000
     (let [session (session-2026 {:tools [greet-tool]})
           sent (atom [])
           context {:session session
                    :send-message (fn [message] (swap! sent conj message) nil)}
           call (fn [message] (json-rpc/handle-message context message))]
       (-> (call {:jsonrpc "2.0"
                  :id 1
                  :method "tools/call"
                  :params {:name "greet"
                           :arguments {}
                           :_meta request-meta}})
           (p/then
            (fn [_]
              (let [interim (-> @sent first :result)]
                ;; Turn one: the server stops and asks.
                (is (= "input_required" (:result-type interim))
                    "an interim result must not be wrapped as tool content")
                (is (= "asked-for-name" (:request-state interim)))
                (is (= ["mcp-toolkit/who"] (keys (:input-requests interim))))
                (is (nil? (:content interim))
                    "the interim result carries requests, not content")

                ;; Turn two: the client answers and re-issues the same call,
                ;; echoing back the keys and the state exactly as received.
                (call {:jsonrpc "2.0"
                       :id 2
                       :method "tools/call"
                       :params {:name "greet"
                                :arguments {}
                                :_meta request-meta
                                :request-state (:request-state interim)
                                :input-responses
                                (into {}
                                      (map (fn [[k _]]
                                             [k {:action "accept"
                                                 :content {:name "octocat"}}]))
                                      (:input-requests interim))}}))))
           (p/then
            (fn [_]
              (let [final (-> @sent second :result)]
                (is (= "complete" (:result-type final)))
                (is (= "hello octocat after asked-for-name"
                       (-> final :content first :text))))))))))

  (testing "an ordinary tool is unaffected by the multi round-trip path"
    (async-test
     3000
     (-> (drive (session-2026 {:tools [echo-tool]})
                [{:jsonrpc "2.0"
                  :id 1
                  :method "tools/call"
                  :params {:name "echo"
                           :arguments {:msg "hi"}
                           :_meta request-meta}}])
         (p/then (fn [sent]
                   (let [result (-> sent first :result)]
                     (is (= "complete" (:result-type result)))
                     (is (= "echo hi" (-> result :content first :text))))))))))

;; ---------------------------------------------------------------------------
;; The handshake revisions must not change
;; ---------------------------------------------------------------------------

(deftest handshake-revisions-unchanged-test
  (testing "initialize still negotiates and still advertises its fixed capabilities"
    (async-test
     3000
     (-> (drive (atom (server/create-session {:on-initialized nil}))
                [{:jsonrpc "2.0"
                  :id 1
                  :method "initialize"
                  :params {:protocol-version "2025-11-25"
                           :capabilities {}
                           :client-info {:name "c"
                                         :version "1"}}}])
         (p/then (fn [sent]
                   (let [result (-> sent first :result)]
                     (is (= "2025-11-25" (:protocol-version result)))
                     (is (nil? (:result-type result))
                         "resultType belongs to 2026-07-28 and must not leak backwards")
                     (is (nil? (:ttl-ms result)))
                     (is (= {:logging {}
                             :completions {}
                             :prompts {:list-changed true}
                             :resources {:subscribe true
                                         :list-changed true}
                             :tools {:list-changed true}}
                            (:capabilities result)))))))))

  (testing "a missing resource still uses the legacy code on handshake revisions"
    (async-test
     3000
     (let [session (atom (server/create-session {:on-initialized nil
                                                 :resources [test-resource]}))]
       (-> (drive session
                  [{:jsonrpc "2.0"
                    :id 1
                    :method "initialize"
                    :params {:protocol-version "2025-11-25"
                             :capabilities {}
                             :client-info {:name "c"
                                           :version "1"}}}
                   {:jsonrpc "2.0"
                    :method "notifications/initialized"}
                   {:jsonrpc "2.0"
                    :id 2
                    :method "resources/read"
                    :params {:uri "ipfs:///nope.md"}}])
           (p/then (fn [sent]
                     (is (= -32002 (-> sent last :error :code))
                         "only 2026-07-28 renumbered this to Invalid Params")))))))

  (testing "ping still answers on handshake revisions"
    (async-test
     3000
     (-> (drive (atom (server/create-session {:on-initialized nil}))
                [{:jsonrpc "2.0"
                  :id 1
                  :method "ping"}])
         (p/then (fn [sent]
                   (is (= {} (-> sent first :result)))))))))

(deftest notify-log-honours-the-per-request-optin-test
  ;; 2026-07-28 replaced logging/setLevel with a per-request _meta field, and
  ;; request-log-level's own docstring states that a server must not log for a
  ;; request that omitted it. notify-log read :logging-level off the session
  ;; instead, which create-session defaults to "debug", so every log passed on
  ;; a stateless session no matter what the request asked for.
  ;; These drive a real handler through the dispatch table rather than
  ;; hand-building a context. The whole mechanism rests on with-request-context
  ;; putting :log-level on the context, and hand-building it assumes exactly
  ;; the thing under test.
  (testing "a stateless request that opted out of logs gets none"
    (async-test
     5000
     (let [sent (atom [])
           logging-tool {:name "logger"
                         :description "Logs, if the request asked for logs"
                         :input-schema {:type "object"}
                         :tool-fn (fn [context _args]
                                    (server/notify-log context "error" "lg" "from the tool")
                                    {:content [{:type "text" :text "done"}]})}
           session (session-2026 {:tools [logging-tool] :on-initialized nil})
           context {:session session
                    :send-message (fn [m] (swap! sent conj m) nil)}]
       (p/let [_ (json-rpc/handle-message
                  context
                  {:jsonrpc "2.0"
                   :id 1
                   :method "tools/call"
                   ;; _meta with no logLevel: this request wants no logs.
                   :params {:name "logger"
                            :arguments {}
                            :_meta {protocol/meta-protocol-version "2026-07-28"}}})]
         (is (empty? (filter (fn [m] (= "notifications/message" (:method m))) @sent))
             "a request with no _meta logLevel must produce no log notifications")
         (is (some (fn [m] (= 1 (:id m))) @sent)
             "and the call itself still answers")))))

  (testing "a stateless request that opted in through _meta gets them"
    (async-test
     5000
     (let [sent (atom [])
           logging-tool {:name "logger"
                         :description "Logs, if the request asked for logs"
                         :input-schema {:type "object"}
                         :tool-fn (fn [context _args]
                                    (server/notify-log context "error" "lg" "from the tool")
                                    {:content [{:type "text" :text "done"}]})}
           session (session-2026 {:tools [logging-tool] :on-initialized nil})
           context {:session session
                    :send-message (fn [m] (swap! sent conj m) nil)}]
       (p/let [_ (json-rpc/handle-message
                  context
                  {:jsonrpc "2.0"
                   :id 2
                   :method "tools/call"
                   :params {:name "logger"
                            :arguments {}
                            :_meta {protocol/meta-protocol-version "2026-07-28"
                                    protocol/meta-log-level "warning"}}})]
         (is (seq (filter (fn [m] (= "notifications/message" (:method m))) @sent))
             "an error log clears a warning threshold and is delivered")))))

  (testing "a stateless request that opted in gets logs at or above its level"
    (let [sent (atom [])
          session (atom (server/create-session {:protocol-version "2026-07-28"
                                                :on-initialized nil}))
          context {:session session
                   :send-message (fn [m] (swap! sent conj m) nil)
                   :protocol-version "2026-07-28"
                   :client-capabilities {}
                   :client-info nil
                   :log-level "warning"}]
      (server/notify-log context "error" "lg" "above threshold")
      (server/notify-log context "debug" "lg" "below threshold")
      (is (= 1 (count @sent)) "only the message at or above the requested level")
      (is (= "error" (-> @sent first :params :level)))))

  (testing "a handshake session still uses its session-level setting"
    (let [sent (atom [])
          session (atom (server/create-session {:on-initialized nil}))
          ;; No :log-level key at all, which is what a handshake context looks like.
          context {:session session
                   :send-message (fn [m] (swap! sent conj m) nil)}]
      (server/notify-log context "error" "lg" "handshake path unchanged")
      (is (= 1 (count @sent))
          "the handshake era must keep working off :logging-level"))))

(deftest create-session-validates-protocol-version-test
  ;; stateless? is a set membership test, so anything outside it quietly built
  ;; a handshake session. One mistyped digit produced a completely different
  ;; server, with no server/discover in its table, and every stateless client
  ;; got -32601 back with nothing to explain why.
  (testing "a mistyped revision is refused rather than silently downgraded"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (server/create-session {:protocol-version "2026-07-29"}))))

  (testing "a handshake revision is refused, since it cannot be pinned here"
    ;; It is negotiated at initialize. Accepting it looked like a pin and was not.
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (server/create-session {:protocol-version "2025-11-25"}))))

  (testing "the stateless revision is accepted, and omitting it still works"
    (is (some? (server/create-session {:protocol-version "2026-07-28"})))
    (is (some? (server/create-session {})))
    (is (false? (:dual-era? (server/create-session {}))))))

(deftest mrtr-correlation-keys-keep-their-namespace-test
  ;; ->wire-key used `name`, which drops the namespace, so :step/one and
  ;; :other/one both rendered as the same wire key. input-required built a map
  ;; keyed by it, so one of the two requests was silently discarded and its
  ;; answer never arrived. That is the exact failure protocol.cljc warns about
  ;; and names mrtr for.
  (testing "two keys differing only by namespace stay distinct on the wire"
    (is (not= (mrtr/->wire-key :step/one)
              (mrtr/->wire-key :other/one))))

  (testing "a namespaced key round-trips back to itself"
    (doseq [k [:step/one :other/one :a.b.c/deep :plain]]
      (is (= k (mrtr/<-wire-key (mrtr/->wire-key k)))
          (str k " must survive the wire round trip"))))

  (testing "a bare key renders exactly as it always did, so the wire is unchanged"
    (is (= "mcp-toolkit/plain" (mrtr/->wire-key :plain))))

  (testing "the wire key stays opaque, or a transport would mangle it"
    (doseq [k [:step/one :plain]]
      (is (true? (protocol/opaque-wire-key? (mrtr/->wire-key k)))
          (str (mrtr/->wire-key k) " must be opaque"))))

  (testing "two input requests differing only by namespace both survive"
    (let [result (mrtr/input-required
                  {:input-requests {:step/one   (mrtr/sampling-request {:messages [] :max-tokens 1})
                                    :other/one  (mrtr/sampling-request {:messages [] :max-tokens 1})}})]
      (is (= 2 (count (:input-requests result)))
          "neither request may be dropped")
      (is (= #{"mcp-toolkit/step/one" "mcp-toolkit/other/one"}
             (set (keys (:input-requests result))))))))
