(ns mcp-toolkit.server
  (:require
   [mate.core :as mc]
   [mcp-toolkit.impl.common :refer [user-callback]]
   [mcp-toolkit.impl.server.handler :as server.handler]
   [mcp-toolkit.json-rpc :as json-rpc]
   [promesa.core :as p]))

;; Functions typically called from a prompt-fn or a tool-fn

(defn notify-progress
  "Notifies the client about progress during tool or prompt execution.
   Only sends if the current message contains a progress token.
   (see https://modelcontextprotocol.io/specification/2025-11-25/basic/utilities/progress#progress-flow)

   Args:
     context  - The server session context
     progress - Map with progress information (e.g., {:progress 50 :total 100})

   Returns:
     nil"
  [context progress]
  (let [{:keys [message]} context]
    (when-some [progress-token (-> message :params :_meta :progress-token)]
      (json-rpc/send-message context (json-rpc/notification "progress"
                                                            (-> {:progress-token progress-token}
                                                                (into progress))))))
  nil)

(def ^:private log-level->importance
  {"debug" 0
   "info" 1
   "notice" 2
   "warning" 3
   "error" 4
   "critical" 5
   "alert" 6
   "emergency" 7})

(defn notify-log
  "Sends a log message to the client if it meets the current logging level threshold.
   (see https://modelcontextprotocol.io/specification/2025-11-25/server/utilities/logging#log-message-notifications)

   Args:
     context - The server session context
     level   - Logging level, accepted values are \"debug\", \"info\", \"notice\", \"warning\", \"error\", \"critical\", \"alert\" and \"emergency\"
     logger  - Logger name/identifier string
     data    - Log message data (typically a string)

   Returns:
     nil"
  [context level logger data]
  (let [{:keys [session]} context
        logging-level (:logging-level @session)]
    (when (>= (log-level->importance level -1) (log-level->importance logging-level))
      (json-rpc/send-message context (json-rpc/notification "message"
                                                            {:level level
                                                             :logger logger
                                                             :data data}))))
  nil)

(defn request-root-list
  "Requests the list of root directories from the MCP client.
   Updates the session's client-root-by-uri index and calls the
   on-client-root-list-updated callback.
   (see https://modelcontextprotocol.io/specification/2025-11-25/client/roots#listing-roots)

   Args:
     context - The server session context

   Returns:
     A promise that resolves when roots are fetched and stored."
  [context]
  (let [{:keys [session]} context
        {:keys [client-capabilities]} @session]
    (when (contains? client-capabilities :roots)
      (-> (json-rpc/call-remote-method context {:method "roots/list"})
          (p/then (fn [result]
                    (swap! session assoc :client-root-by-uri (mc/index-by :uri (:roots result)))
                    ((user-callback :on-client-root-list-updated) context)
                    nil))))))

;; FIXME: implementation is not complete
(defn request-sampling
  "Requests message sampling from the MCP client.
   Returns a promise, either resolved with the result or rejected with the error.
   (see https://modelcontextprotocol.io/specification/2025-11-25/client/sampling)

   Args:
     context - The server session context
     params  - Sampling parameters map:
               :messages         - Vector of message maps with :role and :content (required)
               :system-prompt    - System prompt string (optional)
               :max-tokens       - Maximum tokens to generate (optional)
               :model-preferences - Model selection hints (optional)
               :include-context  - Deprecated: use explicit context instead (optional)
               
               Tool use (2025-11-25, requires client sampling.tools capability):
               :tools       - Vector of tool definitions (see mcp-toolkit.schema/sampling-tool)
               :tool-choice - Tool choice mode map (see mcp-toolkit.schema/tool-choice)
                              {:mode \"auto\"}     - Model decides (default)
                              {:mode \"required\"} - Model MUST use a tool
                              {:mode \"none\"}     - Model MUST NOT use tools

   Returns:
     A promise that resolves to the sampling result:
     - :role        - \"assistant\"
     - :content     - Response content (text, image, audio, or tool_use)
     - :model       - Model that was used
     - :stop-reason - Why generation stopped (\"endTurn\", \"toolUse\", etc.)

   Tool Use Flow:
     When :stop-reason is \"toolUse\", the :content will contain tool_use blocks.
     To continue, execute the tools and send a new request with the tool results
     appended to :messages. Use mcp-toolkit.schema/tool-result-message to create
     properly formatted tool result messages.

   Example - Basic sampling:
     (request-sampling context
       {:messages [{:role \"user\"
                    :content {:type \"text\" :text \"What is 2+2?\"}}]
        :max-tokens 100})

   Example - Sampling with tools:
     (require '[mcp-toolkit.schema :as schema])
     (request-sampling context
       {:messages [{:role \"user\"
                    :content {:type \"text\" :text \"What's the weather?\"}}]
        :tools [(schema/sampling-tool
                  {:name \"get_weather\"
                   :description \"Get weather for a city\"
                   :input-schema {:type \"object\"
                                  :properties {:city {:type \"string\"}}
                                  :required [\"city\"]}})]
        :tool-choice (schema/tool-choice :auto)
        :max-tokens 1000})

   Notes:
     - Requires client to declare :sampling capability
     - Tool use requires client to declare :sampling {:tools {}} capability
     - Returns nil if client doesn't support sampling"
  [context params]
  (let [{:keys [session]} context
        {:keys [client-capabilities]} @session]
    (when (contains? client-capabilities :sampling)
      (json-rpc/call-remote-method context {:method "sampling/createMessage"
                                            :params params}))))

(defn client-supports-sampling-tools?
  "Returns true if the client supports tool use in sampling requests.
   
   Clients must declare {:sampling {:tools {}}} capability to receive
   tool-enabled sampling requests."
  [context]
  (let [{:keys [session]} context
        {:keys [client-capabilities]} @session]
    (boolean (get-in client-capabilities [:sampling :tools]))))

;; =============================================================================
;; Elicitation (2025-11-25)
;; =============================================================================

(defn client-supports-elicitation?
  "Returns true if the client supports elicitation requests.
   
   Clients must declare {:elicitation {}} or {:elicitation {:form {}}}
   capability to receive elicitation requests."
  [context]
  (let [{:keys [session]} context
        {:keys [client-capabilities]} @session]
    (contains? client-capabilities :elicitation)))

(defn client-supports-url-elicitation?
  "Returns true if the client supports URL mode elicitation.
   
   Clients must declare {:elicitation {:url {}}} capability to receive
   URL mode elicitation requests."
  [context]
  (let [{:keys [session]} context
        {:keys [client-capabilities]} @session]
    (boolean (get-in client-capabilities [:elicitation :url]))))

(defn client-supports-form-elicitation?
  "Returns true if the client supports form mode elicitation.
   
   Form mode is supported if client declares {:elicitation {}} (empty = form only)
   or {:elicitation {:form {}}}."
  [context]
  (let [{:keys [session]} context
        {:keys [client-capabilities]} @session]
    (when-let [elicitation (:elicitation client-capabilities)]
      ;; Empty elicitation = form only, or explicit :form key
      (or (empty? elicitation)
          (contains? elicitation :form)))))

(defn request-elicitation
  "Requests information from the user via elicitation.
   Returns a promise resolved with the result or rejected with error.
   (see https://modelcontextprotocol.io/specification/2025-11-25/client/elicitation)

   Args:
     context - The server session context
     params  - Elicitation request map:
               
               Common fields:
               :mode    - \"form\" or \"url\" (optional, defaults to \"form\")
               :message - Human-readable explanation (required)
               
               Form mode specific:
               :requested-schema - JSON Schema for expected response (required for form)
               
               URL mode specific (2025-11-25):
               :elicitation-id - Unique identifier for tracking (required for url)
               :url            - Target URL, must be https:// (required for url)

   Returns:
     A promise that resolves to the elicitation response:
     - :action  - \"accept\", \"decline\", or \"cancel\"
     - :content - User-submitted data (form mode accept only)

   URL Mode Flow:
     When mode is \"url\", the response indicates user consent to navigate.
     The actual interaction happens out-of-band. Use notify-elicitation-complete
     to signal when the out-of-band flow finishes.

   Example - Form mode:
     (require '[mcp-toolkit.schema :as schema])
     (request-elicitation context
       (schema/form-elicitation
         {:message \"Please enter your name\"
          :requested-schema {:type \"object\"
                             :properties {:name {:type \"string\"}}
                             :required [\"name\"]}}))

   Example - URL mode:
     (require '[mcp-toolkit.schema :as schema])
     (request-elicitation context
       (schema/url-elicitation
         {:elicitation-id (str (random-uuid))
          :url \"https://api.example.com/oauth/authorize\"
          :message \"Please authorize access to your account.\"}))

   Notes:
     - Form mode: MUST NOT request sensitive information (passwords, API keys)
     - URL mode: Use for OAuth flows, payments, credential collection
     - Returns nil if client doesn't support elicitation"
  [context params]
  (let [{:keys [session]} context
        {:keys [client-capabilities]} @session
        mode (get params :mode "form")]
    (when (contains? client-capabilities :elicitation)
      ;; Check mode-specific capability
      (when (or (and (= mode "form")
                     (or (empty? (:elicitation client-capabilities))
                         (contains? (:elicitation client-capabilities) :form)))
                (and (= mode "url")
                     (contains? (:elicitation client-capabilities) :url)))
        (json-rpc/call-remote-method context {:method "elicitation/create"
                                              :params params})))))

(defn notify-elicitation-complete
  "Notifies the client that a URL mode elicitation has completed.
   
   Servers MAY send this when an out-of-band URL interaction finishes.
   This allows clients to automatically retry requests or update UI.
   
   Args:
     context        - The server session context
     elicitation-id - ID from the original elicitation request
   
   Notes:
     - Only send to the client that initiated the elicitation
     - Clients MAY use this to retry requests that got URLElicitationRequiredError"
  [context elicitation-id]
  (json-rpc/send-message context (json-rpc/notification "elicitation/complete"
                                                        {:elicitation-id elicitation-id}))
  nil)

;; =============================================================================
;; Tasks (2025-11-25 - Experimental)
;; =============================================================================

(defn client-supports-tasks?
  "Returns true if the client supports any task operations.
   
   Clients must declare {:tasks {...}} capability."
  [context]
  (let [{:keys [session]} context
        {:keys [client-capabilities]} @session]
    (contains? client-capabilities :tasks)))

(defn client-supports-task-augmented-sampling?
  "Returns true if the client supports task-augmented sampling/createMessage.
   
   Clients must declare {:tasks {:requests {:sampling {:create-message {}}}}}."
  [context]
  (let [{:keys [session]} context
        {:keys [client-capabilities]} @session]
    (boolean (get-in client-capabilities [:tasks :requests :sampling :create-message]))))

(defn client-supports-task-augmented-elicitation?
  "Returns true if the client supports task-augmented elicitation/create.
   
   Clients must declare {:tasks {:requests {:elicitation {:create {}}}}}."
  [context]
  (let [{:keys [session]} context
        {:keys [client-capabilities]} @session]
    (boolean (get-in client-capabilities [:tasks :requests :elicitation :create]))))

(defn client-supports-tasks-list?
  "Returns true if the client supports the tasks/list operation.
   
   Clients must declare {:tasks {:list {}}}."
  [context]
  (let [{:keys [session]} context
        {:keys [client-capabilities]} @session]
    (boolean (get-in client-capabilities [:tasks :list]))))

(defn client-supports-tasks-cancel?
  "Returns true if the client supports the tasks/cancel operation.
   
   Clients must declare {:tasks {:cancel {}}}."
  [context]
  (let [{:keys [session]} context
        {:keys [client-capabilities]} @session]
    (boolean (get-in client-capabilities [:tasks :cancel]))))

(defn request-task-get
  "Gets the current status of a task.
   Returns a promise resolved with the Task object.
   (see https://modelcontextprotocol.io/specification/2025-11-25/basic/utilities/tasks)

   Args:
     context - The server session context
     task-id - The unique identifier of the task

   Returns:
     A promise that resolves to the Task object with current status.

   Example:
     (p/let [task (request-task-get context \"abc-123\")]
       (println \"Status:\" (:status task)))"
  [context task-id]
  (json-rpc/call-remote-method context {:method "tasks/get"
                                        :params {:task-id task-id}}))

(defn request-task-result
  "Gets the result of a completed task.
   Blocks until the task reaches a terminal status.
   (see https://modelcontextprotocol.io/specification/2025-11-25/basic/utilities/tasks)

   Args:
     context - The server session context
     task-id - The unique identifier of the task

   Returns:
     A promise that resolves to the actual operation result
     (e.g., CreateMessageResult for sampling, ElicitationResponse for elicitation).
     Returns JSON-RPC error if the underlying operation failed.

   Notes:
     - Blocks until task reaches terminal status (completed/failed/cancelled)
     - For tasks in input_required status, this returns the input request
     - You can continue polling via request-task-get while waiting"
  [context task-id]
  (json-rpc/call-remote-method context {:method "tasks/result"
                                        :params {:task-id task-id}}))

(defn request-task-cancel
  "Cancels a task that has not yet reached terminal status.
   (see https://modelcontextprotocol.io/specification/2025-11-25/basic/utilities/tasks)

   Args:
     context - The server session context
     task-id - The unique identifier of the task to cancel

   Returns:
     A promise that resolves to the Task object with cancelled status.
     Returns error if task is already in terminal status.

   Notes:
     - Cannot cancel tasks already completed/failed/cancelled
     - Cancelled tasks remain in cancelled status even if execution continues"
  [context task-id]
  (when (client-supports-tasks-cancel? context)
    (json-rpc/call-remote-method context {:method "tasks/cancel"
                                          :params {:task-id task-id}})))

(defn request-tasks-list
  "Lists all tasks with optional pagination.
   (see https://modelcontextprotocol.io/specification/2025-11-25/basic/utilities/tasks)

   Args:
     context - The server session context
     cursor  - Optional pagination cursor from previous response

   Returns:
     A promise that resolves to {:tasks [...] :next-cursor ...}

   Example:
     (p/let [result (request-tasks-list context)]
       (doseq [task (:tasks result)]
         (println (:task-id task) (:status task)))
       (when-let [cursor (:next-cursor result)]
         (request-tasks-list context cursor)))"
  ([context]
   (request-tasks-list context nil))
  ([context cursor]
   (when (client-supports-tasks-list? context)
     (json-rpc/call-remote-method context {:method "tasks/list"
                                           :params (cond-> {}
                                                     cursor (assoc :cursor cursor))}))))

(defn notify-task-status
  "Notifies the requestor that a task's status has changed.
   
   Receivers MAY send this when task status changes.
   Requestors MUST NOT rely on receiving this notification.
   
   Args:
     context - The server session context
     task    - The full Task object with updated status
   
   Notes:
     - Only send to the requestor that created the task
     - Requestors should continue polling via tasks/get"
  [context task]
  (json-rpc/send-message context (json-rpc/notification "tasks/status" task))
  nil)

;;
;; Functions typically called by hand from a REPL session while working on MCP tooling
;;

(defn notify-prompt-list-changed
  "Notifies the client that the server's prompt list has changed.
   (see https://modelcontextprotocol.io/specification/2025-11-25/server/prompts#list-changed-notification)

   Args:
     context - The server session context

   Returns:
     nil"
  [context]
  (json-rpc/send-message context (json-rpc/notification "prompt/list_changed"))
  nil)

(defn add-prompt
  "Adds a prompt to the server's prompt registry and notifies the client.

   Args:
     context - The server session context
     prompt  - Prompt map with :name key and other prompt configuration

   Returns:
     nil"
  [context prompt]
  (let [{:keys [session]} context]
    (swap! session update :prompt-by-name assoc (:name prompt) prompt)
    (notify-prompt-list-changed context))
  nil)

(defn remove-prompt
  "Removes a prompt from the server's prompt registry and notifies the client.

   Args:
     context - The server session context
     prompt  - Prompt map with :name key to identify which prompt to remove

   Returns:
     nil"
  [context prompt]
  (let [{:keys [session]} context]
    (swap! session update :prompt-by-name dissoc (:name prompt))
    (notify-prompt-list-changed context))
  nil)

(defn notify-resource-updated
  "Notifies subscribed clients about a specific resource update.
   Only sends notification if the client is subscribed to the resource URI.
   (see https://modelcontextprotocol.io/specification/2025-11-25/server/resources#subscriptions)

   Args:
     context  - The server session context
     resource - Resource map with :uri key

   Returns:
     nil"
  [context resource]
  (let [{:keys [session]} context
        {:keys [client-subscribed-resource-uris]} @session
        {:keys [uri]} resource]
    (when (contains? client-subscribed-resource-uris uri)
      (json-rpc/send-message context (json-rpc/notification "resources/updated"
                                                            {:uri uri}))))
  nil)

(defn notify-resource-list-changed
  "Notifies the client that the server's resource list has changed.
   (see https://modelcontextprotocol.io/specification/2025-11-25/server/resources#list-changed-notification)

   Args:
     context - The server session context

   Returns:
     nil"
  [context]
  (json-rpc/send-message context (json-rpc/notification "resources/list_changed"))
  nil)

(defn add-resource
  "Adds a resource to the server's resource registry and notifies the client.

   Args:
     context  - The server session context
     resource - Resource map with :uri key and other resource configuration

   Returns:
     nil"
  [context resource]
  (let [{:keys [session]} context]
    (swap! session update :resource-by-uri assoc (:uri resource) resource)
    (notify-resource-list-changed context))
  nil)

(defn remove-resource
  "Removes a resource from the server's resource registry and notifies the client.

   Args:
     context  - The server session context
     resource - Resource map with :uri key to identify which resource to remove

   Returns:
     nil"
  [context resource]
  (let [{:keys [session]} context]
    (swap! session update :resource-by-uri dissoc (:uri resource))
    (notify-resource-list-changed context))
  nil)

(defn notify-tool-list-changed
  "Notifies the client that the server's tool list has changed.
  (see https://modelcontextprotocol.io/specification/2025-11-25/server/tools#list-changed-notification)

   Args:
     context - The server session context

   Returns:
     nil"
  [context]
  (json-rpc/send-message context (json-rpc/notification "tools/list_changed"))
  nil)

(defn add-tool
  "Adds a tool to the server's tool registry and notifies the client.

   Args:
     context - The server session context
     tool    - Tool map with :name key and other tool configuration

   Returns:
     nil"
  [context tool]
  (let [{:keys [session]} context]
    (swap! session update :tool-by-name assoc (:name tool) tool)
    (notify-tool-list-changed context))
  nil)

(defn remove-tool
  "Removes a tool from the server's tool registry and notifies the client.

   Args:
     context - The server session context
     tool    - Tool map with :name key to identify which tool to remove

   Returns:
     nil"
  [context tool]
  (let [{:keys [session]} context]
    (swap! session update :tool-by-name dissoc (:name tool))
    (notify-tool-list-changed context))
  nil)

(defn set-resource-templates
  "Sets the resource templates for the server session.

   Args:
     context            - The server session context
     resource-templates - Vector of resource template maps

   Returns:
     nil"
  [context resource-templates]
  (let [{:keys [session]} context]
    (swap! session assoc :resource-templates resource-templates))
  nil)

(defn set-resource-uri-complete-fn
  "Sets the resource URI completion function for the server session.

   Args:
     context                   - The server session context
     resource-uri-complete-fn  - Function to handle resource URI completion requests

   Returns:
     nil"
  [context resource-uri-complete-fn]
  (let [{:keys [session]} context]
    (swap! session assoc :resource-uri-complete-fn resource-uri-complete-fn))
  nil)

(defn create-session
  "Returns the state of a newly created session.
   (see https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle#initialization)

   Options:
     :server-info - Map with server identification (passed to client during initialization)
       :name        - Server name (required)
       :version     - Server version (required)
       :description - Human-readable description of the server (optional, 2025-11-25+)

     :server-instructions - Optional instructions for the client about using this server

     :prompts   - Vector of prompt definitions
     :resources - Vector of resource definitions
     :tools     - Vector of tool definitions

     :resource-templates       - Vector of resource template definitions
     :resource-uri-complete-fn - Function for resource URI completion

     :logging-level - Minimum log level to send to client (default: \"debug\")

     :on-initialized             - Callback when initialization completes (default: request-root-list)
     :on-client-root-list-changed - Callback when client notifies root list changed
     :on-client-root-list-updated - Callback after server updates root data

   Example:
     (create-session
       {:server-info {:name \"my-server\"
                      :version \"1.0.0\"
                      :description \"A helpful MCP server for data analysis\"}
        :tools [...]})"
  [{:keys [server-info
           server-instructions
           ;; MCP server features
           prompts
           resources
           tools
           resource-templates
           resource-uri-complete-fn
           logging-level
           on-initialized
           on-client-root-list-changed ;; called after the server get the notification from the client
           on-client-root-list-updated ;; called after the server updated its data
           ]
    :or {server-info {:name "mcp-toolkit"
                      :version "0.1.1-alpha"}
         logging-level "debug"
         on-initialized request-root-list
         on-client-root-list-changed request-root-list}}]
  {;; About the server
   :server-supported-protocol-versions ["2024-11-05" "2025-03-26" "2025-06-18" "2025-11-25"]
   :server-info server-info
   :server-instructions server-instructions
   :initialized false
   :handler-by-method server.handler/handler-by-method-pre-initialization
   :protocol-version nil ; determined at initialization
   :prompt-by-name (mc/index-by :name prompts)
   :resource-by-uri (mc/index-by :uri resources)
   :tool-by-name (mc/index-by :name tools)
   :resource-templates resource-templates
   :resource-uri-complete-fn resource-uri-complete-fn
   :is-cancelled-by-request-id {} ;; "is-cancelled" atoms indexed by request-id
   :logging-level logging-level
   :on-initialized on-initialized
   :on-client-root-list-changed on-client-root-list-changed
   :on-client-root-list-updated on-client-root-list-updated
   ;; About the client
   :client-info nil
   :client-capabilities nil
   :client-subscribed-resource-uris #{}
   :client-root-by-uri {}
   :last-called-method-id -1 ;; Used for calling methods on the remote site
   :handler-by-called-method-id {}}) ;; The response handlers
