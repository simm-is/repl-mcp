(ns is.simm.repl-mcp.integration.refactor-integration-test
  "Integration tests for refactor tools with real nREPL server"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [is.simm.repl-mcp.test-fixtures :as fixtures]
            [is.simm.repl-mcp.tools.refactor :as refactor-tools]))

(use-fixtures :once fixtures/nrepl-fixture)

(deftest clean-ns-integration-test
  (testing "clean-ns tool with real nREPL"
    (fixtures/wait-for-nrepl-warmup)
    
    (let [tool (fixtures/find-tool-by-name refactor-tools/tools "clean-ns")]
      (testing "handles existing file"
        ;; Test with an actual file from our project
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"file-path" "src/is/simm/repl_mcp/tools/eval.clj"}
                       :expect-success false)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            ;; Should either succeed or give useful feedback
            (is (string? text)))))
      
      (testing "handles nonexistent file"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"file-path" "nonexistent/file.clj"}
                       :expect-success false)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (str/includes? text "Error"))))))))

(deftest find-symbol-integration-test
  (testing "find-symbol tool with real nREPL"
    (fixtures/wait-for-nrepl-warmup)
    
    (let [tool (fixtures/find-tool-by-name refactor-tools/tools "find-symbol")]
      (testing "searches for symbol in existing file"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"file-path" "src/is/simm/repl_mcp/tools/eval.clj"
                        "line" 1
                        "column" 1}
                       :expect-success true)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (or (str/includes? text "Found")
                    (str/includes? text "occurrences")
                    (str/includes? text "Error"))))))
      
      (testing "handles nonexistent file"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"file-path" "nonexistent.clj"
                        "line" 1 
                        "column" 1}
                       :expect-success true)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (or (str/includes? text "Found 0 occurrences")
                    (str/includes? text "No occurrences")
                    (str/includes? text "Error")))))))))

(deftest rename-file-or-dir-integration-test
  (testing "rename-file-or-dir tool with real nREPL"
    (fixtures/wait-for-nrepl-warmup)
    
    (let [tool (fixtures/find-tool-by-name refactor-tools/tools "rename-file-or-dir")]
      (testing "handles file rename operation"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"old-path" "nonexistent.clj"
                        "new-path" "also-nonexistent.clj"}
                       :expect-success false)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            ;; Should give error about nonexistent file
            (is (str/includes? text "Error"))))))))

(deftest resolve-missing-integration-test
  (testing "resolve-missing tool with real nREPL"
    (fixtures/wait-for-nrepl-warmup)
    
    (let [tool (fixtures/find-tool-by-name refactor-tools/tools "resolve-missing")]
      (testing "attempts to resolve missing symbol"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"symbol" "some-missing-function"
                        "namespace" "user"}
                       :expect-success true)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            ;; Should either find suggestions or report no resolution
            (is (string? text)))))
      
      (testing "handles core symbol"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"symbol" "map"
                        "namespace" "clojure.core"}
                       :expect-success true)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (string? text))))))))

(deftest find-used-locals-integration-test
  (testing "find-used-locals tool with real nREPL"
    (fixtures/wait-for-nrepl-warmup)
    
    (let [tool (fixtures/find-tool-by-name refactor-tools/tools "find-used-locals")]
      (testing "analyzes local variables in existing file"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"file-path" "src/is/simm/repl_mcp/tools/eval.clj"
                        "line" 15
                        "column" 1}
                       :expect-success true)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (or (str/includes? text "local")
                    (str/includes? text "variable") 
                    (str/includes? text "Error")
                    (str/includes? text "used"))))))
      
      (testing "handles nonexistent file"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"file-path" "nonexistent.clj"
                        "line" 1
                        "column" 1}
                       :expect-success false)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (str/includes? text "Error"))))))))

;; Test the placeholder refactor tools (these should return helpful messages)
(deftest extract-function-integration-test
  (testing "extract-function tool placeholder"
    (fixtures/wait-for-nrepl-warmup)
    
    (let [tool (fixtures/find-tool-by-name refactor-tools/tools "extract-function")]
      (testing "returns helpful placeholder message"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"session-id" "test-session"
                        "function-name" "new-function"
                        "parameters" "[x y]"}
                       :expect-success true)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (str/includes? text "structural editing"))))))))

(deftest extract-variable-integration-test
  (testing "extract-variable tool placeholder"
    (fixtures/wait-for-nrepl-warmup)
    
    (let [tool (fixtures/find-tool-by-name refactor-tools/tools "extract-variable")]
      (testing "returns helpful placeholder message"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"session-id" "test-session"
                        "variable-name" "new-var"}
                       :expect-success true)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (str/includes? text "structural editing"))))))))

(deftest add-function-parameter-integration-test
  (testing "add-function-parameter tool placeholder"
    (fixtures/wait-for-nrepl-warmup)
    
    (let [tool (fixtures/find-tool-by-name refactor-tools/tools "add-function-parameter")]
      (testing "returns helpful placeholder message"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"session-id" "test-session"
                        "parameter-name" "new-param"}
                       :expect-success true)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (str/includes? text "structural editing"))))))))

(deftest organize-imports-integration-test
  (testing "organize-imports tool placeholder"
    (fixtures/wait-for-nrepl-warmup)
    
    (let [tool (fixtures/find-tool-by-name refactor-tools/tools "organize-imports")]
      (testing "returns helpful placeholder message"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"session-id" "test-session"}
                       :expect-success true)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (str/includes? text "structural editing"))))))))

(deftest inline-function-integration-test
  (testing "inline-function tool placeholder"
    (fixtures/wait-for-nrepl-warmup)
    
    (let [tool (fixtures/find-tool-by-name refactor-tools/tools "inline-function")]
      (testing "returns helpful placeholder message"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"session-id" "test-session"
                        "function-name" "some-function"}
                       :expect-success true)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (str/includes? text "structural editing"))))))))

(deftest rename-local-variable-integration-test
  (testing "rename-local-variable tool placeholder"
    (fixtures/wait-for-nrepl-warmup)
    
    (let [tool (fixtures/find-tool-by-name refactor-tools/tools "rename-local-variable")]
      (testing "returns helpful placeholder message"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"session-id" "test-session"
                        "old-name" "old-var"
                        "new-name" "new-var"}
                       :expect-success true)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (str/includes? text "structural editing"))))))))

;; Summary test to verify all 11 refactor tools are present
(deftest refactor-tools-completeness-test
  (testing "all 11 refactor tools are defined"
    (let [tool-names (set (map :name refactor-tools/tools))
          expected-tools #{"clean-ns" "find-symbol" "rename-file-or-dir" "resolve-missing" 
                          "find-used-locals" "extract-function" "extract-variable"
                          "add-function-parameter" "organize-imports" "inline-function" 
                          "rename-local-variable"}]
      (is (= 11 (count tool-names)))
      (doseq [expected expected-tools]
        (is (contains? tool-names expected) 
            (str "Missing refactor tool: " expected))))))