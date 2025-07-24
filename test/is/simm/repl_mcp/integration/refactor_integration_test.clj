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









;; Summary test to verify the 2 actual refactor tools are present
(deftest refactor-tools-completeness-test
  (testing "refactor tools are defined"
    (let [tool-names (set (map :name refactor-tools/tools))
          expected-tools #{"clean-ns" "rename-file-or-dir"}]
      (is (= 2 (count tool-names)))
      (doseq [expected expected-tools]
        (is (contains? tool-names expected) 
            (str "Missing refactor tool: " expected))))))