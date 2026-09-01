(ns bb-smoke
  "Proves the library runs on Babashka, rather than merely loading there.

   Loading is the easy half. Both walls this smoke test guards against sat
   behind a successful require: Babashka's class allowlist had no
   ReentrantLock, and promesa 11 has a deftype implementing
   java.util.function.Supplier that SCI rejects. Either one turns every
   handler into a failure at call time, not at load time.

   So this drives a real server session over a real dispatch: list the
   tools, call one, and check what comes back. The `_meta` assertion is
   the protocol-level one. A namespaced wire key that decodes to a plain
   keyword loses its namespace silently, and no client recognises the
   field afterwards.

   Run it with `bb bb:smoke`, which supplies the classpath. It is not
   meant to be run bare, because the promesa the main deps pin does not
   work here."
  (:require
   [clojure.string :as str]
   [mcp-toolkit.json-rpc :as json-rpc]
   [mcp-toolkit.server :as server]))

(def ^:private sent (atom []))

(def ^:private greet-tool
  {:name "greet"
   :description "Returns a greeting, so the round trip carries a value we chose."
   :input-schema {:type "object"}
   :tool-fn (fn [_ args]
              {:content [{:type "text" :text (str "hello " (get args :who))}]})})

(defn- reply-for [id]
  (first (filter (fn [m] (= id (:id m))) @sent)))

(defn- check [results label ok? detail]
  (conj results {:label label :ok? ok? :detail detail}))

(defn -main [& _]
  (let [session (atom (server/create-session {:protocol-version "2026-07-28"
                                              :tools [greet-tool]}))
        context {:session session
                 :send-message (fn [m] (swap! sent conj m) nil)}]
    (json-rpc/handle-message context {:jsonrpc "2.0" :id 1 :method "tools/list" :params {}})
    (json-rpc/handle-message context {:jsonrpc "2.0" :id 2 :method "tools/call"
                                      :params {:name "greet" :arguments {:who "babashka"}}})
    (let [listed (reply-for 1)
          called (reply-for 2)
          names  (map :name (-> listed :result :tools))
          text   (-> called :result :content first :text)
          meta-k (first (keys (-> called :result :_meta)))
          results (-> []
                      (check "tools/list answers" (some? listed) (pr-str listed))
                      (check "tools/list names the tool" (= ["greet"] (vec names)) (pr-str names))
                      (check "tools/call answers" (some? called) (pr-str called))
                      (check "tools/call returns the value" (= "hello babashka" text) (pr-str text))
                      (check "_meta keeps its underscore" (contains? (:result called) :_meta) (pr-str (keys (:result called))))
                      (check "_meta key keeps its namespace"
                             (and (string? meta-k) (str/includes? (str meta-k) "/"))
                             (pr-str meta-k)))
          failed  (remove :ok? results)]
      (println (str "Babashka " (System/getProperty "babashka.version")
                    ", " (count results) " checks"))
      (println)
      (doseq [{:keys [label ok? detail]} results]
        (println (str "  " (if ok? "ok  " "FAIL") "  " label))
        (when-not ok? (println (str "          got: " detail))))
      (println)
      (if (seq failed)
        (do (println (str (count failed) " of " (count results) " checks failed."))
            (System/exit 1))
        (println "The library serves a real round trip on Babashka.")))))
