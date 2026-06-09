(ns example.transport.streamable-http
  "A 2025-03-26+ compatible Streamable HTTP transport for MCP Toolkit."
  (:require
   [camel-snake-kebab.core :as csk]
   [clojure.string :as str]
   [jsonista.core :as j]
   [mcp-toolkit.json-rpc :as json-rpc]
   [org.httpkit.server :as http-kit]
   [promesa.core :as p]
   [taoensso.telemere :as tel]))

;; ── JSON boundary (camelCase ↔ kebab-case) ──────────────────────────────────
;; MCP uses `_meta` (leading underscore) as a protocol-defined metadata key.
;; camel-snake-kebab strips leading underscores in both directions
;; (_meta → :meta on decode; :_meta → "meta" on encode), which silently drops
;; the progressToken that drives server/notify-progress.
;; Preserve any key starting with "_" verbatim in both directions.
(defn- decode-key [k]
  (if (str/starts-with? k "_")
    (keyword k)
    (csk/->kebab-case-keyword k)))

(defn- encode-key [k]
  (let [n (name k)]
    (if (str/starts-with? n "_")
      n
      (csk/->camelCaseString k))))

(def object-mapper
  (j/object-mapper {:decode-key-fn decode-key
                    :encode-key-fn encode-key}))

(defn parse-message [ctx]
  (try (j/read-value (-> ctx :req :body) object-mapper)
       (catch Exception _ nil)))

(defn ->json [v]
  (j/write-value-as-string v object-mapper))

(defn error-response [msg status & {:keys [_headers]}]
  {:status status
   :headers {"content-type" "text/plain"}
   :body msg})

;; ── Security: Host / Origin / Content-Type validation ───────────────────────
(defn valid-content-type? [ctx]
  (let [ct (get-in ctx [:req :headers "content-type"])]
    (cond
      (str/blank? ct) false
      (not (str/starts-with? ct "application/json")) false
      :else true)))

