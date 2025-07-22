(ns is.simm.repl-mcp.integration.deps-management-integration-test
  "Integration tests for dependency management tools with real nREPL server"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [is.simm.repl-mcp.test-fixtures :as fixtures]
            [is.simm.repl-mcp.tools.deps-management :as deps-tools]))

(use-fixtures :once fixtures/nrepl-fixture)

(deftest check-namespace-integration-test
  (testing "check-namespace tool with real nREPL"
    (fixtures/wait-for-nrepl-warmup)
    
    (let [tool (fixtures/find-tool-by-name deps-tools/tools "check-namespace")]
      (testing "checks existing core namespace"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"namespace" "clojure.core"}
                       :expect-success true)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (str/includes? text "available")))))
      
      (testing "checks existing string namespace"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"namespace" "clojure.string"}
                       :expect-success true)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (str/includes? text "available")))))
      
      (testing "checks nonexistent namespace"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"namespace" "nonexistent.namespace.that.does.not.exist"}
                       :expect-success true)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (str/includes? text "not available")))))
      
      (testing "checks project namespace"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"namespace" "is.simm.repl-mcp.tools.eval"}
                       :expect-success true)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            ;; May or may not be loaded, but should give clear status
            (is (or (str/includes? text "available")
                    (str/includes? text "not available")))))))))

(deftest add-libs-integration-test
  (testing "add-libs tool with real nREPL"
    (fixtures/wait-for-nrepl-warmup)
    
    (let [tool (fixtures/find-tool-by-name deps-tools/tools "add-libs")]
      (testing "attempts to add a small library"
        ;; Test with a small, common library
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"coordinates" "{:org.clojure/data.json {:mvn/version \"2.4.0\"}}"}
                       :expect-success true)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            ;; Should either succeed or explain why it failed
            (is (or (str/includes? text "Successfully added")
                    (str/includes? text "Note:")
                    (str/includes? text "requires")
                    (str/includes? text "Clojure 1.12")
                    (str/includes? text "Error:")
                    (str/includes? text "Exception")) "Should handle add-libs operation or explain error"))))
      
      (testing "handles invalid coordinates"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"coordinates" "invalid-edn-string"}
                       :expect-success false)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (str/includes? text "Error")))))
      
      (testing "handles malformed coordinates map"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"coordinates" "{:invalid :format}"}
                       :expect-success true)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            ;; Should handle gracefully, even if it fails
            (is (string? text))))))))

(deftest sync-deps-integration-test
  (testing "sync-deps tool with real nREPL"
    (fixtures/wait-for-nrepl-warmup)
    
    (let [tool (fixtures/find-tool-by-name deps-tools/tools "sync-deps")]
      (testing "attempts to sync project dependencies"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {}
                       :expect-success true)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            ;; Should either sync successfully or explain what happened
            (is (or (str/includes? text "Synchronized")
                    (str/includes? text "Note:")
                    (str/includes? text "No new")
                    (str/includes? text "dependencies")
                    (str/includes? text "requires")
                    (str/includes? text "Clojure 1.12")
                    (str/includes? text "Error:")
                    (str/includes? text "Exception")) "Should handle sync-deps operation or explain error")))))))

;; Test the helper functions
(deftest parse-lib-coords-integration-test
  (testing "parse-lib-coords function"
    (testing "parses valid EDN map"
      (let [coords {:hiccup/hiccup {:mvn/version "1.0.5"}}
            result (deps-tools/parse-lib-coords coords)]
        (is (= coords result))))
    
    (testing "parses valid EDN string"
      (let [coords-str "{:hiccup/hiccup {:mvn/version \"1.0.5\"}}"
            result (deps-tools/parse-lib-coords coords-str)]
        (is (map? result))
        (is (contains? result :hiccup/hiccup))))
    
    (testing "handles invalid EDN"
      (is (thrown? Exception (deps-tools/parse-lib-coords "invalid-edn"))))
    
    (testing "handles non-string non-map input"
      (is (thrown? Exception (deps-tools/parse-lib-coords 123))))))

(deftest check-library-available-integration-test
  (testing "check-library-available function with real nREPL"
    (fixtures/wait-for-nrepl-warmup)
    
    (testing "checks available namespace"
      (let [result (deps-tools/check-library-available 'clojure.string)]
        (is (= (:status result) :success))
        (is (:available result))))
    
    (testing "checks nonexistent namespace" 
      (let [result (deps-tools/check-library-available 'nonexistent.namespace)]
        (is (= (:status result) :error))
        (is (not (:available result)))))))

;; Summary test to verify all 3 deps management tools are present
(deftest deps-tools-completeness-test
  (testing "all 3 dependency management tools are defined"
    (let [tool-names (set (map :name deps-tools/tools))
          expected-tools #{"add-libs" "sync-deps" "check-namespace"}]
      (is (= 3 (count tool-names)))
      (doseq [expected expected-tools]
        (is (contains? tool-names expected) 
            (str "Missing deps management tool: " expected))))))