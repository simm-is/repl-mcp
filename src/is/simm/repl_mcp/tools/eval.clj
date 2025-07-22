(ns is.simm.repl-mcp.tools.eval
  "Code evaluation tools for nREPL integration with safe nREPL interactions"
  (:require 
   [taoensso.telemere :as log]
   [is.simm.repl-mcp.tools.nrepl-utils :as nrepl-utils]))

;; ===============================================
;; Core nREPL Helper Functions
;; ===============================================

(defn evaluate-code 
  "Evaluate Clojure code in the nREPL session with proper timeout and interrupt handling"
  [nrepl-client code & {:keys [namespace timeout] :or {timeout 120000}}]
  (log/log! {:level :info :msg "Evaluating code" :data {:code-length (count code)}})
  
  (let [eval-msg (cond-> {:op "eval" :code code}
                   namespace (assoc :ns namespace))
        result (nrepl-utils/safe-nrepl-message nrepl-client eval-msg
                                               :timeout timeout
                                               :operation-name "Code evaluation")]
    (if (= (:status result) :success)
      (nrepl-utils/process-eval-response (:responses result))
      result)))

(defn load-clojure-file
  "Load a Clojure file into the nREPL session"
  [nrepl-client file-path & {:keys [timeout] :or {timeout 120000}}]
  (log/log! {:level :info :msg "Loading file" :data {:file-path file-path}})
  
  (if-let [validation-error (nrepl-utils/validate-file-exists file-path)]
    validation-error
    
    (let [result (nrepl-utils/safe-nrepl-message nrepl-client
                   {:op "load-file" :file (slurp file-path)}
                   :timeout timeout
                   :operation-name "File loading")]
      (if (= (:status result) :success)
        {:status :success
         :value (str "File loaded successfully: " file-path)}
        result))))

;; ===============================================
;; Tool Implementations
;; ===============================================

(defn eval-tool [mcp-context arguments]
  (let [{:keys [code namespace timeout]} arguments]
    (log/log! {:level :debug :msg "Eval tool called" :data {:code code :namespace namespace :timeout timeout}})
    
    (nrepl-utils/with-safe-nrepl mcp-context "Code evaluation"
      (fn [nrepl-client timeout]
        (evaluate-code nrepl-client code 
                       :namespace namespace 
                       :timeout timeout))
      :timeout (or timeout 120000))))

(defn load-file-tool [mcp-context arguments]
  (let [{:keys [file-path]} arguments]
    (nrepl-utils/with-safe-nrepl mcp-context "File loading"
      (fn [nrepl-client timeout]
        (load-clojure-file nrepl-client file-path :timeout timeout)))))

;; ===============================================
;; Tool Definitions
;; ===============================================

(def tools
  "Evaluation tool definitions for mcp-toolkit"
  [{:name "eval"
    :description "Evaluate Clojure code in the connected nREPL session"
    :inputSchema {:type "object"
                  :properties {:code {:type "string" :description "Clojure code to evaluate"}
                              :namespace {:type "string" :description "Namespace to evaluate in"}
                              :timeout {:type "number" :description "Timeout in milliseconds"}}
                  :required ["code"]}
    :tool-fn eval-tool}
   
   {:name "load-file"
    :description "Load a Clojure file into the nREPL session"
    :inputSchema {:type "object"
                  :properties {:file-path {:type "string" :description "Path to the Clojure file to load"}}
                  :required ["file-path"]}
    :tool-fn load-file-tool}])