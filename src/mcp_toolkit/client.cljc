(ns mcp-toolkit.client
  (:require
   [mate.core :as mc]
   [mcp-toolkit.impl.client.handler :as client.handler]
   [mcp-toolkit.impl.client.handler-2026 :as client.handler-2026]
   [mcp-toolkit.impl.common :refer [user-callback]]
   [mcp-toolkit.json-rpc :as json-rpc]
   [mcp-toolkit.protocol :as protocol]
   [promesa.core :as p]))

;; ---------------------------------------------------------------------------
;; 2026-07-28 plumbing
;;
;; Two things change for every request on the stateless revision. It carries
;; the client's own protocol version and capabilities in _meta, since nothing
;; was negotiated up front. And its result may come back asking for input
;; rather than answering, in which case the client fulfils the requests and
;; re-issues the same call.
;;
;; Both are handled here so the request functions below stay unchanged and
;; work on either revision.
;; ---------------------------------------------------------------------------

(def ^:private default-max-round-trips
  "How many times one request may come back asking for input before the client
   gives up. A well-behaved server converges in one or two. The cap exists so a
   server that keeps asking cannot spin a client forever."
  8)

(defn- stateless-session?
  [session]
  (protocol/stateless? (:protocol-version @session)))

(defn- request-meta
  [session]
  (let [{:keys [protocol-version client-capabilities client-info log-level]} @session]
    (cond-> {protocol/meta-protocol-version protocol-version
             ;; An empty map is meaningful here: it says the client supports no
             ;; optional capabilities, which is different from saying nothing.
             protocol/meta-client-capabilities (or client-capabilities {})}
      (some? client-info) (assoc protocol/meta-client-info client-info)
      (some? log-level) (assoc protocol/meta-log-level log-level))))

(defn- own-roots
  [session]
  {:roots (-> @session
              :root-by-uri
              vals
              (->> (mapv (fn [root] (select-keys root [:uri :name])))))})

(defn- missing-handler
  [method callback-key]
  (fn [_context _params]
    (p/rejected
     (ex-info (str "The server asked for " method
                   ", but this session has no " callback-key " handler")
              {:type :missing-input-request-handler
               :method method
               :callback callback-key}))))

(defn- fulfil-input-request
  "Answers one request the server made, and returns a promise of
   [wire-key response]. The key is echoed back exactly as received, since the
   server chose it and treats it as its own."
  [context wire-key {:keys [method params]}]
  (let [{:keys [session]} context
        handler (fn [callback-key]
                  (or (get @session callback-key)
                      (missing-handler method callback-key)))]
    (p/let [response (case method
                       "roots/list"
                       (own-roots session)

                       "sampling/createMessage"
                       ((handler :on-sampling-requested) context params)

                       "elicitation/create"
                       ((handler :on-elicitation-requested) context params)

                       (p/rejected
                        (ex-info (str "Unsupported input request: " method)
                                 {:type :unsupported-input-request
                                  :method method})))]
      [wire-key response])))

(defn- call-with-round-trips
  "Calls a method, answering any request for input and retrying until the
   server returns a complete result.

   The retry is the same request with the answers and the server's own
   request-state attached. A server implementing an earlier revision omits
   result-type entirely, which the spec says to read as complete."
  [context message]
  (let [{:keys [session]} context
        max-round-trips (or (:max-round-trips @session) default-max-round-trips)]
    (p/loop [params (:params message)
             round-trips 0]
      (p/let [result (json-rpc/call-remote-method
                      context
                      (assoc message :params (assoc params :_meta (request-meta session))))]
        (if (not= "input_required" (:result-type result))
          result
          (if (<= max-round-trips round-trips)
            (p/rejected
             (ex-info "The server kept asking for input"
                      {:type :too-many-round-trips
                       :method (:method message)
                       :max-round-trips max-round-trips}))
            (p/let [answers (p/all (mapv (fn [[wire-key request]]
                                           (fulfil-input-request context wire-key request))
                                         (:input-requests result)))]
              (p/recur (assoc params
                              :input-responses (into {} answers)
                              :request-state (:request-state result))
                       (inc round-trips)))))))))

(defn- send-notification
  "Sends a notification, stamping the per-request _meta on the stateless
   revision.

   A dual-era server decides which era a message belongs to by looking for the
   protocol version in _meta. A notification sent without it is treated as a
   handshake-era message and routed to the legacy table, where a 2026-only
   notification has no handler and is silently dropped. That made
   notify-unsubscribe a no-op against a dual-era server: the subscription
   stayed open and kept delivering."
  [context topic params]
  (let [{:keys [session]} context
        message (json-rpc/notification topic params)]
    (json-rpc/send-message context
                           (if (stateless-session? session)
                             (assoc-in message [:params :_meta] (request-meta session))
                             message))))

