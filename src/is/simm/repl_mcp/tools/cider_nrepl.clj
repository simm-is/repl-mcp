(ns is.simm.repl-mcp.tools.cider-nrepl
  "Development tools using cider-nrepl middleware"
  (:require 
   [nrepl.core :as nrepl]
   [clojure.string :as str]
   [taoensso.telemere :as log]))

;; ===============================================
;; Cider-nREPL Functions  
;; ===============================================

(defn with-nrepl-client
  "Helper function to check for nREPL client and execute operation"
  [mcp-context operation-name f]
  (let [nrepl-client (:nrepl-client mcp-context)]
    (if (nil? nrepl-client)
      {:content [{:type "text" 
                  :text (str "Error: nREPL client not available. " operation-name " requires an active nREPL connection.")}]}
      (f nrepl-client))))

(defn format-code-tool [mcp-context arguments]
  (let [{:keys [code]} arguments]
    (with-nrepl-client mcp-context "Code formatting"
      (fn [nrepl-client]
        (try
          (let [responses (nrepl/message nrepl-client {:op "format-code" :code code})
                result (first responses)]
            {:content [{:type "text" 
                        :text (if (:formatted-code result)
                                (:formatted-code result)
                                "Failed to format code")}]})
          (catch Exception e
            (log/log! {:level :error :msg "Error formatting code" :data {:error (.getMessage e)}})
            {:content [{:type "text" :text (str "Error: " (.getMessage e))}]}))))))

(defn macroexpand-tool [mcp-context arguments]
  (let [{:keys [code]} arguments
        nrepl-client (:nrepl-client mcp-context)]
    (try
      (let [responses (nrepl/message nrepl-client {:op "macroexpand" :code code})
            result (first responses)]
        {:content [{:type "text" 
                    :text (if (:expansion result)
                            (:expansion result)
                            "Failed to expand macro")}]})
      (catch Exception e
        (log/log! {:level :error :msg "Error expanding macro" :data {:error (.getMessage e)}})
        {:content [{:type "text" :text (str "Error: " (.getMessage e))}]}))))

(defn eldoc-tool [mcp-context arguments]
  (let [{:keys [symbol ns]} arguments
        nrepl-client (:nrepl-client mcp-context)
        ns (or ns "user")]
    (if (nil? nrepl-client)
      {:content [{:type "text" 
                  :text "Error: nREPL client not available. Symbol documentation requires an active nREPL connection."}]}
      (try
        (let [responses (nrepl/message nrepl-client {:op "eldoc" :symbol symbol :ns ns})
            result (first responses)]
        {:content [{:type "text" 
                    :text (if (:eldoc result)
                            (str "Symbol: " (:name result) "\n"
                                 "Namespace: " (:ns result) "\n"
                                 "Type: " (:type result) "\n"
                                 "Documentation: " (:docstring result) "\n"
                                 "Signatures: " (:eldoc result))
                            "Symbol not found or no documentation available")}]})
        (catch Exception e
          (log/log! {:level :error :msg "Error getting eldoc" :data {:error (.getMessage e)}})
          {:content [{:type "text" :text (str "Error: " (.getMessage e))}]})))))

(defn complete-tool [mcp-context arguments]
  (let [{:keys [prefix ns]} arguments
        nrepl-client (:nrepl-client mcp-context)
        ns (or ns "user")]
    (if (nil? nrepl-client)
      {:content [{:type "text" 
                  :text "Error: nREPL client not available. Code completion requires an active nREPL connection."}]}
      (try
        (let [responses (nrepl/message nrepl-client {:op "complete" :prefix prefix :ns ns})
            result (first responses)
            completions (:completions result)]
        {:content [{:type "text" 
                    :text (if completions
                            (str "Found " (count completions) " completions:\n"
                                 (str/join "\n" (map :candidate completions)))
                            "No completions found")}]})
        (catch Exception e
          (log/log! {:level :error :msg "Error getting completions" :data {:error (.getMessage e)}})
          {:content [{:type "text" :text (str "Error: " (.getMessage e))}]})))))

(defn apropos-tool [mcp-context arguments]
  (let [{:keys [query ns]} arguments
        nrepl-client (:nrepl-client mcp-context)
        message (cond-> {:op "apropos" :query query}
                  ns (assoc :ns ns))]
    (try
      (let [responses (nrepl/message nrepl-client message)
            result (first responses)
            matches (:apropos-matches result)]
        {:content [{:type "text" 
                    :text (if matches
                            (str "Found " (count matches) " matches:\n"
                                 (str/join "\n" matches))
                            "No matches found")}]})
      (catch Exception e
        (log/log! {:level :error :msg "Error in apropos search" :data {:error (.getMessage e)}})
        {:content [{:type "text" :text (str "Error: " (.getMessage e))}]}))))

