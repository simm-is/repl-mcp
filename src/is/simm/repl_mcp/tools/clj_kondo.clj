(ns is.simm.repl-mcp.tools.clj-kondo
  "Code linting and quality tools using clj-kondo"
  (:require 
   [clj-kondo.core :as clj-kondo]
   [taoensso.telemere :as log]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]))

;; ===============================================
;; Clj-kondo Functions
;; ===============================================

(defn lint-code-string
  "Lint a string of Clojure code using clj-kondo"
  [code & {:keys [filename lang config] :or {filename "stdin.clj" lang :clj}}]
  (try
    (log/log! {:level :info :msg "Linting code string" 
               :data {:code-length (count code) :filename filename :lang lang}})
    
    (let [lint-config (merge {:output {:format :edn}} config)
          temp-file (java.io.File/createTempFile "clj-kondo" (str "." (name lang)))
          _ (spit temp-file code)
          result (clj-kondo/run! {:lint [(.getAbsolutePath temp-file)]
                                  :config lint-config})]
      (.delete temp-file)
      
      (if (seq (:findings result))
        {:findings (:findings result)
         :summary (:summary result)
         :status :success}
        {:message "No linting issues found"
         :summary (:summary result)
         :status :success}))
    
    (catch Exception e
      (log/log! {:level :error :msg "Code linting failed" 
                 :data {:error (.getMessage e)}})
      {:error (.getMessage e) :status :error})))

(defn lint-files
  "Lint files or directories using clj-kondo"
  [paths & {:keys [config cache parallel] :or {cache true parallel true}}]
  (try
    (log/log! {:level :info :msg "Linting files/directories" 
               :data {:paths paths :cache cache :parallel parallel}})
    
    (let [lint-config (merge {:output {:format :edn}
                              :cache cache
                              :parallel parallel} 
                             config)
          result (clj-kondo/run! {:lint paths :config lint-config})]
      
      (if (seq (:findings result))
        {:findings (:findings result)
         :summary (:summary result)
         :status :success}
        {:message "No linting issues found"
         :summary (:summary result)
         :status :success}))
    
    (catch Exception e
      (log/log! {:level :error :msg "File linting failed" 
                 :data {:error (.getMessage e) :paths paths}})
      {:error (.getMessage e) :status :error})))

(defn setup-project-config
  "Initialize clj-kondo configuration for a project"
  [project-root & {:keys [copy-configs dependencies] :or {copy-configs true dependencies false}}]
  (try
    (log/log! {:level :info :msg "Setting up clj-kondo configuration" 
               :data {:project-root project-root :copy-configs copy-configs :dependencies dependencies}})
    
    ;; This is a simplified version - in practice you might want to:
    ;; 1. Create .clj-kondo directory
    ;; 2. Copy configurations from dependencies
    ;; 3. Set up custom rules
    
    (let [clj-kondo-dir (io/file project-root ".clj-kondo")]
      (when-not (.exists clj-kondo-dir)
        (.mkdirs clj-kondo-dir))
      
      {:message (str "clj-kondo configuration initialized in " project-root)
       :status :success})
    
    (catch Exception e
      (log/log! {:level :error :msg "Setup-clj-kondo failed" 
                 :data {:error (.getMessage e) :project-root project-root}})
      {:error (.getMessage e) :status :error})))

(defn analyze-project
  "Get full clj-kondo analysis data for a project"
  [paths & {:keys [config] :or {config {}}}]
  (try
    (log/log! {:level :info :msg "Analyzing project with clj-kondo" 
               :data {:paths paths}})
    
    (let [analysis-config (merge {:output {:format :edn}
                                  :analysis {:var-definitions {:shallow true}
                                            :var-usages {:shallow true}
                                            :keywords true
                                            :locals true}} 
                                 config)
          result (clj-kondo/run! {:lint paths :config analysis-config})]
      
      {:analysis (:analysis result)
       :summary (:summary result)
       :findings (:findings result)
       :status :success})
    
    (catch Exception e
      (log/log! {:level :error :msg "Project analysis failed" 
                 :data {:error (.getMessage e) :paths paths}})
      {:error (.getMessage e) :status :error})))

