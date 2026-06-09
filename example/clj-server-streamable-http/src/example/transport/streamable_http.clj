(ns example.transport.streamable-http
  "A 2025-03-26+ compatible Streamable HTTP transport for MCP Toolkit."
  (:require
   [camel-snake-kebab.core :as csk]
   [clojure.string :as str]
   [jsonista.core :as j]
   [mcp-toolkit.json-rpc :as json-rpc]
   [org.httpkit.server :as http-kit]
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

;; ── Outbound: Phase-2 capturing send-message (JSON only; streaming in Phase 3) ─
(defn- capturing-send-message [captured]
  (fn [message] (reset! captured message)))

(defn- json-response [session-id body]
  {:status 200
   :headers {"content-type" "application/json" "mcp-session-id" session-id}
   :body (->json body)})

(def ^:private accepted-response
  {:status 202 :headers {"content-type" "text/plain"} :body "Accepted"})

(defn- run-message!
  "Runs one inbound message against `session-data` with a capturing send-message.
   Returns a ring response: JSON when the message produced a response, else 202."
  [session-id session-data message]
  (let [captured (atom nil)
        mcp-ctx {:session session-data
                 :send-message (capturing-send-message captured)}]
    @(json-rpc/handle-message mcp-ctx message)
    (if-some [resp @captured]
      (json-response session-id resp)
      accepted-response)))

(defn- handle-initialize-post [ctx message]
  (let [session-id   (new-session-id)
        session-data ((:create-session-fn ctx) ctx session-id)]
    (assoc-session! ctx session-id session-data)
    (run-message! session-id session-data message)))

(defn handle-post [ctx req]
  (let [ctx (assoc ctx :req req)]
    (if-not (valid-content-type? ctx)
      (error-response "Invalid Content-Type header" 400)
      (if-some [message (parse-message ctx)]
        (if (= "initialize" (:method message))
          (handle-initialize-post ctx message)
          ;; non-initialize handled in P2.T3
          (error-response "Session not found" 404))
        (error-response "Could not parse message" 400)))))

;; ── Lifecycle / routes ──────────────────────────────────────────────────────
(defn ctx-start [ctx]
  (assoc ctx ::sessions (atom {})))

(defn routes [ctx]
  ["" ["/health" {:get (fn [_req] {:status 200 :headers {"content-type" "text/plain"} :body "ok"})}]
   ["/mcp"    {:post (fn [req] (handle-post ctx req))}]])
