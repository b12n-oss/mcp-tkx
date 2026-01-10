(ns mcp-toolkit.schema-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [mcp-toolkit.schema :as schema]))

(deftest icon-schema-test
  (testing "Icon schema validation"
    (testing "valid icons"
      (is (schema/valid? schema/Icon "https://example.com/icon.png"))
      (is (schema/valid? schema/Icon "https://cdn.example.com/path/to/icon.svg"))
      (is (schema/valid? schema/Icon "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="))
      (is (schema/valid? schema/Icon "data:image/svg+xml;base64,PHN2Zy...")))

    (testing "invalid icons"
      (is (not (schema/valid? schema/Icon "http://example.com/icon.png"))
          "HTTP URLs should be rejected (only HTTPS allowed)")
      (is (not (schema/valid? schema/Icon "ftp://example.com/icon.png"))
          "FTP URLs should be rejected")
      (is (not (schema/valid? schema/Icon "/path/to/icon.png"))
          "Relative paths should be rejected")
      (is (not (schema/valid? schema/Icon "data:text/plain;base64,..."))
          "Non-image data URIs should be rejected"))

    (testing "error messages"
      (is (= ["Icon must be a data:image/ URI or https:// URL"]
             (schema/explain schema/Icon "http://example.com/icon.png"))))))

(deftest json-schema-dialect-test
  (testing "JSON_SCHEMA_DIALECT constant"
    (is (= "https://json-schema.org/draft/2020-12/schema" schema/JSON_SCHEMA_DIALECT)))

  (testing "with-schema-dialect helper"
    (testing "adds $schema to empty map"
      (is (= {:$schema "https://json-schema.org/draft/2020-12/schema"}
             (schema/with-schema-dialect {}))))

    (testing "adds $schema to existing schema"
      (is (= {:$schema "https://json-schema.org/draft/2020-12/schema"
              :type "object"
              :properties {:name {:type "string"}}}
             (schema/with-schema-dialect
               {:type "object"
                :properties {:name {:type "string"}}}))))

    (testing "overwrites existing $schema"
      (is (= {:$schema "https://json-schema.org/draft/2020-12/schema"
              :type "string"}
             (schema/with-schema-dialect
               {:$schema "http://json-schema.org/draft-07/schema#"
                :type "string"}))))))

(deftest enum-schema-test
  (testing "EnumSchema validation"
    (testing "valid schemas"
      ;; Simple enum
      (is (schema/valid? schema/EnumSchema
                         {:type "string"
                          :enum ["low" "medium" "high"]}))

      ;; Enum with titles
      (is (schema/valid? schema/EnumSchema
                         {:type "string"
                          :enum ["low" "medium" "high"]
                          :enum-titles ["Low Priority" "Medium Priority" "High Priority"]}))

      ;; Multi-select enum
      (is (schema/valid? schema/EnumSchema
                         {:type "string"
                          :enum ["email" "sms" "push"]
                          :multi-select true}))

      ;; Enum with default (string)
      (is (schema/valid? schema/EnumSchema
                         {:type "string"
                          :enum ["low" "medium" "high"]
                          :default "medium"}))

      ;; Multi-select with default (array)
      (is (schema/valid? schema/EnumSchema
                         {:type "string"
                          :enum ["email" "sms" "push"]
                          :multi-select true
                          :default ["email" "sms"]}))

      ;; Full example with all fields
      (is (schema/valid? schema/EnumSchema
                         {:type "string"
                          :enum ["a" "b" "c"]
                          :enum-titles ["Option A" "Option B" "Option C"]
                          :multi-select true
                          :default ["a"]})))

    (testing "invalid schemas"
      ;; Missing type
      (is (not (schema/valid? schema/EnumSchema
                              {:enum ["a" "b" "c"]})))

      ;; Wrong type
      (is (not (schema/valid? schema/EnumSchema
                              {:type "number"
                               :enum ["a" "b" "c"]})))

      ;; Empty enum
      (is (not (schema/valid? schema/EnumSchema
                              {:type "string"
                               :enum []})))

      ;; Mismatched enum-titles length
      (is (not (schema/valid? schema/EnumSchema
                              {:type "string"
                               :enum ["a" "b" "c"]
                               :enum-titles ["A" "B"]}))))

    (testing "error messages for mismatched titles"
      (is (= [":enum-titles length must match :enum length"]
             (schema/explain schema/EnumSchema
                             {:type "string"
                              :enum ["a" "b" "c"]
                              :enum-titles ["A" "B"]}))))))

