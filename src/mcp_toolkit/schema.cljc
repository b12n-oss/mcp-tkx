(ns mcp-toolkit.schema
  "Malli schemas for MCP protocol types.
   Provides validation and schema construction helpers.

   See: https://modelcontextprotocol.io/specification/2025-11-25"
  (:require
   [clojure.string :as str]
   [malli.core :as m]
   [malli.error :as me]))

;; =============================================================================
;; JSON Schema Constants
;; =============================================================================

(def JSON_SCHEMA_DIALECT
  "The JSON Schema dialect used by MCP (2020-12).
   Should be specified in $schema field for tool input/output schemas."
  "https://json-schema.org/draft/2020-12/schema")

(defn with-schema-dialect
  "Adds the JSON Schema 2020-12 dialect to a schema map.

   Example:
     (with-schema-dialect {:type \"object\" :properties {...}})
     ;; => {:$schema \"https://json-schema.org/draft/2020-12/schema\"
     ;;     :type \"object\" :properties {...}}"
  [schema]
  (assoc schema :$schema JSON_SCHEMA_DIALECT))

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

;; =============================================================================
;; Sampling Types (2025-11-25)
;; =============================================================================

;; -----------------------------------------------------------------------------
;; Tool Choice
;; -----------------------------------------------------------------------------

(def ToolChoiceMode
  "Valid modes for tool choice in sampling requests."
  [:enum "auto" "required" "none"])

(def ToolChoice
  "Schema for toolChoice in sampling requests.

   Modes:
   - \"auto\"     - Model decides whether to use tools (default)
   - \"required\" - Model MUST use at least one tool
   - \"none\"     - Model MUST NOT use any tools"
  [:map
   [:mode ToolChoiceMode]])

;; -----------------------------------------------------------------------------
;; Sampling Tool Definition
;; -----------------------------------------------------------------------------

(def SamplingTool
  "Schema for tool definitions in sampling requests.

   Fields:
   - :name         - Tool name (required)
   - :description  - Human-readable description (optional)
   - :input-schema - JSON Schema for tool input (required)"
  [:map
   [:name :string]
   [:description {:optional true} :string]
   [:input-schema :map]])

;; -----------------------------------------------------------------------------
;; Content Types
;; -----------------------------------------------------------------------------

(def TextContent
  "Text content in messages."
  [:map
   [:type [:= "text"]]
   [:text :string]])

(def ImageContent
  "Image content in messages."
  [:map
   [:type [:= "image"]]
   [:data :string]
   [:mime-type :string]])

(def AudioContent
  "Audio content in messages."
  [:map
   [:type [:= "audio"]]
   [:data :string]
   [:mime-type :string]])

(def ToolUseContent
  "Tool use request from the model.
   Returned when the model wants to call a tool.

   Fields:
   - :type  - Always \"tool_use\"
   - :id    - Unique identifier for this tool use
   - :name  - Name of the tool to call
   - :input - Arguments to pass to the tool"
  [:map
   [:type [:= "tool_use"]]
   [:id :string]
   [:name :string]
   [:input :map]])

(def ToolResultContent
  "Result of a tool execution.
   Sent back to the model after executing a tool.

   Fields:
   - :type        - Always \"tool_result\"
   - :tool-use-id - ID of the tool_use this is responding to
   - :content     - Result content (text, image, audio)
   - :is-error    - Whether this represents an error (optional)"
  [:map
   [:type [:= "tool_result"]]
   [:tool-use-id :string]
   [:content [:or
              [:map [:type :string]]
              [:vector [:map [:type :string]]]]]
   [:is-error {:optional true} :boolean]])

;; -----------------------------------------------------------------------------
;; Stop Reasons
;; -----------------------------------------------------------------------------

(def StopReason
  "Reasons why model generation stopped.

   Values:
   - \"endTurn\"      - Natural completion
   - \"stopSequence\" - Hit a stop sequence
   - \"maxTokens\"    - Reached token limit
   - \"toolUse\"      - Model wants to use tools"
  [:enum "endTurn" "stopSequence" "maxTokens" "toolUse"])

;; -----------------------------------------------------------------------------
;; Message Validation
;; -----------------------------------------------------------------------------

(def ToolResultMessage
  "Schema for a user message containing only tool results.
   Per MCP spec: Messages with tool results MUST contain ONLY tool results."
  [:map
   [:role [:= "user"]]
   [:content [:or
              ToolResultContent
              [:vector {:min 1} ToolResultContent]]]])

(defn valid-tool-result-message?
  "Validates that a message contains only tool results (no mixed content).
   Per MCP spec: Messages with tool_result cannot contain other content types."
  [message]
  (m/validate ToolResultMessage message))

;; =============================================================================
;; Sampling Constructors
;; =============================================================================

(defn tool-choice
  "Creates a tool choice configuration.

   Mode can be:
   - :auto     - Model decides whether to use tools (default)
   - :required - Model MUST use at least one tool
   - :none     - Model MUST NOT use any tools

   Example:
     (tool-choice :auto)
     (tool-choice :required)"
  [mode]
  {:mode (name mode)})

(defn sampling-tool
  "Creates a tool definition for sampling requests.

   Options:
   - :name         - Tool name (required)
   - :description  - Human-readable description (optional)
   - :input-schema - JSON Schema for input (required)

   Example:
     (sampling-tool {:name \"get_weather\"
                     :description \"Get current weather for a city\"
                     :input-schema {:type \"object\"
                                    :properties {:city {:type \"string\"}}
                                    :required [\"city\"]}})"
  [{:keys [name description input-schema]}]
  (cond-> {:name name
           :input-schema input-schema}
    description (assoc :description description)))

(defn tool-result
  "Creates a tool result content block.

   Options:
   - :tool-use-id - ID of the tool_use being responded to (required)
   - :content     - Result content, either a map or vector of content blocks (required)
   - :is-error    - Whether this is an error result (optional)

   Example:
     (tool-result {:tool-use-id \"call_abc123\"
                   :content {:type \"text\" :text \"Weather: 18°C\"}})

     (tool-result {:tool-use-id \"call_def456\"
                   :content {:type \"text\" :text \"Error: City not found\"}
                   :is-error true})"
  [{:keys [tool-use-id content is-error]}]
  (cond-> {:type "tool_result"
           :tool-use-id tool-use-id
           :content content}
    is-error (assoc :is-error true)))

(defn tool-result-message
  "Creates a user message containing tool results.

   Per MCP spec: Messages with tool results MUST contain ONLY tool results.

   Args:
   - results - A single tool result or vector of tool results

   Example:
     (tool-result-message
       [(tool-result {:tool-use-id \"call_abc\" :content {:type \"text\" :text \"Result 1\"}})
        (tool-result {:tool-use-id \"call_def\" :content {:type \"text\" :text \"Result 2\"}})])"
  [results]
  {:role "user"
   :content (if (vector? results) results [results])})

(defn tool-result-message!
  "Like tool-result-message, but validates the result.
   Throws ex-info if validation fails."
  [results]
  (let [message (tool-result-message results)
        result (validate ToolResultMessage message)]
    (if (:valid? result)
      message
      (throw (ex-info "Invalid tool result message" {:errors (:errors result)
                                                     :message message})))))

;; =============================================================================
;; Elicitation Types (2025-11-25)
;; =============================================================================

;; -----------------------------------------------------------------------------
;; Modes and Actions
;; -----------------------------------------------------------------------------

(def ElicitationMode
  "Valid modes for elicitation requests.

   Modes:
   - \"form\" - In-band structured data collection (default)
   - \"url\"  - Out-of-band URL navigation for sensitive data"
  [:enum "form" "url"])

(def ElicitationAction
  "Response actions for elicitation requests.

   Actions:
   - \"accept\"  - User approved and submitted (with data for form mode)
   - \"decline\" - User explicitly declined the request
   - \"cancel\"  - User dismissed without making a choice"
  [:enum "accept" "decline" "cancel"])

;; -----------------------------------------------------------------------------
;; Request Schemas
;; -----------------------------------------------------------------------------

(def UrlElicitationRequest
  "Schema for URL mode elicitation requests.

   URL mode directs users to external URLs for sensitive interactions
   that must NOT pass through the MCP client (OAuth, payments, API keys).

   Fields:
   - :mode           - Must be \"url\"
   - :elicitation-id - Unique identifier for this elicitation
   - :url            - The URL user should navigate to
   - :message        - Human-readable explanation"
  [:map
   [:mode [:= "url"]]
   [:elicitation-id :string]
   [:url [:and :string
          [:fn {:error/message "URL must be https:// or http://localhost for development"}
           (fn [s]
             (or (str/starts-with? s "https://")
                 (str/starts-with? s "http://localhost")))]]]
   [:message :string]])

(def FormElicitationRequest
  "Schema for form mode elicitation requests.

   Form mode collects structured data directly through the MCP client.
   MUST NOT be used for sensitive information like credentials.

   Fields:
   - :mode             - \"form\" (optional, defaults to form if omitted)
   - :message          - Human-readable explanation
   - :requested-schema - JSON Schema defining expected response structure"
  [:map
   [:mode {:optional true} [:= "form"]]
   [:message :string]
   [:requested-schema :map]])

;; -----------------------------------------------------------------------------
;; Response Schema
;; -----------------------------------------------------------------------------

(def ElicitationResponse
  "Schema for elicitation response from client.

   Fields:
   - :action  - User's response action (accept/decline/cancel)
   - :content - Submitted data (only for form mode accept)"
  [:map
   [:action ElicitationAction]
   [:content {:optional true} :map]])

;; -----------------------------------------------------------------------------
;; Notification Schema
;; -----------------------------------------------------------------------------

(def ElicitationCompleteNotification
  "Schema for elicitation completion notification.

   Servers MAY send this when URL mode elicitation completes out-of-band.

   Fields:
   - :elicitation-id - ID from the original elicitation request"
  [:map
   [:elicitation-id :string]])

;; -----------------------------------------------------------------------------
;; Error Schema
;; -----------------------------------------------------------------------------

(def UrlElicitationRequiredErrorData
  "Schema for URL_ELICITATION_REQUIRED error data (-32042).

   Returned when a request cannot proceed until elicitation completes.

   Fields:
   - :elicitations - Array of URL mode elicitations required"
  [:map
   [:elicitations [:vector {:min 1} UrlElicitationRequest]]])

;; =============================================================================
;; Elicitation Constructors
;; =============================================================================

(defn url-elicitation
  "Creates a URL mode elicitation request.

   URL mode directs users to external URLs for sensitive interactions.
   Use for OAuth flows, payment processing, API key collection, etc.

   Options:
   - :elicitation-id - Unique identifier (required)
   - :url            - Target URL, must be https:// (required)
   - :message        - Human-readable explanation (required)

   Example:
     (url-elicitation
       {:elicitation-id \"550e8400-e29b-41d4-a716-446655440000\"
        :url \"https://api.example.com/oauth/authorize\"
        :message \"Please authorize access to your account.\"})"
  [{:keys [elicitation-id url message]}]
  {:mode "url"
   :elicitation-id elicitation-id
   :url url
   :message message})

(defn url-elicitation!
  "Like url-elicitation, but validates and throws on invalid request."
  [opts]
  (let [request (url-elicitation opts)
        result (validate UrlElicitationRequest request)]
    (if (:valid? result)
      request
      (throw (ex-info "Invalid URL elicitation request" {:errors (:errors result)
                                                         :request request})))))

(defn form-elicitation
  "Creates a form mode elicitation request.

   Form mode collects structured data through the MCP client.
   MUST NOT be used for sensitive information like credentials.

   Options:
   - :message          - Human-readable explanation (required)
   - :requested-schema - JSON Schema for expected response (required)

   Example:
     (form-elicitation
       {:message \"Please provide your display name\"
        :requested-schema {:type \"object\"
                           :properties {:name {:type \"string\"}}
                           :required [\"name\"]}})"
  [{:keys [message requested-schema]}]
  {:mode "form"
   :message message
   :requested-schema requested-schema})

(defn form-elicitation!
  "Like form-elicitation, but validates and throws on invalid request."
  [opts]
  (let [request (form-elicitation opts)
        result (validate FormElicitationRequest request)]
    (if (:valid? result)
      request
      (throw (ex-info "Invalid form elicitation request" {:errors (:errors result)
                                                          :request request})))))

(defn elicitation-response
  "Creates an elicitation response.

   Options:
   - :action  - Response action (:accept, :decline, or :cancel) (required)
   - :content - Response data for form mode accept (optional)

   Example:
     (elicitation-response {:action :accept
                            :content {:name \"Alice\"}})"
  [{:keys [action content]}]
  (cond-> {:action (name action)}
    content (assoc :content content)))

(defn elicitation-complete-notification
  "Creates an elicitation completion notification.

   Servers send this when URL mode elicitation completes out-of-band.

   Args:
   - elicitation-id - ID from the original elicitation request

   Example:
     (elicitation-complete-notification \"550e8400-e29b-41d4-a716-446655440000\")"
  [elicitation-id]
  {:elicitation-id elicitation-id})

(defn url-elicitation-required-error-data
  "Creates error data for URL_ELICITATION_REQUIRED error (-32042).

   Used when a request cannot proceed until elicitation completes.

   Args:
   - elicitations - Vector of URL elicitation requests

   Example:
     (url-elicitation-required-error-data
       [(url-elicitation {:elicitation-id \"abc\"
                          :url \"https://example.com/auth\"
                          :message \"Authorization required\"})])"
  [elicitations]
  {:elicitations elicitations})

;; Error code constant
(def URL_ELICITATION_REQUIRED_ERROR_CODE
  "JSON-RPC error code for URL elicitation required (-32042)."
  -32042)

;; =============================================================================
;; Tasks Types (2025-11-25 - Experimental)
;; =============================================================================

;; -----------------------------------------------------------------------------
;; Task Status and Support Mode
;; -----------------------------------------------------------------------------

(def TaskStatus
  "Valid task execution states.

   States:
   - \"working\"        - Request is currently being processed
   - \"input_required\" - Receiver needs input from requestor
   - \"completed\"      - Request completed successfully
   - \"failed\"         - Request did not complete successfully
   - \"cancelled\"      - Request was cancelled before completion"
  [:enum "working" "input_required" "completed" "failed" "cancelled"])

(def TaskSupportMode
  "Tool execution task support modes.

   Modes:
   - \"required\"  - Tool MUST be invoked as a task
   - \"optional\"  - Tool MAY be invoked as a task or normal request
   - \"forbidden\" - Tool MUST NOT be invoked as a task (default)"
  [:enum "required" "optional" "forbidden"])

;; -----------------------------------------------------------------------------
;; Task Object Schema
;; -----------------------------------------------------------------------------

(def Task
  "Schema for a task object representing execution state.

   A task is a durable state machine for tracking long-running operations.

   Fields:
   - :task-id        - Unique identifier (receiver-generated)
   - :status         - Current execution state
   - :status-message - Human-readable state description (optional)
   - :created-at     - ISO 8601 timestamp of creation
   - :last-updated-at - ISO 8601 timestamp of last update
   - :ttl            - Milliseconds before task may be deleted (nil = unlimited)
   - :poll-interval  - Suggested milliseconds between status checks (optional)"
  [:map
   [:task-id :string]
   [:status TaskStatus]
   [:status-message {:optional true} :string]
   [:created-at :string] ; ISO 8601
   [:last-updated-at :string] ; ISO 8601
   [:ttl [:maybe :int]]
   [:poll-interval {:optional true} :int]])

;; -----------------------------------------------------------------------------
;; Task Request/Response Schemas
;; -----------------------------------------------------------------------------

(def TaskParams
  "Schema for task augmentation parameters in requests.

   Include this in request params to create a task-augmented request.

   Fields:
   - :ttl - Requested duration (ms) to retain task from creation (optional)"
  [:map
   [:ttl {:optional true} :int]])

(def CreateTaskResult
  "Schema for task creation response.

   Returned when a receiver accepts a task-augmented request.
   The actual operation result comes later via tasks/result.

   Fields:
   - :task - The created task object"
  [:map
   [:task Task]])

(def TasksGetRequest
  "Schema for tasks/get request params."
  [:map
   [:task-id :string]])

(def TasksResultRequest
  "Schema for tasks/result request params."
  [:map
   [:task-id :string]])

(def TasksCancelRequest
  "Schema for tasks/cancel request params."
  [:map
   [:task-id :string]])

(def TasksListRequest
  "Schema for tasks/list request params with optional pagination."
  [:map
   [:cursor {:optional true} :string]])

(def TasksListResult
  "Schema for tasks/list response.

   Fields:
   - :tasks       - Array of task objects
   - :next-cursor - Pagination cursor for next page (optional)"
  [:map
   [:tasks [:vector Task]]
   [:next-cursor {:optional true} :string]])

(def TaskStatusNotification
  "Schema for notifications/tasks/status notification params.

   Sent when a task's status changes. Includes full task state."
  Task)

(def RelatedTaskMeta
  "Schema for io.modelcontextprotocol/related-task metadata.

   MUST be included in _meta for all task-related messages.

   Fields:
   - :task-id - ID of the associated task"
  [:map
   [:task-id :string]])

;; =============================================================================
;; Tasks Constructors
;; =============================================================================

(defn task-params
  "Creates task augmentation parameters for a request.

   Options:
   - :ttl - Requested retention duration in milliseconds (optional)

   Example:
     ;; Request with 1-hour TTL
     {:name \"analyze_data\"
      :arguments {:file \"large.csv\"}
      :task (task-params {:ttl 3600000})}"
  ([]
   {})
  ([{:keys [ttl]}]
   (cond-> {}
     ttl (assoc :ttl ttl))))

(defn task
  "Creates a task object.

   Options:
   - :task-id         - Unique identifier (required)
   - :status          - Task status (required, default \"working\")
   - :status-message  - Human-readable message (optional)
   - :created-at      - ISO 8601 timestamp (required)
   - :last-updated-at - ISO 8601 timestamp (required)
   - :ttl             - Retention duration in ms, nil for unlimited (required)
   - :poll-interval   - Suggested polling interval in ms (optional)

   Example:
     (task {:task-id (str (random-uuid))
            :status \"working\"
            :created-at \"2025-11-25T10:30:00Z\"
            :last-updated-at \"2025-11-25T10:30:00Z\"
            :ttl 60000
            :poll-interval 5000})"
  [{:keys [task-id status status-message created-at last-updated-at ttl poll-interval]
    :or {status "working"}}]
  (cond-> {:task-id task-id
           :status status
           :created-at created-at
           :last-updated-at last-updated-at
           :ttl ttl}
    status-message (assoc :status-message status-message)
    poll-interval (assoc :poll-interval poll-interval)))

(defn create-task-result
  "Creates a task creation response.

   Args:
   - task-obj - The task object to wrap

   Example:
     (create-task-result
       (task {:task-id \"abc-123\"
              :status \"working\"
              :created-at \"2025-11-25T10:30:00Z\"
              :last-updated-at \"2025-11-25T10:30:00Z\"
              :ttl 60000}))"
  [task-obj]
  {:task task-obj})

(defn related-task-meta
  "Creates related task metadata for _meta field.

   MUST be included in all task-related messages.

   Args:
   - task-id - ID of the associated task

   Example:
     {:_meta {\"io.modelcontextprotocol/related-task\"
              (related-task-meta \"abc-123\")}}"
  [task-id]
  {:task-id task-id})

(defn tasks-list-result
  "Creates a tasks/list response.

   Options:
   - :tasks       - Vector of task objects (required)
   - :next-cursor - Pagination cursor for next page (optional)

   Example:
     (tasks-list-result {:tasks [task1 task2]
                         :next-cursor \"cursor-xyz\"})"
  [{:keys [tasks next-cursor]}]
  (cond-> {:tasks tasks}
    next-cursor (assoc :next-cursor next-cursor)))

(defn terminal-status?
  "Returns true if the status is a terminal state.

   Terminal states: completed, failed, cancelled"
  [status]
  (contains? #{"completed" "failed" "cancelled"} status))
