(ns example.transport.streamable-http-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [example.transport.streamable-http :as t]))

(deftest json-round-trip
  (testing "->json encodes kebab keys as camelCase; parse-message decodes camelCase to kebab"
    (is (= "{\"protocolVersion\":\"2025-11-25\"}"
           (t/->json {:protocol-version "2025-11-25"})))
    (is (= {:protocol-version "2025-11-25"}
           (t/parse-message {:req {:body "{\"protocolVersion\":\"2025-11-25\"}"}})))
    (is (nil? (t/parse-message {:req {:body "{not json"}})))))

(deftest host-and-origin-validation
  (testing "exact + wildcard host matching"
    (is (true?  (t/valid-host? "127.0.0.1:7926" #{"127.0.0.1:*"})))
    (is (true?  (t/valid-host? "127.0.0.1:1"    #{"127.0.0.1:*"})))
    (is (false? (t/valid-host? "evil.com:80"    #{"127.0.0.1:*"})))
    (is (false? (t/valid-host? ""               #{"127.0.0.1:*"}))))
  (testing "origin: blank is allowed, otherwise must match"
    (is (true?  (t/valid-origin? ""                       #{"http://localhost:7926"})))
    (is (true?  (t/valid-origin? "http://localhost:7926"  #{"http://localhost:7926"})))
    (is (false? (t/valid-origin? "http://evil.com"        #{"http://localhost:7926"})))))

(deftest session-id-shape
  (testing "new-session-id returns a fresh UUID string each call"
    (let [a (t/new-session-id) b (t/new-session-id)]
      (is (string? a))
      (is (not= a b))
      (is (re-matches #"[0-9a-f-]{36}" a)))))

(deftest session-pool-helpers
  (let [ctx (t/ctx-start {})
        rec (t/assoc-session! ctx "sid-1" :fake-data)]
    (testing "assoc-session! stores a namespaced record and returns it"
      (is (= "sid-1" (:session/id rec)))
      (is (= :fake-data (:session/data rec)))
      (is (instance? clojure.lang.Atom (:session/get-channel rec))))
    (testing "fetch-session! returns it by id, nil for unknown"
      (is (= rec (t/fetch-session! ctx "sid-1")))
      (is (nil? (t/fetch-session! ctx "nope"))))
    (testing "dissoc-session! removes it"
      (t/dissoc-session! ctx "sid-1")
      (is (nil? (t/fetch-session! ctx "sid-1"))))))