(deftest enum-schema-constructor-test
  (testing "enum-schema constructor"
    (testing "simple enum"
      (is (= {:type "string"
              :enum ["low" "medium" "high"]}
             (schema/enum-schema {:values ["low" "medium" "high"]}))))

    (testing "enum with titles"
      (is (= {:type "string"
              :enum ["low" "medium" "high"]
              :enum-titles ["Low" "Medium" "High"]}
             (schema/enum-schema {:values ["low" "medium" "high"]
                                  :titles ["Low" "Medium" "High"]}))))

    (testing "multi-select enum"
      (is (= {:type "string"
              :enum ["a" "b" "c"]
              :multi-select true}
             (schema/enum-schema {:values ["a" "b" "c"]
                                  :multi-select true}))))

    (testing "enum with default"
      (is (= {:type "string"
              :enum ["low" "medium" "high"]
              :default "medium"}
             (schema/enum-schema {:values ["low" "medium" "high"]
                                  :default "medium"}))))

    (testing "full example"
      (is (= {:type "string"
              :enum ["email" "sms" "push"]
              :enum-titles ["Email" "SMS" "Push"]
              :multi-select true
              :default ["email"]}
             (schema/enum-schema {:values ["email" "sms" "push"]
                                  :titles ["Email" "SMS" "Push"]
                                  :multi-select true
                                  :default ["email"]}))))))

(deftest enum-schema!-test
  (testing "enum-schema! with validation"
    (testing "valid schema returns result"
      (is (= {:type "string"
              :enum ["a" "b" "c"]
              :enum-titles ["A" "B" "C"]}
             (schema/enum-schema! {:values ["a" "b" "c"]
                                   :titles ["A" "B" "C"]}))))

    (testing "invalid schema throws"
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                            #"Invalid enum schema"
                            (schema/enum-schema! {:values ["a" "b" "c"]
                                                  :titles ["A" "B"]}))))))

(deftest validate-fn-test
  (testing "validate function"
    (testing "returns {:valid? true} for valid data"
      (is (= {:valid? true}
             (schema/validate schema/Icon "https://example.com/icon.png"))))

    (testing "returns {:valid? false :errors [...]} for invalid data"
      (let [result (schema/validate schema/Icon "http://example.com/icon.png")]
        (is (false? (:valid? result)))
        (is (sequential? (:errors result)))
        (is (pos? (count (:errors result))))))))

;; =============================================================================
;; Sampling Types Tests (2025-11-25)
;; =============================================================================

(deftest tool-choice-test
  (testing "ToolChoice schema validation"
    (testing "valid modes"
      (is (schema/valid? schema/ToolChoice {:mode "auto"}))
      (is (schema/valid? schema/ToolChoice {:mode "required"}))
      (is (schema/valid? schema/ToolChoice {:mode "none"})))

    (testing "invalid modes"
      (is (not (schema/valid? schema/ToolChoice {:mode "invalid"})))
      (is (not (schema/valid? schema/ToolChoice {:mode "always"})))
      (is (not (schema/valid? schema/ToolChoice {})))))

  (testing "tool-choice constructor"
    (is (= {:mode "auto"} (schema/tool-choice :auto)))
    (is (= {:mode "required"} (schema/tool-choice :required)))
    (is (= {:mode "none"} (schema/tool-choice :none)))))

(deftest sampling-tool-test
  (testing "SamplingTool schema validation"
    (testing "valid tools"
      (is (schema/valid? schema/SamplingTool
                         {:name "get_weather"
                          :input-schema {:type "object"}}))

      (is (schema/valid? schema/SamplingTool
                         {:name "calculate"
                          :description "Perform calculations"
                          :input-schema {:type "object"
                                         :properties {:expression {:type "string"}}}})))

    (testing "invalid tools"
      ;; Missing name
      (is (not (schema/valid? schema/SamplingTool
                              {:input-schema {:type "object"}})))

      ;; Missing input-schema
      (is (not (schema/valid? schema/SamplingTool
                              {:name "test"})))))

  (testing "sampling-tool constructor"
    (testing "minimal tool"
      (is (= {:name "test"
              :input-schema {:type "object"}}
             (schema/sampling-tool {:name "test"
                                    :input-schema {:type "object"}}))))

    (testing "tool with description"
      (is (= {:name "get_weather"
              :description "Get current weather"
              :input-schema {:type "object"
                             :properties {:city {:type "string"}}}}
             (schema/sampling-tool {:name "get_weather"
                                    :description "Get current weather"
                                    :input-schema {:type "object"
                                                   :properties {:city {:type "string"}}}}))))))

