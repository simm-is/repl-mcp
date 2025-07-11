(ns is.simm.repl-mcp.tools.function-refactor-test
  (:require [clojure.test :refer [deftest is testing]]
            [is.simm.repl-mcp.tools.function-refactor :as func-refactor]
            [is.simm.repl-mcp.dispatch :as dispatch]))

;; Test data
(def sample-function-code
  "(defn calculate-sum
  \"Adds two numbers together\"
  [a b]
  (+ a b))

(defn process-data
  \"Processes data\"
  [data]
  (let [sum (calculate-sum (:x data) (:y data))]
    {:result sum :processed true}))

(defn main-function
  \"Main processing\"
  []
  (calculate-sum 1 2))")

(deftest tool-registration-test
  (testing "All function refactor tools are registered"
    (let [registered-tools (dispatch/get-registered-tools)]
      (is (contains? registered-tools :find-function-definition))
      (is (contains? registered-tools :rename-function-in-file))
      (is (contains? registered-tools :find-function-usages-in-project))
      (is (contains? registered-tools :rename-function-across-project))
      (is (contains? registered-tools :replace-function-definition)))))

(deftest find-function-definition-test
  (testing "Finding function definitions"
    ;; Create a temporary test file
    (let [temp-file (java.io.File/createTempFile "test-function" ".clj")]
      (try
        (spit temp-file sample-function-code)
        
        ;; Test finding existing function
        (let [result (func-refactor/find-function-definition (.getAbsolutePath temp-file) "calculate-sum")]
          (is (= (:status result) :success))
          (is (= (:function-name result) "calculate-sum"))
          (is (number? (:line result)))
          (is (number? (:column result)))
          (is (string? (:file-path result))))
        
        ;; Test finding non-existent function
        (let [result (func-refactor/find-function-definition (.getAbsolutePath temp-file) "non-existent")]
          (is (= (:status result) :error))
          (is (re-find #"not found" (:error result))))
        
        (finally
          (.delete temp-file))))))

(deftest rename-function-in-file-test
  (testing "Renaming function within a single file"
    (let [temp-file (java.io.File/createTempFile "test-rename" ".clj")]
      (try
        (spit temp-file sample-function-code)
        
        ;; Test renaming function
        (let [result (func-refactor/rename-function-in-file (.getAbsolutePath temp-file) "calculate-sum" "sum-values")]
          (is (= (:status result) :success))
          (is (= (:old-name result) "calculate-sum"))
          (is (= (:new-name result) "sum-values"))
          (is (> (:replacements result) 0)))
        
        ;; Verify the file was updated
        (let [updated-content (slurp temp-file)]
          (is (re-find #"defn sum-values" updated-content))
          (is (re-find #"sum-values" updated-content))
          (is (not (re-find #"calculate-sum" updated-content))))
        
        (finally
          (.delete temp-file))))))

(deftest replace-function-definition-test
  (testing "Replacing function definition"
    (let [temp-file (java.io.File/createTempFile "test-replace" ".clj")]
      (try
        (spit temp-file sample-function-code)
        
        ;; Test replacing function definition
        (let [new-impl "(defn calculate-sum
  \"Enhanced function with input validation\"
  [a b]
  {:pre [(number? a) (number? b)]
   :post [(number? %)]}
  (+ a b))"
              result (func-refactor/replace-function-definition (.getAbsolutePath temp-file) "calculate-sum" new-impl)]
          (is (= (:status result) :success))
          (is (= (:function-name result) "calculate-sum"))
          (is (string? (:old-definition result)))
          (is (string? (:new-definition result))))
        
        ;; Verify the function was replaced
        (let [updated-content (slurp temp-file)]
          (is (re-find #":pre" updated-content))
          (is (re-find #":post" updated-content))
          (is (re-find #"Enhanced function" updated-content)))
        
        (finally
          (.delete temp-file))))))

(deftest find-function-usages-in-project-test
  (testing "Finding function usages across project"
    (let [temp-dir (java.io.File/createTempFile "test-project" "")
          temp-file1 (java.io.File. temp-dir "file1.clj")
          temp-file2 (java.io.File. temp-dir "file2.clj")]
      (try
        (.delete temp-dir)
        (.mkdirs temp-dir)
        
        ;; Create test files
        (spit temp-file1 sample-function-code)
        (spit temp-file2 "(ns test2)\n(defn other-fn [] (calculate-sum 5 10))")
        
        ;; Test finding usages (with mock nREPL client)
        (let [result (func-refactor/find-function-usages-in-project nil (.getAbsolutePath temp-dir) "calculate-sum")]
          (is (= (:status result) :success))
          (is (= (:function-name result) "calculate-sum"))
          (is (>= (count (:usages result)) 2))) ; Should find usages in both files
        
        (finally
          (.delete temp-file1)
          (.delete temp-file2)
          (.delete temp-dir))))))

(deftest rename-function-across-project-test
  (testing "Renaming function across entire project"
    (let [temp-dir (java.io.File/createTempFile "test-rename-project" "")
          temp-file1 (java.io.File. temp-dir "file1.clj")
          temp-file2 (java.io.File. temp-dir "file2.clj")]
      (try
        (.delete temp-dir)
        (.mkdirs temp-dir)
        
        ;; Create test files
        (spit temp-file1 sample-function-code)
        (spit temp-file2 "(ns test2)\n(defn other-fn [] (calculate-sum 5 10))")
        
        ;; Test renaming across project (with mock nREPL client)
        (let [result (func-refactor/rename-function-across-project nil (.getAbsolutePath temp-dir) "calculate-sum" "sum-numbers")]
          (is (= (:status result) :success))
          (is (= (:old-name result) "calculate-sum"))
          (is (= (:new-name result) "sum-numbers"))
          (is (> (:total-replacements result) 0))
          (is (> (:files-modified result) 0)))
        
        ;; Verify both files were updated
        (let [content1 (slurp temp-file1)
              content2 (slurp temp-file2)]
          (is (re-find #"defn sum-numbers" content1))
          (is (re-find #"sum-numbers 5 10" content2))
          (is (not (re-find #"calculate-sum" content1)))
          (is (not (re-find #"calculate-sum" content2))))
        
        (finally
          (.delete temp-file1)
          (.delete temp-file2)
          (.delete temp-dir))))))

(deftest error-handling-test
  (testing "Error handling for invalid inputs"
    
    ;; Test with non-existent file
    (let [result (func-refactor/find-function-definition "/non/existent/file.clj" "test-fn")]
      (is (= (:status result) :error))
      (is (re-find #"No such file" (:error result))))
    
    ;; Test with invalid directory
    (let [result (func-refactor/find-function-usages-in-project nil "/non/existent/dir" "test-fn")]
      (is (= (:status result) :error)))
    
    ;; Test with malformed code
    (let [temp-file (java.io.File/createTempFile "test-malformed" ".clj")]
      (try
        (spit temp-file "(defn broken-syntax [")
        
        (let [result (func-refactor/find-function-definition (.getAbsolutePath temp-file) "broken-syntax")]
          (is (= (:status result) :error)))
        
        (finally
          (.delete temp-file))))))

(deftest tool-integration-test
  (testing "Tool integration with MCP dispatch system"
    (let [registered-tools (dispatch/get-registered-tools)]
      
      ;; Test tool specifications
      (testing "Tool specifications are correct"
        (when-let [find-tool (:find-function-definition registered-tools)]
          (is (= (:name find-tool) :find-function-definition))
          (is (string? (:description find-tool)))
          (is (contains? (:parameters find-tool) :file-path))
          (is (contains? (:parameters find-tool) :function-name))
          (is (fn? (:handler find-tool))))
        
        (when-let [rename-tool (:rename-function-in-file registered-tools)]
          (is (= (:name rename-tool) :rename-function-in-file))
          (is (string? (:description rename-tool)))
          (is (contains? (:parameters rename-tool) :file-path))
          (is (contains? (:parameters rename-tool) :old-name))
          (is (contains? (:parameters rename-tool) :new-name))
          (is (fn? (:handler rename-tool))))))))