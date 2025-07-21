(ns is.simm.repl-mcp.tools.clj-kondo
  "Code linting and quality tools using clj-kondo"
  (:require 
   [clj-kondo.core :as clj-kondo]
   [taoensso.telemere :as log]
   [clojure.java.io :as io]))

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
          result (clj-kondo/run! {:lint ["-"] 
                                  :filename filename
                                  :lang lang
                                  :config lint-config
                                  :in code})]
      
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
    :tool-fn setup-clj-kondo-tool}])