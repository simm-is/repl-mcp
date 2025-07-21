(ns is.simm.repl-mcp.tools.clj-kondo-test
  (:require [clojure.test :refer [deftest is testing]]
            [is.simm.repl-mcp.tools.clj-kondo :as clj-kondo-tools]
            [clojure.string :as str]))

(deftest function-existence-test
  (testing "clj-kondo functions exist"
    (is (fn? clj-kondo-tools/lint-code-string))
    (is (fn? clj-kondo-tools/lint-files))
    (is (fn? clj-kondo-tools/setup-project-config))))

(deftest mcp-contract-test
  (testing "clj-kondo tools provide basic MCP contract compliance"
    (is (vector? clj-kondo-tools/tools))
    (is (= 3 (count clj-kondo-tools/tools)))
    
    (testing "all tools have required MCP fields"
      (doseq [tool clj-kondo-tools/tools]
        (is (string? (:name tool)))
        (is (string? (:description tool)))
        (is (map? (:inputSchema tool)))
        (is (fn? (:tool-fn tool)))))
    
    (testing "specific tool names"
      (let [tool-names (set (map :name clj-kondo-tools/tools))]
        (is (contains? tool-names "lint-code"))
        (is (contains? tool-names "lint-project"))
        (is (contains? tool-names "setup-clj-kondo"))))))

(deftest lint-code-string-test
  (testing "lint-code-string with valid code"
    (let [result (clj-kondo-tools/lint-code-string "(defn test-fn [x] (+ x 1))")]
      ;; clj-kondo returns :success with findings, not :error status
      (is (= (:status result) :success))
      (is (or (contains? result :findings) (contains? result :message)))))
  
  (testing "lint-code-string with invalid code"
    (let [result (clj-kondo-tools/lint-code-string "(defn broken [x (+ x 1))")]
      (is (= (:status result) :success))
      (is (or (contains? result :findings) (contains? result :error))))))

(deftest mcp-tool-test
  (testing "lint-code tool returns proper MCP format"
    (let [tool-fn (:tool-fn (first (filter #(= "lint-code" (:name %)) clj-kondo-tools/tools)))
          result (tool-fn {} {:code "(defn test [x] (+ x 1))"})]
      (is (map? result))
      (is (contains? result :content))
      (is (vector? (:content result)))
      (is (= "text" (:type (first (:content result))))))))