(deftest tool-use-content-test
  (testing "ToolUseContent schema validation"
    (testing "valid tool use"
      (is (schema/valid? schema/ToolUseContent
                         {:type "tool_use"
                          :id "call_abc123"
                          :name "get_weather"
                          :input {:city "Paris"}}))

      (is (schema/valid? schema/ToolUseContent
                         {:type "tool_use"
                          :id "call_xyz"
                          :name "calculate"
                          :input {}})))

    (testing "invalid tool use"
      ;; Wrong type
      (is (not (schema/valid? schema/ToolUseContent
                              {:type "text"
                               :id "call_abc"
                               :name "test"
                               :input {}})))

      ;; Missing id
      (is (not (schema/valid? schema/ToolUseContent
                              {:type "tool_use"
                               :name "test"
                               :input {}})))

      ;; Missing name
      (is (not (schema/valid? schema/ToolUseContent
                              {:type "tool_use"
                               :id "call_abc"
                               :input {}}))))))

(deftest tool-result-content-test
  (testing "ToolResultContent schema validation"
    (testing "valid tool results"
      ;; Single content block
      (is (schema/valid? schema/ToolResultContent
                         {:type "tool_result"
                          :tool-use-id "call_abc123"
                          :content {:type "text"
                                    :text "Result"}}))

      ;; Multiple content blocks
      (is (schema/valid? schema/ToolResultContent
                         {:type "tool_result"
                          :tool-use-id "call_abc123"
                          :content [{:type "text"
                                     :text "Line 1"}
                                    {:type "text"
                                     :text "Line 2"}]}))

      ;; With error flag
      (is (schema/valid? schema/ToolResultContent
                         {:type "tool_result"
                          :tool-use-id "call_abc123"
                          :content {:type "text"
                                    :text "Error occurred"}
                          :is-error true})))

    (testing "invalid tool results"
      ;; Wrong type
      (is (not (schema/valid? schema/ToolResultContent
                              {:type "text"
                               :tool-use-id "call_abc"
                               :content {:type "text"
                                         :text "x"}})))

      ;; Missing tool-use-id
      (is (not (schema/valid? schema/ToolResultContent
                              {:type "tool_result"
                               :content {:type "text"
                                         :text "x"}}))))))

(deftest tool-result-constructor-test
  (testing "tool-result constructor"
    (testing "basic result"
      (is (= {:type "tool_result"
              :tool-use-id "call_abc"
              :content {:type "text"
                        :text "Weather: 18°C"}}
             (schema/tool-result {:tool-use-id "call_abc"
                                  :content {:type "text"
                                            :text "Weather: 18°C"}}))))

    (testing "error result"
      (is (= {:type "tool_result"
              :tool-use-id "call_def"
              :content {:type "text"
                        :text "City not found"}
              :is-error true}
             (schema/tool-result {:tool-use-id "call_def"
                                  :content {:type "text"
                                            :text "City not found"}
                                  :is-error true}))))))

(deftest tool-result-message-test
  (testing "tool-result-message constructor"
    (testing "single result"
      (let [result (schema/tool-result {:tool-use-id "call_abc"
                                        :content {:type "text"
                                                  :text "Result"}})]
        (is (= {:role "user"
                :content [result]}
               (schema/tool-result-message result)))))

    (testing "multiple results"
      (let [results [(schema/tool-result {:tool-use-id "call_abc"
                                          :content {:type "text"
                                                    :text "Result 1"}})
                     (schema/tool-result {:tool-use-id "call_def"
                                          :content {:type "text"
                                                    :text "Result 2"}})]]
        (is (= {:role "user"
                :content results}
               (schema/tool-result-message results))))))

  (testing "ToolResultMessage validation"
    (testing "valid messages"
      (is (schema/valid? schema/ToolResultMessage
                         {:role "user"
                          :content {:type "tool_result"
                                    :tool-use-id "call_abc"
                                    :content {:type "text"
                                              :text "x"}}}))

      (is (schema/valid? schema/ToolResultMessage
                         {:role "user"
                          :content [{:type "tool_result"
                                     :tool-use-id "call_abc"
                                     :content {:type "text"
                                               :text "x"}}
                                    {:type "tool_result"
                                     :tool-use-id "call_def"
                                     :content {:type "text"
                                               :text "y"}}]})))

    (testing "invalid messages - wrong role"
      (is (not (schema/valid? schema/ToolResultMessage
                              {:role "assistant"
                               :content {:type "tool_result"
                                         :tool-use-id "call_abc"
                                         :content {:type "text"
                                                   :text "x"}}}))))))

