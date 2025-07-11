(ns is.simm.repl-mcp.tools.cider-nrepl
  (:require [is.simm.repl-mcp.interactive :refer [register-tool!]]
            [nrepl.core :as nrepl]
            [clojure.string :as str]
            [taoensso.telemere :as log]))

;; Code formatting tool
(register-tool! :format-code
  "Format Clojure code using cider-nrepl's format-code operation"
  {:code {:type "string" :description "Clojure code to format"}}
  (fn [tool-call context]
    (let [{:strs [code]} (:args tool-call)
          nrepl-client (:nrepl-client context)]
      (try
        (let [responses (nrepl/message nrepl-client {:op "format-code" :code code})
              result (first responses)]
          (if (:formatted-code result)
            {:formatted-code (:formatted-code result) :status :success}
            {:error "Failed to format code" :status :error}))
        (catch Exception e
          (log/log! {:level :error :msg "Error formatting code" :data {:error (.getMessage e)}})
          {:error (.getMessage e) :status :error})))))

;; Macro expansion tool
(register-tool! :macroexpand
  "Expand Clojure macros using cider-nrepl's macroexpand operation"
  {:code {:type "string" :description "Clojure code containing macros to expand"}}
  (fn [tool-call context]
    (let [{:strs [code]} (:args tool-call)
          nrepl-client (:nrepl-client context)]
      (try
        (let [responses (nrepl/message nrepl-client {:op "macroexpand" :code code})
              result (first responses)]
          (if (:expansion result)
            {:expansion (:expansion result) :status :success}
            {:error "Failed to expand macro" :status :error}))
        (catch Exception e
          (log/log! {:level :error :msg "Error expanding macro" :data {:error (.getMessage e)}})
          {:error (.getMessage e) :status :error})))))

;; Function documentation tool
(register-tool! :eldoc
  "Get function documentation and signatures using cider-nrepl's eldoc operation"
  {:symbol {:type "string" :description "Symbol to get documentation for"}
   :ns {:type "string" :optional true :description "Namespace context (defaults to 'user')"}}
  (fn [tool-call context]
    (let [{:strs [symbol ns]} (:args tool-call)
          nrepl-client (:nrepl-client context)
          ns (or ns "user")]
      (try
        (let [responses (nrepl/message nrepl-client {:op "eldoc" :symbol symbol :ns ns})
              result (first responses)]
          (if (:eldoc result)
            {:symbol (:name result)
             :ns (:ns result)
             :type (:type result)
             :docstring (:docstring result)
             :signatures (:eldoc result)
             :status :success}
            {:error "Symbol not found or no documentation available" :status :error}))
        (catch Exception e
          (log/log! {:level :error :msg "Error getting eldoc" :data {:error (.getMessage e)}})
          {:error (.getMessage e) :status :error})))))

;; Code completion tool
(register-tool! :complete
  "Get code completion candidates using cider-nrepl's complete operation"
  {:prefix {:type "string" :description "Prefix to complete"}
   :ns {:type "string" :optional true :description "Namespace context (defaults to 'user')"}}
  (fn [tool-call context]
    (let [{:strs [prefix ns]} (:args tool-call)
          nrepl-client (:nrepl-client context)
          ns (or ns "user")]
      (try
        (let [responses (nrepl/message nrepl-client {:op "complete" :prefix prefix :ns ns})
              result (first responses)]
          (if (:completions result)
            {:completions (:completions result)
             :count (count (:completions result))
             :status :success}
            {:error "No completions found" :status :error}))
        (catch Exception e
          (log/log! {:level :error :msg "Error getting completions" :data {:error (.getMessage e)}})
          {:error (.getMessage e) :status :error})))))

