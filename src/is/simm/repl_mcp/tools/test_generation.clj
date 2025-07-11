(ns is.simm.repl-mcp.tools.test-generation
  (:require [is.simm.repl-mcp.interactive :refer [register-tool!]]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [taoensso.telemere :as log]))

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

;; Register the tool
(register-tool! :create-test-skeleton
  "Generate a comprehensive test skeleton for a Clojure function with multiple test cases and documentation"
  {:function-name {:type "string" :description "Name of the function to create tests for"}
   :namespace-name {:type "string" :description "Namespace containing the function"}
   :test-namespace {:type "string" :optional true :description "Target test namespace (defaults to namespace-name + '-test')"}
   :test-cases {:type "string" :optional true :description "JSON array of test cases with description, input, expected fields"}}
  (fn [tool-call _context]
    (let [{:strs [function-name namespace-name test-namespace test-cases]} (:args tool-call)]
      (generate-test-skeleton function-name namespace-name 
                             :test-namespace test-namespace 
                             :test-cases (when test-cases 
                                          (try (edn/read-string test-cases)
                                               (catch Exception _ nil)))))))