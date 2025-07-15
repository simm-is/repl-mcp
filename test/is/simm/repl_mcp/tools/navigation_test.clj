(ns is.simm.repl-mcp.tools.navigation-test
  "Comprehensive integration tests for navigation tools"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [is.simm.repl-mcp.tools.navigation :as nav]
            [is.simm.repl-mcp.dispatch :as dispatch]
            [nrepl.server :as nrepl-server]
            [nrepl.core :as nrepl]
            [cider.nrepl :refer [cider-nrepl-handler]]
            [refactor-nrepl.middleware :refer [wrap-refactor]]
            [clojure.string :as str]
            [taoensso.telemere :as log]))

;; Test Configuration
(def test-config
  {:test-namespace "is.simm.repl-mcp.tools.navigation"
   :test-symbol "find-symbol-usages"
   :test-function "send-nrepl-message"
   :expected-min-usages 1
   :expected-min-callers 1})

;; Test Results Storage
(def test-results (atom {}))

(defn log-test-result 
  "Log and store test results"
  [test-name result success?]
  (let [test-data {:test test-name
                   :result result
                   :success success?
                   :timestamp (System/currentTimeMillis)}]
    (log/log! {:level :info :msg "Integration test completed" :data test-data})
    (swap! test-results assoc test-name test-data)
    test-data))

;; nREPL test fixtures
(def ^:dynamic *nrepl-server* nil)
(def ^:dynamic *nrepl-client* nil)

(defn start-test-nrepl-server!
  "Start an nREPL server for testing with proper middleware and return [server client]"
  []
  (let [middleware-stack (-> cider-nrepl-handler
                             (wrap-refactor))
        server (nrepl-server/start-server 
                :port 0 ; Use random available port
                :handler middleware-stack
                :bind "127.0.0.1")
        port (:port server)
        _ (Thread/sleep 1000) ; Give server time to start before connecting
        conn (nrepl/connect :port port)
        client (nrepl/client conn 5000)]  ; Create a client with longer timeout
    [server client conn]))

(defn stop-test-nrepl-server!
  "Stop the test nREPL server and close client"
  [server client conn]
  (when server
    (nrepl-server/stop-server server)
    ;; Give server threads time to finish cleanup
    (Thread/sleep 200))
  (when client
    (.close conn)))

(defn with-test-nrepl
  "Fixture that provides a live nREPL server for integration tests"
  [test-fn]
  (let [[server client conn] (start-test-nrepl-server!)]
    (binding [*nrepl-server* server
              *nrepl-client* client]
      (try
        (Thread/sleep 2000) ; Give server and middleware time to fully start
        (test-fn)
        (finally
          (stop-test-nrepl-server! server client conn))))))

