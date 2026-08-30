(ns ^:no-doc mcp-toolkit.impl.server.handler-2026
  "Server request handling for protocol revision 2026-07-28.

   That revision is stateless. There is no initialize handshake and no
   protocol-level session, so a request carries its own protocol version,
   client capabilities and client identity in `_meta`. Servers advertise
   themselves through `server/discover` instead.

   Rather than reimplement the feature handlers, this namespace wraps the
   existing ones. The wrapper reads the per-request `_meta`, checks the
   protocol version, and decorates whatever the handler returns with the
   fields the revision requires."
  (:require
   [mcp-toolkit.impl.server.handler :as handler]
   [mcp-toolkit.impl.subscriptions :as subscriptions]
   [mcp-toolkit.json-rpc :as json-rpc]
   [mcp-toolkit.protocol :as protocol]
   [promesa.core :as p]))

(def removed-methods
  "Methods 2026-07-28 deleted outright.

   Logging level moved onto each request as `_meta` logLevel, resource
   subscriptions were replaced by `subscriptions/listen`, and ping and the
   roots list-changed notification went away with the session."
  #{"ping"
    "logging/setLevel"
    "resources/subscribe"
    "resources/unsubscribe"
    "notifications/roots/list_changed"})

(def default-cache-policy
  "Freshness hints for the six results the revision marks CacheableResult.

   These are policy rather than protocol, so they are only defaults. A session
   may override any entry with :cache-policy, and a handler that sets :ttl-ms
   or :cache-scope itself always wins."
  {"server/discover"          {:ttl-ms 3600000
                               :cache-scope "public"}
   "tools/list"               {:ttl-ms 60000
                               :cache-scope "public"}
   "prompts/list"             {:ttl-ms 60000
                               :cache-scope "public"}
   "resources/list"           {:ttl-ms 60000
                               :cache-scope "public"}
   "resources/templates/list" {:ttl-ms 60000
                               :cache-scope "public"}
   "resources/read"           {:ttl-ms 5000
                               :cache-scope "private"}})

(defn- full-response?
  "True for a complete JSON-RPC response, which route-message forwards as-is
   instead of nesting under :result."
  [m]
  (and (map? m)
       (contains? m :jsonrpc)
       (or (contains? m :error) (contains? m :result))))

(defn- remap-error-code
  "Rewrites error codes that were renumbered by this revision.

   A missing resource is Invalid Params now, aligning with JSON-RPC, rather
   than the MCP-specific code earlier revisions used."
  [response]
  (if (= protocol/legacy-resource-not-found-code (get-in response [:error :code]))
    (assoc-in response [:error :code] protocol/invalid-params-code)
    response))

(defn- cache-fields
  [session method]
  (get (merge default-cache-policy (:cache-policy @session)) method))

(defn decorate-result
  "Adds the fields every 2026-07-28 result carries.

   Each result states its own :result-type, and an interim multi round-trip
   result keeps the \"input_required\" its handler already set. Servers should
   identify themselves in `_meta`, and the six cacheable results also carry a
   freshness hint.

   Args:
     session - The server session atom
     method  - The method whose result this is
     result  - Whatever the wrapped handler returned

   Returns:
     The decorated result, or the input untouched when it is not a result."
  [session method result]
  (if (or (not (map? result)) (full-response? result))
    result
    (-> (merge (cache-fields session method) result)
        (update :result-type (fn [result-type] (or result-type "complete")))
        (assoc :_meta (merge {protocol/meta-server-info (:server-info @session)}
                             (:_meta result))))))

(def ^:private list-result-ordering
  "The collection each list result returns, and the field that gives it a
   stable order.

   This revision asks servers to return list results in a deterministic order,
   so clients can cache them and so repeated LLM prompts hit their own caches.
   The underlying handlers read from hash maps, whose iteration order is a
   function of the keys rather than of declaration order. That is already
   deterministic for a fixed set of names, but it is not stable across a
   ClojureScript build, nor across an add-tool that reshapes the map. Sorting
   on a field the protocol already requires to be unique costs little and
   removes the question."
  {"tools/list"               [:tools :name]
   "prompts/list"             [:prompts :name]
   "resources/list"           [:resources :uri]
   "resources/templates/list" [:resource-templates :uri-template]})

(defn- order-list-result
  [method result]
  (if-some [[collection-key sort-key] (get list-result-ordering method)]
    (cond-> result
      (contains? result collection-key)
      (update collection-key (fn [items] (vec (sort-by sort-key items)))))
    result))

(defn modern-protocol-versions
  "The versions this session can serve statelessly.

   Not the same as what it serves overall. A dual-era session also answers
   handshake clients, but a request arriving on the modern path may only
   declare a version the modern path actually implements. Validating against
   the wider list would accept a handshake version in `_meta` and then answer
   it with stateless semantics."
  [session]
  (or (:modern-protocol-versions @session)
      (:server-supported-protocol-versions @session)))

(defn- unsupported-protocol-version-response
  [session id requested]
  {:jsonrpc "2.0"
   :id id
   :error {:code protocol/unsupported-protocol-version-code
           :message "Unsupported protocol version"
           :data {:supported (modern-protocol-versions session)
                  :requested requested}}})

(defn- request-meta
  [message]
  (-> message :params :_meta))

(defn- with-request-context
  "Puts the per-request `_meta` onto the context.

   This deliberately does not touch the session. The protocol is stateless, so
   two concurrent requests can carry different capabilities and different
   identities, and writing either onto shared state would let them race."
  [context message]
  (let [meta-fields (request-meta message)]
    (assoc context
           :protocol-version (get meta-fields protocol/meta-protocol-version)
           :client-capabilities (get meta-fields protocol/meta-client-capabilities)
           :client-info (get meta-fields protocol/meta-client-info)
           :log-level (get meta-fields protocol/meta-log-level))))

