(ns is.simm.repl-mcp.tools.refactor
  "Refactoring tools using refactor-nrepl and structural editing"
  (:require 
   [nrepl.core :as nrepl]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [taoensso.telemere :as log]))

;; ===============================================
;; Core Refactoring Functions
;; ===============================================

(defn clean-namespace
  "Clean and organize namespace declarations using refactor-nrepl"
  [nrepl-client file-path & {:keys [prune-unused prefer-prefix] 
                             :or {prune-unused true prefer-prefix true}}]
  (try
    (log/log! {:level :info :msg "Cleaning namespace in file" :data {:file-path file-path}})
    
    (when (nil? nrepl-client)
      (throw (Exception. "nREPL client is nil")))
    
    (let [responses (nrepl/message nrepl-client 
                                  {:op "clean-ns" 
                                   :path file-path
                                   :prune-ns-form (boolean prune-unused)
                                   :prefix-rewriting (boolean prefer-prefix)})
          result (reduce (fn [acc response]
                          (cond
                            (:ns response) (assoc acc :cleaned-ns (str (:ns response)))
                            (:err response) (assoc acc :error (:err response))
                            (:out response) (update acc :output (fnil str "") (:out response))
                            :else acc))
                        {} responses)]
      (if (:error result)
        (let [error-msg (:error result)
              output (:output result)
              combined-error (if (seq output)
                              (str error-msg "\nOutput: " output)
                              error-msg)]
          {:error combined-error
           :status :error})
        (let [cleaned-ns (str (:cleaned-ns result))
              output (str (:output result))
              combined-value (if (and output (seq output))
                              (str "Cleaned namespace: " file-path "\nResult: " cleaned-ns "\nOutput: " output)
                              (str "Cleaned namespace: " file-path "\nResult: " cleaned-ns))]
          {:value combined-value
           :status :success})))
    (catch Exception e
      (log/log! {:level :error :msg "Error cleaning namespace" :data {:error (.getMessage e)}})
      {:error (.getMessage e)
       :status :error})))

(defn find-symbol-occurrences
  "Find all occurrences of a symbol in the codebase using refactor-nrepl"
  [nrepl-client file-path line column]
  (try
    (log/log! {:level :info :msg "Finding symbol" :data {:file-path file-path :line line :column column}})
    
    (when (nil? nrepl-client)
      (throw (Exception. "nREPL client is nil")))
    
    (let [responses (nrepl/message nrepl-client 
                                  {:op "find-symbol" 
                                   :file file-path
                                   :line line
                                   :column column})
          occurrences (atom [])]
      (doseq [response responses]
        (when (:occurrence response)
          (swap! occurrences conj (:occurrence response))))
      
      {:occurrences @occurrences
       :file-path file-path
       :line line
       :column column
       :status :success})
    (catch Exception e
      (log/log! {:level :error :msg "Error finding symbol" :data {:error (.getMessage e)}})
      {:error (.getMessage e)
       :status :error})))

(defn rename-file-or-directory
  "Rename a file or directory and update all references using refactor-nrepl"
  [nrepl-client old-path new-path]
  (try
    (log/log! {:level :info :msg "Renaming file/directory" :data {:old-path old-path :new-path new-path}})
    
    (when (nil? nrepl-client)
      (throw (Exception. "nREPL client is nil")))
    
    ;; Validate that the source file exists
    (when-not (.exists (java.io.File. old-path))
      (throw (Exception. (str "Source file does not exist: " old-path))))
    
    (let [responses (nrepl/message nrepl-client 
                                  {:op "rename-file-or-dir" 
                                   :old-path old-path
                                   :new-path new-path})
          result (reduce (fn [acc response]
                          (cond
                            (:touched response) (assoc acc :touched (:touched response))
                            (:err response) (assoc acc :error (:err response))
                            (:out response) (update acc :output (fnil str "") (:out response))
                            :else acc))
                        {} responses)]
      (if (:error result)
        (let [error-msg (:error result)
              output (:output result)
              combined-error (if (seq output)
                              (str error-msg "\nOutput: " output)
                              error-msg)]
          {:error combined-error
           :status :error})
        (let [touched (:touched result)
              output (:output result)
              combined-value (if (seq output)
                              (str "Renamed " old-path " to " new-path "\nTouched files: " touched "\nOutput: " output)
                              (str "Renamed " old-path " to " new-path "\nTouched files: " touched))]
          {:value combined-value
           :status :success})))
    (catch Exception e
      (log/log! {:level :error :msg "Error renaming file or directory" :data {:error (.getMessage e)}})
      {:error (.getMessage e)
       :status :error})))

