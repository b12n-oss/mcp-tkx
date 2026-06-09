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
(def object-mapper
  (j/object-mapper {:decode-key-fn csk/->kebab-case-keyword
                    :encode-key-fn csk/->camelCaseString}))

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
      (str/starts-with? value (str base ":")))))

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

(defn- request-message? [message]
  (and (contains? message :method) (contains? message :id)))

(defn handle-post [ctx req]
  (let [ctx (assoc ctx :req req)]
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
            (handle-request-over-channel ctx session message)
            (error-response "Session not found" 404))

          :else                                            ; notification or client response
          (if-some [session (fetch-session! ctx (get-in req [:headers "mcp-session-id"]))]
            (run-notification! session message)
            (error-response "Session not found" 404)))
        (error-response "Could not parse message" 400)))))

;; ── GET /mcp server→client stream ────────────────────────────────────────────
(defn handle-get [ctx req]
  (let [session-id (get-in req [:headers "mcp-session-id"])
        session    (fetch-session! ctx session-id)]
    (cond
      (nil? session)                       (error-response "Session not found" 404)
      @(:session/get-channel session)      (error-response "Stream already open" 405)
      :else
      (http-kit/as-channel
       req
       {;; Single GET stream per session: the cond above guards the common case; concurrent GETs are not a supported scenario for this example.
        :on-open  (fn [channel]
                    (reset! (:session/get-channel session) channel)
                    (send-sse-headers! channel session-id)
                    ;; Best-effort replay: under concurrent server-initiated sends a live frame could interleave with replay; not a concern for the single-client example.
                    (when-some [leid (last-event-id req)]
                      (doseq [frame (events-after session leid)]
                        (http-kit/send! channel frame false))))
        :on-close (fn [_channel _status]
                    (reset! (:session/get-channel session) nil))}))))

;; ── DELETE /mcp teardown ─────────────────────────────────────────────────────
(defn handle-delete [ctx req]
  (let [session-id (get-in req [:headers "mcp-session-id"])
        session    (fetch-session! ctx session-id)]
    (if (nil? session)
      (error-response "Session not found" 404)
      (do (when-let [ch @(:session/get-channel session)] (http-kit/close ch))
          (dissoc-session! ctx session-id)
          {:status 204 :headers {} :body nil}))))

;; ── Lifecycle / routes ──────────────────────────────────────────────────────
(defn ctx-start [ctx]
  (assoc ctx ::sessions (atom {})))

(defn routes [ctx]
  ["" ["/health" {:get (fn [_req] {:status 200 :headers {"content-type" "text/plain"} :body "ok"})}]
   ["/mcp" {:post   (fn [req] (handle-post ctx req))
            :get    (fn [req] (handle-get ctx req))
            :delete (fn [req] (handle-delete ctx req))}]])
