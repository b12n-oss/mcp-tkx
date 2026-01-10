(ns mcp-toolkit.core-test
  (:require
   [clojure.test :refer [#?(:cljs async) deftest is testing]]
   [mcp-toolkit.client :as client]
   [mcp-toolkit.impl.meta-support :as meta-support]
   [mcp-toolkit.impl.server.handler :as server.handler]
   [mcp-toolkit.json-rpc :as json-rpc]
   [mcp-toolkit.server :as server]
   [mcp-toolkit.test.util :as util]
   [promesa.core :as p]
   [promesa.exec.csp :as sp]))

(def test-root
  {:uri "file:///home/user/projects/myproject"
   :name "My Project"})

(def test-prompt
  {:name "test_prompt"
   :description "Test prompt"
   :arguments [{:description "A test argument"
                :name "x"
                :required false}]})

(def test-resource
  {:uri "ipfs:///world/hello.md"
   :name "hello.md"
   :description "Hello world"
   :mime-type "text/markdown"})

(def test-tool
  {:name "parentify"
   :description "Parentify a text: wraps a text within parenthesis."
   :input-schema {:properties {:text {:description "the text to be parentified"
                                      :type "string"}}
                  :type "object"
                  :required ["text"]}})

(defn setup-and-connect-client-server
  "Returns a "
  [client-session server-session message-logs]
  (let [;; Message transport
        client-output (sp/chan)
        server-output (sp/chan)

        ;; Client
        client-context {:session (atom client-session)
                        :send-message (fn [message]
                                        (swap! message-logs conj [:-> message]) ; Used for tests
                                        (sp/put client-output message)) ; Transport client -> server
                        :close-connection (fn []
                                            (sp/close! client-output))}

        ;; Server
        server-context {:session (atom server-session)
                        :send-message (fn [message]
                                        (swap! message-logs conj [:<- message]) ; Used for tests
                                        (sp/put server-output message)) ; Transport server -> client
                        :close-connection (fn []
                                            (sp/close! server-output))}

        message-processing-loop (fn [context input-channel]
                                  (p/loop []
                                    (p/let [message (sp/take input-channel)]
                                      (when (some? message) ; A nil message means that the channel was closed.
                                        (p/do
                                          (json-rpc/handle-message context message)
                                          (p/recur))))))]
    ;; A promise for each site, to run a message processing loop.
    (message-processing-loop client-context server-output)
    (message-processing-loop server-context client-output)

    {:client-context client-context
     :server-context server-context}))

(defn promesa-async-test [timeout p]
  #?(:cljs (async done
                  (-> (p/timeout p timeout ::timeout)
                      (p/handle (fn [x error]
                                  (when (= x ::timeout)
                                    (is nil (str "Time out error: the test did not finish after waiting " timeout "ms.")))
                                  (or error x)))
                      (p/then (fn [_] (done)))))
     :clj (-> (p/timeout p timeout ::timeout)
              (p/handle (fn [x error]
                          (when (= x ::timeout)
                            (is nil (str "Time out error: the test did not finish after waiting " timeout "ms.")))
                          (or error x)))
              deref)))