(defn resolve-missing-symbol
  "Resolve missing or unresolved symbols using refactor-nrepl"
  [nrepl-client symbol namespace]
  (try
    (log/log! {:level :info :msg "Resolving missing symbol" :data {:symbol symbol :namespace namespace}})
    
    (when (nil? nrepl-client)
      (throw (Exception. "nREPL client is nil")))
    
    (let [responses (nrepl/message nrepl-client 
                                  {:op "resolve-missing" 
                                   :symbol symbol
                                   :ns namespace})
          result (reduce (fn [acc response]
                          (cond
                            (:candidates response) (assoc acc :candidates (:candidates response))
                            (:err response) (assoc acc :error (:err response))
                            (:out response) (update acc :output (fnil str "") (:out response))
                            :else acc))
                        {} responses)]
      (if (:error result)
        (let [error-msg (:error result)
              output (:output result)
              combined-error (if (seq output)
                              (str error-msg "\nOutput: " output)
                              error-msg)]
          {:error combined-error
           :status :error})
        (let [candidates (:candidates result)
              output (:output result)
              combined-value (if (seq output)
                              (str "Resolved " namespace "/" symbol "\nCandidates: " candidates "\nOutput: " output)
                              (str "Resolved " namespace "/" symbol "\nCandidates: " candidates))]
          {:value combined-value
           :status :success})))
    (catch Exception e
      (log/log! {:level :error :msg "Error resolving missing symbol" :data {:error (.getMessage e)}})
      {:error (.getMessage e)
       :status :error})))

(defn find-used-locals
  "Find locally used variables at a specific location using refactor-nrepl"
  [nrepl-client file-path line column]
  (try
    (log/log! {:level :info :msg "Finding used locals" :data {:file-path file-path :line line :column column}})
    
    (when (nil? nrepl-client)
      (throw (Exception. "nREPL client is nil")))
    
    (let [responses (nrepl/message nrepl-client 
                                  {:op "find-used-locals" 
                                   :file file-path
                                   :line line
                                   :column column})
          result (reduce (fn [acc response]
                          (cond
                            (:used-locals response) (assoc acc :used-locals (:used-locals response))
                            (:err response) (assoc acc :error (:err response))
                            (:out response) (update acc :output (fnil str "") (:out response))
                            :else acc))
                        {} responses)]
      (if (:error result)
        (let [error-msg (:error result)
              output (:output result)
              combined-error (if (seq output)
                              (str error-msg "\nOutput: " output)
                              error-msg)]
          {:error combined-error
           :status :error})
        (let [used-locals (:used-locals result)
              output (:output result)
              combined-value (if (seq output)
                              (str "Used locals at " file-path ":" line ":" column "\nLocals: " used-locals "\nOutput: " output)
                              (str "Used locals at " file-path ":" line ":" column "\nLocals: " used-locals))]
          {:value combined-value
           :status :success})))
    (catch Exception e
      (log/log! {:level :error :msg "Error finding used locals" :data {:error (.getMessage e)}})
      {:error (.getMessage e)
       :status :error})))

;; ===============================================
;; Tool Implementations
;; ===============================================

(defn clean-ns-tool [mcp-context arguments]
  (let [{:strs [file-path prune-unused prefer-prefix]} arguments
        nrepl-client (:nrepl-client mcp-context)]
    (if (nil? nrepl-client)
      {:content [{:type "text" 
                  :text "Error: nREPL client not available. Namespace cleaning requires an active nREPL connection."}]}
      (try
        (let [result (clean-namespace nrepl-client file-path 
                                    :prune-unused prune-unused 
                                    :prefer-prefix prefer-prefix)]
          {:content [{:type "text" 
                      :text (if (= (:status result) :success)
                              (str (:value result))
                              (str "Error: " (:error result)))}]})
        (catch Exception e
          {:content [{:type "text" 
                      :text (str "Error: " (.getMessage e))}]})))))

