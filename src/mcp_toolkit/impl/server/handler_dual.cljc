(ns ^:no-doc mcp-toolkit.impl.server.handler-dual
  "Dispatch for a server that answers both eras on one endpoint.

   The specification allows a server to serve handshake and stateless clients
   at once, choosing per request: a request carrying a protocol version in
   `_meta` gets stateless semantics, an `initialize` gets the handshake.

   The obvious implementation does not work. Merging the two tables and
   letting `notifications/initialized` swap in the post-handshake table would
   serve the first handshake client and break every stateless one, because
   that swap replaces the dispatch table on shared session state. So the dual
   table is fixed for the session's life, and the handshake table it delegates
   to moves underneath it on `:legacy-handler-by-method` instead.

   Nothing here is per-connection, which is correct for the two shapes the
   specification describes. On stdio a process is one connection. On HTTP the
   handshake era has session ids, so a transport gives each handshake client
   its own session while stateless requests share one."
  (:require
   [mcp-toolkit.impl.common :refer [user-callback]]
   [mcp-toolkit.impl.server.handler :as handler]
   [mcp-toolkit.impl.server.handler-2026 :as handler-2026]
   [mcp-toolkit.json-rpc :as json-rpc]
   [mcp-toolkit.protocol :as protocol]))

(defn modern-request?
  "True when a request should be served statelessly.

   Declaring a protocol version in `_meta` is what marks a request as modern;
   the stateless path then decides whether it can serve that version. A
   request without one is a handshake request, which is why the stateless
   handler's leniency about a missing version cannot be relied on here.

   `server/discover` is the exception. A stateless client may probe with it
   before it knows anything about the server, so it carries no version, and
   the handshake era has no such method to confuse it with.

   Args:
     message - The incoming JSON-RPC message

   Returns:
     true for a stateless request."
  [message]
  (or (= "server/discover" (:method message))
      (some? (-> message :params :_meta (get protocol/meta-protocol-version)))))

(defn- legacy-handler
  "The handshake handler for a method, read from wherever the handshake era
   has got to. Pre-handshake a session serves almost nothing; afterwards it
   serves the features."
  [session method]
  (get (:legacy-handler-by-method @session) method))

(defn- dispatch
  [method modern]
  (fn [{:keys [session message]
        :as context}]
    (if (modern-request? message)
      (if (some? modern)
        (modern context)
        ;; A method this revision removed, such as ping, asked for by a
        ;; stateless client. It is gone for them even though the handshake
        ;; table next door still answers it.
        (json-rpc/method-not-found-response (:id message)))
      (if-some [legacy (legacy-handler session method)]
        (legacy context)
        (json-rpc/method-not-found-response (:id message))))))

(defn initialized-notification-handler
  "Completes a handshake without disturbing the dual table.

   The single-era handler assigns `:handler-by-method`, which here holds the
   dual dispatch itself. Writing the post-handshake table there would strand
   every stateless client on this session."
  [{:keys [session]
    :as context}]
  (swap! session assoc
         :initialized true
         :legacy-handler-by-method handler/handler-by-method-post-initialization)
  ((user-callback :on-initialized) context))

(def legacy-handler-by-method-pre-initialization
  "The handshake table a dual session starts on, with the one handler that
   would otherwise clobber the dual dispatch replaced."
  (assoc handler/handler-by-method-pre-initialization
         "notifications/initialized" initialized-notification-handler))

(def handler-by-method
  "The dual dispatch table.

   Its keys are every method either era serves, and its values choose an era
   per request. The table itself never changes."
  (let [methods (into #{}
                      (concat (keys handler-2026/handler-by-method)
                              (keys handler/handler-by-method-pre-initialization)
                              (keys handler/handler-by-method-post-initialization)))]
    (into {}
          (map (fn [method]
                 [method (dispatch method (get handler-2026/handler-by-method method))]))
          methods)))
