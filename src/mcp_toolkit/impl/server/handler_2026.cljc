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
   [mcp-toolkit.impl.mrtr :as mrtr]
   [mcp-toolkit.impl.server.handler :as handler]
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

(defn- unsupported-protocol-version-response
  [id requested]
  {:jsonrpc "2.0"
   :id id
   :error {:code protocol/unsupported-protocol-version-code
           :message "Unsupported protocol version"
           :data {:supported protocol/supported-protocol-versions
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
      ;; subscriptions/listen, which this library does not implement yet.
      (seq resource-by-uri) (assoc :resources {:list-changed true})
      (seq tool-by-name)    (assoc :tools {:list-changed true}))))

(defn discover-handler
  "Handles `server/discover`, the replacement for the initialize handshake.

   Args:
     context - The handler context

   Returns:
     A DiscoverResult naming every protocol version this server speaks."
  [{:keys [session]}]
  (let [{:keys [server-instructions]} @session]
    (cond-> {:supported-versions protocol/supported-protocol-versions
             :capabilities (server-capabilities session)}
      (some? server-instructions) (assoc :instructions server-instructions))))

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
               (not (contains? (set protocol/supported-protocol-versions) requested)))
        (unsupported-protocol-version-response id requested)
        (-> (p/do (handler (with-request-context context message)))
            (p/then (fn [result]
                      (->> (decorate-result session method result)
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
      (assoc "server/discover" (wrap-handler "server/discover" discover-handler))))

(defn log-enabled?
  "Returns true when the current request opted in to log notifications.

   The revision is explicit that a server must not emit
   `notifications/message` for a request that did not set `_meta` logLevel.

   Args:
     context - The handler context

   Returns:
     true when this request asked for logs."
  [context]
  (some? (:log-level context)))

(defn client-capabilities
  "Returns the capabilities the current request declared.

   Args:
     context - The handler context

   Returns:
     The ClientCapabilities map, or nil."
  [context]
  (:client-capabilities context))

(defn input-required
  "Re-exported from `mcp-toolkit.impl.mrtr` so 2026-07-28 handlers have one
   place to reach for."
  [opts]
  (mrtr/input-required opts))
