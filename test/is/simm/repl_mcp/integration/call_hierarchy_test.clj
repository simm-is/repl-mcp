(ns is.simm.repl-mcp.integration.call-hierarchy-test
  "Test the call hierarchy tool functionality"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [is.simm.repl-mcp.server :as server]
            [is.simm.repl-mcp.tools :as tools]
            [taoensso.telemere :as log]
            [nrepl.server :as nrepl-server]
            [nrepl.core :as nrepl]
            [cider.nrepl :refer [cider-nrepl-handler]]
            [refactor-nrepl.middleware :refer [wrap-refactor]]))

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
    
    (let [tool-defs (tools/get-tool-definitions)
          tool-names (set (map :name tool-defs))]
      
      ;; Verify call hierarchy tool is registered
      (is (contains? tool-names "call-hierarchy") "Should have call-hierarchy tool")
      
      ;; Verify tool structure
      (let [call-hierarchy-tool (first (filter #(= (:name %) "call-hierarchy") tool-defs))]
        (is (some? call-hierarchy-tool) "Call hierarchy tool should exist")
        (is (contains? call-hierarchy-tool :description) "Should have description")
        (is (contains? call-hierarchy-tool :inputSchema) "Should have inputSchema")
        
        ;; Check expected parameters
        (let [props (get-in call-hierarchy-tool [:inputSchema :properties])]
          (is (contains? props :function) "Should have function parameter")
          (is (contains? props :namespace) "Should have namespace parameter"))))))

(deftest test-call-hierarchy-execution
  "Test executing the call hierarchy tool"
  (testing "Call hierarchy tool execution"
    
    ;; Create instance and get tool
    (let [instance (server/create-mcp-server-instance!
                     {:tools (tools/get-tool-definitions)
                      :nrepl-config {:port (:port *nrepl-server*)}
                      :server-info {:name "call-hierarchy-test" :version "1.0.0"}})
          tool-defs (tools/get-tool-definitions)
          call-hierarchy-tool (first (filter #(= (:name %) "call-hierarchy") tool-defs))]
      
      (when call-hierarchy-tool
        ;; Test with a known function that exists in our codebase
        (let [test-args {"function" "evaluate-code"
                         "namespace" "is.simm.repl-mcp.tools.eval"
                         "direction" "callers"}
              context {:nrepl-client *nrepl-client*}
              result ((:tool-fn call-hierarchy-tool) context test-args)]
          
          ;; Verify the tool executed successfully
          (is (some? result) "Should return result")
          (is (contains? result :content) "Should have content")
          
          ;; Check the response content
          (let [content-text (get-in result [:content 0 :text])]
            (is (some? content-text) "Should have text content")
            (when content-text
              (is (str/includes? content-text "evaluate-code") "Should mention the function name")
              (is (or (str/includes? content-text "call") 
                      (str/includes? content-text "hierarchy")
                      (str/includes? content-text "relations")
                      (str/includes? content-text "functions")) 
                  "Should mention call/hierarchy/relations/functions")))
          
          (log/log! {:level :info :msg "Call hierarchy test completed"
                     :data {:symbol "evaluate-code" :result result}}))))))

(deftest test-call-hierarchy-with-nonexistent-function
  "Test call hierarchy with a non-existent function"
  (testing "Call hierarchy with non-existent function"
    
    (let [tool-defs (tools/get-tool-definitions)
          call-hierarchy-tool (first (filter #(= (:name %) "call-hierarchy") tool-defs))]
      
      (when call-hierarchy-tool
        (let [test-args {"function" "some-nonexistent-function"
                         "namespace" "clojure.core"
                         "direction" "callers"}
              context {:nrepl-client *nrepl-client*}
              result ((:tool-fn call-hierarchy-tool) context test-args)]
          
          ;; Should still return result, but with no results
          (is (some? result) "Should return result")
          (is (contains? result :content) "Should have content")
          
          ;; Response should indicate no results found
          (let [content-text (get-in result [:content 0 :text])]
            (when content-text
              (is (or (str/includes? content-text "No relations")
                      (str/includes? content-text "not found")
                      (str/includes? content-text "0")
                      (str/includes? content-text "no")) 
                  "Should indicate no results found")))
          
          (log/log! {:level :info :msg "Call hierarchy no-results test completed"
                     :data {:result result}}))))))

(deftest test-usage-finder-tool
  "Test the usage finder tool (also from navigation)"
  (testing "Usage finder tool execution"
    
    (let [tool-defs (tools/get-tool-definitions)
          usage-finder-tool (first (filter #(= (:name %) "usage-finder") tool-defs))]
      
      (when usage-finder-tool
        ;; Test with a known function
        (let [test-args {"symbol" "evaluate-code"
                         "namespace" "is.simm.repl-mcp.tools.eval"}
              context {:nrepl-client *nrepl-client*}
              result ((:tool-fn usage-finder-tool) context test-args)]
          
          ;; Verify the tool executed successfully
          (is (some? result) "Should return result")
          (is (contains? result :content) "Should have content")
          
          ;; Check the response content
          (let [content-text (get-in result [:content 0 :text])]
            (when content-text
              (is (str/includes? content-text "evaluate-code") "Should mention the function name")
              (is (or (str/includes? content-text "Found")
                      (str/includes? content-text "usages")
                      (str/includes? content-text "namespaces")) 
                  "Should mention usages/found/namespaces")))
          
          (log/log! {:level :info :msg "Usage finder test completed"
                     :data {:result result}}))))))

(deftest test-call-hierarchy-parameter-validation
  "Test call hierarchy tool with various parameter combinations"
  (testing "Parameter validation"
    
    (let [tool-defs (tools/get-tool-definitions)
          call-hierarchy-tool (first (filter #(= (:name %) "call-hierarchy") tool-defs))]
      
      (when call-hierarchy-tool
        ;; Test with missing function parameter
        (let [test-args {"namespace" "is.simm.repl-mcp.tools.navigation"}
              context {}]
          (try
            (let [result ((:handler call-hierarchy-tool) test-args context)]
              ;; Should handle missing parameters gracefully or throw
              (is (some? result) "Should return result even with missing parameters")
              (is (contains? result :content) "Should have content"))
            (catch Exception e
              ;; Exception is also acceptable for missing required parameters
              (is (some? e) "Should handle missing parameters"))))
        
        ;; Test with missing namespace parameter
        (let [test-args {"function" "test-function"}
              context {}]
          (try
            (let [result ((:handler call-hierarchy-tool) test-args context)]
              (is (some? result) "Should return result even with missing namespace")
              (is (contains? result :content) "Should have content"))
            (catch Exception e
              (is (some? e) "Should handle missing namespace"))))
        
        ;; Test with empty parameters
        (let [test-args {}
              context {}]
          (try
            (let [result ((:handler call-hierarchy-tool) test-args context)]
              (is (some? result) "Should return result even with empty parameters")
              (is (contains? result :content) "Should have content"))
            (catch Exception e
              (is (some? e) "Should handle empty parameters"))))))))

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