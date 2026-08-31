(ns ^:no-doc mcp-toolkit.impl.server.handler
  (:require
   [mcp-toolkit.impl.common :refer [user-callback]]
   [mcp-toolkit.impl.mrtr :as mrtr]
   [mcp-toolkit.json-rpc :as json-rpc]
   [mcp-toolkit.protocol :as protocol]
   [promesa.core :as p]))

(defn ping-handler
  [_context]
  {})

(defn set-logging-level-handler
  "Handles logging/setLevel.

   The level is validated before it is stored. An unrecognised level used to be
   accepted and written to the session, where it then read as nil severity and
   broke notify-log for the rest of the session, so one bad request from any
   client silently disabled logging for everyone on it."
  [{:keys [session message]}]
  (let [logging-level (-> message :params :level)]
    (if (contains? protocol/log-level->importance logging-level)
      (do (swap! session assoc :logging-level logging-level)
          {})
      (json-rpc/invalid-params-response
       (:id message)
       (str "Unknown logging level: " (pr-str logging-level))))))

(defn completion-complete-handler
  [{:keys [session message]
    :as handler-context}]
  (let [{:keys [ref argument context]} (:params message)] ;; context from params (2025-06-18)
    ;; p/do wraps the whole expression, not the individual complete-fn calls,
    ;; so the (or ...) default still applies when no complete-fn is registered.
    ;; Wrapping the calls themselves would make the case always return a
    ;; promise, which is truthy, and the default would become unreachable.
    (p/do
      (-> (case (:type ref)
          "ref/prompt" (when-some [prompt-param-complete-fn (-> @session :prompt-by-name (get (:name ref)) :complete-fn)]
                         ;; Pass context if provided (2025-06-18 spec)
                         (if context
                           (prompt-param-complete-fn (assoc handler-context :completion-context context)
                                                     (:name argument)
                                                     (:value argument))
                           (prompt-param-complete-fn handler-context (:name argument) (:value argument))))
          "ref/resource" (when-some [resource-uri-complete-fn (:resource-uri-complete-fn @session)]
                           ;; Pass context if provided (2025-06-18 spec)
                           (if context
                             (resource-uri-complete-fn (assoc handler-context :completion-context context)
                                                       (:uri ref)
                                                       (:name argument)
                                                       (:value argument))
                             (resource-uri-complete-fn handler-context (:uri ref) (:name argument) (:value argument)))))
          (or {:completion {:values []
                            :total 0
                            :has-more false}})))))