;; Symbol search tool
(register-tool! :apropos
  "Search for symbols matching a pattern using cider-nrepl's apropos operation"
  {:query {:type "string" :description "Search query/pattern"}
   :ns {:type "string" :optional true :description "Namespace to search in (optional)"}}
  (fn [tool-call context]
    (let [{:strs [query ns]} (:args tool-call)
          nrepl-client (:nrepl-client context)
          msg (if ns
                {:op "apropos" :query query :ns ns}
                {:op "apropos" :query query})]
      (try
        (let [responses (nrepl/message nrepl-client msg)
              result (first responses)]
          (if (:apropos-matches result)
            {:matches (:apropos-matches result)
             :count (count (:apropos-matches result))
             :status :success}
            {:error "No matches found" :status :error}))
        (catch Exception e
          (log/log! {:level :error :msg "Error searching symbols" :data {:error (.getMessage e)}})
          {:error (.getMessage e) :status :error})))))

;; Test runner tool
(register-tool! :test-all
  "Run all tests in the project using cider-nrepl's test-all operation"
  {}
  (fn [_ context]
    (let [nrepl-client (:nrepl-client context)]
      (try
        (let [responses (nrepl/message nrepl-client {:op "test-all"})
              results (filter :results responses)
              summary (first (filter :summary responses))]
          (if summary
            {:summary (:summary summary)
             :elapsed-time (:elapsed-time summary)
             :results (if (seq results) (:results (first results)) {})
             :status :success}
            {:error "Failed to run tests" :status :error}))
        (catch Exception e
          (log/log! {:level :error :msg "Error running tests" :data {:error (.getMessage e)}})
          {:error (.getMessage e) :status :error})))))

;; Enhanced info tool (better than current one)
(register-tool! :enhanced-info
  "Get enhanced symbol information using cider-nrepl's info operation"
  {:symbol {:type "string" :description "Symbol to get info for"}
   :ns {:type "string" :optional true :description "Namespace context (defaults to 'user')"}}
  (fn [tool-call context]
    (let [{:strs [symbol ns]} (:args tool-call)
          nrepl-client (:nrepl-client context)
          ns (or ns "user")]
      (try
        (let [responses (nrepl/message nrepl-client {:op "info" :symbol symbol :ns ns})
              result (first responses)]
          (if (:status result)
            (if (contains? (set (:status result)) "no-info")
              {:error "No information available for symbol" :status :error}
              {:info (dissoc result :id :session :status) :status :success})
            {:error "Failed to get symbol info" :status :error}))
        (catch Exception e
          (log/log! {:level :error :msg "Error getting symbol info" :data {:error (.getMessage e)}})
          {:error (.getMessage e) :status :error})))))

;; =============================================================================
;; HIGH-IMPACT CIDER-NREPL TOOLS FOR SPEED
;; =============================================================================

;; Namespace exploration tools
(register-tool! :ns-list
  "Browse all available namespaces for rapid codebase exploration"
  {}
  (fn [_ context]
    (let [nrepl-client (:nrepl-client context)]
      (try
        (let [responses (nrepl/message nrepl-client {:op "ns-list"})
              result (first responses)]
          (if (:ns-list result)
            {:namespaces (:ns-list result)
             :count (count (:ns-list result))
             :status :success}
            {:error "Failed to list namespaces" :status :error}))
        (catch Exception e
          (log/log! {:level :error :msg "Error listing namespaces" :data {:error (.getMessage e)}})
          {:error (.getMessage e) :status :error})))))

(register-tool! :ns-vars
  "Explore namespace contents - get all vars in a namespace"
  {:ns {:type "string" :description "Namespace to explore"}}
  (fn [tool-call context]
    (let [{:strs [ns]} (:args tool-call)
          nrepl-client (:nrepl-client context)]
      (try
        (let [responses (nrepl/message nrepl-client {:op "ns-vars" :ns ns})
              result (first responses)]
          (if (:ns-vars result)
            {:namespace ns
             :vars (:ns-vars result)
             :count (count (:ns-vars result))
             :status :success}
            {:error (str "Failed to get vars for namespace: " ns) :status :error}))
        (catch Exception e
          (log/log! {:level :error :msg "Error getting namespace vars" :data {:error (.getMessage e)}})
          {:error (.getMessage e) :status :error})))))

