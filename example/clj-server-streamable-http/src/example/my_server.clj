(ns example.my-server
  (:require
   [clojure.string :as str]
   [example.server-content :as content]
   [example.transport.streamable-http :as sht]
   [mcp-toolkit.server :as server]
   [org.httpkit.server :as http-kit]
   [reitit.ring :as reitit]
   [taoensso.telemere :as tel])
  (:import
   (java.util.concurrent Executors)))

(set-agent-send-executor! (Executors/newVirtualThreadPerTaskExecutor))
(set-agent-send-off-executor! (Executors/newVirtualThreadPerTaskExecutor))

(defn create-session
  "Creates an mcp-toolkit server session atom for each client connection."
  [_context _session-id]
  (atom
   (server/create-session {:prompts [content/talk-like-pirate-prompt]
                           :resources [content/hello-doc-resource
                                       content/world-doc-resource]
                           :tools [content/parentify-tool]
                           :resource-templates content/my-resource-templates
                           :resource-uri-complete-fn content/my-resource-uri-complete-fn})))

(def default-transport-env
  {:dev? true
   :create-session-fn create-session
   :settings {:allowed-hosts   #{"127.0.0.1:*"}
              :allowed-origins #{"http://localhost:7926" "http://127.0.0.1:7926"}}})

(defn log-request [{:keys [uri request-method] :as req} {:keys [body status] :as resp}]
  (tel/log! {:level :info :msg (str (str/upper-case (name request-method)) " " uri)
             :data (merge {:status status}
                          (when (>= (or status -1) 400)
                            {:err body
                             :resp-headers (select-keys resp [:headers])
                             :req-headers (select-keys req [:headers])}))}))

(defn log-request-middleware [handler]
  (fn [req] (let [resp (handler req)] (log-request req resp) resp)))

(defn routes [ctx]
  ["" {:middleware [log-request-middleware]}
   (sht/routes ctx)])

(defn handler [ctx]
  (let [f (fn [] (reitit/ring-handler (reitit/router (routes ctx))))]
    (if (:dev? ctx) (reitit/reloading-ring-handler f) (f))))

(defn start-http [ctx {:keys [bind port]}]
  (println (str "Starting Streamable HTTP server on " bind ":" port))
  (assoc ctx ::server
         (http-kit/run-server (handler ctx)
                              {:legacy-return-value? false :port port :ip bind})))

(defn stop-http [{::keys [server]}]
  (when server
    (when-let [result (http-kit/server-stop! server {:timeout 1000})]
      @result)))

(tel/set-min-level! :info)

(defonce system (atom nil))

(defn start [opts] (-> default-transport-env sht/ctx-start (start-http opts)))
(defn stop  []     (-> @system stop-http))
(defn restart [opts] (stop) (reset! system (start opts)))
(defn main [opts] (reset! system (start opts)))

(comment
  (restart {:bind "127.0.0.1" :port 7926})
  (stop))
