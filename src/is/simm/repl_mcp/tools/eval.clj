(ns is.simm.repl-mcp.tools.eval
  "Code evaluation tools for nREPL integration with safe nREPL interactions"
  (:require
   [taoensso.telemere :as log]
   [clojure.string :as str]
   [is.simm.repl-mcp.tools.nrepl-utils :as nrepl-utils]))

;; ===============================================
;; Utility Functions
;; ===============================================

(defn normalize-param-key
  "Convert parameter key from underscore (JSON) to hyphen (Clojure keyword).
   Accepts both string and keyword inputs."
  [k]
  (keyword (str/replace (name k) "_" "-")))

(defn normalize-params
  "Normalize all parameter keys in a map by converting underscores to hyphens.
   This handles the mismatch between JSON (underscores) and Clojure (hyphens)."
  [params]
  (reduce-kv (fn [m k v]
               (assoc m (normalize-param-key k) v))
             {}
             params))

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
  (try
    ;; Normalize parameter keys (though eval usually doesn't have underscores)
    (let [normalized-args (normalize-params arguments)
          {:keys [code namespace timeout]} normalized-args]
      (log/log! {:level :debug :msg "Eval tool called" :data {:code code :namespace namespace :timeout timeout}})

      ;; Validate that code was provided
      (if (nil? code)
        {:status :error
         :error "Missing required parameter: code"
         :details "The code parameter is required but was not provided"}
        (nrepl-utils/with-safe-nrepl mcp-context "Code evaluation"
          (fn [nrepl-client timeout]
            (evaluate-code nrepl-client code
                           :namespace namespace
                           :timeout timeout))
          :timeout (or timeout 120000))))
    (catch Exception e
      (log/log! {:level :error :msg "Error in eval-tool"
                 :data {:error (.getMessage e) :arguments arguments}})
      {:status :error
       :error (.getMessage e)
       :details (str "Exception type: " (.getName (class e)))})))

(defn load-file-tool [mcp-context arguments]
  (try
    ;; Normalize parameter keys to handle both file_path and file-path
    (let [normalized-args (normalize-params arguments)
          {:keys [file-path]} normalized-args]
      ;; Validate that file-path was provided
      (if (nil? file-path)
        {:status :error
         :error "Missing required parameter: file-path"
         :details "The file-path parameter is required but was not provided"}
        (nrepl-utils/with-safe-nrepl mcp-context "File loading"
          (fn [nrepl-client timeout]
            (load-clojure-file nrepl-client file-path :timeout timeout)))))
    (catch Exception e
      (log/log! {:level :error :msg "Error in load-file-tool"
                 :data {:error (.getMessage e) :arguments arguments}})
      {:status :error
       :error (.getMessage e)
       :details (str "Exception type: " (.getName (class e)))})))

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