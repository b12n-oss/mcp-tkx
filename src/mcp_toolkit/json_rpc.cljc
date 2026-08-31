(ns mcp-toolkit.json-rpc
  (:require
   [promesa.core :as p]))

;; https://www.jsonrpc.org/specification
;; RPC call with invalid JSON:
(def parse-error-response
  {:jsonrpc "2.0"
   :error {:code -32700
           :message "Parse error"}
   :id nil})

;; RPC call of non-existent method:
(defn method-not-found-response
  "Creates a JSON-RPC error response for when a requested method is not found.

   Args:
     id - The request ID from the original method call

   Returns:
     A JSON-RPC error response map with method not found error (-32601)."
  [id]
  {:jsonrpc "2.0"
   :error {:code -32601
           :message "Method not found"}
   :id id})

(defn internal-error-response
  "Creates a JSON-RPC error response for a handler that failed.

   A handler that throws, or returns a rejected promise, has no reply of its
   own to send. Without this the caller waits forever on a request the server
   has already given up on, which is worse than an error.

   Args:
     id        - The request ID from the original method call
     exception - The exception the handler failed with

   Returns:
     A JSON-RPC error response map with internal error (-32603)."
  [id exception]
  {:jsonrpc "2.0"
   :error (cond-> {:code -32603
                   :message "Internal error"}
            (some? (ex-message exception))
            (assoc :data {:message (ex-message exception)}))
   :id id})

;; RPC call with invalid Request object:
(def invalid-request-response
  {:jsonrpc "2.0"
   :error {:code -32600
           :message "Invalid Request"}
   :id nil})

(defn resource-not-found
  "Creates a JSON-RPC error response for when a requested resource is not found.

   Args:
     id  - The request ID from the original method call
     uri - The URI of the resource that was not found

   Returns:
     A JSON-RPC error response map with resource not found error (-32002)."
  [id uri]
  {:jsonrpc "2.0"
   :error {:code -32002
           :message "Resource not found"
           :data {:uri uri}}
   :id id})

(defn invalid-params-response
  "Creates a JSON-RPC error response for a request whose params are invalid.

   Args:
     id      - The request ID from the original method call
     message - What was wrong with the params

   Returns:
     A JSON-RPC error response map with invalid params error (-32602)."
  [id message]
  {:jsonrpc "2.0"
   :error {:code -32602
           :message message}
   :id id})

(defn invalid-tool-name
  "Creates a JSON-RPC error response for when a tool name is invalid or unknown.

   Args:
     id        - The request ID from the original method call
     tool-name - The name of the tool that was invalid/unknown

   Returns:
     A JSON-RPC error response map with invalid params error (-32602)."
  [id tool-name]
  {:jsonrpc "2.0"
   :error {:code -32602
           :message (str "Unknown tool: " tool-name)}
   :id id})

(defn notification
  "Creates a JSON-RPC notification message.

   Args:
     topic  - The notification topic (string)
     params - (Optional) Parameters map to include with the notification

   Returns:
     A JSON-RPC notification map with method set to 'notifications/<topic>'."
  ([topic]
   {:jsonrpc "2.0"
    :method (str "notifications/" topic)})
  ([topic params]
   (-> (notification topic)
       (assoc :params params))))

(def hold-open
  "Returned by a handler that takes ownership of its request instead of
   answering it.

   Nearly every request gets exactly one response. `subscriptions/listen` does
   not: the pending request IS the notification stream, and its response
   arrives only when the subscription closes gracefully, or never at all if the
   transport drops. A handler returning this sends nothing now.

   Returning nil would not work, since nil is a perfectly good result and gets
   wrapped as one."
  ::hold-open)

(defn call-remote-method
  "Calls a remote method via JSON-RPC.
   Returns a promise which either resolves with the message's result or
   rejects with the message's error.

   Args:
     context - The session context containing session state and send-message function
     message - Map with :method and optional :params keys

   Returns:
     A promise that resolves to the method result or rejects with the error."
  [context message]
  (let [{:keys [session send-message]} context
        ;; Picks a unique method id for a remote call. Robust to concurrent calls.
        ;; TODO: ensure it loops when reaching the maximum integer value.
        called-method-id (-> (swap! session update :last-called-method-id inc)
                             :last-called-method-id)]
    (p/create
     (fn [resolve reject]
       (let [response-handler (fn [{:keys [session message]}]
                                (swap! session update :handler-by-called-method-id dissoc called-method-id)
                                (if (contains? message :error)
                                  (reject (ex-info "error" (:error message)))
                                  (resolve (:result message))))]
         (swap! session update :handler-by-called-method-id assoc called-method-id response-handler)
         (send-message (-> message
                           (assoc :jsonrpc "2.0"
                                  :id called-method-id))))))))