(defn- call-method
  "Calls a remote method. On the stateless revision this adds the per-request
   _meta and runs the multi round-trip loop. Otherwise it is a plain call."
  [context message]
  (if (stateless-session? (:session context))
    (call-with-round-trips context message)
    (json-rpc/call-remote-method context message)))

(defn request-set-logging-level
  "Sets the logging level on the MCP server.
   (see https://modelcontextprotocol.io/specification/2025-11-25/server/utilities/logging#log-levels)

   Args:
     context - The client session context
     level   - Logging level, accepted values are \"debug\", \"info\", \"notice\", \"warning\", \"error\", \"critical\", \"alert\" and \"emergency\"

   Returns:
     A promise that resolves when the server acknowledges the level change."
  [context level]
  (call-method context {:method "logging/setLevel"
                        :params {:level level}}))

(defn request-complete-prompt-param
  "Requests autocompletion for a prompt parameter from the MCP server.
   (see https://modelcontextprotocol.io/specification/2025-11-25/server/utilities/completion#data-types)

   Args:
     context      - The client session context
     prompt-name  - Name of the prompt to complete
     param-name   - Name of the parameter to complete
     param-value  - Current partial value of the parameter

   Returns:
     A promise that resolves to completion suggestions from the server."
  [context prompt-name
   param-name param-value]
  (call-method context {:method "completion/complete"
                        :params {:ref {:type "ref/prompt"
                                       :name prompt-name}
                                 :argument {:name param-name
                                            :value param-value}}}))

(defn request-complete-resource-uri
  "Requests autocompletion for a resource URI parameter from the MCP server.
   (see https://modelcontextprotocol.io/specification/2025-11-25/server/utilities/completion#data-types)

   Args:
     context      - The client session context
     uri-template - URI template to complete
     param-name   - Name of the parameter to complete
     param-value  - Current partial value of the parameter

   Returns:
     A promise that resolves to completion suggestions from the server."
  [context uri-template
   param-name param-value]
  (call-method context {:method "completion/complete"
                        :params {:ref {:type "ref/resource"
                                       :uri uri-template}
                                 :argument {:name param-name
                                            :value param-value}}}))

(defn request-prompt-list
  "Requests the list of available prompts from the MCP server.
   Updates the session's server-prompt-by-name index and calls the
   on-server-prompt-list-updated callback.
   (see https://modelcontextprotocol.io/specification/2025-11-25/server/prompts#listing-prompts)

   Args:
     context - The client session context

   Returns:
     A promise that resolves when prompts are fetched and stored."
  [context]
  (let [{:keys [session]} context
        {:keys [server-capabilities]} @session]
    ;; The capability gate only applies to the handshake era, where
    ;; :server-capabilities is filled in by initialize. A stateless session has
    ;; no handshake, and request-discover is explicitly optional, so gating
    ;; there made all three of these silently send nothing and return nil on a
    ;; session the library itself calls "usable immediately".
    (when (or (stateless-session? session)
              (contains? server-capabilities :prompts))
      (-> (call-method context {:method "prompts/list"})
          (p/then (fn [{:keys [prompts]}]
                    (swap! session assoc :server-prompt-by-name (mc/index-by :name prompts))
                    ((user-callback :on-server-prompt-list-updated) context)))))))

(defn request-prompt
  "Requests a specific prompt from the MCP server with given arguments.
  (see https://modelcontextprotocol.io/specification/2025-11-25/server/prompts#getting-a-prompt)

   Args:
     context     - The client session context
     prompt-name - Name of the prompt to retrieve
     arguments   - Map of arguments to pass to the prompt

   Returns:
     A promise that resolves to the prompt response from the server."
  [context prompt-name arguments]
  (call-method context {:method "prompts/get"
                        :params {:name prompt-name
                                 :arguments arguments}}))

(defn request-resource-list
  "Requests the list of available resources from the MCP server.
   Updates the session's server-resource-by-uri index and calls the
   on-server-resource-list-updated callback.
   (see https://modelcontextprotocol.io/specification/2025-11-25/server/resources#listing-resources)

   Args:
     context - The client session context

   Returns:
     A promise that resolves when the resource descriptions are fetched and stored."
  [context]
  (let [{:keys [session]} context
        {:keys [server-capabilities]} @session]
    ;; The capability gate only applies to the handshake era, where
    ;; :server-capabilities is filled in by initialize. A stateless session has
    ;; no handshake, and request-discover is explicitly optional, so gating
    ;; there made all three of these silently send nothing and return nil on a
    ;; session the library itself calls "usable immediately".
    (when (or (stateless-session? session)
              (contains? server-capabilities :resources))
      (-> (call-method context {:method "resources/list"})
          (p/then (fn [{:keys [resources]}]
                    (swap! session assoc :server-resource-by-uri (mc/index-by :uri resources))
                    ((user-callback :on-server-resource-list-updated) context)))))))

(defn request-resource
  "Requests a specific resource from the MCP server by URI.
   (see https://modelcontextprotocol.io/specification/2025-11-25/server/resources#reading-resources)

   Args:
     context      - The client session context
     resource-uri - URI of the resource to retrieve

   Returns:
     A promise that resolves to the resource content from the server."
  [context resource-uri]
  (call-method context {:method "resources/read"
                        :params {:uri resource-uri}}))

(defn request-resource-template-list
  "Requests the list of available resource templates from the MCP server.
   (see https://modelcontextprotocol.io/specification/2025-11-25/server/resources#resource-templates)

   Args:
     context - The client session context

   Returns:
     A promise that resolves to the list of resource templates."
  [context]
  (call-method context {:method "resources/templates/list"}))

(defn request-subscribe-resource
  "Subscribes to changes for a specific resource on the MCP server.
   (see https://modelcontextprotocol.io/specification/2025-11-25/server/resources#subscriptions)

   Args:
     context      - The client session context
     resource-uri - URI of the resource to subscribe to

   Returns:
     A promise that resolves when subscription is confirmed."
  [context resource-uri]
  (call-method context {:method "resources/subscribe"
                        :params {:uri resource-uri}}))

(defn request-unsubscribe-resource
  "Unsubscribes from changes for a specific resource on the MCP server.
   (see https://modelcontextprotocol.io/specification/2025-11-25/server/resources#subscriptions)

   Args:
     context      - The client session context
     resource-uri - URI of the resource to unsubscribe from

   Returns:
     A promise that resolves when unsubscription is confirmed."
  [context resource-uri]
  (call-method context {:method "resources/unsubscribe"
                        :params {:uri resource-uri}}))

(defn request-tool-list
  "Requests the list of available tools from the MCP server.
   Updates the session's server-tool-by-name index and triggers the
   on-server-tool-list-updated callback.
   (see https://modelcontextprotocol.io/specification/2025-11-25/server/tools#listing-tools)

   Args:
     context - The client session context

   Returns:
     A promise that resolves when the tool descriptions are fetched and stored."
  [context]
  (let [{:keys [session]} context
        {:keys [server-capabilities]} @session]
    ;; The capability gate only applies to the handshake era, where
    ;; :server-capabilities is filled in by initialize. A stateless session has
    ;; no handshake, and request-discover is explicitly optional, so gating
    ;; there made all three of these silently send nothing and return nil on a
    ;; session the library itself calls "usable immediately".
    (when (or (stateless-session? session)
              (contains? server-capabilities :tools))
      (-> (call-method context {:method "tools/list"})
          (p/then (fn [{:keys [tools]}]
                    (swap! session assoc :server-tool-by-name (mc/index-by :name tools))
                    ((user-callback :on-server-tool-list-updated) context)))))))

(defn request-tool-invocation
  "Invokes a specific tool on the MCP server with given arguments.
   (see https://modelcontextprotocol.io/specification/2025-11-25/server/tools#calling-tools)

   Args:
     context   - The client session context
     tool-name - Name of the tool to invoke
     arguments - Map of arguments to pass to the tool

   Returns:
     A promise that resolves to the tool execution result."
  [context tool-name arguments]
  (call-method context {:method "tools/call"
                        :params {:name tool-name
                                 :arguments arguments}}))

(defn notify-cancel-request
  "Sends a cancellation notification for a specific request to the MCP server.
   (see https://modelcontextprotocol.io/specification/2025-11-25/basic/utilities/cancellation#cancellation-flow)

   Args:
     context    - The client session context
     request-id - ID of the request to cancel

   Returns:
     nil"
  [context request-id]
  (send-notification context "cancelled" {:request-id request-id}))

(defn notify-root-list-changed
  "Notifies the MCP server that the client's root list has been changed.
   (see https://modelcontextprotocol.io/specification/2025-11-25/client/roots#root-list-changes)

   Args:
     context - The client session context

   Returns:
     nil"
  [context]
  (json-rpc/send-message context (json-rpc/notification "roots/list_changed")))

(defn add-root
  "Adds a root to the client's root registry and notifies the server.

   Args:
     context - The client session context
     root    - Root map with :uri key and other root configuration

   Returns:
     nil"
  [context root]
  (let [{:keys [session]} context]
    (swap! session update :root-by-uri assoc (:uri root) root)
    (notify-root-list-changed context))
  nil)

(defn remove-root
  "Removes a root from the client's root registry and notifies the server.

   Args:
     context - The client session context
     root    - Root map with :uri key to identify which root to remove

   Returns:
     nil"
  [context root]
  (let [{:keys [session]} context]
    (swap! session update :root-by-uri dissoc (:uri root))
    (notify-root-list-changed context))
  nil)

(defn request-subscribe
  "Opens a notification stream on a 2026-07-28 server.

   This request deliberately does not resolve when the server starts sending.
   It IS the stream: it stays open for the subscription's life, and its
   response arrives only if the server closes the subscription gracefully. So
   the promise returned here resolves at the END of the subscription, not the
   beginning. Do not await it before carrying on.

   The server answers first with notifications/subscriptions/acknowledged,
   reporting the subset of the filter it will actually honour, which reaches
   :on-subscription-acknowledged. Check it: a type the server does not support
   is omitted rather than refused, so a silent stream and a stream you were
   never going to get anything on look identical otherwise.

   Every notification on the stream carries the subscription id in _meta under
   io.modelcontextprotocol/subscriptionId. On stdio all streams share one
   channel, so that field is the only way to demultiplex them.

   Args:
     context       - The client session context
     notifications - The filter, any of:
                     :tools-list-changed     - boolean
                     :prompts-list-changed   - boolean
                     :resources-list-changed - boolean
                     :resource-subscriptions - vector of resource URIs. A URI
                                               ending in a slash also covers
                                               everything beneath it.

   Returns:
     A promise that resolves when the subscription ends gracefully."
  [context notifications]
  (call-method context {:method "subscriptions/listen"
                        :params {:notifications notifications}}))

(defn notify-unsubscribe
  "Ends a subscription this client opened.

   On stdio a client cancels a stream by referencing the id of the
   subscriptions/listen request that opened it, which is what this sends.

   Args:
     context         - The client session context
     subscription-id - The id of the subscriptions/listen request

   Returns:
     nil"
  [context subscription-id]
  (send-notification context "cancelled" {:request-id subscription-id}))

(defn subscription-id
  "Returns the subscription a notification arrived on.

   Args:
     context - The client session context, inside a notification handler

   Returns:
     The subscription id, or nil for a notification that did not arrive on a
     subscription stream, such as progress on an in-flight request."
  [context]
  (-> context :message :params :_meta (get protocol/meta-subscription-id)))

(defn request-discover
  "Asks a 2026-07-28 server what it supports.

   This replaces the initialize handshake, with one important difference: it
   is an ordinary request that may be sent at any time, and its result is
   cacheable. Calling it is optional, since a stateless client can go straight
   to tools/call, but it is how you learn a server's capabilities and it
   doubles as a backward-compatibility probe on STDIO.

   The server's answer is stored on the session and the :on-initialized
   callback runs, which by default fetches the prompt, resource and tool
   lists.

   This does not switch protocol versions for you. If the server does not list
   the version this session speaks, that is reported through
   `server-supports-protocol-version?` and the choice of what to do about it is
   yours, because moving between 2026-07-28 and the handshake revisions is a
   change of mode rather than of version.

   Args:
     context - The client session context

   Returns:
     A promise that resolves once the server description is stored."
  [context]
  (let [{:keys [session]} context]
    (-> (call-method context {:method "server/discover"})
        (p/then (fn [{:keys [supported-versions capabilities instructions]}]
                  (swap! session assoc
                         :server-supported-protocol-versions supported-versions
                         :server-capabilities capabilities
                         :server-instructions instructions)
                  ((user-callback :on-initialized) context))))))

(defn server-supports-protocol-version?
  "Returns true when the server named this session's protocol version in its
   discovery result.

   Returns nil when discovery has not run, which is not the same as false.

   Args:
     context - The client session context

   Returns:
     true, false, or nil if server/discover has not been called."
  [context]
  (let [{:keys [session]} context
        {:keys [server-supported-protocol-versions protocol-version]} @session]
    (when (some? server-supported-protocol-versions)
      (contains? (set server-supported-protocol-versions) protocol-version))))

(defn send-first-handshake-message
  "Sends the initial handshake message to establish the MCP connection.
   Initializes the session with server capabilities and triggers the on-initialized callback
   upon receiving the server's response.
   (see https://modelcontextprotocol.io/specification/2025-11-25/architecture#capability-negotiation)

   Args:
     context - The client session context

   Returns:
     nil"
  [context]
  (let [{:keys [session]} context
        {:keys [client-info
                client-capabilities
                protocol-version]} @session]
    (-> (json-rpc/call-remote-method context {:method "initialize"
                                              :params {:client-info client-info
                                                       :capabilities client-capabilities
                                                       :protocol-version protocol-version}})
        (p/then (fn [{:keys [protocol-version capabilities server-info]}]
                  (swap! session assoc
                         :server-protocol-version protocol-version
                         :server-capabilities capabilities
                         :server-info server-info
                         :initialized true
                         :handler-by-method client.handler/handler-by-method-post-initialization)
                  (json-rpc/send-message context (json-rpc/notification "initialized"))
                  ((user-callback :on-initialized) context)))))
  nil)

(defn- default-on-initialized [context]
  (request-prompt-list context)
  (request-resource-list context)
  (request-tool-list context))

(defn create-session
  "Returns the state of a newly created session.

   Options:
     :client-info         - Map identifying this client, :name and :version
     :client-capabilities - What this client supports. An empty map means none.
     :protocol-version    - The revision to speak. Pass \"2026-07-28\" for a
                            stateless session, which sends no handshake, calls
                            server/discover instead of initialize, and puts its
                            version and capabilities on every request.
     :roots               - Vector of roots this client exposes
     :log-level           - 2026-07-28 only. Opt in to log notifications by
                            naming a level; a server must not send any without
                            it. Replaces the logging/setLevel request.
     :max-round-trips     - 2026-07-28 only. How many times one request may come
                            back asking for input before the client gives up.
                            Defaults to 8.

     :on-subscription-acknowledged - 2026-07-28. Called when a server accepts a
                                     subscription, with the filter it agreed to
                                     honour. Worth checking, since a type the
                                     server cannot support is omitted rather
                                     than refused.
     :on-elicitation-requested - 2026-07-28. Called as (f context params) when a
                                 server asks the user for input. Returns the
                                 answer, or a promise of it.
     :on-sampling-requested    - Called as (f context params) when a server asks
                                 for a model completion. On the handshake
                                 revisions this arrives as an inbound request;
                                 on 2026-07-28 it arrives through the multi
                                 round-trip loop and is answered the same way.

   Roots requests are answered from :roots automatically and need no callback."
  [{:keys [client-info
           client-capabilities
           protocol-version
           roots
           log-level
           max-round-trips
           on-initialized
           on-subscription-acknowledged
           on-elicitation-requested
           on-sampling-requested
           on-server-progress
           on-server-log
           on-server-prompt-list-changed
           on-server-prompt-list-updated
           on-server-resource-changed
           on-server-resource-list-changed
           on-server-resource-list-updated
           on-server-tool-list-changed
           on-server-tool-list-updated]
    :or {client-info {:name "mcp-toolkit"
                      :version "2026-07-28"}
         client-capabilities {:roots {:list-changed true}}
         protocol-version "2025-11-25"
         on-initialized default-on-initialized
         on-server-prompt-list-changed request-prompt-list
         on-server-resource-list-changed request-resource-list
         on-server-tool-list-changed request-tool-list}}]
  (let [stateless (protocol/stateless? protocol-version)]
    {:client-info client-info
     :client-capabilities client-capabilities
     :protocol-version protocol-version
   ;; A stateless session has no handshake to complete, so it is usable
   ;; immediately and its dispatch table is live from the start.
     :initialized stateless
     :on-initialized on-initialized
     :handler-by-method (if stateless
                          client.handler-2026/handler-by-method
                          client.handler/handler-by-method-pre-initialization)
     :log-level log-level
     :max-round-trips max-round-trips
     :on-subscription-acknowledged on-subscription-acknowledged
     :on-elicitation-requested on-elicitation-requested
     :root-by-uri (mc/index-by :uri roots)
     :server-prompt-by-name {}
     :server-resource-by-uri {}
     :server-tool-by-name {}
     :on-sampling-requested on-sampling-requested
     :on-server-progress on-server-progress
     :on-server-log on-server-log
     :on-server-prompt-list-changed on-server-prompt-list-changed
     :on-server-prompt-list-updated on-server-prompt-list-updated
     :on-server-resource-changed on-server-resource-changed
     :on-server-resource-list-changed on-server-resource-list-changed
     :on-server-resource-list-updated on-server-resource-list-updated
     :on-server-tool-list-changed on-server-tool-list-changed
     :on-server-tool-list-updated on-server-tool-list-updated
     :last-called-method-id -1 ;; Used for calling methods on the remote site
     :handler-by-called-method-id {}})) ;; The response handlers
