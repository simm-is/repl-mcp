(ns is.simm.repl-mcp.tools.refactor-test
  (:require [clojure.test :refer [deftest is testing]]
            [is.simm.repl-mcp.tools.refactor :as refactor-tools]
            [is.simm.repl-mcp.dispatch :as dispatch]))

;; Simple tests that verify the functions exist and have proper signatures
;; Full integration tests would require a running nREPL server with refactor-nrepl middleware

(deftest function-existence-test
  (testing "refactor functions exist"
    ;; Original refactor-nrepl tools
    (is (fn? refactor-tools/clean-namespace))
    (is (fn? refactor-tools/find-symbol-occurrences))
    (is (fn? refactor-tools/rename-file-or-directory))
    (is (fn? refactor-tools/resolve-missing-symbol))
    (is (fn? refactor-tools/find-used-locals))
    
    ;; Advanced refactoring tools
    (is (fn? refactor-tools/extract-function-from-code))
    (is (fn? refactor-tools/extract-variable-from-code))
    (is (fn? refactor-tools/add-function-parameter))
    (is (fn? refactor-tools/organize-namespace-imports))
    (is (fn? refactor-tools/inline-function))
    (is (fn? refactor-tools/rename-local-variable))))

(deftest function-signature-test
  (testing "functions handle parameters correctly"
    ;; Test that functions can be called with nil client (will error gracefully)
    (let [clean-result (refactor-tools/clean-namespace nil "/test/file.clj")]
      (is (= (:status clean-result) :error))
      (is (some? (:error clean-result))))
    
    (let [find-result (refactor-tools/find-symbol-occurrences nil "/test/file.clj" 1 1)]
      (is (= (:status find-result) :error))
      (is (some? (:error find-result))))
    
    (let [rename-result (refactor-tools/rename-file-or-directory nil "/old" "/new")]
      (is (= (:status rename-result) :error))
      (is (some? (:error rename-result))))
    
    (let [resolve-result (refactor-tools/resolve-missing-symbol nil "symbol" "ns")]
      (is (= (:status resolve-result) :error))
      (is (some? (:error resolve-result))))
    
    (let [locals-result (refactor-tools/find-used-locals nil "/test/file.clj" 1 1)]
      (is (= (:status locals-result) :error))
      (is (some? (:error locals-result))))))

(deftest advanced-refactoring-functions-test
  (testing "advanced refactoring functions handle errors gracefully"
    ;; Test that advanced refactoring functions handle invalid sessions
    (let [extract-fn-result (refactor-tools/extract-function-from-code "invalid-session" "test-fn" [])]
      (is (= (:status extract-fn-result) :error))
      (is (= (:error extract-fn-result) "Session not found")))
    
    (let [extract-var-result (refactor-tools/extract-variable-from-code "invalid-session" "test-var")]
      (is (= (:status extract-var-result) :error))
      (is (= (:error extract-var-result) "Session not found")))
    
    (let [add-param-result (refactor-tools/add-function-parameter "invalid-session" "param" nil)]
      (is (= (:status add-param-result) :error))
      (is (= (:error add-param-result) "Session not found")))
    
    (let [organize-result (refactor-tools/organize-namespace-imports "invalid-session")]
      (is (= (:status organize-result) :error))
      (is (= (:error organize-result) "Session not found")))
    
    (let [inline-result (refactor-tools/inline-function "invalid-session" "test-fn")]
      (is (= (:status inline-result) :error))
      (is (= (:error inline-result) "Session not found")))
    
    (let [rename-var-result (refactor-tools/rename-local-variable "invalid-session" "old" "new")]
      (is (= (:status rename-var-result) :error))
      (is (= (:error rename-var-result) "Session not found")))))

(deftest tool-registration-test
  (testing "All refactor tools are registered"
    (let [registered-tools (dispatch/get-registered-tools)]
      ;; Original refactor-nrepl tools
      (is (contains? registered-tools :clean-ns))
      (is (contains? registered-tools :find-symbol))
      (is (contains? registered-tools :rename-file-or-dir))
      (is (contains? registered-tools :resolve-missing))
      (is (contains? registered-tools :find-used-locals))
      
      ;; Advanced refactoring tools
      (is (contains? registered-tools :extract-function))
      (is (contains? registered-tools :extract-variable))
      (is (contains? registered-tools :add-function-parameter))
      (is (contains? registered-tools :organize-imports))
      (is (contains? registered-tools :inline-function))
      (is (contains? registered-tools :rename-local-variable)))))