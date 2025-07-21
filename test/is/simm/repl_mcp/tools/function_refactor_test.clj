(ns is.simm.repl-mcp.tools.function-refactor-test
  (:require [clojure.test :refer [deftest is testing]]
            [is.simm.repl-mcp.tools.function-refactor :as function-refactor-tools]
            [clojure.string :as str]
            [clojure.java.io :as io]))

(deftest function-existence-test
  (testing "function refactor functions exist"
    (is (fn? function-refactor-tools/find-function-definition))
    (is (fn? function-refactor-tools/rename-function-in-file))
    (is (fn? function-refactor-tools/find-function-usages-in-project))
    (is (fn? function-refactor-tools/rename-function-across-project))
    (is (fn? function-refactor-tools/replace-function-definition))))

(deftest tools-definitions-test
  (testing "tools vector exists and has correct structure"
    (is (vector? function-refactor-tools/tools))
    (is (= 5 (count function-refactor-tools/tools)))
    
    (testing "all tools have required fields"
      (doseq [tool function-refactor-tools/tools]
        (is (string? (:name tool)))
        (is (string? (:description tool)))
        (is (map? (:inputSchema tool)))
        (is (fn? (:tool-fn tool)))))
    
    (testing "specific tool names"
      (let [tool-names (set (map :name function-refactor-tools/tools))]
        (is (contains? tool-names "find-function-definition"))
        (is (contains? tool-names "rename-function-in-file"))
        (is (contains? tool-names "find-function-usages-in-project"))
        (is (contains? tool-names "rename-function-across-project"))
        (is (contains? tool-names "replace-function-definition"))))))

(deftest find-function-definition-test
  (testing "find-function-definition with nonexistent file"
    (let [result (function-refactor-tools/find-function-definition "/nonexistent/file.clj" "test-fn")]
      (is (= (:status result) :error))
      (is (str/includes? (:error result) "not exist"))))
  
  (testing "find-function-definition with missing function"
    ;; Create a temporary file for testing
    (let [temp-file (str (System/getProperty "java.io.tmpdir") "/test-" (System/currentTimeMillis) ".clj")]
      (try
        (spit temp-file "(ns test.file)\n\n(defn other-function [x] (+ x 1))")
        (let [result (function-refactor-tools/find-function-definition temp-file "missing-function")]
          (is (= (:status result) :error))
          (is (= (:error result) "Function not found")))
        (finally
          (when (.exists (io/file temp-file))
            (.delete (io/file temp-file)))))))
  
  (testing "find-function-definition with existing function"
    ;; Create a temporary file for testing
    (let [temp-file (str (System/getProperty "java.io.tmpdir") "/test-" (System/currentTimeMillis) ".clj")]
      (try
        (spit temp-file "(ns test.file)\n\n(defn test-function [x] (+ x 1))")
        (let [result (function-refactor-tools/find-function-definition temp-file "test-function")]
          (is (= (:status result) :success))
          (is (= (:function-name result) "test-function"))
          (is (number? (:line result)))
          (is (number? (:column result))))
        (finally
          (when (.exists (io/file temp-file))
            (.delete (io/file temp-file))))))))

(deftest tool-handler-test
  (testing "tool handlers return proper MCP format"
    (testing "find-function-definition-tool handler"
      (let [tool-fn (:tool-fn (first (filter #(= "find-function-definition" (:name %)) function-refactor-tools/tools)))
            result (tool-fn {} {:file-path "/nonexistent.clj" :function-name "test"})]
        (is (map? result))
        (is (contains? result :content))
        (is (vector? (:content result)))
        (is (= "text" (:type (first (:content result)))))
        (is (str/includes? (:text (first (:content result))) "Error"))))
    
    (testing "rename-function-in-file-tool handler"
      (let [tool-fn (:tool-fn (first (filter #(= "rename-function-in-file" (:name %)) function-refactor-tools/tools)))
            result (tool-fn {} {:file-path "/nonexistent.clj" :old-name "old" :new-name "new"})]
        (is (map? result))
        (is (contains? result :content))
        (is (str/includes? (:text (first (:content result))) "Error"))))))