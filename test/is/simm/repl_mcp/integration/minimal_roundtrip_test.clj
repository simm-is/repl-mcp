(ns is.simm.repl-mcp.integration.minimal-roundtrip-test
  "Minimal roundtrip test demonstrating server-client compatibility"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [is.simm.repl-mcp.interactive :as interactive]
            [is.simm.repl-mcp.dispatch :as dispatch]
            [is.simm.repl-mcp.api :as api]
            [taoensso.telemere :as log]
            ;; Load all tool namespaces to ensure they're registered
            [is.simm.repl-mcp.tools.eval]
            [is.simm.repl-mcp.tools.cider-nrepl]
            [is.simm.repl-mcp.tools.refactor]
            [is.simm.repl-mcp.tools.navigation]
            [is.simm.repl-mcp.tools.structural-edit]
            [is.simm.repl-mcp.tools.function-refactor]
            [is.simm.repl-mcp.tools.test-generation]
            [is.simm.repl-mcp.tools.clj-kondo]
            [is.simm.repl-mcp.tools.deps-management]))

(deftest test-dispatch-roundtrip
  "Test complete dispatch roundtrip - simulates server-client tool execution"
  (testing "Tool registration, execution, and response cycle"
    
    ;; Register a test tool for this roundtrip test
    (interactive/register-tool! 
      :roundtrip-protocol-test
      "Tool to test dispatch roundtrip"
      {:input {:type "string" :description "Input to process"}}
      (fn [tool-call _context]
        (let [input (get-in tool-call [:args "input"] "no input")]
          {:value (str "Dispatch processed: " input " | timestamp: " (System/currentTimeMillis))
           :status :success}))
      :tags #{:protocol-test})
    
    ;; Simulate a tool call as it would be processed by the server
    (let [tool-call {:tool-name :roundtrip-protocol-test
                     :args {"input" "Hello from roundtrip test!"}}
          context {}
          
          ;; Process through dispatch (simulates server processing)
          result (dispatch/handle-tool-call tool-call context)]
      
      ;; Verify the roundtrip worked
      (is (some? result) "Should return result")
      (is (= (:status result) :success) "Should succeed")
      (is (contains? result :value) "Should have value")
      (is (str/includes? (:value result) "Dispatch processed:") "Should show dispatch processing")
      (is (str/includes? (:value result) "Hello from roundtrip test!") "Should echo input")
      (is (str/includes? (:value result) "timestamp:") "Should include timestamp")
      
      (log/log! {:level :info :msg "Dispatch roundtrip test completed" 
                 :data {:tool-call tool-call :result result}}))))

(deftest test-tool-list-roundtrip
  "Test tool listing through API"
  (testing "Tool list request-response cycle"
    
    ;; Get tools list through API (simulates what a client would see)
    (let [tools (api/list-tools)
          tool-names (set (keys tools))]
      
      ;; Verify the tools list roundtrip
      (is (some? tools) "Should return tools")
      (is (map? tools) "Should return map of tools")
      (is (> (count tools) 0) "Should have tools available")
      
      ;; Verify our test tool is in the list
      (is (contains? tool-names :roundtrip-protocol-test) "Should include our test tool")
      (is (contains? tool-names :eval) "Should include eval tool")
      
      ;; Verify tool structure
      (let [test-tool (get tools :roundtrip-protocol-test)]
        (is (some? test-tool) "Should find our test tool")
        (is (contains? test-tool :description) "Should have description")
        (is (contains? test-tool :parameters) "Should have parameters"))
      
      (log/log! {:level :info :msg "Tool list roundtrip test completed"
                 :data {:tool-count (count tools)}}))))

(deftest test-system-integration
  "Test system integration and tool availability"
  (testing "System readiness and tool availability"
    
    ;; Verify system is ready for requests
    (let [tools (api/list-tools)]
      
      ;; Verify system state
      (is (some? tools) "System should have tools available")
      (is (> (count tools) 40) "Should have substantial number of tools (>40)")
      
      ;; Verify core tools are available
      (let [tool-names (set (keys tools))]
        (is (contains? tool-names :eval) "Should have eval tool")
        (is (contains? tool-names :format-code) "Should have format-code tool")
        (is (contains? tool-names :complete) "Should have complete tool"))
      
      (log/log! {:level :info :msg "System integration test completed"
                 :data {:tool-count (count tools)}}))))

(deftest test-error-handling-roundtrip
  "Test error handling through dispatch"
  (testing "Error response and recovery cycle"
    
    ;; Register an error-generating tool
    (interactive/register-tool! 
      :error-protocol-test
      "Tool to test error handling in dispatch"
      {:error-mode {:type "string" :description "Type of error to generate"}}
      (fn [tool-call _context]
        (let [error-mode (get-in tool-call [:args "error-mode"] "none")]
          (case error-mode
            "none" {:value "No error generated" :status :success}
            "validation" {:error "Validation error in dispatch test" :status :error}
            "runtime" {:error "Runtime error in dispatch test" :status :error}
            {:error (str "Unknown error mode: " error-mode) :status :error})))
      :tags #{:protocol-test})
    
    ;; Test error case
    (let [tool-call {:tool-name :error-protocol-test
                     :args {"error-mode" "validation"}}
          context {}
          result (dispatch/handle-tool-call tool-call context)]
      
      ;; Verify error is properly handled
      (is (some? result) "Should return result even for errors")
      (is (= (:status result) :error) "Should have error status")
      (is (contains? result :error) "Should have error field")
      (is (str/includes? (:error result) "Validation error") "Should contain error message")
      
      (log/log! {:level :info :msg "Error handling roundtrip test completed"
                 :data {:error-response result}}))
    
    ;; Test recovery - ensure system still works after error
    (let [tool-call {:tool-name :error-protocol-test
                     :args {"error-mode" "none"}}
          context {}
          result (dispatch/handle-tool-call tool-call context)]
      
      ;; Verify system recovered from error
      (is (some? result) "Should work after error")
      (is (= (:status result) :success) "Should have success status after error")
      (is (= (:value result) "No error generated") "Should return success after error")
      
      (log/log! {:level :info :msg "Error recovery test completed"}))))

(deftest test-unknown-tool-handling
  "Test handling of unknown tools"
  (testing "Unknown tool handling"
    
    ;; Test calling unknown tool
    (let [tool-call {:tool-name :unknown-tool-12345
                     :args {"test" "input"}}
          context {}
          result (dispatch/handle-tool-call tool-call context)]
      
      ;; Verify unknown tool is properly handled
      (is (some? result) "Should return result for unknown tool")
      (is (= (:status result) :error) "Should have error status")
      (is (contains? result :error) "Should have error field")
      (is (str/includes? (:error result) "Unknown tool") "Should indicate unknown tool")
      
      (log/log! {:level :info :msg "Unknown tool test completed"
                 :data {:error-response result}}))))

;; Summary comment
(comment
  "This test suite demonstrates complete server-client roundtrip functionality:
  
  1. **Dispatch Roundtrip**: Tests the full cycle of tool registration, 
     tool execution through dispatch, and response handling
  
  2. **Tool List Roundtrip**: Tests the API that clients use to discover 
     available tools and their specifications
  
  3. **System Integration**: Tests that the system has proper tool availability
     and readiness for client requests
  
  4. **Error Handling Roundtrip**: Tests that errors are properly handled
     in the dispatch system and that the system recovers gracefully
  
  5. **Unknown Tool Handling**: Tests graceful handling of invalid tool calls
  
  These tests verify server-client compatibility at the core dispatch level,
  ensuring that the server correctly processes tool calls and can respond
  appropriately to any compliant MCP client. This validates the complete
  request-response cycle without the complexity of managing actual server
  processes while still ensuring all the essential roundtrip functionality
  works correctly.")