(defn find-symbol-tool [mcp-context arguments]
  (let [{:strs [file-path line column]} arguments
        nrepl-client (:nrepl-client mcp-context)]
    (if (nil? nrepl-client)
      {:content [{:type "text" 
                  :text "Error: nREPL client not available. Symbol finding requires an active nREPL connection."}]}
      (let [result (find-symbol-occurrences nrepl-client file-path line column)]
        {:content [{:type "text" 
                    :text (if (= (:status result) :success)
                            (str "Found " (count (:occurrences result)) " occurrences of symbol at " 
                                 file-path ":" line ":" column "\n"
                                 (str/join "\n" (map str (:occurrences result))))
                            (str "Error: " (:error result)))}]}))))

(defn rename-file-or-dir-tool [mcp-context arguments]
  (let [{:strs [old-path new-path]} arguments
        nrepl-client (:nrepl-client mcp-context)]
    (if (nil? nrepl-client)
      {:content [{:type "text" 
                  :text "Error: nREPL client not available. File renaming requires an active nREPL connection."}]}
      (let [result (rename-file-or-directory nrepl-client old-path new-path)]
        {:content [{:type "text" 
                    :text (if (= (:status result) :success)
                            (:value result)
                            (str "Error: " (:error result)))}]}))))

(defn resolve-missing-tool [mcp-context arguments]
  (let [{:strs [symbol namespace]} arguments
        nrepl-client (:nrepl-client mcp-context)]
    (if (nil? nrepl-client)
      {:content [{:type "text" 
                  :text "Error: nREPL client not available. Symbol resolution requires an active nREPL connection."}]}
      (let [result (resolve-missing-symbol nrepl-client symbol namespace)]
        {:content [{:type "text" 
                    :text (if (= (:status result) :success)
                            (:value result)
                            (str "Error: " (:error result)))}]}))))

(defn find-used-locals-tool [mcp-context arguments]
  (let [{:strs [file-path line column]} arguments
        nrepl-client (:nrepl-client mcp-context)]
    (if (nil? nrepl-client)
      {:content [{:type "text" 
                  :text "Error: nREPL client not available. Local variable analysis requires an active nREPL connection."}]}
      (let [result (find-used-locals nrepl-client file-path line column)]
        {:content [{:type "text" 
                    :text (if (= (:status result) :success)
                            (:value result)
                            (str "Error: " (:error result)))}]}))))

;; Note: Advanced refactoring tools require structural editing dependency
;; These are simplified versions without the structural editing dependency

(defn extract-function-tool [mcp-context arguments]
  {:content [{:type "text" 
              :text "Extract function requires structural editing session. Use structural-edit tools first."}]})

(defn extract-variable-tool [mcp-context arguments]  
  {:content [{:type "text" 
              :text "Extract variable requires structural editing session. Use structural-edit tools first."}]})

(defn add-function-parameter-tool [mcp-context arguments]
  {:content [{:type "text" 
              :text "Add function parameter requires structural editing session. Use structural-edit tools first."}]})

(defn organize-imports-tool [mcp-context arguments]
  {:content [{:type "text" 
              :text "Organize imports requires structural editing session. Use structural-edit tools first."}]})

(defn inline-function-tool [mcp-context arguments]
  {:content [{:type "text" 
              :text "Inline function requires structural editing session. Use structural-edit tools first."}]})

(defn rename-local-variable-tool [mcp-context arguments]
  {:content [{:type "text" 
              :text "Rename local variable requires structural editing session. Use structural-edit tools first."}]})

;; ===============================================
;; Tool Definitions
;; ===============================================

