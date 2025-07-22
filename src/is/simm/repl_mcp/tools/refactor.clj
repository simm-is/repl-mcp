(ns is.simm.repl-mcp.tools.refactor
  "Refactoring tools using refactor-nrepl with safe nREPL interactions
  
  Available refactor-nrepl operations (as of refactor-nrepl middleware):
  - artifact-list, artifact-versions, clean-ns, cljr-suggest-libspecs
  - extract-definition, find-symbol, find-used-locals, hotload-dependency  
  - namespace-aliases, rename-file-or-dir, resolve-missing, stubs-for-interface
  - find-used-publics, version, warm-ast-cache, warm-macro-occurrences-cache
  
  NOTE: Symbol renaming operations are NOT provided by refactor-nrepl.
  For symbol renaming, use the custom implementations in function_refactor.clj"
  (:require 
   [clojure.string :as str]
   [is.simm.repl-mcp.tools.nrepl-utils :as nrepl-utils]))

;; ===============================================
;; Response Processing Utilities
;; ===============================================

(defn process-refactor-response
  "Process refactor-nrepl responses that may have :err, :out, and other keys"
  [responses result-key default-msg]
  (let [result (reduce (fn [acc response]
                         (cond
                           (contains? response result-key) (assoc acc :result (get response result-key))
                           (:err response) (assoc acc :error (:err response))
                           (:out response) (update acc :output (fnil str "") (:out response))
                           :else acc))
                       {} responses)]
    (if (:error result)
      (str "Error: " (:error result)
           (when-let [output (:output result)]
             (str "\nOutput: " output)))
      (str default-msg
           (when-let [result-val (:result result)]
             (str "\nResult: " result-val))
           (when-let [output (:output result)]
             (str "\nOutput: " output))))))

;; REMOVED: process-find-symbol-response - was for find-symbol tool that caused hanging

;; ===============================================
;; Core Refactoring Functions
;; ===============================================

(defn clean-namespace
  "Clean and organize namespace declarations using refactor-nrepl"
  [nrepl-client file-path & {:keys [prune-unused prefer-prefix timeout] 
                             :or {prune-unused true prefer-prefix true timeout 120000}}]
  (if-let [validation-error (nrepl-utils/validate-file-exists file-path)]
    validation-error
    
    (let [result (nrepl-utils/safe-nrepl-message nrepl-client
                   {:op "clean-ns" 
                    :path file-path
                    :prune-ns-form (str (boolean prune-unused))
                    :prefix-rewriting (str (boolean prefer-prefix))}
                   :timeout timeout
                   :operation-name "Namespace cleaning")]
      (if (= (:status result) :success)
        {:status :success
         :value (process-refactor-response (:responses result) :ns (str "Cleaned namespace: " file-path))}
        result))))

;; REMOVED: extract-symbol-at-position - was for find-symbol tool that caused hanging

;; REMOVED: extract-namespace-from-file - was for find-symbol tool that caused hanging

;; REMOVED: find-symbol-occurrences - refactor-nrepl operation causes hanging

(defn rename-file-or-directory
  "Rename a file or directory and update all references using refactor-nrepl"
  [nrepl-client old-path new-path & {:keys [timeout] :or {timeout 120000}}]
  (if-let [validation-error (nrepl-utils/validate-file-exists old-path)]
    validation-error
    
    (let [result (nrepl-utils/safe-nrepl-message nrepl-client
                   {:op "rename-file-or-dir" 
                    :old-path old-path
                    :new-path new-path}
                   :timeout timeout
                   :operation-name "File/directory rename")]
      (if (= (:status result) :success)
        {:status :success
         :value (process-refactor-response (:responses result) :touched 
                  (str "Renamed " old-path " to " new-path))}
        result))))


;; ===============================================
;; Tool Implementations
;; ===============================================

(defn clean-ns-tool [mcp-context arguments]
  (let [{:keys [file-path prune-unused prefer-prefix]} arguments]
    (nrepl-utils/with-safe-nrepl mcp-context "Namespace cleaning"
      (fn [nrepl-client timeout]
        (clean-namespace nrepl-client file-path 
                        :prune-unused (boolean prune-unused)
                        :prefer-prefix (boolean prefer-prefix)
                        :timeout timeout)))))

;; REMOVED: find-symbol-tool - refactor-nrepl operation causes hanging

(defn rename-file-or-dir-tool [mcp-context arguments]
  (let [{:keys [old-path new-path]} arguments]
    (nrepl-utils/with-safe-nrepl mcp-context "File/directory rename"
      (fn [nrepl-client timeout]
        (rename-file-or-directory nrepl-client old-path new-path
                                 :timeout timeout)))))


;; ===============================================
;; Tool Definitions
;; ===============================================

(def tools
  "Refactoring tool definitions for mcp-toolkit with safe nREPL interactions"
  [{:name "clean-ns"
    :description "Clean and organize namespace declarations"
    :inputSchema {:type "object"
                  :properties {:file-path {:type "string" :description "Path to the Clojure file"}
                              :prune-unused {:type "boolean" :description "Remove unused requires (default: true)"}
                              :prefer-prefix {:type "boolean" :description "Prefer prefix form for requires (default: true)"}}
                  :required ["file-path"]}
    :tool-fn clean-ns-tool}
   
   ;; REMOVED: find-symbol tool - refactor-nrepl operation causes hanging
   
   {:name "rename-file-or-dir"
    :description "Rename a file or directory and update all references"
    :inputSchema {:type "object"
                  :properties {:old-path {:type "string" :description "Current path of the file or directory"}
                              :new-path {:type "string" :description "New path for the file or directory"}}
                  :required ["old-path" "new-path"]}
    :tool-fn rename-file-or-dir-tool}
   
])