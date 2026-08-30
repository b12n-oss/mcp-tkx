(ns example.my-server-test
  (:require
   [clojure.test :refer [deftest is]]
   [example.my-server :as srv])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)))

(defn- GET [base path]
  (let [client (HttpClient/newHttpClient)
        req (-> (HttpRequest/newBuilder (URI/create (str base path))) (.GET) (.build))]
    (.send client req (HttpResponse$BodyHandlers/ofString))))

(deftest server-boots-and-serves-health
  ;; meta :local-port is absent on this http-kit version; use .getPort instead.
  (let [sys  (srv/start {:bind "127.0.0.1"
                         :port 0})
        port (.getPort (::srv/server sys))]
    (try
      (let [resp (GET (str "http://127.0.0.1:" port) "/health")]
        (is (= 200 (.statusCode resp)))
        (is (= "ok" (.body resp))))
      (finally (srv/stop-http sys)))))
