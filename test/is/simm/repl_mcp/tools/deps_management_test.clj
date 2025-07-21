(ns is.simm.repl-mcp.tools.deps-management-test
  (:require [clojure.test :refer [deftest is testing]]
            [is.simm.repl-mcp.tools.deps-management :as deps-tools]
            [clojure.string :as str]))

(deftest function-existence-test
  (testing "deps management functions exist"
    (is (fn? deps-tools/parse-lib-coords))
    (is (fn? deps-tools/add-libraries))
    (is (fn? deps-tools/sync-project-deps))
    (is (fn? deps-tools/check-library-available))))

(deftest tools-definitions-test
  (testing "tools vector exists and has correct structure"
    (is (vector? deps-tools/tools))
    (is (= 3 (count deps-tools/tools)))
    
    (testing "all tools have required fields"
      (doseq [tool deps-tools/tools]
        (is (string? (:name tool)))
        (is (string? (:description tool)))
        (is (map? (:inputSchema tool)))
        (is (fn? (:tool-fn tool)))))
    
    (testing "specific tool names"
      (let [tool-names (set (map :name deps-tools/tools))]
        (is (contains? tool-names "add-libs"))
        (is (contains? tool-names "sync-deps"))
        (is (contains? tool-names "check-namespace"))))))

(deftest parse-lib-coords-test
  (testing "parse-lib-coords with map input"
    (let [coords {:hiccup/hiccup {:mvn/version "1.0.5"}}
          result (deps-tools/parse-lib-coords coords)]
      (is (= result coords))))
  
  (testing "parse-lib-coords with string input"
    (let [coords-str "{:hiccup/hiccup {:mvn/version \"1.0.5\"}}"
          result (deps-tools/parse-lib-coords coords-str)]
      (is (map? result))
      (is (contains? result :hiccup/hiccup))))
  
  (testing "parse-lib-coords with invalid input"
    (is (thrown? Exception (deps-tools/parse-lib-coords "invalid-edn")))
    (is (thrown? Exception (deps-tools/parse-lib-coords 123)))))

(deftest check-library-available-test
  (testing "check-library-available with existing namespace"
    (let [result (deps-tools/check-library-available 'clojure.string)]
      (is (= (:status result) :success))
      (is (:available result))))
  
  (testing "check-library-available with non-existing namespace"
    (let [result (deps-tools/check-library-available 'non.existent.namespace)]
      (is (= (:status result) :error))
      (is (not (:available result))))))

(deftest tool-handler-test
  (testing "check-namespace-tool handler"
    (let [tool-fn (:tool-fn (first (filter #(= "check-namespace" (:name %)) deps-tools/tools)))
          result (tool-fn {} {:namespace "clojure.string"})]
      (is (map? result))
      (is (contains? result :content))
      (is (vector? (:content result)))
      (is (= "text" (:type (first (:content result)))))
      (is (str/includes? (:text (first (:content result))) "available")))))