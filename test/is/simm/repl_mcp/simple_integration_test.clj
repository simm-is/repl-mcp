(ns is.simm.repl-mcp.simple-integration-test
  "Simplified integration test for basic API functionality"
  (:require [clojure.test :refer [deftest is testing]]
            [is.simm.repl-mcp.api :as api]
            [taoensso.telemere :as log]))

(deftest api-integration-test
  (testing "Basic API integration"
    (log/log! {:level :info :msg "Running simple integration test"})
    
    ;; Test that we can access tools and prompts
    (let [tools (api/list-tools)
          prompts (api/list-prompts)]
      (is (map? tools) "Tools should be a map")
      (is (map? prompts) "Prompts should be a map")
      
      (log/log! {:level :info :msg "API integration test results"
                 :data {:tools-count (count tools)
                        :prompts-count (count prompts)}}))))

(deftest tool-registration-integration-test
  (testing "Tool registration integration"
    ;; Register a test tool
    (api/register-tool! :integration-test-tool
                        "Test tool for integration testing"
                        {:message {:type "string" :description "Test message"}}
                        (fn [_tool-call _context]
                          {:result "Integration test successful" :status :success}))
    
    ;; Verify it was registered
    (let [tools (api/list-tools)]
      (is (contains? tools :integration-test-tool) "Test tool should be registered")
      
      ;; Check tool info
      (let [tool-info (api/get-tool :integration-test-tool)]
        (is (not (nil? tool-info)) "Should be able to get tool info")
        (is (= "Test tool for integration testing" (:description tool-info)) "Description should match")))))

(deftest prompt-registration-integration-test
  (testing "Prompt registration integration"
    ;; Register a test prompt
    (api/register-prompt! :integration-test-prompt
                          "Test prompt for integration testing"
                          {:name {:type "string"}}
                          "Hello {{name}}, this is a test prompt.")
    
    ;; Verify it was registered
    (let [prompts (api/list-prompts)]
      (is (contains? prompts :integration-test-prompt) "Test prompt should be registered"))))