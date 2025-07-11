(ns is.simm.repl-mcp-test
  (:require [clojure.test :refer :all]
            [is.simm.repl-mcp.dispatch :as dispatch]
            [is.simm.repl-mcp.interactive :as interactive]))

(deftest dispatch-test
  (testing "Tool registration"
    (let [test-tool {:name :test-tool
                     :description "A test tool"
                     :parameters {:param1 {:type "string"}}}]
      (dispatch/register-tool! test-tool)
      (let [registered-tool (dispatch/get-tool-spec :test-tool)]
        ;; Check core fields match (excluding generated timestamps/tags)
        (is (= (:name test-tool) (:name registered-tool)))
        (is (= (:description test-tool) (:description registered-tool)))
        (is (= (:parameters test-tool) (:parameters registered-tool)))
        ;; Check that tags and dependencies are sets (may be empty)
        (is (set? (:tags registered-tool)))
        (is (set? (:dependencies registered-tool))))
      (is (contains? (dispatch/get-registered-tools) :test-tool))
      (dispatch/unregister-tool! :test-tool)
      (is (nil? (dispatch/get-tool-spec :test-tool)))))
  
  (testing "Default multimethod behavior"
    (let [unknown-tool {:tool-name :unknown-tool :args {}}
          result (dispatch/handle-tool-call unknown-tool {})]
      (is (= :error (:status result)))
      (is (contains? result :error))))
  
  (testing "Tool spec conversion"
    (let [tool-spec {:name :test-tool
                     :description "Test tool"
                     :parameters {:param1 {:type "string"}
                                  :param2 {:type "number" :optional true}}}
          mcp-tool (dispatch/tool-spec->mcp-tool tool-spec)]
      (is (= "test-tool" (:name mcp-tool)))
      (is (= "Test tool" (:description mcp-tool)))
      (is (= [:param1] (-> mcp-tool :inputSchema :required)))
      (is (contains? (-> mcp-tool :inputSchema :properties) :param1))
      (is (contains? (-> mcp-tool :inputSchema :properties) :param2)))))

(deftest tool-conversion-test
  (testing "MCP tool call conversion"
    (let [mcp-call {:name "test-tool" :arguments {:param1 "value1" :param2 42}}
          clj-call (dispatch/mcp-tool-call->clj mcp-call)]
      (is (= :test-tool (:tool-name clj-call)))
      (is (= {:param1 "value1" :param2 42} (:args clj-call)))))
  
  (testing "Result conversion"
    (let [success-result {:result "success" :status :success}
          mcp-result (dispatch/clj-result->mcp-result success-result)]
      (is (false? (:isError mcp-result)))
      (is (vector? (:content mcp-result))))
    
    (let [error-result {:error "test error" :status :error}
          mcp-result (dispatch/clj-result->mcp-result error-result)]
      (is (true? (:isError mcp-result)))
      (is (= "test error" (:error mcp-result))))))

(deftest tool-listing-test
  (testing "List tools functionality"
    ;; Tools should be registered from the loaded namespaces
    (require '[is.simm.repl-mcp.tools.eval])
    (require '[is.simm.repl-mcp.tools.refactor])
    
    (let [tools (dispatch/get-registered-tools)]
      (is (contains? tools :eval))
      (is (contains? tools :clean-ns))
      (is (contains? tools :find-symbol)))))