(deftest handshake-test
  (is true "yes") ;; <-- this resolves a warning for a missing `(is ,,,)` in CLJ

  (promesa-async-test 3000
                      (testing "the MCP handshake"
                        (let [message-logs (atom [])
                              client-session (client/create-session {;; do not automatically request the prompts, resources and tools
                                                                     :on-initialized nil})
                              server-session (server/create-session {;; do not automatically request the roots
                                                                     :on-initialized nil})
                              {:keys [client-context server-context]} (setup-and-connect-client-server client-session
                                                                                                       server-session
                                                                                                       message-logs)]
                          (-> (p/do
                                ;; Initiate the client-server communication
                                (client/send-first-handshake-message client-context)

                                ;; Wait until we have enough messages for the test.
                                (util/assert-atom message-logs
                                                  (fn [logs] (= (count logs) 3))
                                                  3000
                                                  "MCP handshake")

                                ;; Test if the messages are what we expect.
                                ;; Note: Internal format uses kebab-case keys
                                (is (= [[:-> {:jsonrpc "2.0"
                                              :method "initialize"
                                              :params {:client-info {:name "mcp-toolkit"
                                                                     :version "0.1.1-alpha"}
                                                       :protocol-version "2025-11-25"
                                                       :capabilities {:roots {:list-changed true}}}
                                              :id 0}]
                                        [:<- {:jsonrpc "2.0"
                                              :result {:server-info {:name "mcp-toolkit"
                                                                     :version "0.1.1-alpha"}
                                                       :protocol-version "2025-11-25"
                                                       :capabilities {:logging {}
                                                                      :completions {}
                                                                      :prompts {:list-changed true}
                                                                      :resources {:list-changed true
                                                                                  :subscribe true}
                                                                      :tools {:list-changed true}}}
                                              :id 0}]
                                        [:-> {:jsonrpc "2.0"
                                              :method "notifications/initialized"}]]
                                       @message-logs)))
                              (p/handle (fn [x error]
                                          (json-rpc/close-connection client-context)
                                          (json-rpc/close-connection server-context)

                                          ;; Pass through
                                          (or error x))))))))

(deftest backward-compatibility-test
  (is true "yes") ;; <-- this resolves a warning for a missing `(is ,,,)` in CLJ

  (promesa-async-test 3000
                      (testing "server accepts older protocol versions"
                        (let [message-logs (atom [])
                              ;; Client requesting older protocol version
                              client-session (client/create-session {:protocol-version "2025-03-26"
                                                                     :on-initialized nil})
                              server-session (server/create-session {:on-initialized nil})
                              {:keys [client-context server-context]} (setup-and-connect-client-server client-session
                                                                                                       server-session
                                                                                                       message-logs)]
                          (-> (p/do
                                ;; Initiate the client-server communication
                                (client/send-first-handshake-message client-context)

                                ;; Wait until we have enough messages for the test.
                                (util/assert-atom message-logs
                                                  (fn [logs] (= (count logs) 3))
                                                  3000
                                                  "MCP handshake backward compatibility")

                                ;; Test if the server correctly negotiates down to the older version
                                (let [messages @message-logs
                                      init-request (-> messages first second)
                                      init-response (-> messages second second)]
                                  ;; Client requests 2025-03-26
                                  (is (= "2025-03-26" (get-in init-request [:params :protocol-version])))
                                  ;; Server should respond with 2025-03-26 (negotiated version)
                                  (is (= "2025-03-26" (get-in init-response [:result :protocol-version])))))
                              (p/handle (fn [x error]
                                          (json-rpc/close-connection client-context)
                                          (json-rpc/close-connection server-context)

                                          ;; Pass through
                                          (or error x))))))))

(deftest protocol-negotiation-test-2025-11-25
  (is true "yes") ;; <-- this resolves a warning for a missing `(is ,,,)` in CLJ

  (promesa-async-test 5000
                      (testing "protocol negotiation with 2025-11-25"
                        (let [message-logs (atom [])
                              ;; Client explicitly requests 2025-11-25
                              client-session (client/create-session {:protocol-version "2025-11-25"
                                                                     :on-initialized nil}) ;; disable auto-requests
                              server-session (server/create-session {:on-initialized nil}) ;; disable auto-requests
                              {:keys [client-context server-context]} (setup-and-connect-client-server client-session
                                                                                                       server-session
                                                                                                       message-logs)]
                          (-> (p/do
                                ;; Initiate the handshake
                                (client/send-first-handshake-message client-context)

                                ;; Wait for handshake to complete
                                (util/assert-atom (:session client-context)
                                                  (fn [state] (:initialized state))
                                                  4000
                                                  "Client initialization")

                                ;; Verify the protocol negotiation
                                (let [client-state @(:session client-context)
                                      server-state @(:session server-context)]

                                  ;; Both should agree on 2025-11-25
                                  (is (= "2025-11-25" (:server-protocol-version client-state)))
                                  (is (= "2025-11-25" (:protocol-version server-state)))

                                  ;; Server should have client info
                                  (is (some? (:client-info server-state)))
                                  (is (some? (:client-capabilities server-state)))

                                  ;; Client should have server info
                                  (is (some? (:server-info client-state)))
                                  (is (some? (:server-capabilities client-state)))))

                              (p/handle (fn [x error]
                                          (json-rpc/close-connection client-context)
                                          (json-rpc/close-connection server-context)
                                          ;; Pass through
                                          (or error x))))))))

