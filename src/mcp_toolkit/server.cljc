(ns mcp-toolkit.server
  (:require
   [mate.core :as mc]
   [mcp-toolkit.impl.common :refer [user-callback]]
   [mcp-toolkit.impl.mrtr :as mrtr]
   [mcp-toolkit.impl.server.handler :as server.handler]
   [mcp-toolkit.impl.server.handler-2026 :as server.handler-2026]
   [mcp-toolkit.impl.server.handler-dual :as server.handler-dual]
   [mcp-toolkit.impl.subscriptions :as subscriptions]
   [mcp-toolkit.json-rpc :as json-rpc]
   [mcp-toolkit.protocol :as protocol]
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

(def ^:private log-level->importance protocol/log-level->importance)

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
        ;; 2026-07-28 replaced logging/setLevel with a per-request _meta field,
        ;; and a server must not log for a request that omitted it.
        ;; with-request-context always assocs :log-level, so the key's presence
        ;; marks a request that came through the stateless path. Reading the
        ;; session's :logging-level there instead would apply its "debug"
        ;; default and emit everything, which is what used to happen.
        requested (if (contains? context :log-level)
                    (:log-level context)
                    (:logging-level @session))
        ;; An unknown level is not a quieter level. The per-request _meta one
        ;; arrives straight off the wire and nothing validates it, unlike
        ;; logging/setLevel and create-session which now refuse one. Comparing
        ;; against a default of 0 would read it as "debug" and send the client
        ;; everything, which is the opposite of what an unrecognised threshold
        ;; should do, so it suppresses instead.
        threshold (when (contains? protocol/log-level->importance requested)
                    requested)]
    ;; Both lookups carry a default. Only the first one did, so an unrecognised
    ;; stored level produced nil and blew up the comparison on the JVM, while
    ;; ClojureScript coerced it to 0 and emitted everything instead. Defaulting
    ;; an unknown threshold to "debug" errs toward sending rather than silence.
    (when (and (some? threshold)
               (>= (log-level->importance level -1)
                   (log-level->importance threshold)))
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

(defn request-sampling
  "Requests message sampling from the MCP client.
   Returns a promise, either resolved with the result or rejected with the error.
   (see https://modelcontextprotocol.io/specification/2025-11-25/client/sampling)

   Args:
     context - The server session context
     params  - Sampling parameters map:
               :messages          - Vector of message maps with :role and :content (required)
               :max-tokens        - Maximum tokens to generate (required by the spec)
               :system-prompt     - System prompt string (optional)
               :model-preferences - Model selection hints (optional)
               :temperature       - Sampling temperature (optional)
               :stop-sequences    - Vector of strings that stop generation (optional)
               :metadata          - Provider-specific metadata passed through (optional)
               :include-context   - Deprecated: use explicit context instead (optional)

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
     - Returns nil if client doesn't support sampling
     - Tool use requires the client to declare :sampling {:tools {}}.
       Passing :tools or :tool-choice without it returns a REJECTED
       promise carrying {:type :missing-client-capability}, because the
       spec obliges the client to error on such a request anyway.
     - params are passed through as given, so any field the spec accepts
       works even if it is not listed above"
  [context params]
  (let [{:keys [session]} context
        {:keys [client-capabilities]} @session
        wants-tools? (or (contains? params :tools)
                         (contains? params :tool-choice))]
    (when (contains? client-capabilities :sampling)
      ;; The 2025-11-25 schema says of both `tools` and `toolChoice`:
      ;; "The client MUST return an error if this field is provided but
      ;;  ClientCapabilities.sampling.tools is not declared."
      ;; Sending them anyway produces a request the client is obliged to
      ;; reject, so fail at the call site where the caller can see it.
      (if (and wants-tools?
               (not (get-in client-capabilities [:sampling :tools])))
        (p/rejected
         (ex-info "Client did not declare the sampling.tools capability"
                  {:type                :missing-client-capability
                   :capability          [:sampling :tools]
                   :client-capabilities client-capabilities}))
        (json-rpc/call-remote-method context {:method "sampling/createMessage"
                                              :params params})))))

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

;; ---------------------------------------------------------------------------
;; Server notifications
;;
;; The handshake revisions have an ambient connection, so a notification just
;; goes down it. 2026-07-28 has no such thing. A notification only exists on a
;; subscriptions/listen stream, so it fans out to the subscriptions that asked
;; for it, and each copy is tagged with the stream it belongs to.
;;
;; Progress and log notifications are NOT routed this way. They are
;; request-scoped and ride the response stream of the request they belong to,
;; which is why they still work with no subscription open.
;; ---------------------------------------------------------------------------

(defn- notification-message
  [topic params]
  (if (some? params)
    (json-rpc/notification topic params)
    (json-rpc/notification topic)))

(defn- notify-subscribers
  "Fans out to the stateless subscriptions that opted in, tagging each copy."
  [context topic params uri]
  (doseq [subscription-id (subscriptions/subscriber-ids @(:session context) topic uri)]
    (json-rpc/send-message context
                           (subscriptions/tag (notification-message topic params)
                                              subscription-id))))

(defn- notify-handshake-client
  "Sends straight down the connection, which is all the handshake revisions
   have and all they need."
  [context topic params]
  (json-rpc/send-message context (notification-message topic params)))

(defn- send-notification
  ([context topic]
   (send-notification context topic nil nil))
  ([context topic params]
   (send-notification context topic params nil))
  ([context topic params uri]
   (let [{:keys [dual-era? protocol-version initialized]} @(:session context)]
     (cond
       ;; A dual session may have stateless subscribers and a handshake client
       ;; at the same time, and both are entitled to hear about this.
       dual-era?
       (do (notify-subscribers context topic params uri)
           (when initialized
             (notify-handshake-client context topic params)))

       (protocol/stateless? protocol-version)
       (notify-subscribers context topic params uri)

       :else
       (notify-handshake-client context topic params)))
   nil))

(defn close-subscription!
  "Ends one subscription stream on the server's own initiative.

   Answers the still-open subscriptions/listen request, which tells the client
   the stream closed on purpose. A stream that simply stops looks like a
   dropped transport, and a client may reconnect on that basis.

   Args:
     context         - The server session context
     subscription-id - The id of the subscriptions/listen request

   Returns:
     nil"
  [context subscription-id]
  (let [{:keys [session]} context]
    (when (contains? (:subscription-by-id @session) subscription-id)
      (swap! session update :subscription-by-id dissoc subscription-id)
      (json-rpc/send-message context (subscriptions/close-response subscription-id))))
  nil)

(defn close-all-subscriptions!
  "Ends every open subscription, as a server would on shutdown.

   Args:
     context - The server session context

   Returns:
     nil"
  [context]
  ;; The same comparator subscriber-ids uses. A bare sort throws
  ;; ClassCastException the moment one client picks a numeric JSON-RPC id and
  ;; another picks a string, and JSON-RPC allows both.
  (doseq [subscription-id (sort subscriptions/compare-subscription-ids
                                (keys (:subscription-by-id @(:session context))))]
    (close-subscription! context subscription-id))
  nil)

(defn active-subscriptions
  "Returns the requested filter of every open subscription, keyed by id.

   The requested filter, not the honoured one. What a subscription stores is
   what the client asked for; the acknowledgement reported what was servable
   at that instant. They differ deliberately, so that a capability appearing
   later still reaches a subscriber who asked for it.

   Args:
     context - The server session context

   Returns:
     A map of subscription id to filter."
  [context]
  (:subscription-by-id @(:session context)))

(defn notify-prompt-list-changed
  "Notifies the client that the server's prompt list has changed.
   (see https://modelcontextprotocol.io/specification/2025-11-25/server/prompts#list-changed-notification)

   Args:
     context - The server session context

   Returns:
     nil"
  [context]
  (send-notification context "prompts/list_changed")
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
  ;; The two eras track interest differently. A stateless subscription names
  ;; URIs in its filter, and one ending in a slash covers everything beneath
  ;; it; a handshake client accumulates URIs through resources/subscribe.
  (let [{:keys [session]} context
        {:keys [client-subscribed-resource-uris dual-era? protocol-version initialized]} @session
        {:keys [uri]} resource
        subscribed? (contains? client-subscribed-resource-uris uri)]
    (cond
      dual-era?
      (do (notify-subscribers context "resources/updated" {:uri uri} uri)
          (when (and initialized subscribed?)
            (notify-handshake-client context "resources/updated" {:uri uri})))

      (protocol/stateless? protocol-version)
      (notify-subscribers context "resources/updated" {:uri uri} uri)

      :else
      (when subscribed?
        (notify-handshake-client context "resources/updated" {:uri uri}))))
  nil)

(defn notify-resource-list-changed
  "Notifies the client that the server's resource list has changed.
   (see https://modelcontextprotocol.io/specification/2025-11-25/server/resources#list-changed-notification)

   Args:
     context - The server session context

   Returns:
     nil"
  [context]
  (send-notification context "resources/list_changed")
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
  (send-notification context "tools/list_changed")
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

;; ---------------------------------------------------------------------------
;; Multi Round-Trip Requests (2026-07-28)
;;
;; Earlier revisions let a tool-fn await `request-sampling` or
;; `request-elicitation` mid-call. 2026-07-28 removed server-initiated
;; requests, so a handler that needs something from the client returns
;; `input-required` and stops. The client answers, then re-issues the SAME
;; request with those answers attached, and the handler runs again.
;;
;; The retry may reach a different process, so whatever the handler needs in
;; order to resume has to travel in :request-state. It is a string the client
;; treats as opaque and hands back untouched.
;;
;;   (defn greet [context _arguments]
;;     (if-some [answer (server/input-response context :who)]
;;       {:content [{:type "text" :text (str "hello " (-> answer :content :name))}]}
;;       (server/input-required
;;        {:input-requests {:who (server/elicit-form-request
;;                                {:message "Who are you?"
;;                                 :requested-schema {:type "object"
;;                                                    :properties {:name {:type "string"}}
;;                                                    :required ["name"]}})}
;;         :request-state "asked-for-name"})))
;; ---------------------------------------------------------------------------

(defn input-required
  "Returns an interim result asking the client to answer one or more requests
   before this call can finish.

   Args:
     opts - Map of:
            :input-requests - Map of your own key to a request, built with
                              `sampling-request`, `roots-request`,
                              `elicit-form-request` or `elicit-url-request`
            :request-state  - Optional string handed back on the retry. It must
                              be self-contained, since the retry may reach a
                              different process.

   Returns:
     A result map carrying :result-type \"input_required\"."
  [opts]
  (mrtr/input-required opts))

(defn input-response
  "Returns the client's answer to one request from a previous turn.

   Args:
     context - The handler context
     k       - The key used in the matching `input-required` call

   Returns:
     The answer, or nil when this is a first attempt."
  [context k]
  (mrtr/input-response context k))

(defn input-responses
  "Returns every answer on this request, keyed as originally asked.

   Args:
     context - The handler context

   Returns:
     A map of key to answer, or nil on a first attempt."
  [context]
  (mrtr/input-responses context))

(defn request-state
  "Returns the opaque state this handler sent on its previous turn.

   Args:
     context - The handler context

   Returns:
     The request-state string, or nil on a first attempt."
  [context]
  (mrtr/request-state context))

(defn retry?
  "Returns true when the client is retrying with answers rather than calling
   for the first time.

   Args:
     context - The handler context

   Returns:
     true on a retry."
  [context]
  (mrtr/retry? context))

(defn sampling-request
  "Builds a sampling input request.

   Sampling is deprecated as of 2026-07-28 but stays functional for at least
   twelve months. Prefer talking to a model provider directly.

   Args:
     params - Sampling params, at minimum :messages and :max-tokens

   Returns:
     An input request map."
  [params]
  (mrtr/sampling-request params))

(defn roots-request
  "Builds a roots listing input request.

   Roots is deprecated as of 2026-07-28. Prefer passing directories as tool
   parameters or resource URIs.

   Returns:
     An input request map."
  []
  (mrtr/roots-request))

(defn elicit-form-request
  "Builds a form-mode elicitation input request.

   Args:
     params - Map of :message and :requested-schema

   Returns:
     An input request map."
  [params]
  (mrtr/elicit-form-request params))

(defn elicit-url-request
  "Builds a URL-mode elicitation input request.

   Args:
     params - Map of :message and :url

   Returns:
     An input request map."
  [params]
  (mrtr/elicit-url-request params))

(defn missing-client-capability-error
  "Returns the 2026-07-28 error for a request that needs a client capability
   it did not declare.

   Use it from a handler that cannot proceed, rather than asking for input the
   client has already said it cannot provide. Returning this is clearer than an
   input-required the client will only fail on.

   Args:
     context               - The handler context
     required-capabilities - The ClientCapabilities shape the handler needed

   Returns:
     A full JSON-RPC error response, which the router sends as-is."
  [context required-capabilities]
  (mrtr/missing-client-capability-response (-> context :message :id)
                                           required-capabilities))

(defn request-client-capabilities
  "Returns the capabilities the current request declared.

   Under 2026-07-28 capabilities arrive per request in _meta rather than being
   negotiated once, so this reads the context and falls back to the session for
   handshake-based revisions.

   Args:
     context - The handler context

   Returns:
     The ClientCapabilities map, or nil."
  [context]
  (or (:client-capabilities context)
      (some-> context :session deref :client-capabilities)))

(defn request-log-level
  "Returns the log level this request opted in to.

   2026-07-28 replaced logging/setLevel with a per-request _meta field, and a
   server must not send log notifications for a request that omitted it.

   Args:
     context - The handler context

   Returns:
     The level string, or nil when this request asked for no logs."
  [context]
  (:log-level context))

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

     :protocol-version - Pin the session to one protocol revision. Leave it out
                         for the handshake-based revisions, where the version is
                         negotiated during initialize. Pass \"2026-07-28\" for a
                         stateless session: no handshake, server/discover instead
                         of initialize, and every request carrying its own
                         version and capabilities in _meta.
     :dual-era?        - Serve both eras on one session. A request declaring a
                         protocol version in _meta gets stateless semantics; an
                         initialize gets the handshake. Use it when one endpoint
                         has to serve a mixed fleet of clients. Overrides
                         :protocol-version.
     :cache-policy     - Overrides the per-method {:ttl-ms :cache-scope} freshness
                         hints on 2026-07-28 cacheable results.

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
           protocol-version
           dual-era?
           cache-policy
           on-initialized
           on-client-root-list-changed ;; called after the server get the notification from the client
           on-client-root-list-updated ;; called after the server updated its data
           ]
    :or {server-info {:name "mcp-toolkit"
                      :version "2026-07-28"}
         logging-level "debug"
         on-initialized request-root-list
         on-client-root-list-changed request-root-list}}]
  (let [_ (when-not (contains? protocol/log-level->importance logging-level)
            ;; The same eight levels logging/setLevel is checked against. An
            ;; unknown one used to be stored and then compared with a default
            ;; of 0, which reads as "debug" and floods the client with every
            ;; message rather than filtering anything.
            (throw (ex-info (str "Unknown :logging-level " (pr-str logging-level) ". "
                                 "Accepted levels are "
                                 (pr-str (sort (keys protocol/log-level->importance))) ".")
                            {:type :invalid-logging-level
                             :logging-level logging-level
                             :accepted (sort (keys protocol/log-level->importance))})))
        handshake-versions ["2024-11-05" "2025-03-26" "2025-06-18" "2025-11-25"]
        _ (when (some? protocol-version)
            ;; stateless? is a set membership test, so anything outside it
            ;; quietly built a handshake session instead. One mistyped digit
            ;; produced a completely different server, with no server/discover
            ;; in its table, and every stateless client got -32601 back.
            (when-not (protocol/stateless? protocol-version)
              (throw (ex-info (if (contains? (set handshake-versions) protocol-version)
                                (str "A handshake revision cannot be pinned here. "
                                     protocol-version
                                     " is negotiated with the client at initialize, "
                                     "so omit :protocol-version to serve the handshake era. "
                                     "This option exists to opt into "
                                     protocol/latest-protocol-version ", which has no handshake.")
                                (str "Unknown :protocol-version " (pr-str protocol-version) ". "
                                     "The only value this option accepts is "
                                     protocol/latest-protocol-version "."))
                              {:type :invalid-protocol-version
                               :protocol-version protocol-version
                               :accepted protocol/latest-protocol-version}))))
        stateless (protocol/stateless? protocol-version)]
    {;; About the server
     ;; What this session can actually serve, which is not the same as what the
     ;; library implements. A session carries one era's dispatch table, so a
     ;; stateless one cannot serve a handshake client and must not say it can.
     :server-supported-protocol-versions (cond
                                           dual-era? (conj handshake-versions
                                                           protocol/latest-protocol-version)
                                           stateless [protocol/latest-protocol-version]
                                           :else handshake-versions)
     ;; The subset a request may declare in _meta. A dual session serves both
     ;; eras but only ever answers one of them statelessly.
     :modern-protocol-versions (when (or stateless dual-era?)
                                 [protocol/latest-protocol-version])
     :dual-era? (boolean dual-era?)
     :server-info server-info
     :server-instructions server-instructions
   ;; A stateless session has nothing to initialize. A dual one starts
   ;; un-initialized, because a handshake client may still arrive.
     :initialized (and stateless (not dual-era?))
     :handler-by-method (cond
                          dual-era? server.handler-dual/handler-by-method
                          stateless server.handler-2026/handler-by-method
                          :else server.handler/handler-by-method-pre-initialization)
   ;; Where the handshake era's own table lives on a dual session, since
   ;; :handler-by-method holds the dual dispatch and must not be swapped.
     :legacy-handler-by-method (when dual-era?
                                 server.handler-dual/legacy-handler-by-method-pre-initialization)
     :protocol-version (when (and stateless (not dual-era?)) protocol-version)
     :cache-policy cache-policy
     :prompt-by-name (mc/index-by :name prompts)
     :resource-by-uri (mc/index-by :uri resources)
     :tool-by-name (mc/index-by :name tools)
     :resource-templates resource-templates
     :resource-uri-complete-fn resource-uri-complete-fn
     :is-cancelled-by-request-id {} ;; "is-cancelled" atoms indexed by request-id
   ;; 2026-07-28 subscriptions/listen streams, keyed by the id of the request
   ;; that opened each one. Empty on the handshake revisions.
     :subscription-by-id {}
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
     :handler-by-called-method-id {}})) ;; The response handlers
