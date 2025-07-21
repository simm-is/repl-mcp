(ns is.simm.repl-mcp.tools.refactor-test
  (:require [clojure.test :refer [deftest is testing]]
            [is.simm.repl-mcp.tools.refactor :as refactor-tools]
            [clojure.string :as str]))

(deftest function-existence-test
  (testing "refactor functions exist"
    (is (fn? refactor-tools/clean-namespace))
    (is (fn? refactor-tools/find-symbol-occurrences))
    (is (fn? refactor-tools/rename-file-or-directory))
    (is (fn? refactor-tools/resolve-missing-symbol))
    (is (fn? refactor-tools/find-used-locals))))

(deftest tools-definitions-test
  (testing "tools vector exists and has correct structure"
    (is (vector? refactor-tools/tools))
    (is (= 11 (count refactor-tools/tools)))
    
    (testing "all tools have required fields"
      (doseq [tool refactor-tools/tools]
        (is (string? (:name tool)))
        (is (string? (:description tool)))
        (is (map? (:inputSchema tool)))
        (is (fn? (:tool-fn tool)))))
    
    (testing "specific tool names"
      (let [tool-names (set (map :name refactor-tools/tools))]
        (is (contains? tool-names "clean-ns"))
        (is (contains? tool-names "find-symbol"))
        (is (contains? tool-names "rename-file-or-dir"))
        (is (contains? tool-names "resolve-missing"))
        (is (contains? tool-names "find-used-locals"))
        (is (contains? tool-names "extract-function"))
        (is (contains? tool-names "extract-variable"))))))

(deftest error-handling-test
  (testing "refactor functions handle nil nREPL client gracefully"
    (testing "clean-namespace with nil client"
      (let [result (refactor-tools/clean-namespace nil "test.clj")]
        (is (= (:status result) :error))
        (is (some? (:error result)))))
    
    (testing "find-symbol-occurrences with nil client"
      (let [result (refactor-tools/find-symbol-occurrences nil "test.clj" 1 1)]
        (is (= (:status result) :error))
        (is (some? (:error result)))))
    
    (testing "rename-file-or-directory with nil client"
      (let [result (refactor-tools/rename-file-or-directory nil "old.clj" "new.clj")]
        (is (= (:status result) :error))
        (is (some? (:error result)))))))

(deftest tool-handler-test
  (testing "tool handlers return proper MCP format"
    (testing "clean-ns-tool handler"
      (let [tool-fn (:tool-fn (first (filter #(= "clean-ns" (:name %)) refactor-tools/tools)))
            result (tool-fn {} {:file-path "test.clj"})]
        (is (map? result))
        (is (contains? result :content))
        (is (vector? (:content result)))
        (is (= "text" (:type (first (:content result)))))
        (is (str/includes? (:text (first (:content result))) "Error"))))
    
    (testing "extract-function-tool handler returns helpful message"
      (let [tool-fn (:tool-fn (first (filter #(= "extract-function" (:name %)) refactor-tools/tools)))
            result (tool-fn {} {:session-id "test" :function-name "foo" :parameters "[x]"})]
        (is (map? result))
        (is (contains? result :content))
        (is (str/includes? (:text (first (:content result))) "structural editing"))))))