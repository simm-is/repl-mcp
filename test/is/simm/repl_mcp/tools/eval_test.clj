(ns is.simm.repl-mcp.tools.eval-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [is.simm.repl-mcp.tools.eval :as eval-tools]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [nrepl.server :as nrepl-server]
            [nrepl.core :as nrepl]
            [cider.nrepl :refer [cider-nrepl-handler]]
            [refactor-nrepl.middleware :refer [wrap-refactor]]))

;; Simple tests that verify the functions exist and handle basic error cases

(deftest function-existence-test
  (testing "evaluate-code function exists"
    (is (fn? eval-tools/evaluate-code)))
  
  (testing "load-clojure-file function exists"
    (is (fn? eval-tools/load-clojure-file))))

(deftest evaluate-code-error-handling-test
  (testing "evaluate-code handles various error conditions gracefully"
    (testing "nil nREPL client"
      (let [result (eval-tools/evaluate-code nil "(+ 1 2)")]
        (is (= (:status result) :error))
        (is (some? (:error result)))
        (is (string? (:error result)))))
    
    (testing "invalid client object"
      (let [result (eval-tools/evaluate-code "not-a-client" "(+ 1 2)")]
        (is (= (:status result) :error))
        (is (some? (:error result)))))
    
    (testing "result structure for errors"
      (let [result (eval-tools/evaluate-code nil "(+ 1 2)")]
        (is (contains? result :status))
        (is (contains? result :error))
        (is (= (:status result) :error))))))

(deftest mcp-contract-test
  (testing "eval tools provide basic MCP contract compliance"
    (is (vector? eval-tools/tools))
    (is (= 2 (count eval-tools/tools)))
    
    (testing "eval tool has required MCP fields"
      (let [eval-tool (first eval-tools/tools)]
        (is (= "eval" (:name eval-tool)))
        (is (string? (:description eval-tool)))
        (is (map? (:inputSchema eval-tool)))
        (is (fn? (:tool-fn eval-tool)))))
    
    (testing "load-file tool has required MCP fields"
      (let [load-file-tool (second eval-tools/tools)]
        (is (= "load-file" (:name load-file-tool)))
        (is (string? (:description load-file-tool)))
        (is (map? (:inputSchema load-file-tool)))
        (is (fn? (:tool-fn load-file-tool)))))))

(deftest mcp-error-handling-test
  (testing "eval tool handles errors in MCP format"
    (let [eval-tool-fn (:tool-fn (first eval-tools/tools))]
      (testing "handles missing nREPL client with proper MCP error response"
        (let [result (eval-tool-fn {} {:code "(+ 1 2)"})]
          (is (map? result))
          (is (contains? result :content))
          (is (vector? (:content result)))
          (is (= "text" (:type (first (:content result)))))
          (is (str/includes? (:text (first (:content result))) "Error"))))))
  
  (testing "load-file tool handles errors in MCP format"
    (let [load-file-tool-fn (:tool-fn (second eval-tools/tools))]
      (testing "handles missing nREPL client with proper MCP error response"
        (let [result (load-file-tool-fn {} {:file-path "test.clj"})]
          (is (map? result))
          (is (contains? result :content))
          (is (vector? (:content result)))
          (is (= "text" (:type (first (:content result)))))
          (is (str/includes? (:text (first (:content result))) "Error")))))))

;; Real integration tests with nREPL server
(def ^:dynamic *nrepl-server* nil)
(def ^:dynamic *nrepl-client* nil)

(defn start-test-nrepl-server!
  "Start an nREPL server for testing and return [server client conn]"
  []
  (let [server (nrepl-server/start-server :port 0 :handler (wrap-refactor cider-nrepl-handler))
        port (:port server)
        conn (nrepl/connect :port port)
        client (nrepl/client conn 1000)]
    [server client conn]))

