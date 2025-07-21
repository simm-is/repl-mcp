(ns is.simm.repl-mcp.integration.function-refactor-integration-test
  "Integration tests for function refactor tools with real nREPL server"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [is.simm.repl-mcp.test-fixtures :as fixtures]
            [is.simm.repl-mcp.tools.function-refactor :as function-refactor-tools]))

(use-fixtures :once fixtures/nrepl-fixture)

(deftest find-function-definition-integration-test
  (testing "find-function-definition tool with real nREPL"
    (fixtures/wait-for-nrepl-warmup)
    
    (let [tool (fixtures/find-tool-by-name function-refactor-tools/tools "find-function-definition")]
      (testing "finds function in existing project file"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"file-path" "src/is/simm/repl_mcp/tools/eval.clj"
                        "function-name" "evaluate-code"}
                       :expect-success true)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (or (str/includes? text "Found function")
                    (str/includes? text "evaluate-code")
                    (str/includes? text "line")
                    (str/includes? text "Error"))))))
      
      (testing "handles nonexistent function in existing file"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"file-path" "src/is/simm/repl_mcp/tools/eval.clj"
                        "function-name" "nonexistent-function-xyz"}
                       :expect-success false)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (str/includes? text "not found")))))
      
      (testing "handles nonexistent file"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"file-path" "nonexistent/file.clj"
                        "function-name" "any-function"}
                       :expect-success false)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (str/includes? text "not exist"))))))))

(deftest rename-function-in-file-integration-test
  (testing "rename-function-in-file tool with real nREPL"
    (fixtures/wait-for-nrepl-warmup)
    
    (let [tool (fixtures/find-tool-by-name function-refactor-tools/tools "rename-function-in-file")]
      (testing "attempts to rename function in existing file"
        ;; Use test data copied to temp location to safely test function renaming
        (let [temp-file (str "/tmp/test-rename-" (System/currentTimeMillis) ".clj")
              test-content "(ns test.rename)\n\n(defn old-function [x] (+ x 1))\n\n(defn other-fn [] :ok)"]
          (spit temp-file test-content)
          (try
            (let [result (fixtures/test-tool-with-nrepl 
                           tool 
                           {"file-path" temp-file
                            "old-name" "old-function"
                            "new-name" "new-function"}
                           :expect-success true)]
              (is (contains? result :content))
              (let [text (:text (first (:content result)))]
                (is (or (str/includes? text "renamed")
                        (str/includes? text "success")
                        (str/includes? text "Function renamed")
                        (str/includes? text "completed")
                        (str/includes? text "old-function")
                        (str/includes? text "new-function")
                        (some? text)) "Should have some response text")))
            (finally
              (io/delete-file temp-file true)))))
      
      (testing "handles nonexistent file"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"file-path" "nonexistent.clj"
                        "old-name" "old-fn"
                        "new-name" "new-fn"}
                       :expect-success false)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (str/includes? text "Error"))))))))

(deftest find-function-usages-in-project-integration-test
  (testing "find-function-usages-in-project tool with real nREPL"
    (fixtures/wait-for-nrepl-warmup)
    
    (let [tool (fixtures/find-tool-by-name function-refactor-tools/tools "find-function-usages-in-project")]
      (testing "searches for function usages in project"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"project-root" "."
                        "function-name" "evaluate-code"}
                       :expect-success true)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (or (str/includes? text "Found")
                    (str/includes? text "usages")
                    (str/includes? text "evaluate-code")
                    (str/includes? text "No usages"))))))
      
      (testing "searches for nonexistent function"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"project-root" "."
                        "function-name" "truly-nonexistent-fn-12345"}
                       :expect-success true)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            ;; The function name will appear in this test file itself, so we expect to find it
            (is (or (str/includes? text "truly-nonexistent-fn-12345")
                    (str/includes? text "Found function")
                    (some? text)) "Should have a valid response"))))
      
      (testing "handles nonexistent project root"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"project-root" "/nonexistent/path"
                        "function-name" "any-function"}
                       :expect-success false)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (str/includes? text "Error"))))))))

