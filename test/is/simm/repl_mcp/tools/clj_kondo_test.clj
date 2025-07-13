(ns is.simm.repl-mcp.tools.clj-kondo-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [is.simm.repl-mcp.tools.clj-kondo :as clj-kondo-tools]))

(deftest lint-code-string-test
  (testing "lint-code-string with unused binding"
    (let [result (clj-kondo-tools/lint-code-string "(defn foo [x y] (+ x))" :filename "test.clj")]
      (is (= (:status result) :success))
      (is (= (count (:findings result)) 1))
      (is (= (-> result :findings first :type) :unused-binding))
      (is (str/includes? (:formatted-output result) "unused binding y"))))
  
  (testing "lint-code-string with no issues"
    (let [result (clj-kondo-tools/lint-code-string "(defn foo [x] (+ x 1))" :filename "test.clj")]
      (is (= (:status result) :success))
      (is (empty? (:findings result)))
      (is (= (:formatted-output result) "No issues found."))))
  
  (testing "lint-code-string with custom config"
    (let [result (clj-kondo-tools/lint-code-string 
                   "(defn foo [x y] (+ x))" 
                   :filename "test.clj"
                   :config {:linters {:unused-binding {:level :error}}})]
      (is (= (:status result) :error))
      (is (= (-> result :findings first :level) :error)))))

(deftest format-findings-test
  (testing "format-findings with empty findings"
    (is (= (clj-kondo-tools/format-findings []) "No issues found.")))
  
  (testing "format-findings with findings"
    (let [findings [{:filename "test.clj" :row 1 :col 10 :level :warning :type :unused-binding :message "unused binding y"}]]
      (is (str/includes? 
           (clj-kondo-tools/format-findings findings)
           "test.clj:1:10: warning [unused-binding] unused binding y")))))

(deftest lint-files-test
  (testing "lint non-existent file handles gracefully"
    (let [result (clj-kondo-tools/lint-files ["/nonexistent/file.clj"])]
      (is (= (:status result) :error))
      (is (pos? (count (:findings result))))
      (is (str/includes? (:formatted-output result) "file does not exist")))))