(defn matches-port-wildcard? [pattern value]
  (when (str/ends-with? pattern ":*")
    (let [base (subs pattern 0 (- (count pattern) 2))]
      (when (str/starts-with? value (str base ":"))
        (let [port-part (subs value (inc (count base)))]
          (boolean (re-matches #"\d+" port-part)))))))

(defn valid-host?
  ([host allowed-hosts]
   (cond
     (str/blank? host) false
     (contains? allowed-hosts host) true
     :else (boolean (some #(matches-port-wildcard? % host) allowed-hosts))))
  ([ctx]
   (valid-host? (get-in ctx [:req :headers "host"])
                (get-in ctx [:settings :allowed-hosts]))))

(defn valid-origin?
  ([origin allowed-origins]
   (cond
     (str/blank? origin) true
     (contains? allowed-origins origin) true
     :else (boolean (some #(matches-port-wildcard? % origin) allowed-origins))))
  ([ctx]
   (valid-origin? (get-in ctx [:req :headers "origin"])
                  (get-in ctx [:settings :allowed-origins]))))

;; ── Session ids ─────────────────────────────────────────────────────────────
(defn new-session-id []
  (str (java.util.UUID/randomUUID)))

;; ── Session pool ────────────────────────────────────────────────────────────
;; A session record (keys namespaced :session/…):
;;   :session/id           session id (UUID string)
;;   :session/data         the mcp-toolkit session atom
;;   :session/get-channel  (atom nil) — the open GET stream channel, or nil
;;   :session/event-log    (atom {:next-id 0 :events []}) — resumability (Phase 4)
(defn assoc-session! [ctx session-id session-data]
  (let [rec {:session/id session-id
             :session/data session-data
             :session/get-channel (atom nil)
             :session/event-log (atom {:next-id 0 :events []})}]
    (swap! (::sessions ctx) assoc session-id rec)
    rec))

(defn fetch-session! [ctx session-id]
  (get @(::sessions ctx) session-id))

(defn dissoc-session! [ctx session-id]
  (swap! (::sessions ctx) dissoc session-id)
  nil)

;; ── The JSON↔SSE flip (pure decision; sink fns injected by the http layer) ───
(defn- final-response?
  "True iff `message` is THE response to this POST's request `request-id`."
  [message request-id]
  (and (= (:id message) request-id)
       (or (contains? message :result) (contains? message :error))
       (not (contains? message :method))))

(defn make-request-send-message
  "Returns [send-message state-atom]. `sink` = {:open-sse! fn, :frame! fn}.
   On the first non-response outbound message the stream flips to SSE; from then
   on every message (including the final response) is framed. A lone final
   response is buffered as the JSON body. After handling, inspect @state:
   {:sse? bool :buffered <response-or-nil>}."
  [request-id {:keys [open-sse! frame!]}]
  (let [state (atom {:sse? false :buffered nil})]
    [(fn [message]
       (cond
         (:sse? @state)                       (frame! message)
         (final-response? message request-id) (swap! state assoc :buffered message)
         :else (do (open-sse!)
                   (swap! state assoc :sse? true)
                   (frame! message))))
     state]))

;; ── Phase-2 accepted-response (reused in Phase 3) ──────────────────────────
(def ^:private accepted-response
  {:status 202 :headers {"content-type" "text/plain"} :body "Accepted"})

;; ── SSE framing ─────────────────────────────────────────────────────────────
(def ^:private base-sse-headers
  {"Content-Type" "text/event-stream"
   "Cache-Control" "no-cache, no-transform"})

(defn- send-sse-headers! [channel session-id]
  (http-kit/send! channel
                  {:status 200 :headers (assoc base-sse-headers "mcp-session-id" session-id)}
                  false))                                   ; false = keep open

;; ── Resumability: per-session bounded event ring ────────────────────────────
(def ^:private max-events 1000)
(def ^:private max-age-ms (* 5 60 1000))

(defn- prune-by-age [events now]
  (filterv (fn [{:keys [ts]}] (<= (- now ts) max-age-ms)) events))

(defn record-event!
  "Allocate a monotonic per-session id, build the SSE frame, append to the
   bounded ring (prune by age, then evict oldest beyond max-events). Logs a
   :warn only on real count-eviction — age-pruning is normal and not logged.
   Returns {:id <n> :frame <sse-string>}.

   Public for testability and for the no-stream buffering path in
   session-default-send-message."
  [session message now]
  (let [new (swap! (:session/event-log session)
                   (fn [{:keys [next-id events]}]
                     (let [id    (inc next-id)
                           frame (str "id: " id "\nevent: message\ndata: " (->json message) "\n\n")
                           base  (conj (prune-by-age events now) {:id id :ts now :frame frame})
                           over  (max 0 (- (count base) max-events))]
                       {:next-id id
                        :events  (if (pos? over) (subvec base over) base)
                        :evicted over})))
        ev  (peek (:events new))]
    (when (pos? (:evicted new))
      (tel/log! {:level :warn :id :sht/event-evicted :data {:evicted (:evicted new)}}))
    {:id (:id ev) :frame (:frame ev)}))

(defn- send-frame! [channel session message]
  (let [{:keys [frame]} (record-event! session message (System/currentTimeMillis))]
    (http-kit/send! channel frame false)))

(defn events-after
  "Buffered SSE frame strings with event id > `last-event-id`, in order.
   NOTE: the ring is bounded (max-events / max-age-ms), so if the requested
   id was already evicted the replay is silently lossy — a client cannot
   distinguish 'nothing missed' from 'missed but evicted'. Acceptable for this
   example; a production server would track the oldest-retained id and signal
   the gap to the client."
  [session last-event-id]
  (->> (:events @(:session/event-log session))
       (filter (fn [{:keys [id]}] (> id last-event-id)))
       (mapv :frame)))

(defn last-event-id
  "Parsed `Last-Event-Id` request header as a long, or nil if absent/invalid."
  [req]
  (some-> (get-in req [:headers "last-event-id"]) parse-long))

;; ── Request POST → as-channel (JSON or SSE) ─────────────────────────────────
(defn- json-response-map [session-id body]
  {:status 200
   :headers {"content-type" "application/json" "mcp-session-id" session-id}
   :body (->json body)})

;; NOTE: `accepted-response` is reused from Phase 2 (P2.T2) — do NOT redeclare it.

(defn- handle-request-over-channel [ctx session req-message]
  (let [request-id (:id req-message)
        session-id (:session/id session)]
    (http-kit/as-channel
     (:req ctx)
     {:on-open
      (fn [channel]
        (let [sink {:open-sse! #(send-sse-headers! channel session-id)
                    :frame!    #(send-frame! channel session %)}
              [send-message state] (make-request-send-message request-id sink)
              mcp-ctx {:session (:session/data session)
                       :send-message send-message
                       :close-connection #(http-kit/close channel)}]
          (-> (json-rpc/handle-message mcp-ctx req-message)
              (p/then
               (fn [_]
                 (let [{:keys [sse? buffered]} @state]
                   (if sse?
                     (http-kit/close channel)               ; response already streamed
                     (http-kit/send! channel
                                     (if buffered (json-response-map session-id buffered)
                                         accepted-response)
                                     true)))))
              (p/catch
               (fn [err]
                 (tel/log! {:level :error :id :sht/request-error
                            :data {:err (ex-message err)}})
                 (http-kit/close channel))))))})))  ; true = close after

(defn- session-default-send-message [session]
  (fn [message]
    (if-let [ch @(:session/get-channel session)]
      (send-frame! ch session message)
      (do (record-event! session message (System/currentTimeMillis))
          (tel/log! {:level :debug :id :sht/buffered-no-stream :data {:method (:method message)}})))))

(defn- run-notification! [session message]
  (let [mcp-ctx {:session (:session/data session)
                 :send-message (session-default-send-message session)}]
    @(json-rpc/handle-message mcp-ctx message)
    accepted-response))

(defn- validate-common
  "Host/Origin checks shared by all verbs. Returns an error-response or nil.
   `ctx` must already have :req assoc'd."
  [ctx]
  (cond
    (not (valid-host? ctx))   (error-response "Invalid Host header" 421)
    (not (valid-origin? ctx)) (error-response "Invalid Origin header" 400)
    :else nil))

;; ── Protocol-Version header gate (≥ 2025-06-18 sessions) ──────────────────
(def ^:private supported-protocol-versions
  #{"2024-11-05" "2025-03-26" "2025-06-18" "2025-11-25"})

(defn- requires-protocol-header? [session]
  (let [pv (:protocol-version @(:session/data session))]
    ;; nil = initialize handshake not yet completed for this session; be
    ;; conservative and require the header (a non-initialize request against
    ;; an un-initialized session is anomalous).
    (or (nil? pv)
        (>= (compare pv "2025-06-18") 0))))

(defn valid-protocol-version?
  "True when the request carries an acceptable MCP-Protocol-Version header, or
   when the session's negotiated version predates the header requirement."
  [req session]
  (if (requires-protocol-header? session)
    (let [hv (get-in req [:headers "mcp-protocol-version"])]
      (boolean (and hv (contains? supported-protocol-versions hv))))
    true))

(defn- request-message? [message]
  (and (contains? message :method) (contains? message :id)))

(defn handle-post [ctx req]
  (let [ctx (assoc ctx :req req)]
    (or (validate-common ctx)
        (if-not (valid-content-type? ctx)
          (error-response "Invalid Content-Type header" 400)
          (if-some [message (parse-message ctx)]
            (cond
              (= "initialize" (:method message))
              (let [session-id   (new-session-id)
                    session-data ((:create-session-fn ctx) ctx session-id)
                    session      (assoc-session! ctx session-id session-data)]
                (handle-request-over-channel ctx session message))

              (request-message? message)
              (if-some [session (fetch-session! ctx (get-in req [:headers "mcp-session-id"]))]
                (if (valid-protocol-version? req session)
                  (handle-request-over-channel ctx session message)
                  (error-response "Missing or unsupported MCP-Protocol-Version header" 400))
                (error-response "Session not found" 404))

              :else
              (if-some [session (fetch-session! ctx (get-in req [:headers "mcp-session-id"]))]
                (if (valid-protocol-version? req session)
                  (run-notification! session message)
                  (error-response "Missing or unsupported MCP-Protocol-Version header" 400))
                (error-response "Session not found" 404)))
            (error-response "Could not parse message" 400))))))

;; ── GET /mcp server→client stream ────────────────────────────────────────────
(defn handle-get [ctx req]
  (let [ctx (assoc ctx :req req)]
    (or (validate-common ctx)
        (let [session-id (get-in req [:headers "mcp-session-id"])
              session    (fetch-session! ctx session-id)]
          (cond
            (nil? session)                  (error-response "Session not found" 404)
            @(:session/get-channel session) (error-response "Stream already open" 405)
            :else
            (http-kit/as-channel
             req
             {:on-open  (fn [channel]
                          (if (compare-and-set! (:session/get-channel session) nil channel)
                            (do
                              (send-sse-headers! channel session-id)
                              ;; Best-effort replay: under concurrent server-initiated sends a live
                              ;; frame could interleave with replay; not a concern for the single-client example.
                              (when-some [leid (last-event-id req)]
                                (doseq [frame (events-after session leid)]
                                  (http-kit/send! channel frame false))))
                            (http-kit/close channel)))   ; lost the registration race → close immediately
              :on-close (fn [_channel _status]
                          (reset! (:session/get-channel session) nil))}))))))

;; ── DELETE /mcp teardown ─────────────────────────────────────────────────────
(defn handle-delete [ctx req]
  (let [ctx (assoc ctx :req req)]
    (or (validate-common ctx)
        (let [session-id (get-in req [:headers "mcp-session-id"])
              session    (fetch-session! ctx session-id)]
          (if (nil? session)
            (error-response "Session not found" 404)
            (do (when-let [ch @(:session/get-channel session)] (http-kit/close ch))
                (dissoc-session! ctx session-id)
                {:status 204 :headers {} :body nil}))))))

;; ── Lifecycle / routes ──────────────────────────────────────────────────────
(defn ctx-start [ctx]
  (assoc ctx ::sessions (atom {})))

(defn routes [ctx]
  ["" ["/health" {:get (fn [_req] {:status 200 :headers {"content-type" "text/plain"} :body "ok"})}]
   ["/mcp" {:post   (fn [req] (handle-post ctx req))
            :get    (fn [req] (handle-get ctx req))
            :delete (fn [req] (handle-delete ctx req))}]])