(defn find-unused-vars
  "Find all unused vars in a project"
  [paths & {:keys [config include-private?] :or {config {} include-private? true}}]
  (try
    (log/log! {:level :info :msg "Finding unused vars" 
               :data {:paths paths :include-private? include-private?}})
    
    (let [analysis-result (analyze-project paths :config config)]
      (if (= (:status analysis-result) :success)
        (let [analysis (:analysis analysis-result)
              {:keys [var-definitions var-usages]} analysis
              
              ;; Filter out private vars if requested
              filtered-definitions (if include-private?
                                    var-definitions
                                    (filter #(not (:private %)) var-definitions))
              
              defined-vars (set (map (juxt :ns :name) filtered-definitions))
              used-vars (set (map (juxt :to :name) var-usages))
              unused-vars (set/difference defined-vars used-vars)
              
              ;; Get full definitions for unused vars
              unused-definitions (->> filtered-definitions
                                     (filter (fn [def]
                                               (contains? unused-vars [(:ns def) (:name def)])))
                                     (map #(select-keys % [:ns :name :filename :row :col :tag :private]))
                                     vec)]
          
          {:unused-vars unused-definitions
           :total-definitions (count filtered-definitions)
           :total-unused (count unused-definitions)
           :status :success})
        analysis-result))
    
    (catch Exception e
      (log/log! {:level :error :msg "Find unused vars failed" 
                 :data {:error (.getMessage e) :paths paths}})
      {:error (.getMessage e) :status :error})))

(defn find-var-definitions
  "Find all var definitions in a project with optional filtering"
  [paths & {:keys [config namespace-filter name-filter tag-filter] :or {config {}}}]
  (try
    (log/log! {:level :info :msg "Finding var definitions" 
               :data {:paths paths :filters {:namespace namespace-filter 
                                            :name name-filter 
                                            :tag tag-filter}}})
    
    (let [analysis-result (analyze-project paths :config config)]
      (if (= (:status analysis-result) :success)
        (let [var-definitions (get-in analysis-result [:analysis :var-definitions])
              
              ;; Apply filters
              filtered-definitions (cond->> var-definitions
                                     namespace-filter (filter #(= (str (:ns %)) (str namespace-filter)))
                                     name-filter (filter #(= (str (:name %)) (str name-filter)))
                                     tag-filter (filter #(= (:tag %) tag-filter)))
              
              formatted-definitions (map #(select-keys % [:ns :name :filename :row :col :tag :private :arity]) 
                                         filtered-definitions)]
          
          {:var-definitions formatted-definitions
           :total-found (count formatted-definitions)
           :status :success})
        analysis-result))
    
    (catch Exception e
      (log/log! {:level :error :msg "Find var definitions failed" 
                 :data {:error (.getMessage e) :paths paths}})
      {:error (.getMessage e) :status :error})))

(defn find-var-usages
  "Find all usages of specific vars in a project"
  [paths & {:keys [config namespace-filter name-filter] :or {config {}}}]
  (try
    (log/log! {:level :info :msg "Finding var usages" 
               :data {:paths paths :filters {:namespace namespace-filter :name name-filter}}})
    
    (let [analysis-result (analyze-project paths :config config)]
      (if (= (:status analysis-result) :success)
        (let [var-usages (get-in analysis-result [:analysis :var-usages])
              
              ;; Apply filters
              filtered-usages (cond->> var-usages
                                namespace-filter (filter #(= (str (:to %)) (str namespace-filter)))
                                name-filter (filter #(= (str (:name %)) (str name-filter))))
              
              formatted-usages (map #(select-keys % [:to :name :filename :row :col :from]) 
                                   filtered-usages)]
          
          {:var-usages formatted-usages
           :total-found (count formatted-usages)
           :status :success})
        analysis-result))
    
    (catch Exception e
      (log/log! {:level :error :msg "Find var usages failed" 
                 :data {:error (.getMessage e) :paths paths}})
      {:error (.getMessage e) :status :error})))

;; ===============================================
;; Tool Implementations
;; ===============================================

(defn lint-code-tool [mcp-context arguments]
  (let [{:keys [code filename lang config]} arguments
        result (lint-code-string code 
                                :filename (or filename "stdin.clj")
                                :lang (keyword (or lang "clj"))
                                :config config)]
    {:content [{:type "text" 
                :text (if (= (:status result) :success)
                        (if (:findings result)
                          (str "Found " (count (:findings result)) " linting issues:\n"
                               (clojure.string/join "\n" 
                                 (map (fn [finding]
                                        (str (:filename finding) ":" 
                                             (:row finding) ":" 
                                             (:col finding) " "
                                             (:level finding) " " 
                                             (:message finding)))
                                      (:findings result))))
                          (:message result))
                        (str "Error: " (:error result)))}]}))

(defn lint-project-tool [mcp-context arguments]
  (let [{:keys [paths config cache parallel]} arguments
        result (lint-files paths 
                          :config config
                          :cache (if (nil? cache) true cache)
                          :parallel (if (nil? parallel) true parallel))]
    {:content [{:type "text" 
                :text (if (= (:status result) :success)
                        (if (:findings result)
                          (str "Found " (count (:findings result)) " linting issues in project:\n"
                               (clojure.string/join "\n" 
                                 (map (fn [finding]
                                        (str (:filename finding) ":" 
                                             (:row finding) ":" 
                                             (:col finding) " "
                                             (:level finding) " " 
                                             (:message finding)))
                                      (:findings result))))
                          (:message result))
                        (str "Error: " (:error result)))}]}))

(defn setup-clj-kondo-tool [mcp-context arguments]
  (let [{:keys [project-root copy-configs dependencies]} arguments
        result (setup-project-config project-root 
                                    :copy-configs (if (nil? copy-configs) true copy-configs)
                                    :dependencies (if (nil? dependencies) false dependencies))]
    {:content [{:type "text" 
                :text (if (= (:status result) :success)
                        (:message result)
                        (str "Error: " (:error result)))}]}))

(defn analyze-project-tool [mcp-context arguments]
  (let [{:keys [paths config]} arguments
        result (analyze-project paths :config (or config {}))]
    {:content [{:type "text" 
                :text (if (= (:status result) :success)
                        (str "Project analysis completed\n"
                             "Definitions: " (count (get-in result [:analysis :var-definitions])) "\n"
                             "Usages: " (count (get-in result [:analysis :var-usages])) "\n"
                             "Findings: " (count (:findings result)))
                        (str "Error: " (:error result)))}]}))

(defn find-unused-vars-tool [mcp-context arguments]
  (let [{:keys [paths config include-private]} arguments
        result (find-unused-vars paths 
                                :config (or config {})
                                :include-private? (if (nil? include-private) true include-private))]
    {:content [{:type "text" 
                :text (if (= (:status result) :success)
                        (if (seq (:unused-vars result))
                          (str "Found " (:total-unused result) " unused vars out of " (:total-definitions result) " total definitions:\n"
                               (str/join "\n" 
                                 (map (fn [var]
                                        (str (:ns var) "/" (:name var) 
                                             " (" (:filename var) ":" (:row var) ":" (:col var) ")"
                                             (when (:tag var) (str " [" (:tag var) "]"))
                                             (when (:private var) " [private]")))
                                      (:unused-vars result))))
                          (str "No unused vars found (analyzed " (:total-definitions result) " definitions)"))
                        (str "Error: " (:error result)))}]}))

(defn find-var-definitions-tool [mcp-context arguments]
  (let [{:keys [paths config namespace-filter name-filter tag-filter]} arguments
        result (find-var-definitions paths 
                                    :config (or config {})
                                    :namespace-filter namespace-filter
                                    :name-filter name-filter
                                    :tag-filter (when tag-filter (keyword tag-filter)))]
    {:content [{:type "text" 
                :text (if (= (:status result) :success)
                        (if (seq (:var-definitions result))
                          (str "Found " (:total-found result) " var definitions:\n"
                               (str/join "\n" 
                                 (map (fn [var]
                                        (str (:ns var) "/" (:name var) 
                                             " (" (:filename var) ":" (:row var) ":" (:col var) ")"
                                             (when (:tag var) (str " [" (:tag var) "]"))
                                             (when (:private var) " [private]")
                                             (when (:arity var) (str " arity:" (:arity var)))))
                                      (:var-definitions result))))
                          "No var definitions found matching the criteria")
                        (str "Error: " (:error result)))}]}))

(defn find-var-usages-tool [mcp-context arguments]
  (let [{:keys [paths config namespace-filter name-filter]} arguments
        result (find-var-usages paths 
                               :config (or config {})
                               :namespace-filter namespace-filter
                               :name-filter name-filter)]
    {:content [{:type "text" 
                :text (if (= (:status result) :success)
                        (if (seq (:var-usages result))
                          (str "Found " (:total-found result) " var usages:\n"
                               (str/join "\n" 
                                 (map (fn [usage]
                                        (str (:to usage) "/" (:name usage) 
                                             " used in " (:from usage)
                                             " (" (:filename usage) ":" (:row usage) ":" (:col usage) ")"))
                                      (:var-usages result))))
                          "No var usages found matching the criteria")
                        (str "Error: " (:error result)))}]}))

;; ===============================================
;; Tool Definitions
;; ===============================================

(def tools
  "Clj-kondo tool definitions for mcp-toolkit"
  [{:name "lint-code"
    :description "Lint Clojure code string for errors and style issues"
    :inputSchema {:type "object"
                  :properties {:code {:type "string" :description "Clojure code to lint"}
                              :filename {:type "string" :description "Filename for context (default: stdin.clj)"}
                              :lang {:type "string" :description "Language: clj, cljs, or cljc (default: clj)"}
                              :config {:type "object" :description "Custom clj-kondo configuration as EDN map"}}
                  :required ["code"]}
    :tool-fn lint-code-tool}
   
   {:name "lint-project"
    :description "Lint entire project or specific paths for errors and style issues"
    :inputSchema {:type "object"
                  :properties {:paths {:type "array" :description "Array of file paths or directories to lint"}
                              :config {:type "object" :description "Custom clj-kondo configuration as EDN map"}
                              :cache {:type "boolean" :description "Enable caching (default: true)"}
                              :parallel {:type "boolean" :description "Enable parallel processing (default: true)"}}
                  :required ["paths"]}
    :tool-fn lint-project-tool}
   
   {:name "setup-clj-kondo"
    :description "Initialize or update clj-kondo configuration for the project"
    :inputSchema {:type "object"
                  :properties {:project-root {:type "string" :description "Root directory of the project"}
                              :copy-configs {:type "boolean" :description "Copy configurations from dependencies (default: true)"}
                              :dependencies {:type "boolean" :description "Analyze dependencies for config (default: false)"}}
                  :required ["project-root"]}
    :tool-fn setup-clj-kondo-tool}
   
   {:name "analyze-project"
    :description "Get full clj-kondo analysis data for a project"
    :inputSchema {:type "object"
                  :properties {:paths {:type "array" :description "Array of file paths or directories to analyze"}
                              :config {:type "object" :description "Custom clj-kondo configuration as EDN map"}}
                  :required ["paths"]}
    :tool-fn analyze-project-tool}
   
   {:name "find-unused-vars"
    :description "Find all unused vars in a project using clj-kondo analysis"
    :inputSchema {:type "object"
                  :properties {:paths {:type "array" :description "Array of file paths or directories to analyze"}
                              :config {:type "object" :description "Custom clj-kondo configuration as EDN map"}
                              :include-private {:type "boolean" :description "Include private vars in analysis (default: true)"}}
                  :required ["paths"]}
    :tool-fn find-unused-vars-tool}
   
   {:name "find-var-definitions"
    :description "Find var definitions in a project with optional filtering"
    :inputSchema {:type "object"
                  :properties {:paths {:type "array" :description "Array of file paths or directories to analyze"}
                              :config {:type "object" :description "Custom clj-kondo configuration as EDN map"}
                              :namespace-filter {:type "string" :description "Filter by namespace name"}
                              :name-filter {:type "string" :description "Filter by var name"}
                              :tag-filter {:type "string" :description "Filter by tag (e.g., 'function', 'macro')"}}
                  :required ["paths"]}
    :tool-fn find-var-definitions-tool}
   
   {:name "find-var-usages"
    :description "Find var usages in a project with optional filtering"
    :inputSchema {:type "object"
                  :properties {:paths {:type "array" :description "Array of file paths or directories to analyze"}
                              :config {:type "object" :description "Custom clj-kondo configuration as EDN map"}
                              :namespace-filter {:type "string" :description "Filter by target namespace"}
                              :name-filter {:type "string" :description "Filter by var name"}}
                  :required ["paths"]}
    :tool-fn find-var-usages-tool}])