(ns example.my-client
  #?@
   (:clj
    [(:require
      [example.client-content :as content]
      [jsonista.core :as j]
      [mcp-toolkit.client :as client]
      [mcp-toolkit.json-rpc :as json-rpc]
      [mcp-toolkit.protocol :as protocol])
     (:import
      (clojure.lang LineNumberingPushbackReader)
      (java.io
       BufferedReader
       BufferedWriter
       File
       InputStreamReader
       OutputStreamWriter))]
    :cljs
    [(:require
      ["child_process" :refer [spawn]]
      ["path" :as path]
      [camel-snake-kebab.extras :as cske]
      [clojure.string :as str]
      [example.client-content :as content]
      [mcp-toolkit.client :as client]
      [mcp-toolkit.json-rpc :as json-rpc]
      [mcp-toolkit.protocol :as protocol])]))

;; Example of usage of this library.

(def session
  (atom
   (client/create-session {:client-capabilities {:roots {:list-changed true}
                                                 :sampling {}}
                           :roots content/roots
                           :on-sampling-requested content/sampling-handler})))

(def context
  (atom {:session session}))

;;
;; Platform-specific threading, transport & I/O stuffs
;;

;; on the JVM

#?(:clj
   (defn listen-messages [context
                          ^LineNumberingPushbackReader reader]
     (let [{:keys [send-message]} context
           ;; Convert camelCase strings to kebab-case keywords
           json-mapper (j/object-mapper {:decode-key-fn protocol/decode-key})]
       (loop []
         ;; line = nil means that the reader is closed
         (when-some [line (.readLine reader)]
           (when-some [message (try
                                 ;; In this simple example, we naively assume that there is a json object per line.
                                 (-> (j/read-value line json-mapper))
                                 (catch Exception e
                                   (send-message json-rpc/parse-error-response)
                                   nil))]
             (prn [:<-- message])
             (json-rpc/handle-message context message))
           (recur))))))

#?(:clj
   (defn -main [& args]
     (let [;; Start a server process
           ^Process server-process (-> (ProcessBuilder. ["clojure" "-X:mcp-server"])
                                       (.directory (File. "../cljc-server-stdio"))
                                       (.start))
           ;; A writer to write on the server's stdin
           writer (-> (.getOutputStream server-process)
                      (OutputStreamWriter.)
                      (BufferedWriter.))
           ;; A reader to read lines from the server's stdout
           reader (-> (.getInputStream server-process)
                      (InputStreamReader.)
                      (BufferedReader.)
                      (LineNumberingPushbackReader.))
           ;; Hook up the I/O functions to the context
           ctx (swap! context assoc
                      ;; Convert kebab-case keywords to camelCase strings
                      :send-message (let [json-mapper (j/object-mapper {:encode-key-fn protocol/encode-key})]
                                      (fn [message]
                                        (prn [:--> message])
                                        (.write writer (j/write-value-as-string message json-mapper))
                                        (.write writer "\n")
                                        (.flush writer)))
                      :close-connection (fn []
                                          (.close reader)
                                          (.close writer)))]

       ;; Listen on the reader in a separate thread.
       (future (listen-messages ctx reader))

       ;; Initiate the handshake
       (client/send-first-handshake-message ctx))))

;; on Node JS

#?(:cljs
   (defn main [& args]
     (let [;; Start a server process
           server-process (spawn "clojure" #js ["-X:mcp-server"]
                                 #js {:cwd (.resolve path ".." "cljc-server-stdio")
                                      :stdio #js ["pipe" ; writable stdin
                                                  "pipe" ; writable stdout
                                                  "inherit" ; stderr
                                                  ]})
           ;; A writer to write on the server's stdin
           writer (.-stdin server-process)
           ;; A reader to read lines from the server's stdout
           reader (.-stdout server-process)
           ;; Hook up the I/O functions to the context
           ctx (swap! context assoc
                      ;; Convert kebab-case keywords to camelCase strings
                      :send-message (fn [message]
                                      (prn [:--> message])
                                      (.write writer (str (-> message
                                                              (cske/transform-keys protocol/encode-key)
                                                              clj->js
                                                              js/JSON.stringify) "\n")))
                      :close-connection (fn []
                                          (.kill server-process)))]

       ;; Listen on the reader
       (.on reader "data"
            (fn [chunk]
              ;; In this simple example, we naively assume that there is a json object per line.
              (doseq [line (str/split-lines chunk)]
                (when-some [message (try
                                      ;; Convert camelCase to kebab-case keywords
                                      (-> line
                                          js/JSON.parse
                                          js->clj
                                          (->> (cske/transform-keys protocol/decode-key)))
                                      (catch js/SyntaxError e
                                        (json-rpc/send-message ctx json-rpc/parse-error-response)
                                        (js/process.stderr.write (str "<<-" line "->>"))
                                        nil))]
                  (prn [:<-- message])
                  (json-rpc/handle-message ctx message)))))

       ;; Initiate the handshake
       (client/send-first-handshake-message ctx))))

;;
;; Things to run in the REPL while the server is running
;;

(comment
  (main)

  (-main)

  @session

  (json-rpc/close-connection @context)

  *e)
