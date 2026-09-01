(ns example.transport.streamable-http-2026
  "A 2026-07-28 Streamable HTTP transport.

   Much smaller than the 2025 transport next door, because that revision
   deleted most of what makes the older one big. There is no session and no
   Mcp-Session-Id header, no GET endpoint, and no SSE resumability, so no
   event log and no Last-Event-Id. A broken stream loses its request and the
   client re-issues it.

   What is left is POST, and one long-lived response stream per
   subscriptions/listen. Host, Origin and Content-Type validation carry over
   unchanged, along with the JSON encoding, so those are reused from the 2025
   namespace rather than copied.

   Known limitation, stated rather than hidden: subscription channels are
   keyed by the JSON-RPC id the client chose, and one server session is shared
   by every connection. Two clients that both pick id 1 therefore contend for
   the same key. The library refuses the second with -32602 and the first keeps
   its stream, so nobody is silently displaced, but the second client cannot
   subscribe at all until the first is done. A real deployment wants a session
   per client, or a transport-minted key. It is left simple here because the
   point of the example is the mechanism."
  (:require
   [example.transport.streamable-http :as http-2025]
   [mcp-toolkit.json-rpc :as json-rpc]
   [mcp-toolkit.protocol :as protocol]
   [org.httpkit.server :as http-kit]
   [promesa.core :as p]
   [taoensso.telemere :as tel]))

(def ^:private sse-headers
  {"Content-Type" "text/event-stream"
   "Cache-Control" "no-cache, no-transform"})

(def ^:private json-headers
  {"content-type" "application/json"})

(def ^:private accepted-response
  {:status 202
   :headers {"content-type" "text/plain"}
   :body "Accepted"})

(defn- send-frame!
  [channel message]
  (http-kit/send! channel (str "data: " (http-2025/->json message) "\n\n") false))

;; ── Subscription channels ───────────────────────────────────────────────────
;; A subscriptions/listen POST never gets an ordinary response. Its channel is
;; the stream, so it is held here until the subscription ends and every
;; notification the library fans out to that subscription is written to it.

(defn- register-channel!
  [ctx subscription-id channel]
  (swap! (::channels ctx) assoc subscription-id channel))

(defn- unregister-channel!
  [ctx subscription-id]
  (swap! (::channels ctx) dissoc subscription-id))

(defn- release-channel!
  "Drops this channel's registration, but only if it is the one registered,
   and reports whether it was.

   A second client that collides on an id it does not own must not evict the
   first. Before this existed, a colliding listen registered over the original
   channel, the library's refusal carried :error rather than :result so the
   stream-closing branch never fired, and when the refused connection finally
   dropped its :on-close removed the original subscriber's entry from the
   session. The first client stopped receiving with no error and no way to
   tell."
  [ctx subscription-id channel]
  (let [[before _] (swap-vals! (::channels ctx)
                               (fn [channels]
                                 (if (identical? channel (get channels subscription-id))
                                   (dissoc channels subscription-id)
                                   channels)))]
    (identical? channel (get before subscription-id))))

(defn- subscription-channel
  [ctx subscription-id]
  (get @(::channels ctx) subscription-id))

(defn subscription-send-message
  "The send-message a server uses for notifications it raises on its own.

   Every message the library produces for a subscription carries that
   subscription's id in _meta, which is exactly what is needed to pick the
   channel it belongs on. A message with no subscription id has no stream to
   go to and is dropped, with a log line, since 2026-07-28 has no ambient
   connection to fall back on."
  [ctx]
  (fn [message]
    (let [subscription-id (-> message :params :_meta
                              (get protocol/meta-subscription-id))]
      (if-some [channel (and subscription-id (subscription-channel ctx subscription-id))]
        (send-frame! channel message)
        (tel/log! {:level :debug
                   :id :sht26/no-stream
                   :data {:method (:method message)
                          :subscription-id subscription-id}})))))

;; ── POST ────────────────────────────────────────────────────────────────────

(defn- subscription-request? [message]
  (= "subscriptions/listen" (:method message)))

