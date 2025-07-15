(ns is.simm.repl-mcp.logging
  "Telemere-based logging configuration for repl-mcp"
  (:require [taoensso.telemere :as t]))

(defn setup-file-logging!
  "Configure Telemere with file logging and disable console output for STDIO transport"
  [disable-console?]
  ;; Completely disable all logging temporarily during configuration
  (t/set-min-level! :fatal)
  
  ;; Remove all default handlers (including console)
  (when disable-console?
    (t/remove-handler! :default/console))
  
  ;; Add ONLY file handler to Telemere with proper configuration
  (t/add-handler! 
    :file-handler
    (t/handler:file {:path "repl-mcp.log"})
    {:min-level :debug})
  
  ;; Re-enable logging but now it only goes to file
  (t/set-min-level! :debug)
  
  ;; DO NOT LOG ANYTHING HERE - it might still go to console
  ;; File logging is now configured silently
  )

;; Convenience logging functions with context
(defn log-tool-call
  "Log a tool call with full context"
  [tool-name args result]
  (t/log! {:level :info 
           :msg "Tool call executed"
           :data {:tool-name tool-name
                  :args args
                  :result result
                  :success? (= (:status result) :success)}}))

(defn log-eval-call
  "Log eval-specific details"
  [code namespace timeout result]
  (t/log! {:level :debug
           :msg "Eval call details"
           :data {:code code
                  :namespace namespace 
                  :timeout timeout
                  :result result
                  :value (:value result)
                  :output (:output result)
                  :status (:status result)}}))

(defn log-error
  "Log errors with full context"
  [message error & [context]]
  (t/log! {:level :error
           :msg message
           :data (merge {:error-message (.getMessage error)
                         :error-type (type error)}
                        context)}))
