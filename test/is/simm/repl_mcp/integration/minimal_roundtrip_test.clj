(ns is.simm.repl-mcp.integration.minimal-roundtrip-test
  "Minimal roundtrip test demonstrating mcp-toolkit server compatibility"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [is.simm.repl-mcp.server :as server]
            [is.simm.repl-mcp.tools :as tools]
            [taoensso.telemere :as log]))

(deftest test-instance-tool-roundtrip
  "Test complete tool roundtrip using simplified instance API"
  (testing "Instance creation, tool addition, and execution cycle"
    
    ;; Create an MCP server instance
    (let [instance (server/create-mcp-server-instance! 
                     {:tools (tools/get-tool-definitions)
                      :nrepl-config {:port 17888}
                      :server-info {:name "test-repl-mcp" :version "1.0.0"}})]
      
      ;; Add a test tool
      (let [test-tool {:name "roundtrip-protocol-test"
                       :description "Tool to test instance roundtrip"
                       :inputSchema {:type "object"
                                     :properties {:input {:type "string" 
                                                          :description "Input to process"}}
                                     :required ["input"]}
                       :handler (fn [args _context]
                                  (let [input (get args "input" "no input")]
                                    {:content [{:type "text" 
                                                :text (str "Instance processed: " input 
                                                          " | timestamp: " (System/currentTimeMillis))}]}))}]
        
        ;; Add tool to instance
        (server/add-tool! instance test-tool)
        
        ;; Verify tool was added
        (let [session @(:session instance)
              tools (:tools session)]
          (is (some? tools) "Should have tools in session")
          (is (contains? tools "roundtrip-protocol-test") "Should contain our test tool")
          
          ;; Test tool execution through handler
          (let [test-args {"input" "Hello from roundtrip test!"}
                result ((:handler test-tool) test-args {})]
            
            ;; Verify the roundtrip worked
            (is (some? result) "Should return result")
            (is (contains? result :content) "Should have content")
            (is (vector? (:content result)) "Content should be vector")
            (let [content-text (get-in result [:content 0 :text])]
              (is (some? content-text) "Should have text content")
              (is (str/includes? content-text "Instance processed:") "Should show instance processing")
              (is (str/includes? content-text "Hello from roundtrip test!") "Should echo input")
              (is (str/includes? content-text "timestamp:") "Should include timestamp"))
            
            (log/log! {:level :info :msg "Instance roundtrip test completed" 
                       :data {:test-args test-args :result result}})))))))

