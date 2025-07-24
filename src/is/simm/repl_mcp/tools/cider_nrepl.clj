(ns is.simm.repl-mcp.tools.cider-nrepl
  "Development tools using cider-nrepl middleware with safe nREPL interactions"
  (:require 
   [clojure.string :as str]
   [taoensso.telemere :as log]
   [is.simm.repl-mcp.tools.nrepl-utils :as nrepl-utils]))

;; ===============================================
;; Cider-nREPL Tool Functions
;; ===============================================

(defn format-code-tool [mcp-context arguments]
  (let [{:keys [code]} arguments]
    (nrepl-utils/simple-nrepl-tool mcp-context "Code formatting"
      (fn [] {:op "format-code" :code code})
      (fn [responses] 
        (let [result (first responses)]
          (or (:formatted-code result) "Failed to format code"))))))

(defn macroexpand-tool [mcp-context arguments]
  (let [{:keys [code]} arguments]
    (nrepl-utils/simple-nrepl-tool mcp-context "Macro expansion"
      (fn [] {:op "macroexpand" :code code})
      (fn [responses] 
        (let [result (first responses)]
          (or (:expansion result) "Failed to expand macro"))))))

(defn eldoc-tool [mcp-context arguments]
  (let [{:keys [symbol ns]} arguments
        ns (or ns "user")]
    (nrepl-utils/simple-nrepl-tool mcp-context "Symbol documentation"
      (fn [] {:op "eldoc" :symbol symbol :ns ns})
      (fn [responses] 
        (let [result (first responses)]
          (if (:eldoc result)
            (str "Symbol: " (:name result) "\n"
                 "Namespace: " (:ns result) "\n"
                 "Type: " (:type result) "\n"
                 "Documentation: " (:docstring result) "\n"
                 "Signatures: " (:eldoc result))
            "Symbol not found or no documentation available"))))))

(defn complete-tool [mcp-context arguments]
  (let [{:keys [prefix ns]} arguments
        ns (or ns "user")]
    (nrepl-utils/simple-nrepl-tool mcp-context "Code completion"
      (fn [] {:op "complete" :prefix prefix :ns ns})
      (fn [responses] 
        (let [result (first responses)
              completions (:completions result)]
          (if completions
            (str "Found " (count completions) " completions:\n"
                 (str/join "\n" (map :candidate completions)))
            "No completions found"))))))