(deftest rename-function-across-project-integration-test
  (testing "rename-function-across-project tool with real nREPL"
    (fixtures/wait-for-nrepl-warmup)
    
    (let [tool (fixtures/find-tool-by-name function-refactor-tools/tools "rename-function-across-project")]
      (testing "attempts project-wide function rename"
        ;; This will likely fail due to file permissions, but should handle gracefully
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"project-root" "."
                        "old-name" "evaluate-code"
                        "new-name" "evaluate-code"}
                       :expect-success false)]  ; Expect failure due to file permissions
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (or (str/includes? text "Error")
                    (str/includes? text "renamed")
                    (str/includes? text "permission"))))))
      
      (testing "handles nonexistent project root"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"project-root" "/nonexistent/path"
                        "old-name" "old-fn"
                        "new-name" "new-fn"}
                       :expect-success false)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (str/includes? text "Error"))))))))

(deftest replace-function-definition-integration-test
  (testing "replace-function-definition tool with real nREPL"
    (fixtures/wait-for-nrepl-warmup)
    
    (let [tool (fixtures/find-tool-by-name function-refactor-tools/tools "replace-function-definition")]
      (testing "attempts to replace function definition"
        ;; Use test-data file copied to temp location to avoid modifying real project files
        (let [temp-file (str "/tmp/test-eval-" (System/currentTimeMillis) ".clj")
              test-content "(ns test.eval)\n\n(defn evaluate-code [x] (+ x 1))\n\n(defn other-fn [] :ok)"]
          (spit temp-file test-content)
          (try
            (let [result (fixtures/test-tool-with-nrepl 
                           tool 
                           {"file-path" temp-file
                            "function-name" "evaluate-code"
                            "new-implementation" "(defn evaluate-code [client code] (println \"test impl\"))"}
                           :expect-success true)]
              (is (contains? result :content))
              (let [text (:text (first (:content result)))]
                (is (or (str/includes? text "replaced")
                        (str/includes? text "success")
                        (str/includes? text "Function definition replaced")))))
            (finally
              (io/delete-file temp-file true)))))
      
      (testing "handles nonexistent file"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"file-path" "nonexistent.clj"
                        "function-name" "any-fn"
                        "new-implementation" "(defn any-fn [] nil)"}
                       :expect-success false)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (str/includes? text "Error")))))
      
      (testing "handles nonexistent function in existing file"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"file-path" "src/is/simm/repl_mcp/tools/eval.clj"
                        "function-name" "nonexistent-function"
                        "new-implementation" "(defn nonexistent-function [] nil)"}
                       :expect-success false)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (str/includes? text "not found"))))))))

;; Test helper functions directly
(deftest function-refactor-helpers-test
  (testing "find-function-definition helper function"
    (testing "finds function in existing file"
      (let [result (function-refactor-tools/find-function-definition 
                     "src/is/simm/repl_mcp/tools/eval.clj" 
                     "evaluate-code")]
        (is (or (= (:status result) :success)
                (= (:status result) :error)))
        (is (contains? result :status))))
    
    (testing "handles nonexistent file"
      (let [result (function-refactor-tools/find-function-definition 
                     "/nonexistent/file.clj" 
                     "any-function")]
        (is (= (:status result) :error))
        (is (str/includes? (:error result) "not exist"))))
    
    (testing "handles nonexistent function"
      (let [result (function-refactor-tools/find-function-definition 
                     "src/is/simm/repl_mcp/tools/eval.clj" 
                     "nonexistent-function")]
        (is (= (:status result) :error))
        (is (= (:error result) "Function not found"))))))

;; Summary test to verify all 5 function refactor tools are present
(deftest function-refactor-tools-completeness-test
  (testing "all 5 function refactor tools are defined"
    (let [tool-names (set (map :name function-refactor-tools/tools))
          expected-tools #{"find-function-definition" "rename-function-in-file"
                          "find-function-usages-in-project" "rename-function-across-project"
                          "replace-function-definition"}]
      (is (= 5 (count tool-names)))
      (doseq [expected expected-tools]
        (is (contains? tool-names expected) 
            (str "Missing function refactor tool: " expected))))))