(defn- server-capabilities
  "Derives capabilities from what the session actually holds.

   The handshake handler advertises a fixed map, which is harmless when a
   client can simply call a list method and get nothing back. Discovery is
   different: it is the only place a 2026-07-28 client learns capabilities,
   and its result is cacheable for as long as the server says, so claiming a
   feature that is not there is a durable lie."
  [session]
  (let [{:keys [prompt-by-name resource-by-uri tool-by-name]} @session]
    (cond-> {:completions {}
             :logging {}}
      (seq prompt-by-name)  (assoc :prompts {:list-changed true})
      ;; No :subscribe here. This revision replaced resources/subscribe with
      ;; subscriptions/listen, which lives at "subscriptions/listen" in the
      ;; table below rather than in this capability map.
      (seq resource-by-uri) (assoc :resources {:list-changed true})
      (seq tool-by-name)    (assoc :tools {:list-changed true}))))

(defn discover-handler
  "Handles `server/discover`, the replacement for the initialize handshake.

   Args:
     context - The handler context

   Returns:
     A DiscoverResult naming every protocol version this server speaks."
  [{:keys [session]}]
  (let [{:keys [server-instructions server-supported-protocol-versions]} @session]
    (cond-> {:supported-versions server-supported-protocol-versions
             :capabilities (server-capabilities session)}
      (some? server-instructions) (assoc :instructions server-instructions))))

(defn subscriptions-listen-handler
  "Opens a notification stream.

   The request is not answered here. It stays open and becomes the stream, so
   the handler returns `json-rpc/hold-open` and the response is sent later, by
   `close-subscription!`, if the server ends the stream deliberately.

   The acknowledgement goes out first, before this returns, because the spec
   requires it to precede every notification on the subscription. It reports
   the subset of the requested filter the server can actually deliver."
  [{:keys [session message]
    :as context}]
  (let [subscription-id (:id message)
        requested (-> message :params :notifications)
        honoured (subscriptions/honoured-filter (server-capabilities session) requested)]
    (swap! session assoc-in [:subscription-by-id subscription-id] honoured)
    (json-rpc/send-message
     context
     (subscriptions/tag {:jsonrpc "2.0"
                         :method "notifications/subscriptions/acknowledged"
                         :params {:notifications honoured}}
                        subscription-id))
    json-rpc/hold-open))

(defn cancelled-notification-handler
  "Cancels a request, and ends a subscription when the id names one.

   On stdio a client cancels a subscription by sending notifications/cancelled
   against the id of the subscriptions/listen request that opened it, so this
   has to serve both purposes."
  [{:keys [session message]
    :as context}]
  (swap! session update :subscription-by-id dissoc (-> message :params :request-id))
  (handler/cancelled-notification-handler context))

(defn initialize-handler
  "Answers a handshake client that reached a stateless server.

   This revision has no initialize, so the method is genuinely absent and a
   bare Method-not-found would be a defensible answer. It is a bad one. A
   handshake client has no way to move forward to a newer revision, so this
   error is likely the only thing it can show a user, and the specification
   asks a modern-only server to name the versions it does support in it.

   UnsupportedProtocolVersion is used rather than Method-not-found because the
   code for this case is implementation-defined, its payload is exactly the
   diagnostic that is wanted, and it is a recognised modern error, so anything
   able to read it also learns which era this server belongs to."
  [{:keys [session message]}]
  {:jsonrpc "2.0"
   :id (:id message)
   :error {:code protocol/unsupported-protocol-version-code
           :message (str "This server does not implement the initialize handshake. "
                         "It speaks a stateless revision, where each request carries "
                         "its own protocol version in _meta.")
           :data {:supported (modern-protocol-versions session)
                  :requested (-> message :params :protocol-version)}}})

(defn wrap-handler
  "Wraps one handler for 2026-07-28.

   Args:
     method  - The method name being wrapped
     handler - The underlying handler

   Returns:
     A handler that version-checks, exposes the request `_meta` on the
     context, and decorates the result."
  [method handler]
  (fn [{:keys [session message]
        :as context}]
    (let [requested (get (request-meta message) protocol/meta-protocol-version)
          id (:id message)]
      (if (and (some? id)
               (some? requested)
               (not (contains? (set (modern-protocol-versions session)) requested)))
        (unsupported-protocol-version-response session id requested)
        (-> (p/do (handler (with-request-context context message)))
            (p/then (fn [result]
                      (->> (order-list-result method result)
                           (decorate-result session method)
                           (remap-error-code)))))))))

(def handler-by-method
  "The dispatch table for 2026-07-28.

   Derived from the handshake-era table so the feature handlers stay shared:
   the methods this revision deleted are dropped, the rest are wrapped, and
   discovery is added."
  (-> (reduce-kv (fn [acc method underlying]
                   (if (contains? removed-methods method)
                     acc
                     (assoc acc method (wrap-handler method underlying))))
                 {}
                 handler/handler-by-method-post-initialization)
      (assoc "server/discover" (wrap-handler "server/discover" discover-handler)
             ;; Not implemented, deliberately answered anyway. See the handler.
             "initialize" (wrap-handler "initialize" initialize-handler)
             "subscriptions/listen" (wrap-handler "subscriptions/listen"
                                                  subscriptions-listen-handler)
             ;; Overrides the shared handler, which knows nothing of streams.
             "notifications/cancelled" (wrap-handler "notifications/cancelled"
                                                     cancelled-notification-handler))))
