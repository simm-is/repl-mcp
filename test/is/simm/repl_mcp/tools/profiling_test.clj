(ns is.simm.repl-mcp.tools.profiling-test
  (:require [clojure.test :refer [deftest is testing]]
            [is.simm.repl-mcp.tools.profiling :as profiling-tools]
            [clojure.string :as str]))

(deftest function-existence-test
  (testing "profiling functions exist"
    (is (fn? profiling-tools/profile-expression-comprehensive))
    (is (fn? profiling-tools/analyze-profile-data))
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
        (is (= (:error result) "nREPL client not available"))))
    
    (testing "profile-expression-comprehensive returns error on nil client"
      (let [result (profiling-tools/profile-expression-comprehensive nil "invalid-code")]
        (is (= (:status result) :error))
        (is (some? (:error result)))))))

(deftest tool-handler-test
  (testing "tool handlers return proper MCP format"
    (testing "profile-cpu-tool handler with nil client"
      (let [tool-fn (:tool-fn (first (filter #(= "profile-cpu" (:name %)) profiling-tools/tools)))
            result (tool-fn {} {:code "(+ 1 2)"})]
        (is (map? result))
        (is (contains? result :content))
        (is (vector? (:content result)))
        (is (= "text" (:type (first (:content result)))))
        (is (str/includes? (:text (first (:content result))) "Error"))))
    
    (testing "profile-alloc-tool handler with nil client"
      (let [tool-fn (:tool-fn (first (filter #(= "profile-alloc" (:name %)) profiling-tools/tools)))
            result (tool-fn {} {:code "(range 1000)"})]
        (is (map? result))
        (is (contains? result :content))
        (is (str/includes? (:text (first (:content result))) "Error"))))
    
    (testing "profile-cpu-tool with duration parameter"
      (let [tool-fn (:tool-fn (first (filter #(= "profile-cpu" (:name %)) profiling-tools/tools)))
            result (tool-fn {} {:code "(+ 1 2)" :duration 3000})]
        (is (map? result))
        (is (contains? result :content))))
    
    (testing "profile-cpu-tool validates input parameters"
      (let [tool-fn (:tool-fn (first (filter #(= "profile-cpu" (:name %)) profiling-tools/tools)))]
        ;; Test with missing required code parameter
        (let [result (tool-fn {} {})]
          (is (map? result))
          (is (contains? result :content))
          (is (str/includes? (:text (first (:content result))) "Error")))))))

(deftest analyze-profile-data-test
  (testing "analyze-profile-data handles empty data gracefully"
    (let [empty-data {:stacks [] :id->frame {}}
          result (profiling-tools/analyze-profile-data empty-data :cpu 1000)]
      (is (map? result))
      (is (= 0 (get-in result [:summary :total-samples])))
      (is (= :success (get-in result [:summary :status])))))
  
  (testing "analyze-profile-data produces expected structure"
    (let [test-data {:stacks [[[1 2 3] 5] [[1 2] 3]] 
                     :id->frame {1 "foo" 2 "bar" 3 "baz"}}
          result (profiling-tools/analyze-profile-data test-data :cpu 1000)]
      (is (map? result))
      (is (contains? result :summary))
      (is (contains? result :call-tree))
      (is (contains? result :all-functions))
      (is (contains? result :hot-paths))
      (is (= 8 (get-in result [:summary :total-samples])))
      (is (= 2 (get-in result [:summary :unique-stacks]))))))

(deftest format-profile-result-test
  (testing "format-profile-result produces readable output"
    (let [test-result {:summary {:event-type "cpu"
                                :duration-ms 1000
                                :samples-per-second 100.0
                                :total-samples 100
                                :unique-stacks 10
                                :unique-functions 20}
                      :all-functions [{:frame "foo" :samples 50 :percentage 50.0}
                                     {:frame "bar" :samples 30 :percentage 30.0}]
                      :hot-paths [{:stack-names ["foo" "bar" "baz"] 
                                  :samples 25 
                                  :percentage 25.0}]
                      :call-tree {:name "ROOT"
                                 :total-samples 100
                                 :self-samples 0
                                 :total-percentage 100.0
                                 :self-percentage 0.0
                                 :children []}}
          output (profiling-tools/format-profile-result test-result)]
      (is (string? output))
      (is (str/includes? output "CPU Profile Results"))
      (is (str/includes? output "TOP FUNCTIONS BY SAMPLES"))
      (is (str/includes? output "CALL TREE"))
      (is (str/includes? output "HOTTEST EXECUTION PATHS")))))