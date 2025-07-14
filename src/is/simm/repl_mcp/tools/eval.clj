(ns is.simm.repl-mcp.tools.eval
  (:require [is.simm.repl-mcp.interactive :refer [register-tool!]]
            [is.simm.repl-mcp.logging :as logging]
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
  "Evaluate Clojure code using nREPL client. Returns result map with :value, :output, :error, :status."
  [nrepl-client code & {:keys [namespace timeout]
                                      :or {timeout 5000}}]
  (try
    (log/log! {:level :info :msg "Starting eval" :data {:code code :namespace namespace :timeout timeout}})
    (let [;; Create a session if needed
          session-responses (nrepl/message nrepl-client {:op "clone"})
          session-id (-> session-responses first :new-session)
          eval-msg (cond-> {:op "eval" :code code :session session-id}
                     namespace (assoc :ns namespace)
                     timeout (assoc :timeout timeout))
          
          responses (doall (nrepl/message nrepl-client eval-msg))
          _ (log/log! {:level :debug :msg "nREPL responses" :data {:responses responses}})
          
          ;; Use response-values as intended by nREPL
          values (nrepl/response-values responses)
          _ (log/log! {:level :debug :msg "nREPL values" :data {:values values}})
          
          combined (nrepl/combine-responses responses)
          _ (log/log! {:level :debug :msg "nREPL combined" :data {:combined combined}})
          
          result (cond
                   (:err combined) (let [output (:out combined)
                                        error-msg (:err combined)
                                        combined-error (if (seq output)
                                                        (str error-msg "\nOutput: " output)
                                                        error-msg)]
                                    {:error combined-error
                                     :status :error})
                   :else (let [eval-result (format-result-for-mcp (first values) namespace)
                              output (:out combined)
                              combined-value (if (seq output)
                                              (str eval-result "\nOutput: " output)
                                              eval-result)]
                          {:value combined-value
                           :status :success}))]
      (log/log! {:level :info :msg "Eval completed" :data {:code code :result result}})
      (logging/log-eval-call code namespace timeout result)
      result)
    (catch Exception e
      (log/log! {:level :error :msg "Error evaluating code" :data {:error (.getMessage e) :code code}})
      (logging/log-error "Eval failed" e {:code code :namespace namespace})
      {:error (.getMessage e)
       :status :error})))

;; MCP Tool Registration

(register-tool! :eval
  "Evaluate Clojure code in the connected nREPL session"
  {:code {:type "string" :description "Clojure code to evaluate"}
   :namespace {:type "string" :optional true :description "Namespace to evaluate in"}
   :timeout {:type "number" :optional true :description "Timeout in milliseconds"}}
  (fn [tool-call context]
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
      (let [result (eval-code nrepl-client code :namespace namespace :timeout (or timeout 5000))]
        (log/log! {:level :info :msg "Eval tool result" :data {:result result}})
        result)))
  :tags #{:development :repl :evaluation}
  :dependencies #{:nrepl})


(defn load-clojure-file
  "Load a Clojure file into nREPL session. Returns result map with :value, :output, :error, :status."
  [nrepl-client file-path]
  (try
    (log/log! {:level :info :msg "Loading file" :data {:file-path file-path}})
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
    (let [{:strs [file-path]} (:args tool-call)
          nrepl-client (:nrepl-client context)]
      (load-clojure-file nrepl-client file-path)))
  :tags #{:development :repl :file-loading}
  :dependencies #{:nrepl})
