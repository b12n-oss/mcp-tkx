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

;; ── Lifecycle / routes ──────────────────────────────────────────────────────
(defn ctx-start [ctx]
  (assoc ctx ::sessions (atom {})))

(defn routes
  "reitit route data. Replaced incrementally through Phases 2–5."
  [_ctx]
  ["" ["/health" {:get (fn [_req] {:status 200
                                   :headers {"content-type" "text/plain"}
                                   :body "ok"})}]])