(defn- handle-subscription
  "Serves a subscriptions/listen POST.

   The response stream opens immediately and stays open. The library's handler
   returns hold-open, so no response is written now; the one that eventually
   closes the stream is written when the server ends the subscription."
  [ctx req message]
  (let [subscription-id (:id message)]
    (http-kit/as-channel
     req
     {:on-open
      (fn [channel]
        (http-kit/send! channel {:status 200
                                 :headers sse-headers} false)
        ;; Only claim the id if it is free. The library refuses a duplicate
        ;; below, and registering first would evict the client that holds it.
        (when-not (subscription-channel ctx subscription-id)
          (register-channel! ctx subscription-id channel))
        (let [mcp-context
              {:session (:session ctx)
               :send-message
               (fn [outgoing]
                 (send-frame! channel outgoing)
                 ;; The response to the listen request itself ends the stream.
                 (when (and (= subscription-id (:id outgoing))
                            (contains? outgoing :result))
                   (unregister-channel! ctx subscription-id)
                   (http-kit/close channel)))
               :close-connection (fn [] (http-kit/close channel))}]
          (-> (json-rpc/handle-message mcp-context message)
              (p/catch (fn [error]
                         (tel/log! {:level :error
                                    :id :sht26/subscription-error
                                    :data {:err (ex-message error)}})
                         (http-kit/close channel))))))
      :on-close
      (fn [channel _status]
        ;; The client went away. Drop the subscription so the server stops
        ;; producing for a stream nobody is reading, but only when this channel
        ;; is the one that owns the id. A refused, colliding listen also lands
        ;; here, and it must leave the original subscriber alone.
        (when (release-channel! ctx subscription-id channel)
          (swap! (:session ctx) update :subscription-by-id dissoc subscription-id)))})))

(defn- handle-plain-request
  "Serves an ordinary request. One request, one JSON response.

   No JSON-to-SSE flip is needed here. Progress and log notifications for a
   request would belong on its response stream, which this example does not
   implement, so anything the handler emits before its result is logged and
   dropped rather than silently misdelivered."
  [ctx req message]
  (http-kit/as-channel
   req
   {:on-open
    (fn [channel]
      (let [response (atom nil)
            mcp-context
            {:session (:session ctx)
             :send-message (fn [outgoing]
                             (if (= (:id message) (:id outgoing))
                               (reset! response outgoing)
                               (tel/log! {:level :debug
                                          :id :sht26/dropped-request-scoped
                                          :data {:method (:method outgoing)}})))
             :close-connection (fn [] (http-kit/close channel))}]
        (-> (json-rpc/handle-message mcp-context message)
            (p/then (fn [_]
                      (http-kit/send! channel
                                      (if-some [body @response]
                                        {:status 200
                                         :headers json-headers
                                         :body (http-2025/->json body)}
                                        accepted-response)
                                      true)))
            (p/catch (fn [error]
                       (tel/log! {:level :error
                                  :id :sht26/request-error
                                  :data {:err (ex-message error)}})
                       (http-kit/close channel))))))}))

(defn handle-post
  [ctx req]
  (let [validation-ctx (assoc ctx :req req)]
    (or (cond
          (not (http-2025/valid-host? validation-ctx))
          (http-2025/error-response "Invalid Host header" 421)

          (not (http-2025/valid-origin? validation-ctx))
          (http-2025/error-response "Invalid Origin header" 400)

          (not (http-2025/valid-content-type? validation-ctx))
          (http-2025/error-response "Invalid Content-Type header" 400)

          :else nil)
        (if-some [message (http-2025/parse-message validation-ctx)]
          (cond
            (subscription-request? message) (handle-subscription ctx req message)
            (contains? message :id) (handle-plain-request ctx req message)
            :else (do @(json-rpc/handle-message
                        {:session (:session ctx)
                         :send-message (subscription-send-message ctx)}
                        message)
                      accepted-response))
          (http-2025/error-response "Could not parse message" 400)))))

;; ── Lifecycle / routes ──────────────────────────────────────────────────────

(defn ctx-start
  "Prepares a transport context.

   Unlike the 2025 transport there is no session pool, because there are no
   sessions. One server session is shared, and the only per-connection state
   is the set of open subscription streams."
  [ctx]
  (assoc ctx ::channels (atom {})))

(defn server-context
  "The context to hand application code that raises notifications, for example
   after add-tool or when a watched resource changes."
  [ctx]
  {:session (:session ctx)
   :send-message (subscription-send-message ctx)})

(defn routes
  [ctx]
  ["" ["/health" {:get (fn [_req] {:status 200
                                   :headers {"content-type" "text/plain"}
                                   :body "ok"})}]
   ["/mcp" {:post (fn [req] (handle-post ctx req))
            ;; 2026-07-28 removed the GET endpoint. subscriptions/listen
            ;; replaced it, and it is a POST.
            :get (fn [_req] (http-2025/error-response "Method Not Allowed" 405))
            :delete (fn [_req] (http-2025/error-response "Method Not Allowed" 405))}]])
