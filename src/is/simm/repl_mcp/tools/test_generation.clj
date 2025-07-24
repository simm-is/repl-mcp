(ns is.simm.repl-mcp.tools.test-generation
  "Test generation tools for Clojure functions"
  (:require 
   [clojure.string :as str]
   [clojure.edn :as edn]
   [taoensso.telemere :as log]))

;; ===============================================
;; Test Generation Functions  
;; ===============================================

(defn generate-test-skeleton
  "Generate a test skeleton for a given function"
  [function-name namespace-name & {:keys [test-namespace test-cases]}]
  (try
    (log/log! {:level :info :msg "Generating test skeleton" 
               :data {:function-name function-name :namespace-name namespace-name}})
    
    (let [test-ns (or test-namespace (str namespace-name "-test"))
          func-name-clean (if (str/starts-with? function-name ":")
                           (subs function-name 1)
                           function-name)
          test-fn-name (str func-name-clean "-test")
          default-test-cases (or test-cases
                                [{:description "basic functionality" 
                                  :input "example-input"
                                  :expected "expected-output"}
                                 {:description "edge case"
                                  :input "edge-case-input"
                                  :expected "edge-case-output"}
                                 {:description "error case"
                                  :input "invalid-input"
                                  :expected "error-handling"}])
          
          test-code (str "(ns " test-ns "\n"
                        "  (:require [clojure.test :refer [deftest is testing]]\n"
                        "            [" namespace-name " :as sut]))\n\n"
                        "(deftest " test-fn-name "\n"
                        "  (testing \"" func-name-clean " function\"\n"
                        (str/join "\n"
                          (map (fn [test-case]
                                 (str "    (testing \"" (:description test-case) "\"\n"
                                     "      ;; TODO: Implement test\n"
                                     "      ;; Input: " (:input test-case) "\n"
                                     "      ;; Expected: " (:expected test-case) "\n"
                                     "      (is (= " (:expected test-case) "\n"
                                     "             (sut/" func-name-clean " " (:input test-case) "))))"))
                               default-test-cases))
                        "))\n\n"
                        ";; TODO: Add more comprehensive tests\n"
                        ";; Consider testing:\n"
                        ";; - Boundary conditions\n"
                        ";; - Invalid inputs\n"
                        ";; - Performance characteristics\n"
                        ";; - Integration scenarios")]
      
      {:test-code test-code
       :test-namespace test-ns
       :test-function test-fn-name
       :function-name func-name-clean
       :namespace namespace-name
       :test-cases (count default-test-cases)
       :status :success})
    
    (catch Exception e
      (log/log! {:level :error :msg "Error generating test skeleton" 
                 :data {:error (.getMessage e) :function-name function-name}})
      {:error (.getMessage e)
       :status :error})))

;; ===============================================
;; Tool Implementations
;; ===============================================

(defn create-test-skeleton-tool [mcp-context arguments]
  (let [{:keys [function-name namespace-name test-namespace test-cases]} arguments
        result (generate-test-skeleton function-name namespace-name 
                                     :test-namespace test-namespace 
                                     :test-cases (when test-cases 
                                                  (try (edn/read-string test-cases)
                                                       (catch Exception _ nil))))]
    {:content [{:type "text" 
                :text (if (= (:status result) :success)
                        (str "Generated test skeleton for " (:function-name result) ":\n\n"
                             (:test-code result))
                        (str "Error: " (:error result)))}]}))

;; ===============================================
;; Tool Definitions
;; ===============================================

(def tools
  "Test generation tool definitions for mcp-toolkit"
  [{:name "create-test-skeleton"
    :description "Generate a comprehensive test skeleton for a Clojure function with multiple test cases and documentation"
    :inputSchema {:type "object"
                  :properties {:function-name {:type "string" :description "Name of the function to create tests for"}
                              :namespace-name {:type "string" :description "Namespace containing the function"}
                              :test-namespace {:type "string" :description "Target test namespace (defaults to namespace-name + '-test')"}
                              :test-cases {:type "string" :description "JSON array of test cases with description, input, expected fields"}}
                  :required ["function-name" "namespace-name"]}
    :tool-fn create-test-skeleton-tool}])