(defn test-all-tool [mcp-context arguments]
  (let [nrepl-client (:nrepl-client mcp-context)]
    (try
      (let [responses (nrepl/message nrepl-client {:op "test-all"})
            results-msgs (filter #(contains? % :results) responses)
            summary-msgs (filter #(contains? % :summary) responses)]
        {:content [{:type "text" 
                    :text (str "Test Results:\n"
                               "Results: " (str results-msgs) "\n"
                               "Summary: " (str summary-msgs))}]})
      (catch Exception e
        (log/log! {:level :error :msg "Error running tests" :data {:error (.getMessage e)}})
        {:content [{:type "text" :text (str "Error: " (.getMessage e))}]}))))

(defn info-tool [mcp-context arguments]
  (let [{:keys [symbol ns]} arguments
        nrepl-client (:nrepl-client mcp-context)
        ns (or ns "user")]
    (try
      (let [responses (nrepl/message nrepl-client {:op "info" :symbol symbol :ns ns})
            result (first responses)]
        {:content [{:type "text" 
                    :text (if (contains? (:status result) "no-info")
                            "No information available for symbol"
                            (pr-str (dissoc result :id :session :status)))}]})
      (catch Exception e
        (log/log! {:level :error :msg "Error getting symbol info" :data {:error (.getMessage e)}})
        {:content [{:type "text" :text (str "Error: " (.getMessage e))}]}))))

(defn ns-list-tool [mcp-context arguments]
  (let [nrepl-client (:nrepl-client mcp-context)]
    (try
      (let [responses (nrepl/message nrepl-client {:op "ns-list"})
            result (first responses)
            namespaces (:ns-list result)]
        {:content [{:type "text" 
                    :text (if namespaces
                            (str "Found " (count namespaces) " namespaces:\n"
                                 (str/join "\n" namespaces))
                            "No namespaces found")}]})
      (catch Exception e
        (log/log! {:level :error :msg "Error listing namespaces" :data {:error (.getMessage e)}})
        {:content [{:type "text" :text (str "Error: " (.getMessage e))}]}))))

(defn ns-vars-tool [mcp-context arguments]
  (let [{:keys [ns]} arguments
        nrepl-client (:nrepl-client mcp-context)]
    (try
      (let [responses (nrepl/message nrepl-client {:op "ns-vars" :ns ns})
            result (first responses)
            vars (:ns-vars result)]
        {:content [{:type "text" 
                    :text (if vars
                            (str "Vars in " ns " (" (count vars) "):\n"
                                 (str/join "\n" vars))
                            (str "No vars found in namespace: " ns))}]})
      (catch Exception e
        (log/log! {:level :error :msg "Error listing namespace vars" :data {:error (.getMessage e)}})
        {:content [{:type "text" :text (str "Error: " (.getMessage e))}]}))))

(defn classpath-tool [mcp-context arguments]
  (let [nrepl-client (:nrepl-client mcp-context)]
    (try
      (let [responses (nrepl/message nrepl-client {:op "classpath"})
            result (first responses)
            classpath (:classpath result)]
        {:content [{:type "text" 
                    :text (if classpath
                            (str "Classpath (" (count classpath) " entries):\n"
                                 (str/join "\n" classpath))
                            "No classpath information available")}]})
      (catch Exception e
        (log/log! {:level :error :msg "Error getting classpath" :data {:error (.getMessage e)}})
        {:content [{:type "text" :text (str "Error: " (.getMessage e))}]}))))

(defn refresh-tool [mcp-context arguments]
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
  (let [{:keys [var-query]} arguments
        nrepl-client (:nrepl-client mcp-context)]
    (try
      (let [query-map (if (str/includes? var-query "/")
                        (let [[ns-name var-name] (str/split var-query #"/" 2)]
                          {:ns-query {:exactly [ns-name]}
                           :var-query {:exactly [var-name]}})
                        {:ns-query {:exactly [var-query]}})
            responses (nrepl/message nrepl-client (assoc query-map :op "test-var-query"))
            results-msgs (filter #(contains? % :results) responses)
            summary-msgs (filter #(contains? % :summary) responses)]
        {:content [{:type "text" 
                    :text (str "Test Results for " var-query ":\n"
                               "Results: " (str results-msgs) "\n"
                               "Summary: " (str summary-msgs))}]})
      (catch Exception e
        (log/log! {:level :error :msg "Error running specific tests" :data {:error (.getMessage e)}})
        {:content [{:type "text" :text (str "Error: " (.getMessage e))}]}))))

;; ===============================================
;; Tool Definitions
;; ===============================================

(def tools
  "Cider-nREPL tool definitions for mcp-toolkit"
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