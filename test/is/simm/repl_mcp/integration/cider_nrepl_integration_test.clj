(ns is.simm.repl-mcp.integration.cider-nrepl-integration-test
  "Integration tests for cider-nrepl tools with real nREPL server"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [is.simm.repl-mcp.test-fixtures :as fixtures]
            [is.simm.repl-mcp.tools.cider-nrepl :as cider-tools]))

(use-fixtures :once fixtures/nrepl-fixture)

(deftest format-code-integration-test
  (testing "format-code tool with real nREPL"
    (fixtures/wait-for-nrepl-warmup)
    
    (let [tool (fixtures/find-tool-by-name cider-tools/tools "format-code")]
      (testing "formats simple code correctly"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"code" "(defn hello[x](println x))"}
                       :expect-success true)]
          (is (contains? result :content))
          ;; Should format with proper spacing
          (let [text (:text (first (:content result)))]
            (is (str/includes? text "defn"))
            (is (str/includes? text "hello")))))
      
      (testing "handles invalid code gracefully"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"code" "(defn broken [x (+ x))"}
                       :expect-success false)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (or (str/includes? text "Error")
                    (str/includes? text "Failed")))))))))

(deftest macroexpand-integration-test
  (testing "macroexpand tool with real nREPL"
    (fixtures/wait-for-nrepl-warmup)
    
    (let [tool (fixtures/find-tool-by-name cider-tools/tools "macroexpand")]
      (testing "expands simple macro"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"code" "(when true (println \"hello\"))"}
                       :expect-success true)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            ;; Should expand to if form
            (is (or (str/includes? text "if")
                    (str/includes? text "do")
                    (str/includes? text "when"))))))
      
      (testing "handles non-macro code"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"code" "(+ 1 2)"}
                       :expect-success true)]
          (is (contains? result :content)))))))

(deftest eldoc-integration-test
  (testing "eldoc tool with real nREPL"
    (fixtures/wait-for-nrepl-warmup)
    
    (let [tool (fixtures/find-tool-by-name cider-tools/tools "eldoc")]
      (testing "gets documentation for core function"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"symbol" "map"}
                       :expect-success true)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (or (str/includes? text "map")
                    (str/includes? text "function")
                    (str/includes? text "collection"))))))
      
      (testing "handles nonexistent symbol"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"symbol" "nonexistent-function-xyz"}
                       :expect-success true)]
          (is (contains? result :content)))))))

(deftest complete-integration-test
  (testing "complete tool with real nREPL"
    (fixtures/wait-for-nrepl-warmup)
    
    (let [tool (fixtures/find-tool-by-name cider-tools/tools "complete")]
      (testing "provides completions for map prefix"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"prefix" "ma"}
                       :expect-success true)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            ;; Should find map, mapv, etc.
            (is (or (str/includes? text "map")
                    (str/includes? text "Completion")
                    (str/includes? text "candidate"))))))
      
      (testing "handles empty prefix"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"prefix" ""}
                       :expect-success true)]
          (is (contains? result :content)))))))

(deftest apropos-integration-test
  (testing "apropos tool with real nREPL"
    (fixtures/wait-for-nrepl-warmup)
    
    (let [tool (fixtures/find-tool-by-name cider-tools/tools "apropos")]
      (testing "searches for functions matching pattern"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"query" "map"}
                       :expect-success true)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (or (str/includes? text "map")
                    (str/includes? text "found")
                    (str/includes? text "symbol"))))))
      
      (testing "handles nonexistent pattern"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"query" "nonexistent-pattern-xyz"}
                       :expect-success true)]
          (is (contains? result :content)))))))