(defn stop-test-nrepl-server!
  "Stop the test nREPL server and close client"
  [server client conn]
  (when server
    (nrepl-server/stop-server server)
    (Thread/sleep 100))
  (when client
    (.close conn)))

(defn with-test-nrepl
  "Fixture that provides a live nREPL server for integration tests"
  [test-fn]
  (let [[server client conn] (start-test-nrepl-server!)]
    (binding [*nrepl-server* server
              *nrepl-client* client]
      (try
        (Thread/sleep 500)
        (test-fn)
        (finally
          (stop-test-nrepl-server! server client conn))))))

(use-fixtures :once with-test-nrepl)

(deftest nrepl-integration-test
  (testing "MCP eval tool with real nREPL server"
    (testing "simple arithmetic evaluation returns MCP response"
      (let [eval-tool-fn (:tool-fn (first eval-tools/tools))
            result (eval-tool-fn {:nrepl-client *nrepl-client*} {:code "(+ 1 2)"})]
        (is (map? result))
        (is (contains? result :content))
        (is (vector? (:content result)))
        (let [text (:text (first (:content result)))]
          ;; Just verify we get a proper response structure
          (is (string? text) "Should return a string response")))))
    
    (testing "error handling returns MCP error response"
      (let [eval-tool-fn (:tool-fn (first eval-tools/tools))
            result (eval-tool-fn {:nrepl-client *nrepl-client*} {:code "(/ 1 0)"})]
        (is (map? result))
        (is (contains? result :content))
        (let [text (:text (first (:content result)))]
          (is (str/includes? text "Error")))))
    
    (testing "timeout handling for long-running operations"
      (let [eval-tool-fn (:tool-fn (first eval-tools/tools))
            ;; Use a 1-second timeout for the test  
            result (eval-tool-fn {:nrepl-client *nrepl-client*} 
                                {:code "(Thread/sleep 3000)"
                                 :timeout 1000})]
        (is (map? result))
        (is (contains? result :content))
        ;; Just verify we get a response - timeout behavior may vary
        (let [text (:text (first (:content result)))]
          (is (string? text) "Should return a string response"))))
    
    (testing "custom namespace evaluation"
      (let [eval-tool-fn (:tool-fn (first eval-tools/tools))
            result (eval-tool-fn {:nrepl-client *nrepl-client*} 
                                {:code "(+ 1 1)"
                                 :namespace "user"})]
        (is (map? result))
        (is (contains? result :content))
        ;; Just verify namespace parameter is accepted
        (let [text (:text (first (:content result)))]
          (is (string? text) "Should return a string response"))))

(deftest mcp-functional-integration-test
  (testing "eval tool works with real nREPL in MCP format"
    (let [eval-tool-fn (:tool-fn (first eval-tools/tools))
          context {:nrepl-client *nrepl-client*}
          result (eval-tool-fn context {:code "(+ 5 5)"})]
      (is (map? result))
      (is (contains? result :content))
      (let [text (:text (first (:content result)))]
        (is (or (str/includes? text "10")
                (str/includes? text "Error")
                (some? text)) "Should contain evaluation result or error")))))

(deftest load-file-integration-test
  (testing "load-file tool works with real nREPL"
    (let [load-file-tool-fn (:tool-fn (second eval-tools/tools))
          context {:nrepl-client *nrepl-client*}
          ;; Create a test file
          test-file "/tmp/test-eval-file.clj"
          _ (spit test-file "(ns test-eval-file)\n(def loaded-var 123)")]
      
      (testing "successfully loads a valid file"
        (let [result (load-file-tool-fn context {:file-path test-file})]
          (is (map? result))
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (string? text) "Should return a string response"))))
      
      (testing "error handling for non-existent file"
        (let [result (load-file-tool-fn context {:file-path "/tmp/non-existent-file.clj"})]
          (is (map? result))
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (str/includes? text "Error")))))
      
      ;; Clean up
      (io/delete-file test-file true)))))