(deftest tool-result-message!-test
  (testing "tool-result-message! with validation"
    (testing "valid message returns result"
      (let [result (schema/tool-result {:tool-use-id "call_abc"
                                        :content {:type "text"
                                                  :text "OK"}})]
        (is (= {:role "user"
                :content [result]}
               (schema/tool-result-message! result)))))

    (testing "validates constructed message"
      (let [results [(schema/tool-result {:tool-use-id "call_abc"
                                          :content {:type "text"
                                                    :text "R1"}})
                     (schema/tool-result {:tool-use-id "call_def"
                                          :content {:type "text"
                                                    :text "R2"}})]]
        (is (= {:role "user"
                :content results}
               (schema/tool-result-message! results)))))))

(deftest stop-reason-test
  (testing "StopReason schema validation"
    (testing "valid stop reasons"
      (is (schema/valid? schema/StopReason "endTurn"))
      (is (schema/valid? schema/StopReason "stopSequence"))
      (is (schema/valid? schema/StopReason "maxTokens"))
      (is (schema/valid? schema/StopReason "toolUse")))

    (testing "invalid stop reasons"
      (is (not (schema/valid? schema/StopReason "done")))
      (is (not (schema/valid? schema/StopReason "end")))
      (is (not (schema/valid? schema/StopReason ""))))))

(deftest valid-tool-result-message?-test
  (testing "valid-tool-result-message? predicate"
    (testing "valid messages"
      (is (schema/valid-tool-result-message?
           {:role "user"
            :content {:type "tool_result"
                      :tool-use-id "call_abc"
                      :content {:type "text"
                                :text "OK"}}})))

    (testing "invalid messages"
      ;; Wrong role
      (is (not (schema/valid-tool-result-message?
                {:role "assistant"
                 :content {:type "tool_result"
                           :tool-use-id "call_abc"
                           :content {:type "text"
                                     :text "OK"}}})))

      ;; Missing content
      (is (not (schema/valid-tool-result-message?
                {:role "user"}))))))

;; =============================================================================
;; Elicitation Types Tests (2025-11-25)
;; =============================================================================

(deftest elicitation-mode-test
  (testing "ElicitationMode schema validation"
    (testing "valid modes"
      (is (schema/valid? schema/ElicitationMode "form"))
      (is (schema/valid? schema/ElicitationMode "url")))

    (testing "invalid modes"
      (is (not (schema/valid? schema/ElicitationMode "invalid")))
      (is (not (schema/valid? schema/ElicitationMode ""))))))

(deftest elicitation-action-test
  (testing "ElicitationAction schema validation"
    (testing "valid actions"
      (is (schema/valid? schema/ElicitationAction "accept"))
      (is (schema/valid? schema/ElicitationAction "decline"))
      (is (schema/valid? schema/ElicitationAction "cancel")))

    (testing "invalid actions"
      (is (not (schema/valid? schema/ElicitationAction "reject")))
      (is (not (schema/valid? schema/ElicitationAction "ok"))))))

