(ns is.simm.repl-mcp.tools.eval-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [is.simm.repl-mcp.tools.eval :as eval-tools]
            [clojure.string :as str]
            [nrepl.server :as nrepl-server]
            [nrepl.core :as nrepl]
            [cider.nrepl :refer [cider-nrepl-handler]]
            [refactor-nrepl.middleware :refer [wrap-refactor]]))

;; Simple tests that verify the functions exist and handle basic error cases
;; Full integration tests would require a running nREPL server

(deftest function-existence-test
  (testing "eval-code function exists"
    (is (fn? eval-tools/eval-code)))
  
  (testing "load-clojure-file function exists"
    (is (fn? eval-tools/load-clojure-file)))
  
  (testing "format-result-for-mcp function exists"
    (is (fn? eval-tools/format-result-for-mcp))))

(deftest eval-code-error-handling-test
  (testing "eval-code handles various error conditions gracefully"
    (testing "nil nREPL client"
      (let [result (eval-tools/eval-code nil "(+ 1 2)")]
        (is (= (:status result) :error))
        (is (some? (:error result)))
        (is (string? (:error result)))))
    
    (testing "invalid client object"
      (let [result (eval-tools/eval-code "not-a-client" "(+ 1 2)")]
        (is (= (:status result) :error))
        (is (some? (:error result)))))
    
    (testing "result structure for errors"
      (let [result (eval-tools/eval-code nil "(+ 1 2)")]
        (is (contains? result :status))
        (is (contains? result :error))
        (is (= (:status result) :error))))))

(deftest eval-code-parameters-test
  (testing "eval-code accepts optional parameters correctly"
    ;; These tests verify parameter handling without requiring actual nREPL
    (testing "namespace parameter is accepted"
      ;; This will error due to nil client, but we can verify it doesn't crash on params
      (let [result (eval-tools/eval-code nil "(+ 1 2)" :namespace "user")]
        (is (= (:status result) :error))))
    
    (testing "timeout parameter is accepted"
      (let [result (eval-tools/eval-code nil "(+ 1 2)" :timeout 10000)]
        (is (= (:status result) :error))))
    
    (testing "both namespace and timeout parameters"
      (let [result (eval-tools/eval-code nil "(+ 1 2)" :namespace "user" :timeout 10000)]
        (is (= (:status result) :error))))))

(deftest load-clojure-file-error-handling-test
  (testing "file loading with nonexistent file handles errors gracefully"
    ;; This will fail on file read, which tests our error handling
    (let [result (eval-tools/load-clojure-file nil "/nonexistent/file.clj")]
      (is (= (:status result) :error))
      (is (some? (:error result)))
      (is (str/includes? (:error result) "No such file")))))

