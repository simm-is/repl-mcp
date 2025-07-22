(ns is.simm.repl-mcp.tools.nrepl-utils
  "Shared utilities for safe nREPL interactions with timeout and error handling"
  (:require 
   [nrepl.core :as nrepl]
   [taoensso.telemere :as log]
   [clojure.string :as str]))

;; ===============================================
;; Safe nREPL Message Handling
;; ===============================================

(defn safe-nrepl-message
  "Send nREPL message with proper timeout and interrupt handling.
   Returns {:status :success :responses responses} or {:status :error/:timeout :error error-msg}"
  [nrepl-client message & {:keys [timeout operation-name] :or {timeout 120000 operation-name "nREPL operation"}}]
  (try
    (log/log! {:level :debug :msg "Starting safe nREPL message" 
               :data {:operation operation-name :message message :timeout timeout}})
    
    (when (nil? nrepl-client)
      (throw (Exception. "nREPL client is nil")))
    
    (let [;; Set up timeout handling with interrupt
          response-future (future 
                            (try
                              (log/log! {:level :debug :msg "Sending nREPL message" :data {:message message}})
                              (doall (nrepl/message nrepl-client message))
                              (catch Exception e
                                (log/log! {:level :error :msg "nREPL message error" :data {:error (.getMessage e)}})
                                ::message-error)))
          
          ;; Wait for response with timeout
          responses (deref response-future timeout ::timeout)]
      
      (log/log! {:level :debug :msg "nREPL message completed" 
                 :data {:response-type (type responses) :timed-out? (= responses ::timeout)}})
      
      (cond
        ;; Handle timeout
        (= responses ::timeout)
        (do
          (log/log! {:level :warn :msg "nREPL message timed out" :data {:timeout timeout :operation operation-name}})
          ;; Try to send interrupt
          (try
            (let [clone-result (first (nrepl/message nrepl-client {:op "clone"}))
                  session-id (:new-session clone-result)]
              (when session-id
                (future (nrepl/message nrepl-client {:op "interrupt" :session session-id}))))
            (catch Exception e
              (log/log! {:level :warn :msg "Failed to send interrupt" :data {:error (.getMessage e)}})))
          
          ;; Cancel the future
          (future-cancel response-future)
          
          {:status :timeout
           :error (str operation-name " timed out after " timeout "ms")})
        
        ;; Handle message error
        (= responses ::message-error)
        (do
          (log/log! {:level :error :msg "nREPL message failed" :data {:operation operation-name}})
          {:status :error
           :error (str "Error occurred during " operation-name)})
        
        ;; Handle success
        :else
        (if (or (nil? responses) (empty? responses))
          (do
            (log/log! {:level :error :msg "No response received from nREPL" :data {:operation operation-name}})
            {:status :error
             :error (str "No response received from nREPL for " operation-name)})
          
          (do
            (log/log! {:level :debug :msg "nREPL message successful" 
                       :data {:operation operation-name :response-count (count responses)}})
            {:status :success
             :responses responses}))))
    
    (catch Exception e
      (log/log! {:level :error :msg "Safe nREPL message failed" 
                 :data {:error (.getMessage e) :operation operation-name}})
      {:status :error
       :error (.getMessage e)})))

;; ===============================================
;; Response Processing Utilities
;; ===============================================

(defn process-eval-response
  "Process evaluation response with combined values and output"
  [responses]
  (let [values (nrepl/response-values responses)
        combined (nrepl/combine-responses responses)]
    (cond
      (:err combined) 
      {:status :error
       :error (str (:err combined)
                   (when-let [output (:out combined)]
                     (str "\nOutput: " output)))}
      
      :else 
      {:status :success
       :value (str (first values)
                   (when-let [output (:out combined)]
                     (str "\nOutput: " output)))})))

(defn process-simple-response
  "Process simple response extracting specific key or first response"
  [responses key-or-fn]
  (let [result (first responses)]
    (cond
      (fn? key-or-fn)
      (key-or-fn result)
      
      (keyword? key-or-fn)
      (get result key-or-fn)
      
      :else
      result)))

(defn process-combined-response
  "Process response using nrepl/combine-responses"
  [responses]
  (nrepl/combine-responses responses))

;; ===============================================
;; High-Level Tool Wrappers
;; ===============================================

(defn with-safe-nrepl
  "Execute function with nREPL client, handling errors and timeouts.
   Returns MCP-formatted response."
  [mcp-context operation-name f & {:keys [timeout] :or {timeout 120000}}]
  (let [nrepl-client (:nrepl-client mcp-context)]
    (if (nil? nrepl-client)
      {:content [{:type "text" 
                  :text (str "Error: nREPL client not available. " operation-name " requires an active nREPL connection.")}]}
      
      (let [result (f nrepl-client timeout)]
        (cond
          (= (:status result) :success)
          {:content [{:type "text" :text (or (:value result) "Operation completed successfully")}]}
          
          (= (:status result) :timeout)
          {:content [{:type "text" :text (str "Timeout: " (:error result))}]}
          
          :else
          {:content [{:type "text" :text (str "Error: " (:error result))}]})))))

(defn simple-nrepl-tool
  "Create a simple nREPL tool with message, response processing, and error handling"
  [mcp-context operation-name message-fn response-fn & {:keys [timeout] :or {timeout 120000}}]
  (with-safe-nrepl mcp-context operation-name
    (fn [nrepl-client timeout]
      (let [message (message-fn)
            result (safe-nrepl-message nrepl-client message 
                                       :timeout timeout 
                                       :operation-name operation-name)]
        (if (= (:status result) :success)
          {:status :success
           :value (response-fn (:responses result))}
          result)))
    :timeout timeout))

;; ===============================================
;; Validation Utilities
;; ===============================================

(defn validate-file-exists
  "Validate that file exists, return error map if not"
  [file-path]
  (when-not (.exists (java.io.File. file-path))
    {:status :error
     :error (str "File does not exist: " file-path)}))

(defn validate-required-params
  "Validate required parameters are present"
  [params required-keys]
  (let [missing (filter #(nil? (get params %)) required-keys)]
    (when (seq missing)
      {:status :error
       :error (str "Missing required parameters: " (str/join ", " missing))})))