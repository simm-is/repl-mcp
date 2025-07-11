(ns is.simm.repl-mcp.tools.cider-nrepl-test
  (:require [clojure.test :refer [deftest is testing]]
            [is.simm.repl-mcp.tools.cider-nrepl]
            [is.simm.repl-mcp.dispatch :as dispatch]))


;; Tests for cider-nrepl tools
;; These tests verify tool registration and basic functionality
;; Full integration tests would require a running nREPL server with cider-nrepl middleware

(deftest tool-registration-test
  (testing "All cider-nrepl tools are registered"
    (let [registered-tools (dispatch/get-registered-tools)]
      (is (contains? registered-tools :format-code))
      (is (contains? registered-tools :macroexpand))
      (is (contains? registered-tools :eldoc))
      (is (contains? registered-tools :complete))
      (is (contains? registered-tools :apropos))
      (is (contains? registered-tools :test-all))
      (is (contains? registered-tools :enhanced-info))
      (is (contains? registered-tools :ns-list))
      (is (contains? registered-tools :ns-vars))
      (is (contains? registered-tools :classpath))
      (is (contains? registered-tools :refresh))
      (is (contains? registered-tools :test-var-query)))))

(deftest tool-specification-test
  (testing "Tools have proper specifications"
    (let [registered-tools (dispatch/get-registered-tools)]
      
      (testing "format-code tool specification"
        (when-let [tool (:format-code registered-tools)]
          (is (= (:name tool) :format-code))
          (is (string? (:description tool)))
          (is (contains? (:parameters tool) :code))
          (is (fn? (:handler tool)))))
      
      (testing "eldoc tool specification"
        (when-let [tool (:eldoc registered-tools)]
          (is (= (:name tool) :eldoc))
          (is (string? (:description tool)))
          (is (contains? (:parameters tool) :symbol))
          (is (fn? (:handler tool)))))
      
      (testing "complete tool specification"
        (when-let [tool (:complete registered-tools)]
          (is (= (:name tool) :complete))
          (is (string? (:description tool)))
          (is (contains? (:parameters tool) :prefix))
          (is (fn? (:handler tool)))))
      
      (testing "ns-list tool specification"
        (when-let [tool (:ns-list registered-tools)]
          (is (= (:name tool) :ns-list))
          (is (string? (:description tool)))
          (is (fn? (:handler tool)))))
      
      (testing "ns-vars tool specification"
        (when-let [tool (:ns-vars registered-tools)]
          (is (= (:name tool) :ns-vars))
          (is (string? (:description tool)))
          (is (contains? (:parameters tool) :ns))
          (is (fn? (:handler tool)))))
      
      (testing "classpath tool specification"
        (when-let [tool (:classpath registered-tools)]
          (is (= (:name tool) :classpath))
          (is (string? (:description tool)))
          (is (fn? (:handler tool)))))
      
      (testing "refresh tool specification"
        (when-let [tool (:refresh registered-tools)]
          (is (= (:name tool) :refresh))
          (is (string? (:description tool)))
          (is (fn? (:handler tool)))))
      
      (testing "test-var-query tool specification"
        (when-let [tool (:test-var-query registered-tools)]
          (is (= (:name tool) :test-var-query))
          (is (string? (:description tool)))
          (is (contains? (:parameters tool) :var-query))
          (is (fn? (:handler tool))))))))

(deftest tool-description-test
  (testing "All tools have meaningful descriptions"
    (let [registered-tools (dispatch/get-registered-tools)]
      
      (testing "Tool descriptions are non-empty strings"
        (doseq [tool-name [:format-code :macroexpand :eldoc :complete :apropos 
                          :test-all :enhanced-info :ns-list :ns-vars :classpath 
                          :refresh :test-var-query]]
          (when-let [tool (get registered-tools tool-name)]
            (is (string? (:description tool)))
            (is (> (count (:description tool)) 10)))))
      
      (testing "Tool descriptions mention their purpose"
        (let [format-tool (:format-code registered-tools)
              eldoc-tool (:eldoc registered-tools)
              complete-tool (:complete registered-tools)]
          
          (when format-tool
            (is (re-find #"(?i)format" (:description format-tool))))
          (when eldoc-tool
            (is (re-find #"(?i)documentation" (:description eldoc-tool))))
          (when complete-tool
            (is (re-find #"(?i)completion" (:description complete-tool)))))))))

(deftest tool-parameter-types-test
  (testing "Tool parameters have correct types"
    (let [registered-tools (dispatch/get-registered-tools)]
      
      (testing "String parameters have string type"
        (when-let [format-tool (:format-code registered-tools)]
          (is (= (get-in format-tool [:parameters :code :type]) "string")))
        (when-let [eldoc-tool (:eldoc registered-tools)]
          (is (= (get-in eldoc-tool [:parameters :symbol :type]) "string")))
        (when-let [complete-tool (:complete registered-tools)]
          (is (= (get-in complete-tool [:parameters :prefix :type]) "string"))))
      
      (testing "Parameters have descriptions"
        (when-let [format-tool (:format-code registered-tools)]
          (is (string? (get-in format-tool [:parameters :code :description]))))
        (when-let [eldoc-tool (:eldoc registered-tools)]
          (is (string? (get-in eldoc-tool [:parameters :symbol :description]))))
        (when-let [complete-tool (:complete registered-tools)]
          (is (string? (get-in complete-tool [:parameters :prefix :description]))))))))

(deftest high-impact-tools-test
  (testing "High-impact tools for AI coding speed are present"
    (let [registered-tools (dispatch/get-registered-tools)]
      
      (testing "Namespace exploration tools"
        (is (contains? registered-tools :ns-list))
        (is (contains? registered-tools :ns-vars)))
      
      (testing "Development tools"
        (is (contains? registered-tools :classpath))
        (is (contains? registered-tools :refresh)))
      
      (testing "Testing tools"
        (is (contains? registered-tools :test-var-query))
        (is (contains? registered-tools :test-all)))
      
      (testing "Code analysis tools"
        (is (contains? registered-tools :enhanced-info))
        (is (contains? registered-tools :eldoc))
        (is (contains? registered-tools :complete))
        (is (contains? registered-tools :apropos))
        (is (contains? registered-tools :format-code))
        (is (contains? registered-tools :macroexpand))))))

(deftest tool-handler-function-test
  (testing "Tool handlers are proper functions"
    (let [registered-tools (dispatch/get-registered-tools)]
      
      (testing "All tool handlers are functions"
        (doseq [tool-name [:format-code :macroexpand :eldoc :complete :apropos 
                          :test-all :enhanced-info :ns-list :ns-vars :classpath 
                          :refresh :test-var-query]]
          (when-let [handler (get-in registered-tools [tool-name :handler])]
            (is (fn? handler)))))
      
      (testing "Tool handlers accept tool-call and context parameters"
        (when-let [handler (get-in registered-tools [:ns-list :handler])]
          (let [tool-call {:tool-name :ns-list :args {}}
                context {:nrepl-client nil}]
            ;; Should not throw an exception when called with proper parameters
            (is (map? (handler tool-call context)))))))))