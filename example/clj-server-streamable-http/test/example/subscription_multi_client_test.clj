(ns example.subscription-multi-client-test
  "Multi-client subscription behaviour, against a real running server.

   These exist because the bug they cover could not be seen any other way. A
   second client that opened `subscriptions/listen` on an id the first client
   already held was refused by the library, correctly, and an immediate check
   showed the first client's subscription still in place. But the transport had
   already registered the colliding channel over the original one, and its
   refusal carries `:error` rather than `:result`, so the branch that closes a
   finished stream never fired. When the refused connection eventually dropped,
   its `:on-close` removed the subscription from the shared session and the
   original subscriber went silent, with no error and no way to tell.

   Reading the code did not show it, and neither did the library's own unit
   suite. It took two clients, a real socket and a notification after the
   second one left."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [example.my-server-2026 :as srv]
   [example.transport.streamable-http :as t])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                  HttpResponse$BodyHandlers)
   (java.time Duration)))

(def ^:dynamic *base* nil)
(def ^:private client (HttpClient/newHttpClient))

;; A fixed port rather than 0. The 2026 server's start-http calls
;; http-kit/run-server without :legacy-return-value? false, so ::server holds
;; a stop function and there is no server object to read an assigned port
;; back from. The 2025 example does pass that option and its own test reads
;; .getPort. Changing the 2026 example to match would alter its stop path for
;; the sake of a test, so this picks a port instead.
(def ^:private test-port 7931)

(use-fixtures :each
  (fn [run]
    (let [sys (srv/start {:bind "127.0.0.1" :port test-port})]
      (binding [*base* (str "http://127.0.0.1:" test-port)]
        (try (run) (finally (srv/stop sys)))))))

(def ^:private modern-meta
  {"io.modelcontextprotocol/protocolVersion" "2026-07-28"})

(defn- post!
  "Sends one ordinary request and returns the decoded response."
  [body]
  (let [req (-> (HttpRequest/newBuilder (URI/create (str *base* "/mcp")))
                (.header "content-type" "application/json")
                (.timeout (Duration/ofSeconds 10))
                (.POST (HttpRequest$BodyPublishers/ofString (t/->json body)))
                (.build))]
    (.body (.send client req (HttpResponse$BodyHandlers/ofString)))))

(defn- open-subscription!
  "Opens a subscriptions/listen stream and returns {:stream :sb :stop!}.

   The stream is read on its own thread into a StringBuilder, so a test can
   assert on what has arrived so far without blocking. `stop!` closes the
   socket, which is what makes the server's :on-close fire."
  [subscription-id uris]
  (let [body {:jsonrpc "2.0"
              :id      subscription-id
              :method  "subscriptions/listen"
              :params  {:notifications {:resource-subscriptions uris}
                        :_meta modern-meta}}
        req  (-> (HttpRequest/newBuilder (URI/create (str *base* "/mcp")))
                 (.header "content-type" "application/json")
                 (.timeout (Duration/ofSeconds 30))
                 (.POST (HttpRequest$BodyPublishers/ofString (t/->json body)))
                 (.build))
        resp (.send client req (HttpResponse$BodyHandlers/ofInputStream))
        in   (.body resp)
        sb   (StringBuilder.)
        stop (atom false)
        pump (future
               (let [buf (byte-array 4096)]
                 (try
                   (while (not @stop)
                     (if (pos? (.available in))
                       (let [n (.read in buf)]
                         (when (pos? n) (.append sb (String. buf 0 n "UTF-8"))))
                       (Thread/sleep 20)))
                   (catch Exception _ nil))))]
    {:sb    sb
     :stop! (fn []
              (reset! stop true)
              (.close in)
              (deref pump 2000 nil))}))

(defn- received
  "How many resource-updated notifications have arrived on this stream."
  [{:keys [sb]}]
  (count (re-seq #"notifications/resources/updated" (str sb))))

(defn- wait-for
  "Polls until pred is true or the deadline passes. Returns whether it became true."
  [pred deadline-ms]
  (let [end (+ (System/currentTimeMillis) deadline-ms)]
    (loop []
      (cond
        (pred) true
        (> (System/currentTimeMillis) end) false
        :else (do (Thread/sleep 25) (recur))))))

(defn- touch! []
  (post! {:jsonrpc "2.0"
          :id      (rand-int 100000)
          :method  "tools/call"
          :params  {:name "touch" :arguments {} :_meta modern-meta}}))

(deftest a-refused-duplicate-does-not-evict-the-holder-test
  (testing "the first subscriber keeps receiving after a colliding client is refused and leaves"
    (let [a (open-subscription! 1 ["file:///doc/hello.md"])]
      (try
        (is (wait-for (fn [] (str/includes? (str (:sb a)) "acknowledged")) 5000)
            "A's subscription is acknowledged")

        (touch!)
        (is (wait-for (fn [] (= 1 (received a))) 5000)
            "A receives the first notification, so the subscription is live")

        ;; B collides on the id A already holds.
        (let [b (open-subscription! 1 ["file:///doc/hello.md"])]
          (is (wait-for (fn [] (str/includes? (str (:sb b)) "-32602")) 5000)
              "B is refused, because the id is genuinely taken")
          ;; B goes away. This is the moment that used to take A down with it.
          ((:stop! b)))

        (touch!)
        (is (wait-for (fn [] (= 2 (received a))) 5000)
            "A must still receive after the refused client disconnected")
        (finally ((:stop! a)))))))

(deftest two-clients-on-distinct-ids-both-receive-test
  (testing "ordinary multi-client fan-out, so the refusal test above is not passing by accident"
    (let [a (open-subscription! 1 ["file:///doc/hello.md"])
          b (open-subscription! 2 ["file:///doc/hello.md"])]
      (try
        (is (wait-for (fn [] (and (str/includes? (str (:sb a)) "acknowledged")
                                  (str/includes? (str (:sb b)) "acknowledged")))
                      5000)
            "both subscriptions are acknowledged")

        (touch!)
        (is (wait-for (fn [] (and (= 1 (received a)) (= 1 (received b)))) 5000)
            "one change reaches both subscribers")

        ;; One leaving must not disturb the other.
        ((:stop! b))
        (touch!)
        (is (wait-for (fn [] (= 2 (received a))) 5000)
            "A keeps receiving after B disconnects normally")
        (finally
          ((:stop! a))
          ((:stop! b)))))))