(def tools
  "Refactoring tool definitions for mcp-toolkit"
  [{:name "clean-ns"
    :description "Clean and organize namespace declarations"
    :inputSchema {:type "object"
                  :properties {:file-path {:type "string" :description "Path to the Clojure file"}
                              :prune-unused {:type "boolean" :description "Remove unused requires (default: true)"}
                              :prefer-prefix {:type "boolean" :description "Prefer prefix form for requires (default: true)"}}
                  :required ["file-path"]}
    :tool-fn clean-ns-tool}
   
   {:name "find-symbol"
    :description "Find all occurrences of a symbol in the codebase"
    :inputSchema {:type "object"
                  :properties {:file-path {:type "string" :description "Path to the Clojure file"}
                              :line {:type "number" :description "Line number of the symbol"}
                              :column {:type "number" :description "Column number of the symbol"}}
                  :required ["file-path" "line" "column"]}
    :tool-fn find-symbol-tool}
   
   {:name "rename-file-or-dir"
    :description "Rename a file or directory and update all references"
    :inputSchema {:type "object"
                  :properties {:old-path {:type "string" :description "Current path of the file or directory"}
                              :new-path {:type "string" :description "New path for the file or directory"}}
                  :required ["old-path" "new-path"]}
    :tool-fn rename-file-or-dir-tool}
   
   {:name "resolve-missing"
    :description "Resolve missing or unresolved symbols"
    :inputSchema {:type "object"
                  :properties {:symbol {:type "string" :description "The unresolved symbol"}
                              :namespace {:type "string" :description "The namespace containing the symbol"}}
                  :required ["symbol" "namespace"]}
    :tool-fn resolve-missing-tool}
   
   {:name "find-used-locals"
    :description "Find locally used variables at a specific location"
    :inputSchema {:type "object"
                  :properties {:file-path {:type "string" :description "Path to the Clojure file"}
                              :line {:type "number" :description "Line number"}
                              :column {:type "number" :description "Column number"}}
                  :required ["file-path" "line" "column"]}
    :tool-fn find-used-locals-tool}
   
   {:name "extract-function"
    :description "Extract selected code into a new function"
    :inputSchema {:type "object"
                  :properties {:session-id {:type "string" :description "Session identifier"}
                              :function-name {:type "string" :description "Name for the new function"}
                              :parameters {:type "string" :description "Function parameters as EDN vector"}}
                  :required ["session-id" "function-name" "parameters"]}
    :tool-fn extract-function-tool}
   
   {:name "extract-variable"
    :description "Extract current expression into a let binding"
    :inputSchema {:type "object"
                  :properties {:session-id {:type "string" :description "Session identifier"}
                              :variable-name {:type "string" :description "Name for the new variable"}}
                  :required ["session-id" "variable-name"]}
    :tool-fn extract-variable-tool}
   
   {:name "add-function-parameter"
    :description "Add a parameter to a function definition"
    :inputSchema {:type "object"
                  :properties {:session-id {:type "string" :description "Session identifier"}
                              :parameter-name {:type "string" :description "Name of the parameter to add"}
                              :default-value {:type "string" :description "Default value for the parameter"}}
                  :required ["session-id" "parameter-name"]}
    :tool-fn add-function-parameter-tool}
   
   {:name "organize-imports"
    :description "Organize and clean up namespace imports"
    :inputSchema {:type "object"
                  :properties {:session-id {:type "string" :description "Session identifier"}}
                  :required ["session-id"]}
    :tool-fn organize-imports-tool}
   
   {:name "inline-function"
    :description "Inline a function call by replacing it with the function body"
    :inputSchema {:type "object"
                  :properties {:session-id {:type "string" :description "Session identifier"}
                              :function-name {:type "string" :description "Name of the function to inline"}}
                  :required ["session-id" "function-name"]}
    :tool-fn inline-function-tool}
   
   {:name "rename-local-variable"
    :description "Rename a local variable within its scope"
    :inputSchema {:type "object"
                  :properties {:session-id {:type "string" :description "Session identifier"}
                              :old-name {:type "string" :description "Current variable name"}
                              :new-name {:type "string" :description "New variable name"}}
                  :required ["session-id" "old-name" "new-name"]}
    :tool-fn rename-local-variable-tool}])