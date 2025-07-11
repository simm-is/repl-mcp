(ns is.simm.repl-mcp.transports.stdio
  "STDIO transport implementation for MCP server"
  (:require [is.simm.repl-mcp.transport :as transport]
            [is.simm.repl-mcp.util :as util]
            [taoensso.telemere :as log])
  (:import [io.modelcontextprotocol.server McpServer]
           [io.modelcontextprotocol.server.transport StdioServerTransportProvider]
           [io.modelcontextprotocol.spec McpSchema$ServerCapabilities]))

;; =============================================================================
;; STDIO Transport Implementation
;; =============================================================================

(defrecord StdioTransport [config mcp-server running?]
  
  transport/McpTransport
  (start! [_this context]
    (when @running?
      (throw (ex-info "STDIO transport already running" {})))
    
    (log/log! {:level :info :msg "Starting STDIO MCP transport"})
    
    (try
      ;; Create transport provider
      (let [transport-provider (StdioServerTransportProvider.)
                
                ;; Create server capabilities based on context
                capabilities (-> (McpSchema$ServerCapabilities/builder)
                                (.tools true)
                                (.prompts true)
                                (.resources false false)
                                (.logging)
                                (.build))
                
                ;; Create MCP server
                mcp-srv (-> (McpServer/sync transport-provider)
                           (.serverInfo "repl-mcp-stdio" "1.0.0")
                           (.capabilities capabilities)
                           (.build))]
            
            ;; Register tools from context
            (let [tools (vals (:tools context))]
              (doseq [tool tools]
                (let [tool-spec (util/create-tool-specification tool context)]
                  (.addTool mcp-srv tool-spec))))
            
            ;; Register prompts from context  
            (let [prompts (:prompts context)]
              (doseq [[prompt-name prompt-config] prompts]
                (let [prompt-spec (util/create-workflow-prompt-specification
                                  prompt-name
                                  (:description prompt-config)
                                  (:args prompt-config)
                                  (:template prompt-config))]
                  (.addPrompt mcp-srv prompt-spec))))
            
            ;; Update state
            (reset! mcp-server mcp-srv)
            (reset! running? true)
            
            (log/log! {:level :info :msg "STDIO transport started successfully"
                       :data {
                              :tools-count (count (:tools context))
                              :prompts-count (count (:prompts context))}})
            
            ;; STDIO server is ready when created - no explicit start needed
            ;; The transport provider handles the I/O automatically
            )
        
      (catch Exception e
        (log/log! {:level :error :msg "Failed to start STDIO transport"
                   :data {:error (.getMessage e)}})
        (throw e))))
  
  (stop! [this]
    (when @running?
      (log/log! {:level :info :msg "Stopping STDIO transport"})
      
      (try
        ;; Note: nREPL cleanup is handled by server layer, not transport
        
        ;; MCP server stops automatically when run() completes
        (reset! mcp-server nil)
        (reset! running? false)
        
        (log/log! {:level :info :msg "STDIO transport stopped"})
        
        (catch Exception e
          (log/log! {:level :error :msg "Error stopping STDIO transport"
                     :data {:error (.getMessage e)}})
          (throw e))))
    this)
  
  (info [this]
    {:transport-type :stdio
     :running? @running?
     :config config
     :features #{:bidirectional}
     :clients (if @running? 1 0)  ; STDIO only supports single client
     :endpoints {:stdio "process stdin/stdout"}})
  
  (supports-feature? [this feature]
    (contains? #{:bidirectional} feature)))

;; =============================================================================
;; Constructor Function
;; =============================================================================

(defn create-stdio-transport
  "Create STDIO transport with configuration"
  [config]
  (->StdioTransport config 
                    (atom nil)  ; mcp-server
                    (atom false))) ; running?

;; =============================================================================
;; Registration
;; =============================================================================

;; Register this transport with the transport registry
(transport/register-transport! :stdio create-stdio-transport)