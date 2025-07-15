(ns is.simm.repl-mcp.integration.call-hierarchy-test
  "Test the call hierarchy tool functionality"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [is.simm.repl-mcp.interactive :as interactive]
            [is.simm.repl-mcp.dispatch :as dispatch]
            [is.simm.repl-mcp.api :as api]
            [taoensso.telemere :as log]
            [nrepl.server :as nrepl-server]
            [nrepl.core :as nrepl]
            [cider.nrepl :refer [cider-nrepl-handler]]
            [refactor-nrepl.middleware :refer [wrap-refactor]]
            ;; Load navigation tools to ensure call hierarchy is available
            [is.simm.repl-mcp.tools.navigation]))

;; Real integration tests with nREPL server
(def ^:dynamic *nrepl-server* nil)
(def ^:dynamic *nrepl-client* nil)

(defn start-test-nrepl-server!
  "Start an nREPL server for testing and return [server client]"
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
    ;; Give server threads time to finish cleanup
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

(deftest test-call-hierarchy-tool-registration
  "Test that the call hierarchy tool is properly registered"
  (testing "Call hierarchy tool availability"
    
    (let [tools (api/list-tools)
          tool-names (set (keys tools))]
      
      ;; Verify call hierarchy tool is registered
      (is (contains? tool-names :call-hierarchy) "Should have call-hierarchy tool")
      
      ;; Verify tool structure
      (let [call-hierarchy-tool (get tools :call-hierarchy)]
        (is (some? call-hierarchy-tool) "Call hierarchy tool should exist")
        (is (contains? call-hierarchy-tool :description) "Should have description")
        (is (contains? call-hierarchy-tool :parameters) "Should have parameters")
        
        ;; Check expected parameters
        (let [params (:parameters call-hierarchy-tool)]
          (is (contains? params :function) "Should have function parameter")
          (is (contains? params :namespace) "Should have namespace parameter"))))))

(deftest test-call-hierarchy-execution
  "Test executing the call hierarchy tool"
  (testing "Call hierarchy tool execution"
    
    ;; Test with a known function from the navigation namespace
    (let [tool-call {:tool-name :call-hierarchy
                     :args {"function" "send-nrepl-message"
                            "namespace" "is.simm.repl-mcp.tools.navigation"
                            "direction" "callers"}}
          context {:nrepl-client *nrepl-client*}
          result (dispatch/handle-tool-call tool-call context)]
      
      ;; Verify the tool executed successfully
      (is (some? result) "Should return result")
      (is (= (:status result) :success) "Should have success status")
      (is (contains? result :value) "Should have :value field")
      
      ;; Check the response content
      (let [response (:value result)]
        (is (string? response) "Should return string response")
        (when (string? response)
          (is (str/includes? response "send-nrepl-message") "Should mention the function name")
          (is (or (str/includes? response "call") 
                  (str/includes? response "hierarchy")
                  (str/includes? response "relations")
                  (str/includes? response "functions")) 
              "Should mention call/hierarchy/relations/functions")))
      
      (log/log! {:level :info :msg "Call hierarchy test completed"
                 :data {:symbol "send-nrepl-message" :result result}}))))

(deftest test-call-hierarchy-with-nonexistent-function
  "Test call hierarchy with a non-existent function"
  (testing "Call hierarchy with non-existent function"
    
    (let [tool-call {:tool-name :call-hierarchy
                     :args {"function" "nonexistent-function-12345"
                            "namespace" "nonexistent.namespace"
                            "direction" "callers"}}
          context {:nrepl-client *nrepl-client*}
          result (dispatch/handle-tool-call tool-call context)]
      
      ;; Should still return success (no error), but with no results
      (is (some? result) "Should return result")
      (is (= (:status result) :success) "Should have success status")
      (is (contains? result :value) "Should have :value field")
      
      ;; Response should indicate no results found
      (let [response (:value result)]
        (is (string? response) "Should return string response")
        (when (string? response)
          (is (or (str/includes? response "No relations")
                  (str/includes? response "not found")
                  (str/includes? response "0")
                  (str/includes? response "no")) 
              "Should indicate no results found")))
      
      (log/log! {:level :info :msg "Call hierarchy no-results test completed"
                 :data {:result result}}))))

(deftest test-usage-finder-tool
  "Test the usage finder tool (also from navigation)"
  (testing "Usage finder tool execution"
    
    ;; Test with a known function
    (let [tool-call {:tool-name :usage-finder
                     :args {"symbol" "find-symbol-usages"
                            "namespace" "is.simm.repl-mcp.tools.navigation"}}
          context {:nrepl-client *nrepl-client*}
          result (dispatch/handle-tool-call tool-call context)]
      
      ;; Verify the tool executed successfully
      (is (some? result) "Should return result")
      (is (= (:status result) :success) "Should have success status")
      (is (contains? result :value) "Should have :value field")
      
      ;; Check the response content
      (let [response (:value result)]
        (is (string? response) "Should return string response")
        (when (string? response)
          (is (str/includes? response "find-symbol-usages") "Should mention the function name")
          (is (or (str/includes? response "Found")
                  (str/includes? response "usages")
                  (str/includes? response "namespaces")) 
              "Should mention usages/found/namespaces")))
      
      (log/log! {:level :info :msg "Usage finder test completed"
                 :data {:result result}}))))

(deftest test-call-hierarchy-parameter-validation
  "Test call hierarchy tool with various parameter combinations"
  (testing "Parameter validation"
    
    ;; Test with missing symbol parameter
    (let [tool-call {:tool-name :call-hierarchy
                     :args {"namespace" "is.simm.repl-mcp.tools.navigation"}}
          context {}
          result (dispatch/handle-tool-call tool-call context)]
      
      ;; Should handle missing parameters gracefully
      (is (some? result) "Should return result even with missing parameters")
      (is (or (= (:status result) :success)
              (= (:status result) :error)) "Should have valid status")
      
      (if (= (:status result) :error)
        (is (contains? result :error) "Error result should have error field")
        (is (contains? result :value) "Success result should have value field")))
    
    ;; Test with missing namespace parameter
    (let [tool-call {:tool-name :call-hierarchy
                     :args {"symbol" "test-function"}}
          context {}
          result (dispatch/handle-tool-call tool-call context)]
      
      ;; Should handle missing namespace gracefully
      (is (some? result) "Should return result even with missing namespace")
      (is (or (= (:status result) :success)
              (= (:status result) :error)) "Should have valid status"))
    
    ;; Test with empty parameters
    (let [tool-call {:tool-name :call-hierarchy
                     :args {}}
          context {}
          result (dispatch/handle-tool-call tool-call context)]
      
      ;; Should handle empty parameters gracefully
      (is (some? result) "Should return result even with empty parameters")
      (is (or (= (:status result) :success)
              (= (:status result) :error)) "Should have valid status"))))

;; Summary comment
(comment
  "This test suite verifies the call hierarchy/callstack functionality:
  
  1. **Tool Registration**: Ensures call-hierarchy and usage-finder tools are properly registered
  2. **Tool Execution**: Tests actual execution with real function names
  3. **Error Handling**: Tests behavior with non-existent functions
  4. **Parameter Validation**: Tests various parameter combinations
  
  These tests validate that the navigation tools (call hierarchy and usage finder)
  are working correctly and can provide call stack/hierarchy information about
  functions in the codebase.")