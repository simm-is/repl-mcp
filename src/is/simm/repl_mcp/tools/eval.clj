(ns is.simm.repl-mcp.tools.eval
  (:require [is.simm.repl-mcp.interactive :refer [register-tool!]]
            [nrepl.core :as nrepl]
            [taoensso.telemere :as log]))

;; Pure implementation functions for testing

(defn format-result-for-mcp
  "Format evaluation result with context."
  [value namespace]
  (let [result-str (if (nil? value)
                     "nil"
                     (str value))]
    (if namespace
      (str "[" namespace "] " result-str)
      result-str)))

(defn eval-code
  "Evaluate Clojure code using nREPL client with proper timeout and interrupt handling. 
   Returns result map with :value, :output, :error, :status."
  [nrepl-client code & {:keys [namespace timeout]
                                      :or {timeout 120000}}]
  (try
    (when (nil? nrepl-client)
      (throw (Exception. "nREPL client is nil")))
    
    (let [;; Prepare eval message - use existing session if available, otherwise create one
          eval-msg (cond-> {:op "eval" :code code}
                     namespace (assoc :ns namespace))
          
          ;; Set up timeout handling with interrupt
          response-future (future 
                            (try
                              (doall (nrepl/message nrepl-client eval-msg))
                              (catch Exception _
                                ::eval-error)))
          
          ;; Wait for response with timeout
          responses (deref response-future timeout ::timeout)]
      
      (cond
        ;; Handle timeout - send interrupt if we have a session
        (= responses ::timeout)
        (do
          ;; Try to send interrupt - we'll need to get the session from the client
          (try
            (let [clone-result (first (nrepl/message nrepl-client {:op "clone"}))
                  session-id (:new-session clone-result)]
              (when session-id
                (future (nrepl/message nrepl-client {:op "interrupt" :session session-id}))))
            (catch Exception _)) ; Ignore interrupt failures
          
          ;; Cancel the future
          (future-cancel response-future)
          
          {:error (str "Operation timed out after " timeout "ms")
           :status :timeout})
        
        ;; Handle eval error
        (= responses ::eval-error)
        {:error "Error occurred during nREPL evaluation"
         :status :error}
        
        ;; Handle success - process responses normally
        :else
        (if (or (nil? responses) (empty? responses))
          {:error "No response received from nREPL"
           :status :error}
          
          ;; Process successful responses
          (let [values (nrepl/response-values responses)
                combined (nrepl/combine-responses responses)]
            
            (cond
              (:err combined) 
              (let [output (:out combined)
                    error-msg (:err combined)
                    combined-error (if (seq output)
                                    (str error-msg "\nOutput: " output)
                                    error-msg)]
                {:error combined-error
                 :status :error})
              
              :else 
              (let [eval-result (format-result-for-mcp (first values) namespace)
                    output (:out combined)
                    combined-value (if (seq output)
                                    (str eval-result "\nOutput: " output)
                                    eval-result)]
                {:value combined-value
                 :status :success}))))))
    
    (catch Exception e
      {:error (.getMessage e)
       :status :error})))

;; MCP Tool Registration

(register-tool! :eval
  "Evaluate Clojure code in the connected nREPL session"
  {:code {:type "string" :description "Clojure code to evaluate"}
   :namespace {:type "string" :optional true :description "Namespace to evaluate in"}
   :timeout {:type "number" :optional true :description "Timeout in milliseconds"}}
  (fn [tool-call context]
    (try
      (log/log! {:level :info :msg "Eval tool entry" 
                 :data {:tool-call tool-call :context-keys (keys context)}})
      (let [{:strs [code namespace timeout]} (:args tool-call)
            nrepl-client (:nrepl-client context)]
        (log/log! {:level :info :msg "Eval tool called" 
                   :data {:args (:args tool-call) 
                          :code code 
                          :namespace namespace 
                          :timeout timeout
                          :nrepl-client-available? (some? nrepl-client)}})
        (when (nil? code)
          (log/log! {:level :error :msg "Code is nil!" :data {:args (:args tool-call)}}))
        (when (nil? nrepl-client)
          (log/log! {:level :error :msg "nREPL client is nil!" :data {:context context}}))
        (let [result (eval-code nrepl-client code :namespace namespace :timeout (or timeout 120000))]
          (log/log! {:level :info :msg "Eval tool result" :data {:result result}})
          result))
      (catch Exception e
        (log/log! {:level :error :msg "Eval tool handler failed" 
                   :data {:error (.getMessage e) 
                          :stack-trace (str e)
                          :tool-call tool-call}})
        {:error (.getMessage e) :status :error})))
  :tags #{:development :repl :evaluation}
  :dependencies #{:nrepl})


(defn load-clojure-file
  "Load a Clojure file into nREPL session. Returns result map with :value, :output, :error, :status."
  [nrepl-client file-path]
  (try
    (log/log! {:level :info :msg "Loading file" :data {:file-path file-path}})
    (when (nil? nrepl-client)
      (throw (Exception. "nREPL client is nil")))
    (let [file-content (slurp file-path)
          responses (nrepl/message nrepl-client {:op "load-file" 
                                                :file file-content 
                                                :file-path file-path})
          result (reduce (fn [acc response]
                          (cond
                            (:value response) (assoc acc :value (:value response))
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
        (let [load-result (:value result)
              output (:output result)
              combined-value (if (seq output)
                              (str "File loaded: " file-path "\nResult: " load-result "\nOutput: " output)
                              (str "File loaded: " file-path "\nResult: " load-result))]
          {:value combined-value
           :status :success})))
    (catch Exception e
      (log/log! {:level :error :msg "Error loading file" :data {:error (.getMessage e) :file-path file-path}})
      {:error (.getMessage e)
       :status :error})))

(register-tool! :load-file
  "Load a Clojure file into the nREPL session"
  {:file-path {:type "string" :description "Path to the Clojure file to load"}}
  (fn [tool-call context]
    (try
      (let [{:strs [file-path]} (:args tool-call)
            nrepl-client (:nrepl-client context)]
        (log/log! {:level :info :msg "Load-file tool called" 
                   :data {:file-path file-path :nrepl-client-available? (some? nrepl-client)}})
        (load-clojure-file nrepl-client file-path))
      (catch Exception e
        (log/log! {:level :error :msg "Load-file tool handler failed" 
                   :data {:error (.getMessage e) 
                          :stack-trace (str e)
                          :tool-call tool-call}})
        {:error (.getMessage e) :status :error})))
  :tags #{:development :repl :file-loading}
  :dependencies #{:nrepl})