(defn prompt-list-handler
  [{:keys [session]}]
  {:prompts (-> @session
                :prompt-by-name
                vals
                (->> (mapv (fn [prompt]
                             (select-keys prompt [:name :title :description :arguments :icon])))))
   #_#_:next-cursor "next-page-cursor"})

(defn prompt-get-handler
  [{:keys [session message]
    :as context}]
  (let [{:keys [name arguments]} (:params message)]
    (if-some [prompt-fn (-> @session :prompt-by-name (get name) :prompt-fn)]
      ;; p/do, so a prompt-fn that throws synchronously becomes a rejected
      ;; promise that route-message can answer, rather than an exception that
      ;; escapes handle-message and kills the caller's read loop. Matches how
      ;; tool-fn is guarded.
      (p/do (prompt-fn context arguments))
      (json-rpc/method-not-found-response (:id message)))))

(defn resource-list-handler
  [{:keys [session]}]
  {:resources (-> @session :resource-by-uri vals
                  (->> (mapv (fn [resource]
                               (select-keys resource [:uri :name :title :description :mime-type :icon])))))
   #_#_:next-cursor "next-page-cursor"})

(defn- read-error-response
  "Turns a :read-fn failure into a JSON-RPC error response.

   Takes the caller's :code when it is already an integer, since the spec
   requires one, and falls back to -32603 for anything else, including the
   string codes the old shape encouraged."
  [id error]
  (let [code (:code error)]
    {:jsonrpc "2.0"
     :error {:code (if (int? code) code -32603)
             :message (or (:message error) "Resource read failed")}
     :id id}))

(defn resource-read-handler
  "Handle resources/read requests.
   
   Resources can provide content in two ways:
   1. Static content: :text or :blob keys in the resource definition
   2. Dynamic content: :read-fn function that generates content on-demand
   
   If :read-fn is provided, it will be called with (read-fn context uri)
   and should return a map with :contents or :error.
   
   The :read-fn receives the full handler context (including :session, :message)
   and the URI being read."
  [{:keys [session message]
    :as context}]
  (let [{:keys [uri]} (:params message)]
    (if-some [resource (-> @session :resource-by-uri (get uri))]
      (if-some [read-fn (:read-fn resource)]
        ;; Dynamic content via :read-fn
        ;; p/do for the same reason as prompt-fn above. The p/catch below only
        ;; sees an async rejection; without this a synchronous throw from
        ;; read-fn never reaches it.
        (-> (p/do (read-fn context uri))
            (p/then (fn [result]
                      (if-some [error (:error result)]
                        ;; A real JSON-RPC error response, not a map nested
                        ;; under :result. Returning {:error ...} bare made
                        ;; route-message wrap it as a SUCCESS, so a client
                        ;; checking the JSON-RPC :error key saw none, and the
                        ;; code was a string where the spec requires an integer.
                        (read-error-response (:id message) error)
                        ;; Ensure contents is returned
                        (if (:contents result)
                          result
                          {:contents [(merge (select-keys resource [:uri :description :mime-type])
                                             result)]}))))
            (p/catch (fn [exception]
                       (read-error-response (:id message)
                                            {:message (ex-message exception)}))))
        ;; Static content from :text or :blob
        {:contents [(select-keys resource [:uri :description :mime-type :text :blob])]})
      ;; Full JSON-RPC response, route-message sends it as-is (no :result wrap)
      (json-rpc/resource-not-found (:id message) uri))))

(defn resource-templates-list-handler
  [{:keys [session]}]
  {:resource-templates (-> @session :resource-templates (or [])
                           (->> (mapv (fn [template]
                                        (select-keys template [:uri-template :name :title :description :mime-type :icon])))))})

(defn resource-subscribe-handler
  [{:keys [session message]}]
  (let [{:keys [uri]} (:params message)]
    (swap! session update :client-subscribed-resource-uris conj uri))
  {})

(defn resource-unsubscribe-handler
  [{:keys [session message]}]
  (let [{:keys [uri]} (:params message)]
    (swap! session update :client-subscribed-resource-uris disj uri))
  {})

(defn tool-list-handler
  [{:keys [session]}]
  {:tools (-> @session :tool-by-name vals
              (->> (mapv (fn [tool]
                           (cond-> (select-keys tool [:name :title :description :input-schema :icon])
                             ;; Add output-schema if present (2025-06-18 spec)
                             (:output-schema tool) (assoc :output-schema (:output-schema tool)))))))
   #_#_:next-cursor "next-page-cursor"})

(defn tool-call-handler
  [{:keys [session message]
    :as context}]
  (let [{:keys [name arguments]} (:params message)]
    (if-some [tool (-> @session :tool-by-name (get name))]
      (let [tool-fn (:tool-fn tool)]
        (-> (p/do (tool-fn context arguments))
            (p/then (fn [result]
                      ;; Support both simple and structured responses (2025-06-18 spec)
                      (cond
                        ;; A 2026-07-28 multi round-trip interim result is not
                        ;; tool content. Wrapping it in a text block would hide
                        ;; the input requests from the client, so pass it out
                        ;; untouched. Earlier revisions never set :result-type,
                        ;; so this branch cannot fire for them.
                        (mrtr/input-required? result)
                        result

                        ;; A tool-fn may return a full JSON-RPC response to
                        ;; report a protocol-level failure, the way this handler
                        ;; itself does for an unknown tool name. route-message
                        ;; forwards those as-is, so wrapping one here would turn
                        ;; an error into a successful result carrying the error's
                        ;; printed form as text.
                        (and (map? result)
                             (contains? result :jsonrpc)
                             (or (contains? result :error)
                                 (contains? result :result)))
                        result

                        ;; If result already has content/resources structure, use as-is
                        (and (map? result)
                             (or (contains? result :content)
                                 (contains? result :resources)))
                        result

                        ;; Legacy simple response - wrap in content
                        :else
                        {:content [{:type "text"
                                    :text (if (string? result)
                                            result
                                            (pr-str result))}]})))
            (p/catch (fn [exception]
                       {:content [{:type "text"
                                   :text (ex-message exception)}]
                        :is-error true}))))
      ;; Full JSON-RPC response, route-message sends it as-is (no :result wrap)
      (json-rpc/invalid-tool-name (:id message) name))))

(defn cancelled-notification-handler
  [{:keys [session message]}]
  (when-some [is-cancelled-atom (-> @session
                                    :is-cancelled-by-request-id
                                    (get (-> message :params :request-id)))]
    (reset! is-cancelled-atom true)))

(def handler-by-method-post-initialization
  {"ping" ping-handler
   "logging/setLevel" set-logging-level-handler
   "completion/complete" completion-complete-handler
   "prompts/list" prompt-list-handler
   "prompts/get" prompt-get-handler
   "resources/list" resource-list-handler
   "resources/read" resource-read-handler
   "resources/templates/list" resource-templates-list-handler
   "resources/subscribe" resource-subscribe-handler
   "resources/unsubscribe" resource-unsubscribe-handler
   "tools/list" tool-list-handler
   "tools/call" tool-call-handler
   "notifications/cancelled" cancelled-notification-handler
   "notifications/roots/list_changed" (user-callback :on-client-root-list-changed)})

;; Initialization phase, a handshake where protocol versions are tentatively agreed.

(defn initialize-handler
  [{:keys [session message]}]
  (let [{client-protocol-version :protocol-version
         client-info :client-info
         client-capabilities :capabilities} (:params message)

        {:keys [server-info
                server-supported-protocol-versions
                server-instructions]} @session

        protocol-version (if (contains? (set server-supported-protocol-versions) client-protocol-version)
                           client-protocol-version
                           (last server-supported-protocol-versions))]
    (swap! session assoc
           :protocol-version protocol-version
           :client-info client-info
           :client-capabilities client-capabilities)
    (-> {:protocol-version protocol-version
         :capabilities {:logging {}
                        :completions {}
                        :prompts {:list-changed true}
                        :resources {:subscribe true
                                    :list-changed true}
                        :tools {:list-changed true}}
         :server-info server-info}
        (cond-> (some? server-instructions) (assoc :instructions server-instructions)))))

(defn initialized-notification-handler
  [{:keys [session]
    :as context}]
  (swap! session assoc
         :initialized true
         :handler-by-method handler-by-method-post-initialization)
  ((user-callback :on-initialized) context))

(def handler-by-method-pre-initialization
  {"ping" ping-handler
   "initialize" initialize-handler
   "notifications/initialized" initialized-notification-handler})
