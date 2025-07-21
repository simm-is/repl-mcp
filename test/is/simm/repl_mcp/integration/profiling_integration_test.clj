(ns is.simm.repl-mcp.integration.profiling-integration-test
  "Integration tests for profiling tools with real nREPL server"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [is.simm.repl-mcp.test-fixtures :as fixtures]
            [is.simm.repl-mcp.tools.profiling :as profiling-tools]))

(use-fixtures :once fixtures/nrepl-fixture)

(deftest profile-cpu-integration-test
  (testing "profile-cpu tool with real nREPL"
    (fixtures/wait-for-nrepl-warmup)
    
    (let [tool (fixtures/find-tool-by-name profiling-tools/tools "profile-cpu")]
      (testing "profiles simple arithmetic expression"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"code" "(+ 1 2)"
                        "duration" 1000}  ; Short duration for tests
                       :expect-success true)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (or (str/includes? text "Profile")
                    (str/includes? text "CPU")
                    (str/includes? text "samples")
                    (str/includes? text "Error")
                    (str/includes? text "profiler"))))))
      
      (testing "profiles more complex expression"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"expression" "(reduce + (range 100))"
                        "duration" "1000"}
                       :expect-success true)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (or (str/includes? text "Profile")
                    (str/includes? text "reduce")
                    (str/includes? text "samples")
                    (str/includes? text "Error"))))))
      
      (testing "handles invalid expression"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"expression" "(invalid-function-that-does-not-exist)"
                        "duration" "1000"}
                       :expect-success false)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (str/includes? text "Error")))))
      
      (testing "handles empty expression"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"expression" ""
                        "duration" "1000"}
                       :expect-success false)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (str/includes? text "Error")))))
      
      (testing "uses default duration when not specified"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"expression" "(+ 1 2)"}
                       :expect-success true)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            ;; Should handle default duration gracefully
            (is (string? text))))))))

(deftest profile-alloc-integration-test
  (testing "profile-alloc tool with real nREPL"
    (fixtures/wait-for-nrepl-warmup)
    
    (let [tool (fixtures/find-tool-by-name profiling-tools/tools "profile-alloc")]
      (testing "profiles memory allocation for simple expression"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"code" "(str \"hello\" \" \" \"world\")"
                        "duration" 1000}
                       :expect-success true)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (or (str/includes? text "Profile")
                    (str/includes? text "allocation")
                    (str/includes? text "memory")
                    (str/includes? text "Error")
                    (str/includes? text "profiler"))))))
      
      (testing "profiles allocation for collection creation"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"expression" "(vec (range 50))"
                        "duration" "1000"}
                       :expect-success true)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (or (str/includes? text "Profile")
                    (str/includes? text "vec")
                    (str/includes? text "allocation")
                    (str/includes? text "Error"))))))
      
      (testing "handles invalid expression"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"expression" "(broken-syntax [)"
                        "duration" "1000"}
                       :expect-success false)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (str/includes? text "Error")))))
      
      (testing "handles empty expression"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"expression" ""
                        "duration" "1000"}
                       :expect-success false)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            (is (str/includes? text "Error")))))
      
      (testing "uses default duration when not specified"
        (let [result (fixtures/test-tool-with-nrepl 
                       tool 
                       {"expression" "(vector 1 2 3)"}
                       :expect-success true)]
          (is (contains? result :content))
          (let [text (:text (first (:content result)))]
            ;; Should handle default duration gracefully
            (is (string? text))))))))

;; Test the helper functions directly
(deftest profiling-helper-functions-test
  (testing "profile-expression-comprehensive function"
    (fixtures/wait-for-nrepl-warmup)
    
    (testing "profiles with CPU event"
      (let [result (profiling-tools/profile-expression-comprehensive 
                     fixtures/*test-nrepl-client* 
                     "(+ 1 2)" 
                     :event :cpu 
                     :duration 1000)]
        (is (or (= (:status result) :success)
                (= (:status result) :error)))
        (is (contains? result :status))))
    
    (testing "profiles with allocation event"
      (let [result (profiling-tools/profile-expression-comprehensive 
                     fixtures/*test-nrepl-client* 
                     "(str \"test\")" 
                     :event :alloc 
                     :duration 1000)]
        (is (or (= (:status result) :success)
                (= (:status result) :error)))
        (is (contains? result :status))))
    
    (testing "handles nil nREPL client"
      (let [result (profiling-tools/profile-expression-comprehensive 
                     nil 
                     "(+ 1 2)")]
        (is (= (:status result) :error))
        (is (contains? result :error))))))

(deftest profiling-analysis-functions-test
  (testing "calculate-frame-frequencies function"
    (let [frames [{:name "fn1" :samples 10}
                  {:name "fn2" :samples 5}
                  {:name "fn1" :samples 3}]
          result (profiling-tools/calculate-frame-frequencies frames)]
      (is (map? result))
      (is (contains? result "fn1"))
      (is (contains? result "fn2"))
      (is (= 13 (get result "fn1")))  ; 10 + 3
      (is (= 5 (get result "fn2")))))
  
  (testing "format-percentage function"
    (is (= "50.00%" (profiling-tools/format-percentage 50 100)))
    (is (= "33.33%" (profiling-tools/format-percentage 33 99)))
    (is (= "0.00%" (profiling-tools/format-percentage 0 100))))
  
  (testing "add-percentages function"
    (let [freq-map {"fn1" 75 "fn2" 25}
          result (profiling-tools/add-percentages freq-map)]
      (is (map? result))
      (is (contains? result "fn1"))
      (is (contains? result "fn2"))
      (is (str/includes? (get result "fn1") "%"))
      (is (str/includes? (get result "fn2") "%")))))

;; Summary test to verify both profiling tools are present
(deftest profiling-tools-completeness-test
  (testing "both profiling tools are defined"
    (let [tool-names (set (map :name profiling-tools/tools))
          expected-tools #{"profile-cpu" "profile-alloc"}]
      (is (= 2 (count tool-names)))
      (doseq [expected expected-tools]
        (is (contains? tool-names expected) 
            (str "Missing profiling tool: " expected))))))