(deftest url-elicitation-request-test
  (testing "UrlElicitationRequest schema validation"
    (testing "valid requests"
      (is (schema/valid? schema/UrlElicitationRequest
                         {:mode "url"
                          :elicitation-id "abc-123"
                          :url "https://example.com/auth"
                          :message "Please authorize"}))

      ;; localhost allowed for development
      (is (schema/valid? schema/UrlElicitationRequest
                         {:mode "url"
                          :elicitation-id "abc-123"
                          :url "http://localhost:3000/callback"
                          :message "Dev mode"})))

    (testing "invalid requests"
      ;; HTTP not allowed (except localhost)
      (is (not (schema/valid? schema/UrlElicitationRequest
                              {:mode "url"
                               :elicitation-id "abc-123"
                               :url "http://example.com/auth"
                               :message "Not secure"})))

      ;; Wrong mode
      (is (not (schema/valid? schema/UrlElicitationRequest
                              {:mode "form"
                               :elicitation-id "abc"
                               :url "https://example.com"
                               :message "Wrong mode"})))

      ;; Missing elicitation-id
      (is (not (schema/valid? schema/UrlElicitationRequest
                              {:mode "url"
                               :url "https://example.com"
                               :message "Missing ID"})))))

  (testing "url-elicitation constructor"
    (is (= {:mode "url"
            :elicitation-id "abc-123"
            :url "https://example.com/oauth"
            :message "Please authorize"}
           (schema/url-elicitation {:elicitation-id "abc-123"
                                    :url "https://example.com/oauth"
                                    :message "Please authorize"})))))

(deftest url-elicitation!-test
  (testing "url-elicitation! with validation"
    (testing "valid request returns result"
      (is (= {:mode "url"
              :elicitation-id "abc"
              :url "https://example.com/auth"
              :message "Test"}
             (schema/url-elicitation! {:elicitation-id "abc"
                                       :url "https://example.com/auth"
                                       :message "Test"}))))

    (testing "invalid request throws"
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                            #"Invalid URL elicitation"
                            (schema/url-elicitation! {:elicitation-id "abc"
                                                      :url "http://insecure.com"
                                                      :message "Bad"}))))))

(deftest form-elicitation-request-test
  (testing "FormElicitationRequest schema validation"
    (testing "valid requests"
      ;; With explicit mode
      (is (schema/valid? schema/FormElicitationRequest
                         {:mode "form"
                          :message "Enter your name"
                          :requested-schema {:type "object"
                                             :properties {:name {:type "string"}}}}))

      ;; Without mode (defaults to form)
      (is (schema/valid? schema/FormElicitationRequest
                         {:message "Enter your name"
                          :requested-schema {:type "object"}})))

    (testing "invalid requests"
      ;; Missing message
      (is (not (schema/valid? schema/FormElicitationRequest
                              {:requested-schema {:type "object"}})))

      ;; Missing requested-schema
      (is (not (schema/valid? schema/FormElicitationRequest
                              {:message "Test"})))))

  (testing "form-elicitation constructor"
    (is (= {:mode "form"
            :message "Enter name"
            :requested-schema {:type "object"}}
           (schema/form-elicitation {:message "Enter name"
                                     :requested-schema {:type "object"}})))))

(deftest form-elicitation!-test
  (testing "form-elicitation! with validation"
    (testing "valid request returns result"
      (is (= {:mode "form"
              :message "Test"
              :requested-schema {:type "object"}}
             (schema/form-elicitation! {:message "Test"
                                        :requested-schema {:type "object"}}))))

    (testing "invalid request throws"
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                            #"Invalid form elicitation"
                            (schema/form-elicitation! {:message "Missing schema"}))))))

(deftest elicitation-response-test
  (testing "ElicitationResponse schema validation"
    (testing "valid responses"
      (is (schema/valid? schema/ElicitationResponse
                         {:action "accept"
                          :content {:name "Alice"}}))

      (is (schema/valid? schema/ElicitationResponse
                         {:action "decline"}))

      (is (schema/valid? schema/ElicitationResponse
                         {:action "cancel"})))

    (testing "invalid responses"
      (is (not (schema/valid? schema/ElicitationResponse
                              {:action "invalid"})))))

  (testing "elicitation-response constructor"
    (testing "accept with content"
      (is (= {:action "accept"
              :content {:name "Alice"}}
             (schema/elicitation-response {:action :accept
                                           :content {:name "Alice"}}))))

    (testing "decline without content"
      (is (= {:action "decline"}
             (schema/elicitation-response {:action :decline}))))

    (testing "cancel without content"
      (is (= {:action "cancel"}
             (schema/elicitation-response {:action :cancel}))))))