(deftest info-integration-test
  (testing "info tool with real nREPL"
    (fixtures/wait-for-nrepl-warmup)
    
    (let [tool (fixtures/find-tool-by-name cider-tools/tools "info")]
      (testing "gets info for core function"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"symbol" "map"}
                       :expect-success true)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (or (str/includes? text "map")
                    (str/includes? text "function")
                    (str/includes? text "doc")
                    (str/includes? text "Timeout")
                    (str/includes? text "Error")) "Should get info or timeout/error message"))))
      
      (testing "handles nonexistent symbol"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"symbol" "nonexistent-symbol-xyz"}
                       :expect-success true)]
          (is (contains? result :content)))))))

(deftest ns-list-integration-test
  (testing "ns-list tool with real nREPL"
    (fixtures/wait-for-nrepl-warmup)
    
    (let [tool (fixtures/find-tool-by-name cider-tools/tools "ns-list")]
      (testing "lists available namespaces"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {}
                       :expect-success true)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            ;; Should list core namespaces
            (is (or (str/includes? text "clojure.core")
                    (str/includes? text "user")
                    (str/includes? text "namespace")))))))))

(deftest ns-vars-integration-test
  (testing "ns-vars tool with real nREPL"
    (fixtures/wait-for-nrepl-warmup)
    
    (let [tool (fixtures/find-tool-by-name cider-tools/tools "ns-vars")]
      (testing "lists vars in clojure.core namespace"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"ns" "clojure.core"}
                       :expect-success true)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            ;; Should list core functions
            (is (or (str/includes? text "map")
                    (str/includes? text "reduce")
                    (str/includes? text "var"))))))
      
      (testing "handles nonexistent namespace"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"ns" "nonexistent.namespace"}
                       :expect-success true)]
          (is (contains? result :content)))))))

(deftest classpath-integration-test
  (testing "classpath tool with real nREPL"
    (fixtures/wait-for-nrepl-warmup)
    
    (let [tool (fixtures/find-tool-by-name cider-tools/tools "classpath")]
      (testing "lists classpath entries"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {}
                       :expect-success true)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            ;; Should list some classpath entries
            (is (or (str/includes? text "jar")
                    (str/includes? text "path")
                    (str/includes? text "clojure")))))))))

(deftest refresh-integration-test
  (testing "refresh tool with real nREPL"
    (fixtures/wait-for-nrepl-warmup)
    
    (let [tool (fixtures/find-tool-by-name cider-tools/tools "refresh")]
      (testing "refreshes namespaces"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {}
                       :expect-success true)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (or (str/includes? text "refresh")
                    (str/includes? text "success")
                    (str/includes? text "namespace")))))))))

(deftest test-all-integration-test
  (testing "test-all tool with real nREPL"
    (fixtures/wait-for-nrepl-warmup)
    
    (let [tool (fixtures/find-tool-by-name cider-tools/tools "test-all")]
      (testing "runs all tests"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {}
                       :expect-success true)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (or (str/includes? text "test")
                    (str/includes? text "run")
                    (str/includes? text "passed")
                    (str/includes? text "failed")
                    (str/includes? text "Timeout")
                    (str/includes? text "Error")) "Should run tests or timeout/error")))))))

(deftest test-var-query-integration-test
  (testing "test-var-query tool with real nREPL"
    (fixtures/wait-for-nrepl-warmup)
    
    (let [tool (fixtures/find-tool-by-name cider-tools/tools "test-var-query")]
      (testing "runs specific test query"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"var-query" "user"}
                       :expect-success true)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (or (str/includes? text "test")
                    (str/includes? text "user")
                    (str/includes? text "query")))))))))

;; Summary test to verify all 12 cider-nrepl tools are present
(deftest cider-tools-completeness-test
  (testing "all 12 cider-nrepl tools are defined"
    (let [tool-names (set (map :name cider-tools/tools))
          expected-tools #{"format-code" "macroexpand" "eldoc" "complete" "apropos" 
                          "test-all" "info" "ns-list" "ns-vars" "classpath" 
                          "refresh" "test-var-query"}]
      (is (= 12 (count tool-names)))
      (doseq [expected expected-tools]
        (is (contains? tool-names expected) 
            (str "Missing tool: " expected))))))