(defn apropos-tool [mcp-context arguments]
  (let [{:keys [query ns]} arguments]
    (nrepl-utils/simple-nrepl-tool mcp-context "Symbol search"
      (fn [] (cond-> {:op "apropos" :query query}
               ns (assoc :ns ns)))
      (fn [responses] 
        (let [result (first responses)
              matches (:apropos-matches result)]
          (if matches
            (let [symbol-names (map #(or (:name %) (str %)) matches)
                  limited-matches (take 20 symbol-names)]
              (str "Found " (count matches) " matches"
                   (when (> (count matches) 20) " (showing first 20)")
                   ":\n"
                   (str/join "\n" limited-matches)))
            "No matches found"))))))

(defn test-all-tool [mcp-context _arguments]
  (nrepl-utils/simple-nrepl-tool mcp-context "Test execution"
    (fn [] {:op "test-all"})
    (fn [responses] 
      (let [results-msgs (filter #(contains? % :results) responses)
            summary-msgs (filter #(contains? % :summary) responses)
            results (mapcat :results results-msgs)
            summary (first (map :summary summary-msgs))]
        (str "Test execution completed:\n"
             (if (empty? results)
               "No tests found in project"
               (str "Results:\n" 
                    (str/join "\n" 
                      (map #(format "  %s.%s: %s" 
                                   (:ns %) 
                                   (:var %) 
                                   (:type %)) 
                           results))))
             "\n"
             (if summary
               (str "Summary: " 
                    (:test summary 0) " tests, "
                    (:pass summary 0) " passed, "
                    (:fail summary 0) " failed, "
                    (:error summary 0) " errors")
               "No summary available"))))))

(defn info-tool [mcp-context arguments]
  (let [{:keys [symbol ns]} arguments
        ns (or ns "user")]
    (nrepl-utils/simple-nrepl-tool mcp-context "Symbol information"
      (fn [] {:op "info" :symbol symbol :ns ns})
      (fn [responses] 
        (let [result (first responses)]
          (if (contains? (:status result) "no-info")
            "No information available for symbol"
            (pr-str (dissoc result :id :session :status))))))))

(defn ns-list-tool [mcp-context _arguments]
  (nrepl-utils/simple-nrepl-tool mcp-context "Namespace listing"
    (fn [] {:op "ns-list"})
    (fn [responses] 
      (let [result (first responses)
            namespaces (:ns-list result)]
        (if namespaces
          (str "Found " (count namespaces) " namespaces:\n"
               (str/join "\n" namespaces))
          "No namespaces found")))))

(defn ns-vars-tool [mcp-context arguments]
  (let [{:keys [ns]} arguments]
    (nrepl-utils/simple-nrepl-tool mcp-context "Namespace vars listing"
      (fn [] {:op "ns-vars" :ns ns})
      (fn [responses] 
        (let [result (first responses)
              vars (:ns-vars result)]
          (if vars
            (str "Vars in " ns " (" (count vars) "):\n"
                 (str/join "\n" vars))
            (str "No vars found in namespace: " ns)))))))

(defn classpath-tool [mcp-context _arguments]
  (nrepl-utils/simple-nrepl-tool mcp-context "Classpath information"
    (fn [] {:op "classpath"})
    (fn [responses] 
      (let [result (first responses)
            classpath (:classpath result)]
        (if classpath
          (str "Classpath (" (count classpath) " entries):\n"
               (str/join "\n" classpath))
          "No classpath information available")))))

(defn refresh-tool 
  "Safely refresh user namespaces without killing server infrastructure"
  [_mcp-context _arguments]
  (try
    (let [all-namespaces (all-ns)
          server-prefixes ["is.simm.repl-mcp" "clojure." "nrepl." "cider." "refactor-" 
                          "rewrite-" "taoensso." "pogonos." "orchard."]
          user-namespaces (filter (fn [ns]
                                   (let [ns-name (str (ns-name ns))]
                                     (not (some #(str/starts-with? ns-name %) server-prefixes))))
                                 all-namespaces)
          results (atom {:success [] :failed []})
          total (count user-namespaces)]
      
      (doseq [ns user-namespaces]
        (try
          (require (ns-name ns) :reload)
          (swap! results update :success conj (str (ns-name ns)))
          (catch Exception e
            (swap! results update :failed conj (str (ns-name ns) " - " (.getMessage e))))))
      
      (let [final-results @results
            success-count (count (:success final-results))
            failed-count (count (:failed final-results))]
        {:content [{:type "text" 
                    :text (str "Namespace refresh completed:\n"
                               "Total: " total " user namespaces\n"
                               "Successful: " success-count "\n"
                               "Failed: " failed-count "\n\n"
                               (when (seq (:failed final-results))
                                 (str "Failed namespaces:\n" (str/join "\n" (:failed final-results)))))}]}))
    (catch Exception e
      (log/log! {:level :error :msg "Error refreshing namespaces" :data {:error (.getMessage e)}})
      {:content [{:type "text" :text (str "Error: " (.getMessage e))}]})))

(defn test-var-query-tool [mcp-context arguments]
  (let [{:keys [var-query]} arguments]
    (nrepl-utils/simple-nrepl-tool mcp-context "Specific test execution"
      (fn [] 
        (let [query-map (if (and var-query (str/includes? var-query "/"))
                          (let [[ns-name var-name] (str/split var-query #"/" 2)]
                            {:ns-query {:exactly [ns-name]}
                             :var-query {:exactly [var-name]}})
                          {:ns-query {:exactly [(str var-query)]}})]
          (assoc query-map :op "test-var-query")))
      (fn [responses] 
        (let [results-msgs (filter #(contains? % :results) responses)
              summary-msgs (filter #(contains? % :summary) responses)
              results (mapcat :results results-msgs)
              summary (first (map :summary summary-msgs))]
          (str "Test Results for " var-query ":\n"
               (if (empty? results)
                 "No test results found"
                 (str "Results:\n" 
                      (str/join "\n" 
                        (map #(format "  %s: %s" 
                                     (or (:var %) (:name %)) 
                                     (or (:type %) "unknown")) 
                             results))))
               "\n"
               (if summary
                 (str "Summary: " 
                      (:test summary 0) " tests, "
                      (:pass summary 0) " passed, "
                      (:fail summary 0) " failed, "
                      (:error summary 0) " errors")
                 "No summary available")))))))

;; ===============================================
;; Tool Definitions
;; ===============================================

(def tools
  "Cider-nREPL tool definitions for mcp-toolkit with safe nREPL interactions"
  [{:name "format-code"
    :description "Format Clojure code using cider-nrepl's format-code operation"
    :inputSchema {:type "object"
                  :properties {:code {:type "string" :description "Clojure code to format"}}
                  :required ["code"]}
    :tool-fn format-code-tool}
   
   {:name "macroexpand"
    :description "Expand Clojure macros using cider-nrepl's macroexpand operation"
    :inputSchema {:type "object"
                  :properties {:code {:type "string" :description "Clojure code containing macros to expand"}}
                  :required ["code"]}
    :tool-fn macroexpand-tool}
   
   {:name "eldoc"
    :description "Get function documentation and signatures using cider-nrepl's eldoc operation"
    :inputSchema {:type "object"
                  :properties {:symbol {:type "string" :description "Symbol to get documentation for"}
                              :ns {:type "string" :description "Namespace context (defaults to 'user')"}}
                  :required ["symbol"]}
    :tool-fn eldoc-tool}
   
   {:name "complete"
    :description "Get code completion candidates using cider-nrepl's complete operation"
    :inputSchema {:type "object"
                  :properties {:prefix {:type "string" :description "Prefix to complete"}
                              :ns {:type "string" :description "Namespace context (defaults to 'user')"}}
                  :required ["prefix"]}
    :tool-fn complete-tool}
   
   {:name "apropos"
    :description "Search for symbols matching a pattern using cider-nrepl's apropos operation"
    :inputSchema {:type "object"
                  :properties {:query {:type "string" :description "Search query/pattern"}
                              :ns {:type "string" :description "Namespace to search in (optional)"}}
                  :required ["query"]}
    :tool-fn apropos-tool}
   
   {:name "test-all"
    :description "Run all tests in the project using cider-nrepl's test-all operation"
    :inputSchema {:type "object"
                  :properties {}}
    :tool-fn test-all-tool}
   
   {:name "info"
    :description "Get enhanced symbol information using cider-nrepl's info operation"
    :inputSchema {:type "object"
                  :properties {:symbol {:type "string" :description "Symbol to get info for"}
                              :ns {:type "string" :description "Namespace context (defaults to 'user')"}}
                  :required ["symbol"]}
    :tool-fn info-tool}
   
   {:name "ns-list"
    :description "Browse all available namespaces for rapid codebase exploration"
    :inputSchema {:type "object"
                  :properties {}}
    :tool-fn ns-list-tool}
   
   {:name "ns-vars"
    :description "Explore namespace contents - get all vars in a namespace"
    :inputSchema {:type "object"
                  :properties {:ns {:type "string" :description "Namespace to explore"}}
                  :required ["ns"]}
    :tool-fn ns-vars-tool}
   
   {:name "classpath"
    :description "Understand available dependencies and classpath entries"
    :inputSchema {:type "object"
                  :properties {}}
    :tool-fn classpath-tool}
   
   {:name "refresh"
    :description "Safely refresh user namespaces without killing server infrastructure"
    :inputSchema {:type "object"
                  :properties {}}
    :tool-fn refresh-tool}
   
   {:name "test-var-query"
    :description "Run specific tests instead of all tests for rapid iteration"
    :inputSchema {:type "object"
                  :properties {:var-query {:type "string" :description "Test var query - can be namespace (e.g., 'my.ns') or specific var (e.g., 'my.ns/my-test')"}}
                  :required ["var-query"]}
    :tool-fn test-var-query-tool}])