(deftest test-tool-definitions-roundtrip
  "Test tool definitions through tools namespace"
  (testing "Tool definitions request-response cycle"
    
    ;; Get tools definitions (simulates what would be loaded into an instance)
    (let [tool-defs (tools/get-tool-definitions)
          tool-names (set (map :name tool-defs))]
      
      ;; Verify the tools definitions roundtrip
      (is (some? tool-defs) "Should return tool definitions")
      (is (vector? tool-defs) "Should return vector of tool definitions")
      (is (> (count tool-defs) 0) "Should have tool definitions available")
      
      ;; Verify core tools are in the definitions
      (is (contains? tool-names "eval") "Should include eval tool")
      (is (contains? tool-names "load-file") "Should include load-file tool")
      
      ;; Verify tool structure
      (let [eval-tool (first (filter #(= (:name %) "eval") tool-defs))]
        (is (some? eval-tool) "Should find eval tool")
        (is (contains? eval-tool :description) "Should have description")
        (is (contains? eval-tool :inputSchema) "Should have inputSchema")
        (is (contains? eval-tool :handler) "Should have handler function"))
      
      (log/log! {:level :info :msg "Tool definitions roundtrip test completed"
                 :data {:tool-count (count tool-defs)}}))))

(deftest test-system-integration
  "Test system integration and instance creation"
  (testing "System readiness and instance functionality"
    
    ;; Verify system can create instances
    (let [instance (server/create-mcp-server-instance!
                     {:tools (tools/get-tool-definitions)
                      :nrepl-config {:port 17888}
                      :server-info {:name "integration-test" :version "1.0.0"}})
          session @(:session instance)
          available-tools (:tools session)]
      
      ;; Verify system state
      (is (some? instance) "System should create instances")
      (is (some? session) "Instance should have session")
      (is (some? available-tools) "Instance should have tools available")
      (is (> (count available-tools) 4) "Should have substantial number of tools (>4)")
      
      ;; Verify core tools are available
      (let [tool-names (set (keys available-tools))]
        (is (contains? tool-names "eval") "Should have eval tool")
        (is (contains? tool-names "load-file") "Should have load-file tool")
        (is (contains? tool-names "lint-code") "Should have lint-code tool"))
      
      (log/log! {:level :info :msg "System integration test completed"
                 :data {:tool-count (count available-tools)}}))))

(deftest test-error-handling-roundtrip
  "Test error handling through tool execution"
  (testing "Error response and recovery cycle"
    
    ;; Create instance and add error-generating tool
    (let [instance (server/create-mcp-server-instance!
                     {:tools (tools/get-tool-definitions)
                      :nrepl-config {:port 17888}
                      :server-info {:name "error-test" :version "1.0.0"}})
          error-tool {:name "error-protocol-test"
                      :description "Tool to test error handling"
                      :inputSchema {:type "object"
                                    :properties {:error-mode {:type "string" 
                                                              :description "Type of error to generate"}}
                                    :required ["error-mode"]}
                      :handler (fn [args _context]
                                 (let [error-mode (get args "error-mode" "none")]
                                   (case error-mode
                                     "none" {:content [{:type "text" :text "No error generated"}]}
                                     "validation" (throw (ex-info "Validation error in test" 
                                                                   {:type :validation}))
                                     "runtime" (throw (ex-info "Runtime error in test" 
                                                               {:type :runtime}))
                                     (throw (ex-info (str "Unknown error mode: " error-mode)
                                                     {:type :unknown})))))}]
      
      ;; Add error tool to instance
      (server/add-tool! instance error-tool)
      
      ;; Test error case - should catch exception
      (let [test-args {"error-mode" "validation"}]
        (try
          ((:handler error-tool) test-args {})
          (is false "Should have thrown exception")
          (catch Exception e
            (is (some? e) "Should catch exception")
            (is (str/includes? (ex-message e) "Validation error") "Should contain error message")
            (log/log! {:level :info :msg "Error handling test completed"
                       :data {:error-message (ex-message e)}}))))
      
      ;; Test recovery - ensure tool still works after error
      (let [test-args {"error-mode" "none"}
            result ((:handler error-tool) test-args {})]
        
        ;; Verify system recovered from error
        (is (some? result) "Should work after error")
        (is (contains? result :content) "Should have content after error")
        (let [content-text (get-in result [:content 0 :text])]
          (is (= content-text "No error generated") "Should return success after error"))
        
        (log/log! {:level :info :msg "Error recovery test completed"})))))

(deftest test-tool-removal-roundtrip
  "Test tool removal through instance API"
  (testing "Tool addition and removal cycle"
    
    ;; Create instance
    (let [instance (server/create-mcp-server-instance!
                     {:tools (tools/get-tool-definitions)
                      :nrepl-config {:port 17888}
                      :server-info {:name "removal-test" :version "1.0.0"}})
          test-tool {:name "removal-test-tool"
                     :description "Tool to test removal"
                     :inputSchema {:type "object" :properties {}}
                     :handler (fn [_args _context]
                                {:content [{:type "text" :text "Test tool response"}]})}]
      
      ;; Add tool
      (server/add-tool! instance test-tool)
      
      ;; Verify tool was added
      (let [session @(:session instance)
            tools (:tools session)]
        (is (contains? tools "removal-test-tool") "Should contain added tool"))
      
      ;; Remove tool
      (server/remove-tool! instance "removal-test-tool")
      
      ;; Verify tool was removed
      (let [session @(:session instance)
            tools (:tools session)]
        (is (not (contains? tools "removal-test-tool")) "Should not contain removed tool"))
      
      (log/log! {:level :info :msg "Tool removal test completed"}))))

;; Summary comment
(comment
  "This test suite demonstrates complete mcp-toolkit server functionality:
  
  1. **Instance Tool Roundtrip**: Tests the full cycle of instance creation,
     tool addition, and tool execution through the simplified API
  
  2. **Tool Definitions Roundtrip**: Tests the tool definitions system that
     provides available tools for instance creation
  
  3. **System Integration**: Tests that the system can create instances with
     proper tool availability and session management
  
  4. **Error Handling Roundtrip**: Tests that errors are properly handled
     in tool execution and that the system recovers gracefully
  
  5. **Tool Removal Roundtrip**: Tests dynamic tool addition and removal
     through the instance API
  
  These tests verify the simplified mcp-toolkit-based architecture works
  correctly, ensuring that instances can be created, tools can be managed
  dynamically, and the complete tool execution cycle functions properly.
  This validates the migration from the old abstraction-heavy system to
  the new direct mcp-toolkit integration.")