(ns is.simm.repl-mcp.tools.structural-edit-test
  (:require [clojure.test :refer [deftest is testing]]
            [is.simm.repl-mcp.tools.structural-edit]
            [is.simm.repl-mcp.structural-edit :as edit]
            [is.simm.repl-mcp.dispatch :as dispatch]))

;; Test data for structural editing
(def simple-test-code "(+ 1 2 3)")

(def function-test-code 
  "(defn hello-world 
     [name]
     (str \"Hello, \" name \"!\"))")

(defn read-test-file [filename]
  (slurp (str "test-data/" filename)))

(deftest tool-registration-test
  (testing "All structural editing tools are registered"
    (let [registered-tools (dispatch/get-registered-tools)]
      (is (contains? registered-tools :structural-create-session))
      (is (contains? registered-tools :structural-save-session))
      (is (contains? registered-tools :structural-close-session))
      (is (contains? registered-tools :structural-get-info))
      (is (contains? registered-tools :structural-list-sessions))
      (is (contains? registered-tools :structural-find-symbol-enhanced))
      (is (contains? registered-tools :structural-replace-node))
      (is (contains? registered-tools :structural-bulk-find-and-replace))
      (is (contains? registered-tools :structural-extract-to-let))
      (is (contains? registered-tools :structural-thread-first)))))

(deftest session-management-test
  (testing "Session creation from string"
    (let [session-id "test-session-1"]
      
      ;; Test session creation from code string
      (let [result (edit/create-session session-id simple-test-code :from-file? false)]
        (is (= (:status result) :success))
        (is (= (:session-id result) session-id)))
      
      ;; Test session info retrieval
      (let [info (edit/get-zipper-info session-id)]
        (is (= (:status info) :success))
        (is (contains? info :current-node))
        (is (contains? info :available-operations)))
      
      ;; Clean up
      (edit/close-session session-id)))
  
  (testing "Session creation from file"
    (let [session-id "test-session-file"]
      
      ;; Test session creation from file
      (let [result (edit/create-session session-id "test-data/simple-function.clj" :from-file? true)]
        (is (= (:status result) :success))
        (is (= (:session-id result) session-id)))
      
      ;; Test session listing
      (let [sessions (edit/get-all-sessions)]
        (is (contains? sessions session-id)))
      
      ;; Test session save as string (file-based session returns file-path, not code)
      (let [result (edit/save-session session-id)]
        (is (= (:status result) :success))
        (is (string? (:file-path result)))
        (is (re-find #"simple-function" (:file-path result))))
      
      ;; Clean up
      (edit/close-session session-id))))

(deftest find-symbol-functionality-test
  (testing "Symbol finding with enhanced matching"
    (let [session-id "test-session-2"]
      (edit/create-session session-id function-test-code :from-file? false)
      
      ;; Test finding function name
      (let [result (edit/find-by-symbol session-id "hello-world")]
        (is (= (:status result) :success))
        (is (contains? (:info result) :current-node)))
      
      ;; Test case-insensitive search
      (let [result (edit/find-by-symbol session-id "HELLO" :case-sensitive? false)]
        (is (= (:status result) :success)))
      
      ;; Test exact match
      (let [result (edit/find-by-symbol session-id "hello" :exact-match? true)]
        (is (= (:status result) :error))) ; Should not find partial match
      
      ;; Clean up
      (edit/close-session session-id))))

(deftest bulk-find-and-replace-test
  (testing "Bulk find and replace functionality"
    (let [session-id "test-session-3"
          ;; Create temporary copy of test file
          temp-file (java.io.File/createTempFile "test-bulk-replace" ".clj")
          original-content (slurp "test-data/simple-function.clj")]
      (try
        ;; Copy original content to temp file
        (spit temp-file original-content)
        
        ;; Create session from temp file
        (edit/create-session session-id (.getAbsolutePath temp-file) :from-file? true)
        
        ;; Test replacing function name (replace existing "sum-values" with "calculate-sum")
        (let [result (edit/bulk-find-and-replace session-id "sum-values" "calculate-sum")]
          (is (= (:status result) :success))
          (is (> (:replacements result) 0)))
        
        ;; Verify replacement worked (read file content to check)
        (let [code-result (edit/save-session session-id)]
          (is (= (:status code-result) :success))
          (let [file-content (slurp (:file-path code-result))]
            (is (re-find #"calculate-sum" file-content))
            (is (not (re-find #"sum-values" file-content)))))
        
        ;; Clean up
        (edit/close-session session-id)
        
        (finally
          ;; Always delete temp file
          (.delete temp-file))))))

(deftest extract-to-let-test
  (testing "Extract expression to let binding"
    (let [session-id "test-session-4"]
      (edit/create-session session-id "(+ 1 2 3)" :from-file? false)
      
      ;; Navigate to the addition expression
      (edit/find-by-symbol session-id "+")
      
      ;; Extract to let binding
      (let [result (edit/extract-to-let session-id "temp-sum")]
        (is (= (:status result) :success)))
      
      ;; Verify let binding was created
      (let [code-result (edit/save-session session-id)]
        (is (= (:status code-result) :success))
        (is (re-find #"let.*temp-sum" (:code code-result))))
      
      ;; Clean up
      (edit/close-session session-id))))

(deftest thread-first-test
  (testing "Thread-first macro conversion"
    (let [session-id "test-session-5"]
      (edit/create-session session-id "(str (clojure.string/upper-case \"hello\"))" :from-file? false)
      
      ;; Convert to thread-first
      (let [result (edit/thread-first session-id)]
        (is (= (:status result) :success)))
      
      ;; Verify thread-first was created
      (let [code-result (edit/save-session session-id)]
        (is (= (:status code-result) :success))
        (is (re-find #"->" (:code code-result))))
      
      ;; Clean up
      (edit/close-session session-id))))

(deftest replace-node-test
  (testing "Node replacement functionality"
    (let [session-id "test-session-6"]
      (edit/create-session session-id "42" :from-file? false)
      
      ;; Replace number with string
      (let [result (edit/replace-node session-id "hello")]
        (is (= (:status result) :success)))
      
      ;; Verify replacement
      (let [code-result (edit/save-session session-id)]
        (is (= (:status code-result) :success))
        (is (re-find #"hello" (:code code-result))))
      
      ;; Clean up
      (edit/close-session session-id))))

(deftest error-handling-test
  (testing "Error handling for invalid operations"
    
    ;; Test invalid session
    (let [result (edit/get-zipper-info "non-existent-session")]
      (is (= (:status result) :error))
      (is (= (:error result) "Session not found")))
    
    ;; Test invalid find operation
    (let [session-id "test-session-7"]
      (edit/create-session session-id "42" :from-file? false)
      
      (let [result (edit/find-by-symbol session-id "non-existent-symbol")]
        (is (= (:status result) :error)))
      
      ;; Clean up
      (edit/close-session session-id))))

(deftest tool-integration-test
  (testing "Tool integration with MCP dispatch system"
    (let [registered-tools (dispatch/get-registered-tools)]
      
      ;; Test that tools have proper specifications
      (testing "Tool specifications"
        (when-let [create-tool (:structural-create-session registered-tools)]
          (is (= (:name create-tool) :structural-create-session))
          (is (string? (:description create-tool)))
          (is (contains? (:parameters create-tool) :session-id))
          (is (contains? (:parameters create-tool) :source))
          (is (fn? (:handler create-tool))))
        
        (when-let [find-tool (:structural-find-symbol-enhanced registered-tools)]
          (is (= (:name find-tool) :structural-find-symbol-enhanced))
          (is (string? (:description find-tool)))
          (is (contains? (:parameters find-tool) :session-id))
          (is (contains? (:parameters find-tool) :symbol-name))
          (is (fn? (:handler find-tool))))))))