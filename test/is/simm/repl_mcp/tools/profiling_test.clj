(ns is.simm.repl-mcp.tools.profiling-test
  (:require [clojure.test :refer [deftest is testing]]
            [is.simm.repl-mcp.tools.profiling :as profiling-tools]
            [clojure.string :as str]))

(deftest function-existence-test
  (testing "profiling functions exist"
    (is (fn? profiling-tools/profile-expression-comprehensive))
    (is (fn? profiling-tools/format-profile-result))
    (is (fn? profiling-tools/profile-cpu-tool))
    (is (fn? profiling-tools/profile-alloc-tool))))

(deftest tools-definitions-test
  (testing "tools vector exists and has correct structure"
    (is (vector? profiling-tools/tools))
    (is (= 2 (count profiling-tools/tools)))
    
    (testing "all tools have required fields"
      (doseq [tool profiling-tools/tools]
        (is (string? (:name tool)))
        (is (string? (:description tool)))
        (is (map? (:inputSchema tool)))
        (is (fn? (:tool-fn tool)))))
    
    (testing "specific tool names"
      (let [tool-names (set (map :name profiling-tools/tools))]
        (is (contains? tool-names "profile-cpu"))
        (is (contains? tool-names "profile-alloc"))))))


(deftest error-handling-test
  (testing "profiling functions handle nil nREPL client gracefully"
    (testing "profile-expression-comprehensive with nil client"
      (let [result (profiling-tools/profile-expression-comprehensive nil "(+ 1 2)")]
        (is (= (:status result) :error))
        (is (some? (:error result)))))
    
    (testing "profile with invalid code"
      (let [result (profiling-tools/profile-expression-comprehensive nil "invalid-code")]
        (is (= (:status result) :error))))))

(deftest tool-handler-test
  (testing "tool handlers return proper MCP format"
    (testing "profile-cpu-tool handler"
      (let [tool-fn (:tool-fn (first (filter #(= "profile-cpu" (:name %)) profiling-tools/tools)))
            result (tool-fn {} {:expression "(+ 1 2)"})]
        (is (map? result))
        (is (contains? result :content))
        (is (vector? (:content result)))
        (is (= "text" (:type (first (:content result)))))
        (is (str/includes? (:text (first (:content result))) "Error"))))
    
    (testing "profile-alloc-tool handler"
      (let [tool-fn (:tool-fn (first (filter #(= "profile-alloc" (:name %)) profiling-tools/tools)))
            result (tool-fn {} {:expression "(range 1000)"})]
        (is (map? result))
        (is (contains? result :content))
        (is (str/includes? (:text (first (:content result))) "Error"))))
    
    (testing "profile-cpu-tool with duration parameter"
      (let [tool-fn (:tool-fn (first (filter #(= "profile-cpu" (:name %)) profiling-tools/tools)))
            result (tool-fn {} {:expression "(+ 1 2)" :duration "3000"})]
        (is (map? result))
        (is (contains? result :content))))))