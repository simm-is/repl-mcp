(ns is.simm.repl-mcp.tools.cider-nrepl-test
  (:require [clojure.test :refer [deftest is testing]]
            [is.simm.repl-mcp.tools.cider-nrepl :as cider-tools]
            [clojure.string :as str]))

(deftest function-existence-test
  (testing "cider-nrepl tool functions exist"
    (is (fn? cider-tools/format-code-tool))
    (is (fn? cider-tools/macroexpand-tool))
    (is (fn? cider-tools/eldoc-tool))
    (is (fn? cider-tools/complete-tool))
    (is (fn? cider-tools/apropos-tool))
    (is (fn? cider-tools/test-all-tool))))

(deftest tools-definitions-test
  (testing "tools vector exists and has correct structure"
    (is (vector? cider-tools/tools))
    (is (= 12 (count cider-tools/tools)))
    
    (testing "all tools have required fields"
      (doseq [tool cider-tools/tools]
        (is (string? (:name tool)))
        (is (string? (:description tool)))
        (is (map? (:inputSchema tool)))
        (is (fn? (:tool-fn tool)))))
    
    (testing "specific tool names"
      (let [tool-names (set (map :name cider-tools/tools))]
        (is (contains? tool-names "format-code"))
        (is (contains? tool-names "macroexpand"))
        (is (contains? tool-names "eldoc"))
        (is (contains? tool-names "complete"))
        (is (contains? tool-names "apropos"))
        (is (contains? tool-names "test-all"))))))

(deftest error-handling-test
  (testing "cider tool functions handle nil context gracefully"
    (testing "format-code-tool with nil context"
      (let [result (cider-tools/format-code-tool {:code "(defn test [x] x)"} {})]
        (is (map? result))
        (is (contains? result :content))))
    
    (testing "eldoc-tool with nil context"
      (let [result (cider-tools/eldoc-tool {:symbol "map"} {})]
        (is (map? result))
        (is (contains? result :content))))
    
    (testing "complete-tool with nil context"
      (let [result (cider-tools/complete-tool {:prefix "ma"} {})]
        (is (map? result))
        (is (contains? result :content))))))

(deftest tool-handler-test
  (testing "tool handlers return proper MCP format"
    (testing "format-code-tool handler"
      (let [tool-fn (:tool-fn (first (filter #(= "format-code" (:name %)) cider-tools/tools)))
            result (tool-fn {} {:code "(defn test [x] x)"})]
        (is (map? result))
        (is (contains? result :content))
        (is (vector? (:content result)))
        (is (= "text" (:type (first (:content result)))))
        (is (str/includes? (:text (first (:content result))) "Error"))))
    
    (testing "eldoc-tool handler"
      (let [tool-fn (:tool-fn (first (filter #(= "eldoc" (:name %)) cider-tools/tools)))
            result (tool-fn {} {:symbol "map"})]
        (is (map? result))
        (is (contains? result :content))
        (is (str/includes? (:text (first (:content result))) "Error"))))))