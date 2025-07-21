(ns is.simm.repl-mcp.tools.structural-edit-test
  (:require [clojure.test :refer [deftest is testing]]
            [is.simm.repl-mcp.tools.structural-edit :as structural-tools]
            [clojure.string :as str]))

(deftest function-existence-test
  (testing "structural edit tool functions exist"
    (is (fn? structural-tools/placeholder-structural-operation))
    (is (fn? structural-tools/structural-create-session-tool))
    (is (fn? structural-tools/structural-navigate-tool))
    (is (fn? structural-tools/structural-replace-node-tool))
    (is (fn? structural-tools/structural-insert-after-tool))))

(deftest tools-definitions-test
  (testing "tools vector exists and has correct structure"
    (is (vector? structural-tools/tools))
    (is (= 10 (count structural-tools/tools)))
    
    (testing "all tools have required fields"
      (doseq [tool structural-tools/tools]
        (is (string? (:name tool)))
        (is (string? (:description tool)))
        (is (map? (:inputSchema tool)))
        (is (fn? (:tool-fn tool)))))
    
    (testing "specific tool names"
      (let [tool-names (set (map :name structural-tools/tools))]
        (is (contains? tool-names "structural-create-session"))
        (is (contains? tool-names "structural-save-session"))
        (is (contains? tool-names "structural-navigate"))
        (is (contains? tool-names "structural-replace-node"))
        (is (contains? tool-names "structural-insert-after"))))))

(deftest placeholder-implementation-test
  (testing "structural edit tool functions return placeholder messages"
    (testing "structural-create-session-tool"
      (let [result (structural-tools/structural-create-session-tool {:file-path "test.clj"} {})]
        (is (map? result))
        (is (contains? result :content))
        (is (str/includes? (:text (first (:content result))) "not yet implemented"))))
    
    (testing "structural-replace-node-tool"
      (let [result (structural-tools/structural-replace-node-tool {:session-id "test" :new-code "(+ 1 2)"} {})]
        (is (map? result))
        (is (contains? result :content))
        (is (str/includes? (:text (first (:content result))) "session infrastructure"))))
    
    (testing "structural-navigate-tool"
      (let [result (structural-tools/structural-navigate-tool {:session-id "session-1" :path "[:down :right]"} {})]
        (is (map? result))
        (is (contains? result :content))
        (is (str/includes? (:text (first (:content result))) "session infrastructure"))))))

(deftest tool-handler-test
  (testing "tool handlers return proper MCP format"
    (testing "structural-create-session-tool handler"
      (let [tool-fn (:tool-fn (first (filter #(= "structural-create-session" (:name %)) structural-tools/tools)))
            result (tool-fn {} {:file-path "test.clj"})]
        (is (map? result))
        (is (contains? result :content))
        (is (vector? (:content result)))
        (is (= "text" (:type (first (:content result)))))
        (is (str/includes? (:text (first (:content result))) "not yet implemented"))))
    
    (testing "structural-save-session-tool handler"
      (let [tool-fn (:tool-fn (first (filter #(= "structural-save-session" (:name %)) structural-tools/tools)))
            result (tool-fn {} {:session-id "test"})]
        (is (map? result))
        (is (contains? result :content))
        (is (str/includes? (:text (first (:content result))) "not yet implemented"))))
    
    (testing "structural-navigate-tool handler"
      (let [tool-fn (:tool-fn (first (filter #(= "structural-navigate" (:name %)) structural-tools/tools)))
            result (tool-fn {} {:session-id "test" :path "[:down]"})]
        (is (map? result))
        (is (contains? result :content))
        (is (str/includes? (:text (first (:content result))) "session infrastructure"))))))