(deftest elicitation-complete-notification-test
  (testing "ElicitationCompleteNotification schema validation"
    (testing "valid notification"
      (is (schema/valid? schema/ElicitationCompleteNotification
                         {:elicitation-id "abc-123"})))

    (testing "invalid notification"
      (is (not (schema/valid? schema/ElicitationCompleteNotification
                              {})))))

  (testing "elicitation-complete-notification constructor"
    (is (= {:elicitation-id "abc-123"}
           (schema/elicitation-complete-notification "abc-123")))))

(deftest url-elicitation-required-error-data-test
  (testing "UrlElicitationRequiredErrorData schema validation"
    (testing "valid error data"
      (is (schema/valid? schema/UrlElicitationRequiredErrorData
                         {:elicitations [{:mode "url"
                                          :elicitation-id "abc"
                                          :url "https://example.com/auth"
                                          :message "Auth required"}]})))

    (testing "invalid error data"
      ;; Empty elicitations
      (is (not (schema/valid? schema/UrlElicitationRequiredErrorData
                              {:elicitations []})))))

  (testing "url-elicitation-required-error-data constructor"
    (let [elicitation (schema/url-elicitation {:elicitation-id "abc"
                                               :url "https://example.com/auth"
                                               :message "Auth required"})]
      (is (= {:elicitations [elicitation]}
             (schema/url-elicitation-required-error-data [elicitation])))))

  (testing "error code constant"
    (is (= -32042 schema/URL_ELICITATION_REQUIRED_ERROR_CODE))))

;; =============================================================================
;; Tasks Types Tests (2025-11-25 - Experimental)
;; =============================================================================

(deftest task-status-test
  (testing "TaskStatus schema validation"
    (testing "valid statuses"
      (is (schema/valid? schema/TaskStatus "working"))
      (is (schema/valid? schema/TaskStatus "input_required"))
      (is (schema/valid? schema/TaskStatus "completed"))
      (is (schema/valid? schema/TaskStatus "failed"))
      (is (schema/valid? schema/TaskStatus "cancelled")))

    (testing "invalid statuses"
      (is (not (schema/valid? schema/TaskStatus "pending")))
      (is (not (schema/valid? schema/TaskStatus "running")))
      (is (not (schema/valid? schema/TaskStatus ""))))))

(deftest task-support-mode-test
  (testing "TaskSupportMode schema validation"
    (testing "valid modes"
      (is (schema/valid? schema/TaskSupportMode "required"))
      (is (schema/valid? schema/TaskSupportMode "optional"))
      (is (schema/valid? schema/TaskSupportMode "forbidden")))

    (testing "invalid modes"
      (is (not (schema/valid? schema/TaskSupportMode "enabled")))
      (is (not (schema/valid? schema/TaskSupportMode "disabled"))))))

(deftest task-schema-test
  (testing "Task schema validation"
    (testing "valid task with all fields"
      (is (schema/valid? schema/Task
                         {:task-id "abc-123"
                          :status "working"
                          :status-message "Processing data..."
                          :created-at "2025-11-25T10:30:00Z"
                          :last-updated-at "2025-11-25T10:35:00Z"
                          :ttl 60000
                          :poll-interval 5000})))

    (testing "valid task with minimal fields"
      (is (schema/valid? schema/Task
                         {:task-id "abc-123"
                          :status "completed"
                          :created-at "2025-11-25T10:30:00Z"
                          :last-updated-at "2025-11-25T10:35:00Z"
                          :ttl 60000})))

    (testing "valid task with nil ttl (unlimited)"
      (is (schema/valid? schema/Task
                         {:task-id "abc-123"
                          :status "working"
                          :created-at "2025-11-25T10:30:00Z"
                          :last-updated-at "2025-11-25T10:30:00Z"
                          :ttl nil})))

    (testing "invalid task - missing required fields"
      (is (not (schema/valid? schema/Task
                              {:task-id "abc-123"
                               :status "working"})))
      (is (not (schema/valid? schema/Task
                              {:status "working"
                               :created-at "2025-11-25T10:30:00Z"
                               :last-updated-at "2025-11-25T10:30:00Z"
                               :ttl 60000})))))

  (testing "task constructor"
    (testing "creates task with defaults"
      (let [t (schema/task {:task-id "abc-123"
                            :created-at "2025-11-25T10:30:00Z"
                            :last-updated-at "2025-11-25T10:30:00Z"
                            :ttl 60000})]
        (is (= "abc-123" (:task-id t)))
        (is (= "working" (:status t)))
        (is (= 60000 (:ttl t)))
        (is (nil? (:status-message t)))
        (is (nil? (:poll-interval t)))))

    (testing "creates task with all options"
      (let [t (schema/task {:task-id "abc-123"
                            :status "completed"
                            :status-message "Done!"
                            :created-at "2025-11-25T10:30:00Z"
                            :last-updated-at "2025-11-25T10:35:00Z"
                            :ttl 60000
                            :poll-interval 5000})]
        (is (= "completed" (:status t)))
        (is (= "Done!" (:status-message t)))
        (is (= 5000 (:poll-interval t)))))))