(defn send-message
  "Sends a message using the context's send-message function.

   Args:
     context - The session context containing the send-message function
     message - The message to send

   Returns:
     The result of calling the send-message function."
  [context message]
  (let [{:keys [send-message]} context]
    (send-message message)))

(defn close-connection
  "Closes the connection if a close-connection function is available in the context.

   Args:
     context - The session context that may contain a close-connection function

   Returns:
     The result of calling close-connection, or nil if not available."
  [context]
  (when-some [session (:session context)]
    ;; Settle every in-flight remote call before the transport goes away.
    ;; call-remote-method returns a promise that resolves only when a matching
    ;; response arrives, and there is no timeout, so without this every
    ;; request-sampling, request-elicitation and request-root-list outstanding
    ;; at disconnect stayed pending forever and leaked its registry entry.
    ;; The snapshot is taken first because each handler removes its own entry.
    (let [pending (:handler-by-called-method-id @session)]
      (doseq [[_ response-handler] pending]
        (response-handler (assoc context
                                 :message {:error {:code -32603
                                                   :message "Connection closed"}})))
      (swap! session assoc :handler-by-called-method-id {}))
    ;; Subscriptions go too. close-subscription! is deliberately not used here:
    ;; it writes a closing response down a channel that is already gone.
    (swap! session assoc :subscription-by-id {}))
  (when-some [close-connection (:close-connection context)]
    (close-connection)))

(defn- route-message
  "Returns a Promesa promise which handles a given json-rpc-message."
  [{:keys [session message]
    :as context}]
  (if (contains? message :method)
    (let [{:keys [id method]} message
          handler (-> @session :handler-by-method (get method))]
      (if (nil? handler)
        ;; JSON-RPC: a notification (no id) never gets a reply, even when
        ;; the method is unknown; only method calls get the -32601.
        (when (some? id)
          (method-not-found-response id))
        (if (nil? id)
          ;; Notification, shall not return a result
          (do
            (handler context)
            nil)
          ;; Method call, cancellable, with result value when not cancelled
          (let [is-cancelled (atom false)
                context (assoc context :is-cancelled is-cancelled)]
            (swap! session update :is-cancelled-by-request-id assoc id is-cancelled)
            (-> (handler context)
                (p/then (fn [result]
                          (when-not @is-cancelled
                            (cond
                              ;; The handler is keeping this request open and
                              ;; will answer later, or not at all.
                              (= hold-open result)
                              nil

                              ;; The handler returned a full JSON-RPC response
                              ;; (e.g. invalid-tool-name, resource-not-found),
                              ;; send it as-is instead of nesting it in :result.
                              (and (map? result)
                                   (contains? result :jsonrpc)
                                   (or (contains? result :error)
                                       (contains? result :result)))
                              result

                              :else
                              {:jsonrpc "2.0"
                               :result result
                               :id id}))))
                (p/handle (fn [result error]
                            ;; Clean up, side effect
                            (swap! session update :is-cancelled-by-request-id dissoc id)

                            ;; Pass through as if this p/handle was not there.
                            ;; We avoided using p/finally because it does not allow chaining further promises.
                            (or error result)))
                ;; A handler that failed still owes the client a reply.
                ;; Without this the request goes unanswered and the caller
                ;; waits on a response the server will never send. A cancelled
                ;; request is the exception: it has already been answered.
                (p/catch (fn [exception]
                           (when-not @is-cancelled
                             (internal-error-response id exception)))))))))
    ;; Method call response
    (if (and (contains? message :id)
             (or (contains? message :result)
                 (contains? message :error)))
      (if-some [handler (-> @session
                            :handler-by-called-method-id
                            (get (:id message)))]
        (do
          (handler context)
          nil)
        ;; TODO: handle the case where the id is unknown to us.
        nil)
      ;; TODO: handle the message's structural problem.
      nil)))

(defn handle-message
  "Handles incoming JSON-RPC messages. As of protocol version 2025-06-18,
   batch requests are no longer supported and will return an error.

   Args:
     context - The context, containing session and send-message
     message - The message to handle

   Returns:
     A promise that resolves when message handling is complete."
  [context message]
  (let [{:keys [send-message]} context]
    (if (vector? message)
      ;; Batch requests are not supported as of 2025-06-18
      ;; Return an Invalid Request error
      (send-message invalid-request-response)
      ;; It is a single message
      (-> (route-message (assoc context :message message))
          (p/then (fn [response]
                    (when (some? response)
                      (send-message response))))))))
