(ns example.my-server-2026
  "A 2026-07-28 Streamable HTTP server.

   The contrast with `example.my-server` is the point. There is no
   create-session-fn, because there are no per-client sessions: one server
   session is shared, and every request carries its own protocol version and
   capabilities. Change notifications reach clients over subscriptions/listen
   rather than a GET stream."
  (:require
   [example.server-content :as content]
   [example.transport.streamable-http-2026 :as sht]
   [mcp-toolkit.server :as server]
   [org.httpkit.server :as http-kit]
   [reitit.ring :as reitit])
  (:import
   (java.util.concurrent Executors)))

(set-agent-send-executor! (Executors/newVirtualThreadPerTaskExecutor))
(set-agent-send-off-executor! (Executors/newVirtualThreadPerTaskExecutor))

(defn create-session []
  (atom
   (server/create-session
    {:protocol-version "2026-07-28"
     :server-info {:name "mcp-tkx-example" :version "1.0.0"}
     :server-instructions "An example 2026-07-28 server."
     :prompts [content/talk-like-pirate-prompt]
     :resources [content/hello-doc-resource
                 content/world-doc-resource]
     :tools [content/parentify-tool]
     :resource-templates content/my-resource-templates
     :resource-uri-complete-fn content/my-resource-uri-complete-fn})))

(defn default-env []
  {:session (create-session)
   :settings {:allowed-hosts #{"127.0.0.1:*" "localhost:*"}
              :allowed-origins #{"http://localhost:7927" "http://127.0.0.1:7927"}}})

(defn handler [ctx]
  (reitit/ring-handler (reitit/router ["" (sht/routes ctx)])))

(defn start-http [ctx {:keys [bind port]}]
  (assoc ctx ::server (http-kit/run-server (handler ctx) {:ip bind :port port})))

(defn touch-tool
  "A tool that changes something, so a subscriber has something to receive.

   Note which context it notifies through. A tool-fn's own context sends to
   the response of the call being served, which is the wrong place for a
   subscription notification. The transport's server-context routes by the
   subscription id the library stamps on each message, so that is what
   application code raising notifications must use."
  [notify-context]
  {:name "touch"
   :description "Marks the hello doc as updated, notifying any subscriber"
   :input-schema {:type "object" :properties {}}
   :tool-fn (fn [_context _arguments]
              (server/notify-resource-updated notify-context
                                              {:uri "file:///doc/hello.md"})
              {:content [{:type "text" :text "touched file:///doc/hello.md"}]})})

(defn start [opts]
  (let [ctx (sht/ctx-start (default-env))
        notify-context (sht/server-context ctx)]
    (server/add-tool notify-context (touch-tool notify-context))
    (start-http ctx opts)))

(defn stop [ctx]
  (when-some [stop-fn (::server ctx)] (stop-fn))
  nil)

(defonce system (atom nil))

(defn -main [& _args]
  (reset! system (start {:bind "127.0.0.1" :port 7927}))
  (println "2026-07-28 MCP server on http://127.0.0.1:7927/mcp")
  @(promise))
