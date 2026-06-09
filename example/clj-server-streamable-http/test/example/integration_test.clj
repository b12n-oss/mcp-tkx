(ns example.integration-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [example.my-server :as srv]
   [example.transport.streamable-http :as t])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                  HttpResponse$BodyHandlers)
   (java.time Duration)))

(def ^:dynamic *base* nil)
(def ^:dynamic *sys* nil)
(def ^:private client (HttpClient/newHttpClient))

(use-fixtures :each
  (fn [run]
    (let [sys  (srv/start {:bind "127.0.0.1" :port 0})
          port (.getPort (::srv/server sys))]   ; http-kit 2.9.0-beta3: meta has no :local-port; use .getPort
      (binding [*sys* sys *base* (str "http://127.0.0.1:" port)]
        (try (run) (finally (srv/stop-http sys)))))))

(defn- post [body headers]
  (let [b (-> (HttpRequest/newBuilder (URI/create (str *base* "/mcp")))
              (.header "content-type" "application/json")
              (.POST (HttpRequest$BodyPublishers/ofString (t/->json body))))
        b (reduce-kv (fn [acc k v] (.header acc k v)) b headers)]
    (.send client (.build b) (HttpResponse$BodyHandlers/ofString))))

(defn- header-val [resp k] (-> resp (.headers) (.firstValue k) (.orElse nil)))
(defn- decode [resp] (t/parse-message {:req {:body (.body resp)}}))

(defn- handshake! []
  (let [resp (post {:jsonrpc "2.0" :id 0 :method "initialize"
                    :params {:protocol-version "2025-11-25"
                             :client-info {:name "it" :version "1"} :capabilities {}}}
                   {})
        sid (header-val resp "mcp-session-id")]
    (post {:jsonrpc "2.0" :method "notifications/initialized"}
          {"mcp-session-id" sid "mcp-protocol-version" "2025-11-25"})
    sid))

(deftest initialize-assigns-session-and-result
  (let [resp (post {:jsonrpc "2.0" :id 0 :method "initialize"
                    :params {:protocol-version "2025-11-25"
                             :client-info {:name "it" :version "1"} :capabilities {}}}
                   {})]
    (is (= 200 (.statusCode resp)))
    (is (string? (header-val resp "mcp-session-id")))
    (is (= "2025-11-25" (get-in (decode resp) [:result :protocol-version])))))

(deftest tools-list-json
  (let [sid  (handshake!)
        resp (post {:jsonrpc "2.0" :id 1 :method "tools/list"}
                   {"mcp-session-id" sid "mcp-protocol-version" "2025-11-25"})]
    (is (= 200 (.statusCode resp)))
    (is (str/includes? (str (header-val resp "content-type")) "application/json"))
    (is (some #(= "parentify" (:name %)) (get-in (decode resp) [:result :tools])))))

(deftest tools-call-parentify-streams-then-closes
  (let [sid  (handshake!)
        ;; _meta.progressToken must be present for server/notify-progress to fire;
        ;; omitting it means no progress notifications are sent and the response
        ;; is plain JSON (not SSE). The MCP spec requires the client to opt-in to
        ;; progress by supplying a progressToken.
        resp (post {:jsonrpc "2.0" :id 2 :method "tools/call"
                    :params {:name "parentify" :arguments {:text "hi"}
                             :_meta {:progress-token "integration-tok"}}}
                   {"mcp-session-id" sid "mcp-protocol-version" "2025-11-25"})
        body (.body resp)]
    (is (= 200 (.statusCode resp)))
    (is (str/includes? (str (header-val resp "content-type")) "text/event-stream"))
    (testing "progress frames precede the final response frame"
      (is (str/includes? body "notifications/progress"))
      (is (str/includes? body "(hi)")))))
