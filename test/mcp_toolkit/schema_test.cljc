(ns mcp-toolkit.schema-test
  (:require [clojure.test :refer [deftest testing is are]]
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
