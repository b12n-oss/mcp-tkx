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
                          :content {:type "text" :text "Result"}}))

      ;; Multiple content blocks
      (is (schema/valid? schema/ToolResultContent
                         {:type "tool_result"
                          :tool-use-id "call_abc123"
                          :content [{:type "text" :text "Line 1"}
                                    {:type "text" :text "Line 2"}]}))

      ;; With error flag
      (is (schema/valid? schema/ToolResultContent
                         {:type "tool_result"
                          :tool-use-id "call_abc123"
                          :content {:type "text" :text "Error occurred"}
                          :is-error true})))

    (testing "invalid tool results"
      ;; Wrong type
      (is (not (schema/valid? schema/ToolResultContent
                              {:type "text"
                               :tool-use-id "call_abc"
                               :content {:type "text" :text "x"}})))

      ;; Missing tool-use-id
      (is (not (schema/valid? schema/ToolResultContent
                              {:type "tool_result"
                               :content {:type "text" :text "x"}}))))))

(deftest tool-result-constructor-test
  (testing "tool-result constructor"
    (testing "basic result"
      (is (= {:type "tool_result"
              :tool-use-id "call_abc"
              :content {:type "text" :text "Weather: 18°C"}}
             (schema/tool-result {:tool-use-id "call_abc"
                                  :content {:type "text" :text "Weather: 18°C"}}))))

    (testing "error result"
      (is (= {:type "tool_result"
              :tool-use-id "call_def"
              :content {:type "text" :text "City not found"}
              :is-error true}
             (schema/tool-result {:tool-use-id "call_def"
                                  :content {:type "text" :text "City not found"}
                                  :is-error true}))))))

(deftest tool-result-message-test
  (testing "tool-result-message constructor"
    (testing "single result"
      (let [result (schema/tool-result {:tool-use-id "call_abc"
                                        :content {:type "text" :text "Result"}})]
        (is (= {:role "user"
                :content [result]}
               (schema/tool-result-message result)))))

    (testing "multiple results"
      (let [results [(schema/tool-result {:tool-use-id "call_abc"
                                          :content {:type "text" :text "Result 1"}})
                     (schema/tool-result {:tool-use-id "call_def"
                                          :content {:type "text" :text "Result 2"}})]]
        (is (= {:role "user"
                :content results}
               (schema/tool-result-message results))))))

  (testing "ToolResultMessage validation"
    (testing "valid messages"
      (is (schema/valid? schema/ToolResultMessage
                         {:role "user"
                          :content {:type "tool_result"
                                    :tool-use-id "call_abc"
                                    :content {:type "text" :text "x"}}}))

      (is (schema/valid? schema/ToolResultMessage
                         {:role "user"
                          :content [{:type "tool_result"
                                     :tool-use-id "call_abc"
                                     :content {:type "text" :text "x"}}
                                    {:type "tool_result"
                                     :tool-use-id "call_def"
                                     :content {:type "text" :text "y"}}]})))

    (testing "invalid messages - wrong role"
      (is (not (schema/valid? schema/ToolResultMessage
                              {:role "assistant"
                               :content {:type "tool_result"
                                         :tool-use-id "call_abc"
                                         :content {:type "text" :text "x"}}}))))))

(deftest tool-result-message!-test
  (testing "tool-result-message! with validation"
    (testing "valid message returns result"
      (let [result (schema/tool-result {:tool-use-id "call_abc"
                                        :content {:type "text" :text "OK"}})]
        (is (= {:role "user" :content [result]}
               (schema/tool-result-message! result)))))

    (testing "validates constructed message"
      (let [results [(schema/tool-result {:tool-use-id "call_abc"
                                          :content {:type "text" :text "R1"}})
                     (schema/tool-result {:tool-use-id "call_def"
                                          :content {:type "text" :text "R2"}})]]
        (is (= {:role "user" :content results}
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
                      :content {:type "text" :text "OK"}}})))

    (testing "invalid messages"
      ;; Wrong role
      (is (not (schema/valid-tool-result-message?
                {:role "assistant"
                 :content {:type "tool_result"
                           :tool-use-id "call_abc"
                           :content {:type "text" :text "OK"}}})))

      ;; Missing content
      (is (not (schema/valid-tool-result-message?
                {:role "user"}))))))

