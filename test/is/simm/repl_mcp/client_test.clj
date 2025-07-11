(ns is.simm.repl-mcp.client-test
  "Tests for simplified API functions"
  (:require [clojure.test :refer [deftest is testing]]
            [is.simm.repl-mcp.api :as api]
            [taoensso.telemere :as log]))

(deftest api-functions-exist-test
  (testing "API functions exist and are callable"
    (is (fn? api/register-tool!) "Tool registration function should exist")
    (is (fn? api/list-tools) "List tools function should exist")
    (is (fn? api/get-tool) "Get tool function should exist")
    (is (fn? api/register-prompt!) "Prompt registration function should exist")
    (is (fn? api/list-prompts) "List prompts function should exist")))

(deftest tool-registration-test
  (testing "Tool registration API works"
    ;; Test that we can register a simple tool
    (api/register-tool! :test-tool-123
                        "Test tool for API verification"
                        {:param1 {:type "string" :description "Test parameter"}}
                        (fn [tool-call context]
                          {:result "test successful" :status :success}))
    
    ;; Test that the tool appears in the list
    (let [tools (api/list-tools)]
      (is (contains? tools :test-tool-123) "Registered tool should appear in list")
      
      ;; Test that we can get the tool info
      (let [tool-info (api/get-tool :test-tool-123)]
        (is (not (nil? tool-info)) "Should be able to get tool info")
        (is (= "Test tool for API verification" (:description tool-info)) "Tool description should match")))))

(deftest prompt-registration-test
  (testing "Prompt registration API works"
    ;; Test that we can register a prompt
    (api/register-prompt! :test-prompt-123
                          "Test prompt for API verification"
                          {:param1 {:type "string"}}
                          "Test prompt template with {{param1}}")
    
    ;; Test that the prompt appears in the list
    (let [prompts (api/list-prompts)]
      (is (contains? prompts :test-prompt-123) "Registered prompt should appear in list"))))

;; Basic integration test that doesn't require servers
(deftest basic-integration-test
  (testing "Basic API integration"
    (log/log! {:level :info :msg "Running basic API integration test"})
    
    ;; Test that we can get the initial state
    (let [initial-tools (api/list-tools)
          initial-prompts (api/list-prompts)]
      (is (map? initial-tools) "Initial tools should be a map")
      (is (map? initial-prompts) "Initial prompts should be a map")
      
      ;; Log the counts for debugging
      (log/log! {:level :info :msg "API state"
                 :data {:tools-count (count initial-tools)
                        :prompts-count (count initial-prompts)}}))))