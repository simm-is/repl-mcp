(ns is.simm.repl-mcp.tools.eval
  "Code evaluation tools for nREPL integration"
  (:require 
   [nrepl.core :as nrepl]
   [taoensso.telemere :as log]
   [clojure.java.io :as io]))

;; ===============================================
;; Core nREPL Helper Functions
;; ===============================================

(defn evaluate-code 
  "Evaluate Clojure code in the nREPL session"
  [nrepl-client code & {:keys [namespace timeout] :or {timeout 120000}}]
  (try
    (log/log! {:level :info :msg "Evaluating code" :data {:code-length (count code)}})
    
    (when (nil? nrepl-client)
      (throw (Exception. "nREPL client is nil")))
    
    (when (nil? code)
      (throw (Exception. "Code is nil")))
    
    ;; Create dedicated session for isolation
    (let [session (nrepl/new-session nrepl-client)
          responses (nrepl/message nrepl-client 
                                  (cond-> {:op "eval" :code code :session session}
                                    namespace (assoc :ns namespace)))]
      
      ;; Close session after evaluation
      (try 
        (nrepl/message nrepl-client {:op "close" :session session})
        (catch Exception e
          (log/log! {:level :warn :msg "Failed to close session" :data {:error (.getMessage e)}})))
      
      ;; Process responses
      (let [result-msgs (filter #(contains? % :value) responses)
            error-msgs (filter #(contains? % :err) responses)
            output-msgs (filter #(contains? % :out) responses)
            
            values (mapv :value result-msgs)
            errors (apply str (map :err error-msgs))
            output (apply str (map :out output-msgs))]
        
        (if (seq error-msgs)
          {:error errors :output output :status :error}
          {:value (last values) :output output :status :success})))
    
    (catch Exception e
      (log/log! {:level :error :msg "Evaluation failed" 
                 :data {:error (.getMessage e) :code-length (count code)}})
      {:error (.getMessage e) :status :error})))

(defn load-clojure-file
  "Load a Clojure file into the nREPL session"
  [nrepl-client file-path]
  (try
    (log/log! {:level :info :msg "Loading file" :data {:file-path file-path}})
    
    (when (nil? nrepl-client)
      (throw (Exception. "nREPL client is nil")))
    
    (when-not (.exists (io/file file-path))
      (throw (Exception. (str "File does not exist: " file-path))))
    
    (let [responses (nrepl/message nrepl-client {:op "load-file" :file (slurp file-path)})
          error-msgs (filter #(contains? % :err) responses)]
      
      (if (seq error-msgs)
        {:error (apply str (map :err error-msgs)) :status :error}
        {:value (str "File loaded successfully: " file-path) :status :success}))
    
    (catch Exception e
      (log/log! {:level :error :msg "File loading failed" 
                 :data {:error (.getMessage e) :file-path file-path}})
      {:error (.getMessage e) :status :error})))

;; ===============================================
;; Tool Implementations
;; ===============================================

(defn eval-tool [mcp-context arguments]
  (log/log! {:level :debug :msg "Eval tool called" :data {:arguments arguments :context-keys (keys mcp-context)}})
  (let [{:keys [code namespace timeout]} arguments
        nrepl-client (:nrepl-client mcp-context)]
    (log/log! {:level :debug :msg "Eval tool extracted params" :data {:code code :namespace namespace :timeout timeout :nrepl-client-available? (some? nrepl-client)}})
    (if (nil? nrepl-client)
      {:content [{:type "text" 
                  :text "Error: nREPL client not available. Code evaluation requires an active nREPL connection."}]}
      (let [result (evaluate-code nrepl-client code 
                             :namespace namespace 
                             :timeout (or timeout 120000))]
        (log/log! {:level :debug :msg "Eval tool internal result" :data {:result result}})
        {:content [{:type "text" 
                    :text (if (= (:status result) :success)
                            (str (:value result) 
                                 (when (seq (:output result)) 
                                   (str "\n" (:output result))))
                            (str "Error: " (:error result)))}]}))))

(defn load-file-tool [mcp-context arguments]
  (let [{:keys [file-path]} arguments
        nrepl-client (:nrepl-client mcp-context)]
    (if (nil? nrepl-client)
      {:content [{:type "text" 
                  :text "Error: nREPL client not available. File loading requires an active nREPL connection."}]}
      (let [result (load-clojure-file nrepl-client file-path)]
        {:content [{:type "text" 
                    :text (if (= (:status result) :success)
                            (:value result)
                            (str "Error: " (:error result)))}]}))))

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