(deftest result-structure-test
  (testing "eval result structure contains expected keys"
    ;; Test the structure of results returned by eval functions
    (let [result (eval-tools/eval-code nil "test")]
      (is (contains? result :status))
      (is (#{:success :error} (:status result)))
      (when (= (:status result) :error)
        (is (contains? result :error))))))

;; Real integration tests with nREPL server
(def ^:dynamic *nrepl-server* nil)
(def ^:dynamic *nrepl-client* nil)

(defn start-test-nrepl-server!
  "Start an nREPL server for testing and return [server client]"
  []
  (let [server (nrepl-server/start-server :port 0 :handler (wrap-refactor cider-nrepl-handler)) ; Use random available port with middleware
        port (:port server)
        conn (nrepl/connect :port port)
        client (nrepl/client conn 1000)]  ; Create a client with timeout
    [server client conn]))

(defn stop-test-nrepl-server!
  "Stop the test nREPL server and close client"
  [server client conn]
  (when client
    (.close conn))
  (when server
    (nrepl-server/stop-server server)))

(defn with-test-nrepl
  "Fixture that provides a live nREPL server for integration tests"
  [test-fn]
  (let [[server client conn] (start-test-nrepl-server!)]
    (binding [*nrepl-server* server
              *nrepl-client* client]
      (try
        (Thread/sleep 500) ; Give server a moment to fully start
        (test-fn)
        (finally
          (stop-test-nrepl-server! server client conn))))))

(use-fixtures :once with-test-nrepl)

(deftest nrepl-integration-test
  (testing "eval-code with real nREPL server"
    (testing "simple arithmetic evaluation"
      (let [result (eval-tools/eval-code *nrepl-client* "(+ 1 2)")]
        (is (= (:status result) :success))
        (is (str/includes? (:value result) "3"))
        (is (string? (:value result)))) ; Should end with newline from pretty-printing
    
    (testing "complex data structure evaluation"
      (let [result (eval-tools/eval-code *nrepl-client* "{:a 1 :b [1 2 3] :c #{:x :y}}")]
        (is (= (:status result) :success))
        (is (str/includes? (:value result) ":a"))
        (is (str/includes? (:value result) ":b"))
        (is (str/includes? (:value result) "[1 2 3]"))
        (is (str/includes? (:value result) "#{"))
        (is (string? (:value result)))))
    
    (testing "function definition and invocation"
      ;; Define a function
      (let [def-result (eval-tools/eval-code *nrepl-client* "(defn square [x] (* x x))")]
        (is (= (:status def-result) :success))
        (is (str/includes? (:value def-result) "user/square")))
      
      ;; Use the function
      (let [use-result (eval-tools/eval-code *nrepl-client* "(square 5)")]
        (is (= (:status use-result) :success))
        (is (str/includes? (:value use-result) "25"))))
    
    (testing "evaluation with namespace parameter"
      (let [result (eval-tools/eval-code *nrepl-client* "(+ 10 20)" :namespace "user")]
        (is (= (:status result) :success))
        (is (str/includes? (:value result) "30"))))
    
    (testing "evaluation with timeout parameter"
      (let [result (eval-tools/eval-code *nrepl-client* "(do (Thread/sleep 100) 42)" :timeout 1000)]
        (is (= (:status result) :success))
        (is (str/includes? (:value result) "42"))))
    
    (testing "error handling with real nREPL"
      (let [result (eval-tools/eval-code *nrepl-client* "(/ 1 0)")]
        (is (= (:status result) :error))
        (is (some? (:error result)))
        (is (str/includes? (:error result) "Divide by zero"))))
    
    (testing "output capture"
      (let [result (eval-tools/eval-code *nrepl-client* "(do (println \"Hello from nREPL!\") 42)")]
        (is (= (:status result) :success))
        (is (str/includes? (:value result) "42"))
        (is (str/includes? (:value result) "Hello from nREPL!"))))
    
    (testing "complex nested structures"
      ;; First define the data structure
      (let [def-result (eval-tools/eval-code *nrepl-client* "(def data {:users [{:name \"Alice\" :skills #{:clojure :python}} 
                                            {:name \"Bob\" :skills #{:java :sql}}] 
                                   :meta {:version 1.0}})")]
        (is (= (:status def-result) :success)))
      
      ;; Then evaluate it to get the result
      (let [result (eval-tools/eval-code *nrepl-client* "data")]
        (is (= (:status result) :success))
        (is (str/includes? (:value result) ":users"))
        (is (str/includes? (:value result) "Alice"))
        (is (str/includes? (:value result) ":skills"))
        (is (str/includes? (:value result) ":meta"))
        (is (string? (:value result))))))

(deftest format-result-for-mcp-test
  (testing "format-result-for-mcp function"
    (testing "nil value"
      (is (= (eval-tools/format-result-for-mcp nil nil) "nil"))
      (is (= (eval-tools/format-result-for-mcp nil "user") "[user] nil")))
    
    (testing "simple values"
      (is (= (eval-tools/format-result-for-mcp 42 nil) "42"))
      (is (= (eval-tools/format-result-for-mcp "hello" nil) "hello"))
      (is (= (eval-tools/format-result-for-mcp :keyword nil) ":keyword")))
    
    (testing "with namespace context"
      (is (= (eval-tools/format-result-for-mcp 42 "user") "[user] 42"))
      (is (= (eval-tools/format-result-for-mcp "hello" "my.ns") "[my.ns] hello")))
    
    (testing "complex structures"
      (is (= (eval-tools/format-result-for-mcp {:a 1} nil) "{:a 1}"))
      (is (= (eval-tools/format-result-for-mcp [1 2 3] "test") "[test] [1 2 3]"))))))

(deftest load-file-integration-test
  (testing "load-clojure-file with real nREPL server"
    (testing "loading existing test file"
      (let [result (eval-tools/load-clojure-file *nrepl-client* "test-data/simple-function.clj")]
        (is (= (:status result) :success))
        (is (str/includes? (:value result) "extract-names-and-jobs"))))
    
    (testing "using loaded function"
      ;; First load the file
      (eval-tools/load-clojure-file *nrepl-client* "test-data/simple-function.clj")
      
      ;; Then use the function
      (let [result (eval-tools/eval-code *nrepl-client* 
                                        "(test-data.simple-function/extract-names-and-jobs 
                                          [{:name \"Alice\" :job \"Engineer\"} 
                                           {:name \"Bob\" :job \"Designer\"}])")]
        (is (= (:status result) :success))
        (is (str/includes? (:value result) ":names"))
        (is (str/includes? (:value result) ":jobs"))
        (is (str/includes? (:value result) "Alice"))
        (is (str/includes? (:value result) "Engineer")))))))

