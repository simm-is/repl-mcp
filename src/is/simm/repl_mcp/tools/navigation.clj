(ns is.simm.repl-mcp.tools.navigation
  "Advanced code navigation tools using refactor-nrepl with safe nREPL interactions"
  (:require 
   [clojure.string :as str]
   [clojure.java.io :as io]
   [taoensso.telemere :as log]
   [is.simm.repl-mcp.tools.nrepl-utils :as nrepl-utils]))

;; ===============================================
;; Navigation Functions with Safe nREPL Interactions
;; ===============================================

(defn bencode-friendly-data
  "Transform data to be bencode-friendly"
  [data]
  (cond
    (map? data) (reduce-kv (fn [acc k v] (assoc acc k (bencode-friendly-data v))) {} data)
    (coll? data) (mapv bencode-friendly-data data)
    (boolean? data) (str data)
    (set? data) (vec data)
    :else data))

(defn process-find-symbol-response
  "Process find-symbol response combining multiple results"
  [responses]
  (let [combined-results (mapcat #(:value % []) responses)
        final-result (first (filter #(contains? % :status) responses))]
    (-> (or final-result {})
        (assoc :value combined-results)
        bencode-friendly-data)))

(defn is-external-namespace?
  "Check if a namespace is external (not part of our project)"
  [namespace-name]
  (or (str/starts-with? namespace-name "clojure.")
      (str/starts-with? namespace-name "java.")
      (str/starts-with? namespace-name "javax.")
      (str/starts-with? namespace-name "cljs.")
      ;; Add common external library prefixes
      (str/starts-with? namespace-name "ring.")
      (str/starts-with? namespace-name "compojure.")
      (str/starts-with? namespace-name "hiccup.")))

(defn find-namespace-file
  "Find the source file for a namespace"
  [namespace-name]
  (let [namespace-path (str/replace namespace-name "." "/")
        namespace-path-underscores (str/replace namespace-name "-" "_")
        file-paths [(str "src/" namespace-path ".clj")
                   (str "src/" namespace-path-underscores ".clj") 
                   (str "src/main/clojure/" namespace-path ".clj")
                   (str namespace-path ".clj")
                   ;; Handle the case where we might be in a different working directory
                   (str "../src/" namespace-path ".clj")
                   (str "./src/" namespace-path ".clj")]]
    (first (filter #(-> % io/file .exists) file-paths))))

(defn find-function-callers
  "Find functions that call the specified function using refactor-nrepl"
  [nrepl-client namespace-name function-name & {:keys [timeout] :or {timeout 120000}}]
  (let [symbol-str (str namespace-name "/" function-name)]
    
    ;; Check if this is an external namespace first
    (if (is-external-namespace? namespace-name)
      (do
        (log/log! {:level :info :msg "Skipping external namespace for call hierarchy" 
                   :data {:namespace namespace-name :symbol symbol-str}})
        {:status :success
         :value {:value [] 
                :status "success"
                :message (str "External namespace " namespace-name " - no project callers found")}})
      
      ;; For project namespaces, try to find the file
      (let [existing-file (find-namespace-file namespace-name)]
        
        ;; Only proceed if we found an actual file
        (if existing-file
          (do
            (log/log! {:level :info :msg "Finding callers for function" 
                       :data {:symbol symbol-str :file existing-file}})
            
            (let [result (nrepl-utils/safe-nrepl-message nrepl-client
                           {:op "find-symbol"
                            :ns namespace-name
                            :sym function-name
                            :file existing-file
                            :line "1"
                            :column "1"}
                           :timeout timeout
                           :operation-name "Function caller search")]
              (if (= (:status result) :success)
                {:status :success
                 :value (process-find-symbol-response (:responses result))}
                result)))
          (do
            (log/log! {:level :info :msg "No source file found for namespace" 
                       :data {:namespace namespace-name :symbol symbol-str}})
            {:status :success
             :value {:value [] 
                    :status "success"
                    :message (str "No source file found for namespace " namespace-name)}}))))))

(defn build-call-hierarchy
  "Build call hierarchy for a function"
  [nrepl-client namespace-name function-name max-depth & {:keys [timeout] :or {timeout 120000}}]
  (try
    (log/log! {:level :info :msg "Building call hierarchy" 
               :data {:namespace namespace-name :function function-name :max-depth max-depth}})
    
    (let [result (find-function-callers nrepl-client namespace-name function-name :timeout timeout)]
      (if (= (:status result) :success)
        {:status :success
         :value {:callers (:value (:value result))
                :function function-name
                :namespace namespace-name
                :depth 1 ;; Simplified - only 1 level for now
                :status "success"}}
        result))
    (catch Exception e
      (log/log! {:level :error :msg "Call hierarchy failed" 
                 :data {:error (.getMessage e)}})
      {:status :error
       :error (.getMessage e)})))

(defn format-call-hierarchy
  "Format call hierarchy results for display"
  [hierarchy-result]
  (if (:error hierarchy-result)
    (str "Error: " (:error hierarchy-result))
    (let [hierarchy-data (:value hierarchy-result)
          {:keys [callers function namespace message]} hierarchy-data
          caller-count (count callers)]
      (cond
        message
        message
        
        (zero? caller-count)
        (str "No callers found for " namespace "/" function)
        
        :else
        (str "Found " caller-count " caller(s) for " namespace "/" function ":\n"
             (str/join "\n" (map (fn [caller]
                                   (str "  " (or (:name caller) (:file caller) "Unknown")))
                                 callers)))))))

(defn ensure-namespace-loaded
  "Ensure namespace is loaded in nREPL session"
  [nrepl-client namespace-name & {:keys [timeout] :or {timeout 30000}}]
  (let [result (nrepl-utils/safe-nrepl-message nrepl-client
                 {:op "eval" :code (str "(require '" namespace-name ")")}
                 :timeout timeout
                 :operation-name "Namespace loading")]
    (= (:status result) :success)))

(defn find-symbol-usages
  "Find usages of a symbol with enhanced error handling"
  [nrepl-client namespace-name symbol-name include-context & {:keys [timeout] :or {timeout 120000}}]
  (try
    ;; Check if this is an external namespace
    (if (is-external-namespace? namespace-name)
      {:status :success
       :value {:usages []
              :message (str "External namespace " namespace-name " - no usages found")
              :symbol symbol-name
              :namespace namespace-name
              :status "success"}}
      
      (let [existing-file (find-namespace-file namespace-name)]
        
        ;; Only proceed if we found an actual file
        (if existing-file
          (do
            ;; Ensure namespace is loaded
            (ensure-namespace-loaded nrepl-client namespace-name :timeout 30000)
            
            ;; Find symbol usages  
            (let [result (nrepl-utils/safe-nrepl-message nrepl-client
                           {:op "find-symbol"
                            :ns namespace-name
                            :sym symbol-name
                            :file existing-file
                            :line "1"
                            :column "1"}
                           :timeout timeout
                           :operation-name "Symbol usage search")]
              
              (if (= (:status result) :success)
                {:status :success
                 :value {:usages (:value (process-find-symbol-response (:responses result)))
                        :symbol symbol-name
                        :namespace namespace-name
                        :include-context include-context
                        :status "success"}}
                result)))
          {:status :success
           :value {:usages []
                  :message (str "No source file found for namespace " namespace-name)
                  :symbol symbol-name
                  :namespace namespace-name
                  :status "success"}})))
    
    (catch Exception e
      (log/log! {:level :error :msg "Usage finding failed" 
                 :data {:namespace namespace-name :symbol symbol-name :error (.getMessage e)}})
      {:status :error
       :error (.getMessage e)})))

(defn categorize-usages
  "Categorize usages by type and file"
  [usages]
  (let [by-type (group-by :type usages)
        by-file (group-by :file usages)]
    {:by-type by-type
     :by-file by-file
     :total (count usages)}))

(defn format-usage-analysis
  "Format comprehensive usage analysis"
  [usage-result]
  (if (:error usage-result)
    (str "Error: " (:error usage-result))
    (let [usage-data (:value usage-result)
          {:keys [usages symbol namespace message]} usage-data
          analysis (categorize-usages usages)]
      (cond
        message
        message
        
        (zero? (:total analysis))
        (str "No usages found for " namespace "/" symbol)
        
        :else
        (str "Found " (:total analysis) " usage(s) for " namespace "/" symbol ":\n"
             (str/join "\n" (map (fn [usage]
                                   (str "  " (:file usage) " (line " (:line usage) ")"))
                                 usages)))))))

;; ===============================================
;; Tool Implementations
;; ===============================================

(defn call-hierarchy-tool [mcp-context arguments]
  (let [{:keys [namespace function direction max-depth]} arguments
        max-depth (or max-depth 3)]
    (cond
      (or (nil? namespace) (empty? namespace))
      {:content [{:type "text" 
                  :text "Error: namespace parameter is required"}]}
      
      (or (nil? function) (empty? function))
      {:content [{:type "text" 
                  :text "Error: function parameter is required"}]}
      
      (not= direction "callers")
      {:content [{:type "text" 
                  :text "Error: Only 'callers' direction is currently supported"}]}
      
      :else
      (let [result (nrepl-utils/with-safe-nrepl mcp-context "Call hierarchy analysis"
                     (fn [nrepl-client timeout]
                       (build-call-hierarchy nrepl-client (str namespace) (str function) max-depth :timeout timeout))
                     :timeout 120000)]
        (if (= (:status result) :success)
          {:content [{:type "text" :text (format-call-hierarchy result)}]}
          result)))))

(defn usage-finder-tool [mcp-context arguments]
  (let [{:keys [namespace symbol include-context]} arguments
        include-context (if (nil? include-context) true include-context)]
    (cond
      (or (nil? namespace) (empty? namespace))
      {:content [{:type "text" 
                  :text "Error: namespace parameter is required"}]}
      
      (or (nil? symbol) (empty? symbol))
      {:content [{:type "text" 
                  :text "Error: symbol parameter is required"}]}
      
      :else
      (nrepl-utils/with-safe-nrepl mcp-context "Symbol usage analysis"
        (fn [nrepl-client timeout]
          (find-symbol-usages nrepl-client (str namespace) (str symbol) include-context :timeout timeout))
        :timeout 120000))))

;; ===============================================
;; Tool Definitions
;; ===============================================

(def tools
  "Navigation tool definitions for mcp-toolkit"
  [{:name "call-hierarchy"
    :description "Analyze function call hierarchy (callers) in a Clojure project"
    :inputSchema {:type "object"
                  :properties {:namespace {:type "string" :description "Namespace containing the function"}
                              :function {:type "string" :description "Function name to analyze"}
                              :direction {:type "string" :description "Direction: 'callers' (who calls this)" :enum ["callers"]}
                              :max-depth {:type "number" :description "Maximum depth to traverse (default: 3)"}}
                  :required ["namespace" "function"]}
    :tool-fn call-hierarchy-tool}
   
   {:name "usage-finder"
    :description "Find all usages of a symbol across the project with detailed analysis"
    :inputSchema {:type "object"
                  :properties {:namespace {:type "string" :description "Namespace containing the symbol"}
                              :symbol {:type "string" :description "Symbol name to find usages for"}
                              :include-context {:type "boolean" :description "Include surrounding code context (default: true)"}}
                  :required ["namespace" "symbol"]}
    :tool-fn usage-finder-tool}])