(deftest task-params-test
  (testing "TaskParams schema validation"
    (testing "valid params with ttl"
      (is (schema/valid? schema/TaskParams {:ttl 60000})))

    (testing "valid params empty (ttl optional)"
      (is (schema/valid? schema/TaskParams {}))))

  (testing "task-params constructor"
    (testing "empty params"
      (is (= {} (schema/task-params))))

    (testing "with ttl"
      (is (= {:ttl 60000} (schema/task-params {:ttl 60000}))))))

(deftest create-task-result-test
  (testing "CreateTaskResult schema validation"
    (let [task {:task-id "abc-123"
                :status "working"
                :created-at "2025-11-25T10:30:00Z"
                :last-updated-at "2025-11-25T10:30:00Z"
                :ttl 60000}]
      (is (schema/valid? schema/CreateTaskResult {:task task}))))

  (testing "create-task-result constructor"
    (let [t (schema/task {:task-id "abc-123"
                          :created-at "2025-11-25T10:30:00Z"
                          :last-updated-at "2025-11-25T10:30:00Z"
                          :ttl 60000})]
      (is (= {:task t} (schema/create-task-result t))))))

(deftest tasks-list-result-test
  (testing "TasksListResult schema validation"
    (testing "valid result with tasks"
      (let [task {:task-id "abc-123"
                  :status "working"
                  :created-at "2025-11-25T10:30:00Z"
                  :last-updated-at "2025-11-25T10:30:00Z"
                  :ttl 60000}]
        (is (schema/valid? schema/TasksListResult {:tasks [task]}))))

    (testing "valid result with cursor"
      (let [task {:task-id "abc-123"
                  :status "completed"
                  :created-at "2025-11-25T10:30:00Z"
                  :last-updated-at "2025-11-25T10:35:00Z"
                  :ttl 60000}]
        (is (schema/valid? schema/TasksListResult
                           {:tasks [task]
                            :next-cursor "cursor-xyz"}))))

    (testing "valid result with empty tasks"
      (is (schema/valid? schema/TasksListResult {:tasks []}))))

  (testing "tasks-list-result constructor"
    (let [t1 (schema/task {:task-id "abc"
                           :created-at "2025-11-25T10:30:00Z"
                           :last-updated-at "2025-11-25T10:30:00Z"
                           :ttl 60000})
          t2 (schema/task {:task-id "def"
                           :status "completed"
                           :created-at "2025-11-25T10:30:00Z"
                           :last-updated-at "2025-11-25T10:35:00Z"
                           :ttl 60000})]
      (testing "without cursor"
        (is (= {:tasks [t1 t2]}
               (schema/tasks-list-result {:tasks [t1 t2]}))))

      (testing "with cursor"
        (is (= {:tasks [t1]
                :next-cursor "xyz"}
               (schema/tasks-list-result {:tasks [t1]
                                          :next-cursor "xyz"})))))))

(deftest related-task-meta-test
  (testing "RelatedTaskMeta schema validation"
    (is (schema/valid? schema/RelatedTaskMeta {:task-id "abc-123"}))
    (is (not (schema/valid? schema/RelatedTaskMeta {}))))

  (testing "related-task-meta constructor"
    (is (= {:task-id "abc-123"}
           (schema/related-task-meta "abc-123")))))

(deftest terminal-status?-test
  (testing "terminal-status? predicate"
    (testing "terminal statuses"
      (is (true? (schema/terminal-status? "completed")))
      (is (true? (schema/terminal-status? "failed")))
      (is (true? (schema/terminal-status? "cancelled"))))

    (testing "non-terminal statuses"
      (is (false? (schema/terminal-status? "working")))
      (is (false? (schema/terminal-status? "input_required"))))))


