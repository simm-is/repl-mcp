(ns is.simm.repl-mcp.tools.eval-test
  (:require [clojure.test :refer [deftest is testing]]
            [is.simm.repl-mcp.tools.eval :as eval-tools]))

;; Simple tests that verify the functions exist and handle basic error cases
;; Full integration tests would require a running nREPL server

(deftest function-existence-test
  (testing "eval-code function exists"
    (is (fn? eval-tools/eval-code)))
  
  (testing "load-clojure-file function exists"
    (is (fn? eval-tools/load-clojure-file))))

(deftest load-clojure-file-error-handling-test
  (testing "file loading with nonexistent file handles errors gracefully"
    ;; This will fail on file read, which tests our error handling
    (let [result (eval-tools/load-clojure-file nil "/nonexistent/file.clj")]
      (is (= (:status result) :error))
      (is (some? (:error result)))
      (is (re-find #"No such file" (:error result))))))