;; Project understanding tools
(register-tool! :classpath
  "Understand available dependencies and classpath entries"
  {}
  (fn [_ context]
    (let [nrepl-client (:nrepl-client context)]
      (try
        (let [responses (nrepl/message nrepl-client {:op "classpath"})
              result (first responses)]
          (if (:classpath result)
            {:classpath (:classpath result)
             :count (count (:classpath result))
             :status :success}
            {:error "Failed to get classpath" :status :error}))
        (catch Exception e
          (log/log! {:level :error :msg "Error getting classpath" :data {:error (.getMessage e)}})
          {:error (.getMessage e) :status :error})))))

;; Development iteration tools
(register-tool! :refresh
  "Safely refresh user namespaces without killing server infrastructure"
  {}
  (fn [_ _]
    (try
      (let [all-namespaces (all-ns)
            server-prefixes ["is.simm.repl-mcp" "clojure." "nrepl." "cider." 
                            "refactor-" "rewrite-" "taoensso." "pogonos." "orchard."]
            protected-ns? (fn [ns-name]
                            (some #(clojure.string/starts-with? (str ns-name) %) 
                                  server-prefixes))
            user-namespaces (remove #(protected-ns? (ns-name %)) all-namespaces)
            user-ns-names (map ns-name user-namespaces)]
        
        ;; Refresh each user namespace
        (let [refresh-results (atom [])]
          (doseq [ns-name user-ns-names]
            (try
              (require ns-name :reload)
              (swap! refresh-results conj {:namespace ns-name :status :success})
              (catch Exception e
                (swap! refresh-results conj {:namespace ns-name :status :error :error (.getMessage e)}))))
          
          (let [successful (count (filter #(= :success (:status %)) @refresh-results))
                failed (count (filter #(= :error (:status %)) @refresh-results))]
            {:refreshed-count successful
             :failed-count failed
             :protected-count (- (count all-namespaces) (count user-ns-names))
             :refreshed-namespaces (map :namespace (filter #(= :success (:status %)) @refresh-results))
             :failed-namespaces (map :namespace (filter #(= :error (:status %)) @refresh-results))
             :status :success})))
      (catch Exception e
        (log/log! {:level :error :msg "Error during safe refresh" :data {:error (.getMessage e)}})
        {:error (.getMessage e) :status :error}))))

;; Targeted testing tools
(register-tool! :test-var-query
  "Run specific tests instead of all tests for rapid iteration"
  {:var-query {:type "string" :description "Test var query - can be namespace (e.g., 'my.ns') or specific var (e.g., 'my.ns/my-test')"}}
  (fn [tool-call context]
    (let [{:strs [var-query]} (:args tool-call)
          nrepl-client (:nrepl-client context)]
      (try
        ;; Parse the var-query string into proper format
        (let [query-map (cond
                         ;; If it contains a slash, it's a specific var
                         (str/includes? var-query "/")
                         (let [[ns-name var-name] (str/split var-query #"/")]
                           {:ns-query {:exactly [ns-name]}
                            :var-query {:exactly [var-name]}})
                         
                         ;; Otherwise, it's a namespace query  
                         :else
                         {:ns-query {:exactly [var-query]}})
                         
              responses (nrepl/message nrepl-client {:op "test-var-query" :var-query query-map})]
          (log/log! {:level :debug :msg "Test var query responses" :data {:responses responses :var-query var-query :query-map query-map}})
          (if (seq responses)
            (let [results (filter :results responses)
                  summary (first (filter :summary responses))]
              (if summary
                {:summary (:summary summary)
                 :results (if (seq results) (:results (first results)) {})
                 :var-query var-query
                 :query-map query-map
                 :status :success}
                {:error "No test results found" :status :error}))
            {:error "No responses from nREPL client" :status :error}))
        (catch Exception e
          (log/log! {:level :error :msg "Error running test query" :data {:error (.getMessage e)}})
          {:error (.getMessage e) :status :error})))))