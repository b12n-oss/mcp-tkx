(ns mcp-toolkit.schema
  "Malli schemas for MCP protocol types.
   Provides validation and schema construction helpers.
   
   See: https://modelcontextprotocol.io/specification/2025-11-25"
  (:require [malli.core :as m]
            [malli.error :as me]
            [clojure.string :as str]))

;; =============================================================================
;; Icon Schema
;; =============================================================================

(def Icon
  "Schema for MCP icon field.
   Must be either a data:image/ URI or https:// URL."
  [:and :string
   [:fn {:error/message "Icon must be a data:image/ URI or https:// URL"}
    (fn [s]
      (or (str/starts-with? s "data:image/")
          (str/starts-with? s "https://")))]])

;; =============================================================================
;; Enum Schema (2025-11-25)
;; =============================================================================

(def EnumSchema
  "Schema for MCP EnumSchema (2025-11-25 spec).
   
   Supports:
   - :type        - Must be \"string\"
   - :enum        - Required vector of string values
   - :enum-titles - Optional display names (must match :enum length)
   - :multi-select - Allow multiple selections (default: false)
   - :default     - Default value(s)"
  [:and
   [:map
    [:type [:= "string"]]
    [:enum [:vector {:min 1} :string]]
    [:enum-titles {:optional true} [:vector :string]]
    [:multi-select {:optional true} :boolean]
    [:default {:optional true} [:or :string [:vector :string]]]]
   ;; Custom validation: enum-titles must match enum length
   [:fn {:error/message ":enum-titles length must match :enum length"}
    (fn [{:keys [enum enum-titles]}]
      (or (nil? enum-titles)
          (= (count enum) (count enum-titles))))]])

;; =============================================================================
;; Validation Functions
;; =============================================================================

(defn valid?
  "Returns true if value is valid according to schema."
  [schema value]
  (m/validate schema value))

(defn explain
  "Returns human-readable explanation of validation errors, or nil if valid."
  [schema value]
  (when-let [explanation (m/explain schema value)]
    (me/humanize explanation)))

(defn validate
  "Validates value against schema.
   Returns {:valid? true} or {:valid? false :errors [...]}."
  [schema value]
  (if (m/validate schema value)
    {:valid? true}
    {:valid? false
     :errors (-> (m/explain schema value) me/humanize)}))

;; =============================================================================
;; Schema Constructors
;; =============================================================================

(defn enum-schema
  "Creates an enum schema map (2025-11-25 spec).
   
   Options:
   - :values       - Vector of string values (required)
   - :titles       - Vector of display titles (optional, must match values length)
   - :multi-select - Allow multiple selections (default: false)
   - :default      - Default value(s)
   
   Example:
     (enum-schema {:values [\"low\" \"medium\" \"high\"]
                   :titles [\"Low\" \"Medium\" \"High\"]
                   :default \"medium\"})
     
     (enum-schema {:values [\"email\" \"sms\" \"push\"]
                   :multi-select true
                   :default [\"email\"]})"
  [{:keys [values titles multi-select default]}]
  (cond-> {:type "string"
           :enum values}
    titles (assoc :enum-titles titles)
    multi-select (assoc :multi-select true)
    default (assoc :default default)))

(defn enum-schema!
  "Like enum-schema, but validates the result and throws on invalid schema.
   
   Throws ex-info with :errors key if validation fails."
  [opts]
  (let [schema (enum-schema opts)
        result (validate EnumSchema schema)]
    (if (:valid? result)
      schema
      (throw (ex-info "Invalid enum schema" {:errors (:errors result)
                                             :schema schema})))))