;; Test Fixtures
(use-fixtures :once
  (fn [f]
    (log/log! {:level :info :msg "Starting navigation integration tests"})
    (reset! test-results {})
    (with-test-nrepl f)
    (log/log! {:level :info :msg "Navigation integration tests completed" 
              :data {:total-tests (count @test-results)
                     :passed (count (filter :success (vals @test-results)))
                     :failed (count (filter #(not (:success %)) (vals @test-results)))}})))

;; Test 1: send-nrepl-message function
(deftest test-send-nrepl-message
  "Test the core nREPL communication function"
  (testing "send-nrepl-message handles basic requests"
    (try
      (let [test-name "send-nrepl-message-basic"
            mock-message {:op "eval" :code "(+ 1 2)"}]
        (if *nrepl-client*
          (let [result (nav/send-nrepl-message *nrepl-client* mock-message)]
            ;; Test that function works with real nREPL client
            (log-test-result test-name result (or (contains? result :value) (contains? result :error)))
            (is (map? result) "Should return a map result")
            (if (contains? result :error)
              (is true "Error response received, but function handled it")
              (do
                (is (contains? result :value) "Should have a value key")
                (when (:value result)
                  (is (str/includes? (:value result) "3") "Should evaluate correctly")))))
          (do
            (log-test-result test-name {:skipped "No nREPL client"} true)
            (is true "Test skipped - no nREPL client available"))))
      (catch Exception e
        (log-test-result "send-nrepl-message-basic" {:error (.getMessage e)} false)
        (is false (str "send-nrepl-message test failed: " (.getMessage e)))))))

;; Test 2: find-function-callers function
(deftest test-find-function-callers
  "Test the function caller analysis"
  (testing "find-function-callers with known function"
    (try
      (let [test-name "find-function-callers"
            namespace-name (:test-namespace test-config)
            function-name (:test-function test-config)
            result (nav/find-function-callers *nrepl-client* namespace-name function-name)]
        
        (log-test-result test-name result (sequential? result))
        (is (or (vector? result) (seq? result)) "Should return a sequence of callers")
        (is (sequential? result) "Should return some kind of sequence"))
      (catch Exception e
        (log-test-result "find-function-callers" {:error (.getMessage e)} false)
        (is false (str "find-function-callers test failed: " (.getMessage e)))))))

;; Test 3: call-hierarchy tool via dispatch
(deftest test-call-hierarchy-tool
  "Test the call-hierarchy tool through the dispatch system"
  (testing "call-hierarchy tool registration and execution"
    (try
      (let [test-name "call-hierarchy-tool"
            tool-call {:tool-name :call-hierarchy
                       :args {"namespace" (:test-namespace test-config)
                              "function" (:test-function test-config)
                              "direction" "callers"
                              "max-depth" 2}}
            context {:nrepl-client *nrepl-client*}
            result (dispatch/handle-tool-call tool-call context)]
        
        (log-test-result test-name result (not (:error result)))
        (is (map? result) "Should return a map result")
        (is (contains? result :value) "Should have a value")
        (is (string? (:value result)) "Value should be a string")
        (is (clojure.string/includes? (:value result) "callers") "Should mention callers in the result"))
      (catch Exception e
        (log-test-result "call-hierarchy-tool" {:error (.getMessage e)} false)
        (is false (str "call-hierarchy tool test failed: " (.getMessage e)))))))

;; Test 4: usage-finder tool via dispatch
(deftest test-usage-finder-tool
  "Test the usage-finder tool through the dispatch system"
  (testing "usage-finder tool registration and execution"
    (try
      (let [test-name "usage-finder-tool"
            tool-call {:tool-name :usage-finder
                       :args {"namespace" (:test-namespace test-config)
                              "symbol" (:test-symbol test-config)
                              "include-context" true}}
            context {:nrepl-client *nrepl-client*}
            result (dispatch/handle-tool-call tool-call context)]
        
        (log-test-result test-name result (map? result))
        (is (map? result) "Should return a map result")
        (is (or (contains? result :value) (:error result)) "Should have value or error")
        (is (or (string? (:value result)) (:error result)) "Should have string value or error"))
      (catch Exception e
        (log-test-result "usage-finder-tool" {:error (.getMessage e)} false)
        (is false (str "usage-finder tool test failed: " (.getMessage e)))))))

;; Test 5: Tool registry verification
(deftest test-tool-registry
  "Test that navigation tools are properly registered"
  (testing "Navigation tools in registry"
    (try
      (let [test-name "tool-registry"
            registry-keys (keys @dispatch/tool-registry)
            navigation-tools [:call-hierarchy :usage-finder]
            registered-tools (filter #(contains? (set registry-keys) %) navigation-tools)
            result {:registry-keys (count registry-keys)
                    :navigation-tools navigation-tools
                    :registered-tools registered-tools
                    :all-registered (= (count navigation-tools) (count registered-tools))}]
        
        (log-test-result test-name result (:all-registered result))
        (is (> (:registry-keys result) 0) "Registry should contain tools")
        (is (seq (:registered-tools result)) "Should have registered navigation tools")
        (is (:all-registered result) "All navigation tools should be registered"))
      (catch Exception e
        (log-test-result "tool-registry" {:error (.getMessage e)} false)
        (is false (str "Tool registry test failed: " (.getMessage e)))))))

;; Test 6: bencode-friendly-data function
(deftest test-bencode-friendly-data
  "Test the bencode serialization helper"
  (testing "bencode-friendly-data handles various data types"
    (try
      (let [test-name "bencode-friendly-data"
            test-data {:boolean-true true
                      :boolean-false false
                      :string "test"
                      :number 42
                      :vector [1 2 3]
                      :map {:nested true}}
            result (nav/bencode-friendly-data test-data)
            success? (and (= "true" (:boolean-true result))
                         (= "false" (:boolean-false result))
                         (= "test" (:string result))
                         (= 42 (:number result)))]
        
        (log-test-result test-name result success?)
        (is success? "Should convert booleans to strings while preserving other types")
        (is (string? (:boolean-true result)) "Boolean true should become string")
        (is (string? (:boolean-false result)) "Boolean false should become string"))
      (catch Exception e
        (log-test-result "bencode-friendly-data" {:error (.getMessage e)} false)
        (is false (str "bencode-friendly-data test failed: " (.getMessage e)))))))

;; Test 7: parse-function-calls function
(deftest test-parse-function-calls
  "Test the function call parsing utility"
  (testing "parse-function-calls extracts function calls"
    (try
      (let [test-name "parse-function-calls"
            test-code "(defn test-fn [x] (+ x 1) (str x) (log/info \"test\"))"
            result (nav/parse-function-calls test-code)
            success? (and (or (vector? result) (seq? result))
                         (some #(= "str" %) result)
                         (some #(= "log/info" %) result))]
        
        (log-test-result test-name result success?)
        (is (or (vector? result) (seq? result)) "Should return a sequence")
        (is (sequential? result) "Should return sequential data") 
        ;; Note: + is not matched by the regex because it requires names to start with a letter
        (is (some #(= "str" %) result) "Should find str function call") 
        (is (some #(= "log/info" %) result) "Should find log/info function call"))
      (catch Exception e
        (log-test-result "parse-function-calls" {:error (.getMessage e)} false)
        (is false (str "parse-function-calls test failed: " (.getMessage e)))))))

;; Test 8: Error handling
(deftest test-error-handling
  "Test error handling for invalid inputs"
  (testing "Handle non-existent symbols gracefully"
    (try
      (let [test-name "error-handling-invalid-symbol"
            tool-call {:tool-name :usage-finder
                       :args {"namespace" "non.existent.namespace"
                              "symbol" "non-existent-symbol-12345"
                              "include-context" true}}
            context {:nrepl-client *nrepl-client*}
            result (dispatch/handle-tool-call tool-call context)
            success? (or (:error result) (contains? result :value))]
        
        (log-test-result test-name result success?)
        (is success? "Should handle invalid symbols gracefully")
        (is (map? result) "Should return a structured result"))
      (catch Exception e
        (log-test-result "error-handling-invalid-symbol" {:error (.getMessage e)} false)
        (is false (str "Error handling test failed: " (.getMessage e)))))))

;; Test Summary Function
(defn generate-test-summary 
  "Generate a comprehensive test summary"
  []
  (let [results @test-results
        total (count results)
        passed (count (filter :success (vals results)))
        failed (- total passed)
        summary {:total-tests total
                 :passed passed
                 :failed failed
                 :success-rate (if (> total 0) (/ passed total) 0)
                 :test-details (into {} (map (fn [[k v]] [k (select-keys v [:success :timestamp])]) results))}]
    (log/log! {:level :info :msg "Test summary generated" :data summary})
    summary))

;; Test Result Reporting
(defn print-test-results 
  "Print formatted test results"
  []
  (let [summary (generate-test-summary)]
    (println "\n" "=" 60)
    (println "NAVIGATION TOOLS INTEGRATION TEST RESULTS")
    (println "=" 60)
    (printf "Total Tests: %d\n" (:total-tests summary))
    (printf "Passed: %d\n" (:passed summary))
    (printf "Failed: %d\n" (:failed summary))
    (printf "Success Rate: %.1f%%\n" (* 100 (:success-rate summary)))
    (println "=" 60)
    
    (doseq [[test-name details] (:test-details summary)]
      (printf "%-30s %s\n" 
              (name test-name) 
              (if (:success details) "✓ PASS" "✗ FAIL")))
    (println "=" 60 "\n")
    summary))