(deftest json-rpc-batching-removal-test
  (is true "yes") ;; <-- this resolves a warning for a missing `(is ,,,)` in CLJ

  (promesa-async-test 3000
                      (testing "JSON-RPC batching is not supported in 2025-11-25"
                        (let [message-logs (atom [])
                              client-session (client/create-session {:protocol-version "2025-11-25"
                                                                     :on-initialized nil})
                              server-session (server/create-session {:on-initialized nil})
                              {:keys [client-context server-context]} (setup-and-connect-client-server client-session
                                                                                                       server-session
                                                                                                       message-logs)]
                          (-> (p/do
                                ;; Try to send a batch request (array of requests)
                                ;; This should be rejected in 2025-11-25
                                (let [batch-request [{:jsonrpc "2.0"
                                                      :method "ping"
                                                      :id 1}
                                                     {:jsonrpc "2.0"
                                                      :method "ping"
                                                      :id 2}]]
                                  ;; Send batch request directly to server
                                  (json-rpc/handle-message server-context batch-request))

                                ;; Give time for processing
                                (p/delay 100)

                                ;; Check that an error was returned for batch requests
                                (let [logs @message-logs
                                      responses (filter #(= :<- (first %)) logs)]
                                  ;; In 2025-11-25, batch requests should return an error
                                  (is (= 1 (count responses)) "Should return single error for batch request")
                                  (when (seq responses)
                                    (let [response (second (first responses))]
                                      (is (contains? response :error) "Response should be an error")
                                      (when (contains? response :error)
                                        (is (= -32600 (get-in response [:error :code]))
                                            "Should return Invalid Request error"))))))

                              (p/handle (fn [x error]
                                          (json-rpc/close-connection client-context)
                                          (json-rpc/close-connection server-context)
                                          ;; Pass through
                                          (or error x))))))))

(deftest title-field-support-test
  (is true "yes") ;; <-- this resolves a warning for a missing `(is ,,,)` in CLJ

  (testing "title field support in prompts, resources, and tools"
    ;; Create test data with title fields
    (let [test-prompt-with-title {:name "test_prompt"
                                  :title "Test Prompt Display Name"
                                  :description "A test prompt with title"
                                  :arguments []}
          test-resource-with-title {:uri "test://resource"
                                    :name "test_resource"
                                    :title "Test Resource Display Name"
                                    :description "A test resource with title"
                                    :mime-type "text/plain"}
          test-tool-with-title {:name "test_tool"
                                :title "Test Tool Display Name"
                                :description "A test tool with title"
                                :input-schema {:type "object"}}

          ;; Create a session with test data
          session (atom {:prompt-by-name {(:name test-prompt-with-title) test-prompt-with-title}
                         :resource-by-uri {(:uri test-resource-with-title) test-resource-with-title}
                         :tool-by-name {(:name test-tool-with-title) test-tool-with-title}})]

      ;; Test prompt list handler
      (let [result (server.handler/prompt-list-handler {:session session})
            prompt (first (:prompts result))]
        (is (= "test_prompt" (:name prompt)))
        (is (= "Test Prompt Display Name" (:title prompt)))
        (is (contains? prompt :title) "Prompt should have title field"))

      ;; Test resource list handler
      (let [result (server.handler/resource-list-handler {:session session})
            resource (first (:resources result))]
        (is (= "test_resource" (:name resource)))
        (is (= "Test Resource Display Name" (:title resource)))
        (is (contains? resource :title) "Resource should have title field"))

      ;; Test tool list handler
      (let [result (server.handler/tool-list-handler {:session session})
            tool (first (:tools result))]
        (is (= "test_tool" (:name tool)))
        (is (= "Test Tool Display Name" (:title tool)))
        (is (contains? tool :title) "Tool should have title field")))))

(deftest structured-tool-output-test
  (is true "yes") ;; <-- this resolves a warning for a missing `(is ,,,)` in CLJ

  (testing "structured tool output with output-schema (2025-11-25 spec)"
    (let [test-tool-with-schema {:name "calculator"
                                 :title "Calculator Tool"
                                 :description "A calculator that returns structured results"
                                 :input-schema {:type "object"
                                                :properties {:operation {:type "string"}
                                                             :a {:type "number"}
                                                             :b {:type "number"}}}
                                 :output-schema {:type "object"
                                                 :properties {:result {:type "number"}
                                                              :formula {:type "string"}}}}

          session (atom {:tool-by-name {(:name test-tool-with-schema) test-tool-with-schema}})]

      ;; Test that output-schema is included in tool list
      (let [result (server.handler/tool-list-handler {:session session})
            tool (first (:tools result))]
        (is (contains? tool :output-schema) "Tool should include output-schema")
        (is (= (:output-schema test-tool-with-schema) (:output-schema tool)))))))

(deftest tool-result-resources-test
  (is true "yes") ;; <-- this resolves a warning for a missing `(is ,,,)` in CLJ

  (promesa-async-test 3000
                      (testing "tool results can include resource links (2025-11-25 spec)"
                        (let [session (atom {:tool-by-name {"file_reader" {:name "file_reader"
                                                                           :title "File Reader"
                                                                           :tool-fn (fn [_ _]
                                                                                      ;; Return structured result with resources
                                                                                      (p/resolved
                                                                                       {:content [{:type "text"
                                                                                                   :text "File content here"}]
                                                                                        :resources [{:uri "file:///test.txt"
                                                                                                     :name "test.txt"
                                                                                                     :mime-type "text/plain"}]}))}}})
                              context {:session session}
                              message {:params {:name "file_reader"
                                                :arguments {}}}]

                          (-> (server.handler/tool-call-handler (assoc context :message message))
                              (p/then (fn [result]
                                        (is (contains? result :content) "Result should have content")
                                        (is (contains? result :resources) "Result should have resources")
                                        (is (= 1 (count (:resources result))))
                                        (is (= "file:///test.txt" (-> result :resources first :uri))))))))))

(deftest completion-context-test
  (is true "yes") ;; <-- this resolves a warning for a missing `(is ,,,)` in CLJ

  (testing "completion requests can include context (2025-11-25 spec)"
    (let [completion-called (atom false)
          completion-context-received (atom nil)

          session (atom {:prompt-by-name
                         {"test_prompt"
                          {:name "test_prompt"
                           :complete-fn (fn [ctx arg-name arg-value]
                                          (reset! completion-called true)
                                          (reset! completion-context-received (:completion-context ctx))
                                          {:completion {:values ["value1" "value2"]
                                                        :total 2
                                                        :has-more false}})}}})

          context {:session session}

          ;; Test with context provided
          message-with-context {:params {:ref {:type "ref/prompt"
                                               :name "test_prompt"}
                                         :argument {:name "arg1"
                                                    :value "val"}
                                         :context {:previous-values {:key "value"}}}}]

      ;; Call handler with context
      (server.handler/completion-complete-handler (assoc context :message message-with-context))

      (is @completion-called "Completion function should be called")
      (is (= {:previous-values {:key "value"}} @completion-context-received)
          "Context should be passed to completion function")

      ;; Reset for next test
      (reset! completion-called false)
      (reset! completion-context-received nil)

      ;; Test without context (backward compatibility)
      (let [message-without-context {:params {:ref {:type "ref/prompt"
                                                    :name "test_prompt"}
                                              :argument {:name "arg1"
                                                         :value "val"}}}]
        (server.handler/completion-complete-handler (assoc context :message message-without-context))

        (is @completion-called "Completion function should be called without context")
        (is (nil? @completion-context-received) "No context should be passed when not provided")))))

(deftest meta-field-support-test
  (is true "yes") ;; <-- this resolves a warning for a missing `(is ,,,)` in CLJ

  (testing "_meta field utilities"
    (let [data {:name "test"
                :value 42}
          meta-info {:timestamp 123456
                     :source "test"}]

      ;; Test with-meta-field
      (let [with-meta (meta-support/with-meta-field data meta-info)]
        (is (contains? with-meta :_meta) "Should have _meta field")
        (is (= meta-info (:_meta with-meta)) "Meta should match"))

      ;; Test extract-meta
      (let [with-meta (assoc data :_meta meta-info)]
        (is (= meta-info (meta-support/extract-meta with-meta))))

      ;; Test strip-meta
      (let [with-meta (assoc data :_meta meta-info)
            stripped (meta-support/strip-meta with-meta)]
        (is (not (contains? stripped :_meta)) "Should not have _meta after stripping")
        (is (= data stripped) "Should match original data"))

      ;; Test has-meta?
      (is (meta-support/has-meta? {:_meta {}}))
      (is (not (meta-support/has-meta? {:name "test"}))))))

(deftest server-description-test
  (is true "yes") ;; <-- this resolves a warning for a missing `(is ,,,)` in CLJ

  (promesa-async-test 3000
                      (testing "server-info description field (2025-11-25 spec)"
                        (let [message-logs (atom [])
                              client-session (client/create-session {:on-initialized nil})
                              server-session (server/create-session
                                              {:server-info {:name "test-server"
                                                             :version "1.0.0"
                                                             :description "A test server with description"}
                                               :on-initialized nil})
                              {:keys [client-context server-context]} (setup-and-connect-client-server client-session
                                                                                                       server-session
                                                                                                       message-logs)]
                          (-> (p/do
                                ;; Initiate the client-server communication
                                (client/send-first-handshake-message client-context)

                                ;; Wait until we have enough messages for the test.
                                (util/assert-atom message-logs
                                                  (fn [logs] (= (count logs) 3))
                                                  3000
                                                  "MCP handshake with description")

                                ;; Verify description is included in server-info
                                (let [messages @message-logs
                                      init-response (-> messages second second)
                                      server-info (get-in init-response [:result :server-info])]
                                  (is (= "test-server" (:name server-info)))
                                  (is (= "1.0.0" (:version server-info)))
                                  (is (= "A test server with description" (:description server-info))
                                      "Server description should be included in initialize response")))
                              (p/handle (fn [x error]
                                          (json-rpc/close-connection client-context)
                                          (json-rpc/close-connection server-context)
                                          ;; Pass through
                                          (or error x))))))))

(deftest icon-support-test
  (is true "yes") ;; <-- this resolves a warning for a missing `(is ,,,)` in CLJ

  (testing "icon field support in prompts, resources, tools, and templates (2025-11-25 spec)"
    ;; Create test data with icon fields
    (let [test-prompt-with-icon {:name "test_prompt"
                                 :title "Test Prompt"
                                 :description "A test prompt with icon"
                                 :arguments []
                                 :icon "https://example.com/prompt-icon.png"}
          test-resource-with-icon {:uri "test://resource"
                                   :name "test_resource"
                                   :title "Test Resource"
                                   :description "A test resource with icon"
                                   :mime-type "text/plain"
                                   :icon "data:image/svg+xml;base64,PHN2Zy..."}
          test-tool-with-icon {:name "test_tool"
                               :title "Test Tool"
                               :description "A test tool with icon"
                               :input-schema {:type "object"}
                               :icon "https://example.com/tool-icon.png"}
          test-template-with-icon {:uri-template "test://resource/{id}"
                                   :name "test_template"
                                   :title "Test Template"
                                   :description "A test template with icon"
                                   :mime-type "application/json"
                                   :icon "https://example.com/template-icon.png"}

          ;; Create a session with test data
          session (atom {:prompt-by-name {(:name test-prompt-with-icon) test-prompt-with-icon}
                         :resource-by-uri {(:uri test-resource-with-icon) test-resource-with-icon}
                         :tool-by-name {(:name test-tool-with-icon) test-tool-with-icon}
                         :resource-templates [test-template-with-icon]})]

      ;; Test prompt list handler includes icon
      (let [result (server.handler/prompt-list-handler {:session session})
            prompt (first (:prompts result))]
        (is (contains? prompt :icon) "Prompt should have icon field")
        (is (= "https://example.com/prompt-icon.png" (:icon prompt))))

      ;; Test resource list handler includes icon
      (let [result (server.handler/resource-list-handler {:session session})
            resource (first (:resources result))]
        (is (contains? resource :icon) "Resource should have icon field")
        (is (= "data:image/svg+xml;base64,PHN2Zy..." (:icon resource))))

      ;; Test tool list handler includes icon
      (let [result (server.handler/tool-list-handler {:session session})
            tool (first (:tools result))]
        (is (contains? tool :icon) "Tool should have icon field")
        (is (= "https://example.com/tool-icon.png" (:icon tool))))

      ;; Test resource templates list handler includes icon
      (let [result (server.handler/resource-templates-list-handler {:session session})
            template (first (:resource-templates result))]
        (is (contains? template :icon) "Resource template should have icon field")
        (is (= "https://example.com/template-icon.png" (:icon template)))))))

;; =============================================================================
;; Sampling with Tools Tests (2025-11-25)
;; =============================================================================

(deftest sampling-tools-capability-test
  (is true "yes")

  (testing "client-supports-sampling-tools? function"
    (testing "returns true when client declares sampling.tools capability"
      (let [session (atom {:client-capabilities {:sampling {:tools {}}}})
            context {:session session}]
        (is (true? (server/client-supports-sampling-tools? context)))))

    (testing "returns false when client only has basic sampling capability"
      (let [session (atom {:client-capabilities {:sampling {}}})
            context {:session session}]
        (is (false? (server/client-supports-sampling-tools? context)))))

    (testing "returns false when client has no sampling capability"
      (let [session (atom {:client-capabilities {}})
            context {:session session}]
        (is (false? (server/client-supports-sampling-tools? context)))))

    (testing "returns false when client-capabilities is nil"
      (let [session (atom {})
            context {:session session}]
        (is (false? (server/client-supports-sampling-tools? context)))))))

;; =============================================================================
;; Elicitation Capability Tests (2025-11-25)
;; =============================================================================

(deftest elicitation-capability-test
  (is true "yes")

  (testing "client-supports-elicitation? function"
    (testing "returns true when client declares elicitation capability"
      (let [session (atom {:client-capabilities {:elicitation {}}})
            context {:session session}]
        (is (true? (server/client-supports-elicitation? context)))))

    (testing "returns true when client declares form and url modes"
      (let [session (atom {:client-capabilities {:elicitation {:form {} :url {}}}})
            context {:session session}]
        (is (true? (server/client-supports-elicitation? context)))))

    (testing "returns false when client has no elicitation capability"
      (let [session (atom {:client-capabilities {}})
            context {:session session}]
        (is (false? (server/client-supports-elicitation? context))))))

  (testing "client-supports-url-elicitation? function"
    (testing "returns true when client declares url mode"
      (let [session (atom {:client-capabilities {:elicitation {:url {}}}})
            context {:session session}]
        (is (true? (server/client-supports-url-elicitation? context)))))

    (testing "returns false when client only has empty elicitation (form only)"
      (let [session (atom {:client-capabilities {:elicitation {}}})
            context {:session session}]
        (is (false? (server/client-supports-url-elicitation? context)))))

    (testing "returns false when client has no elicitation capability"
      (let [session (atom {:client-capabilities {}})
            context {:session session}]
        (is (false? (server/client-supports-url-elicitation? context))))))

  (testing "client-supports-form-elicitation? function"
    (testing "returns true when client declares empty elicitation (form only per spec)"
      (let [session (atom {:client-capabilities {:elicitation {}}})
            context {:session session}]
        (is (true? (server/client-supports-form-elicitation? context)))))

    (testing "returns true when client explicitly declares form mode"
      (let [session (atom {:client-capabilities {:elicitation {:form {}}}})
            context {:session session}]
        (is (true? (server/client-supports-form-elicitation? context)))))

    (testing "returns true when client declares both form and url modes"
      (let [session (atom {:client-capabilities {:elicitation {:form {} :url {}}}})
            context {:session session}]
        (is (true? (server/client-supports-form-elicitation? context)))))

    (testing "returns nil when client has no elicitation capability"
      (let [session (atom {:client-capabilities {}})
            context {:session session}]
        (is (nil? (server/client-supports-form-elicitation? context)))))))


