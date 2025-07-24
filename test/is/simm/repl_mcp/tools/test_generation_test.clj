(ns is.simm.repl-mcp.tools.test-generation-test
  (:require [clojure.test :refer [deftest is testing]]
            [is.simm.repl-mcp.tools.test-generation :as test-gen-tools]
            [clojure.string :as str]))

(deftest function-existence-test
  (testing "test generation functions exist"
    (is (fn? test-gen-tools/generate-test-skeleton))
    (is (fn? test-gen-tools/create-test-skeleton-tool))))

(deftest tools-definitions-test
  (testing "tools vector exists and has correct structure"
    (is (vector? test-gen-tools/tools))
    (is (= 1 (count test-gen-tools/tools)))
    
    (testing "test generation tool has required fields"
      (let [tool (first test-gen-tools/tools)]
        (is (= "create-test-skeleton" (:name tool)))
        (is (string? (:description tool)))
        (is (map? (:inputSchema tool)))
        (is (fn? (:tool-fn tool)))))))

(deftest generate-test-function-test
  (testing "generate-test-skeleton with various inputs"
    (testing "with valid namespace and function"
      (let [result (test-gen-tools/generate-test-skeleton "test.ns" "add" "[a b]" "(+ a b)")]
        (is (= (:status result) :success))
        (is (some? (:test-code result)))
        (is (str/includes? (:test-code result) "deftest"))
        (is (str/includes? (:test-code result) "add"))))
    
    (testing "with minimal inputs"
      (let [result (test-gen-tools/generate-test-skeleton "test.ns" "simple-fn")]
        (is (= (:status result) :success))
        (is (str/includes? (:test-code result) "simple-fn-test"))))
    
    (testing "with complex function parameters"
      (let [result (test-gen-tools/generate-test-skeleton "test.ns" "complex-fn" "[{:keys [a b]} & rest]")]
        (is (= (:status result) :success))
        (is (str/includes? (:test-code result) "complex-fn"))))))

(deftest tool-handler-test
  (testing "generate-test-tool handler"
    (let [tool-fn (:tool-fn (first test-gen-tools/tools))]
      (testing "with valid inputs"
        (let [result (tool-fn {} {:namespace "test.ns" :function-name "test-fn"})]
          (is (map? result))
          (is (contains? result :content))
          (is (vector? (:content result)))
          (is (= "text" (:type (first (:content result)))))
          (is (str/includes? (:text (first (:content result))) "deftest"))))
      
      (testing "with full parameters"
        (let [result (tool-fn {} {:namespace "math.core" 
                                  :function-name "add" 
                                  :parameters "[a b]" 
                                  :body "(+ a b)"})]
          (is (map? result))
          (is (str/includes? (:text (first (:content result))) "add")))))))