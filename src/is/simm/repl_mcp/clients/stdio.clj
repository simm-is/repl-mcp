(ns is.simm.repl-mcp.clients.stdio
  "STDIO MCP client implementation"
  (:require [is.simm.repl-mcp.client :as client]
            [taoensso.telemere :as log])
  (:import [io.modelcontextprotocol.client McpClient]
           [io.modelcontextprotocol.client.transport StdioClientTransport ServerParameters ServerParameters$Builder]
           [io.modelcontextprotocol.spec McpSchema$CallToolRequest McpSchema$GetPromptRequest]
           [com.fasterxml.jackson.databind ObjectMapper]
           [java.time Duration]))

;; =============================================================================
;; STDIO Client Implementation
;; =============================================================================

(defrecord StdioMcpClient [config java-client connected?]
  
  client/McpClient
  (connect! [this]
    (when @connected?
      (throw (ex-info "Client already connected" {})))
    
    (log/log! {:level :info :msg "Connecting STDIO MCP client"
               :data {:server-command (:server-command config)}})
    
    (try
      ;; Create STDIO transport
      (let [server-params (-> (ServerParameters$Builder. (:server-command config))
                             (.build))
            transport (StdioClientTransport. server-params (ObjectMapper.))
            
            ;; Create MCP client
            mcp-client (-> (McpClient/sync transport)
                          (.requestTimeout (Duration/ofSeconds (:request-timeout config)))
                          (.initializationTimeout (Duration/ofSeconds (:init-timeout config)))
                          (.clientInfo (io.modelcontextprotocol.spec.McpSchema$Implementation.
                                       (:client-name config)
                                       (:client-version config)))
                          (.build))]
        
        ;; Initialize connection
        (let [init-result (.initialize mcp-client)]
          (log/log! {:level :info :msg "STDIO client initialized"
                     :data {:server-info (.serverInfo init-result)
                            :capabilities (.capabilities init-result)}})
          
          (reset! java-client mcp-client)
          (reset! connected? true)
          
          {:status :success
           :server-info (.serverInfo init-result)
           :capabilities (.capabilities init-result)}))
      
      (catch Exception e
        (log/log! {:level :error :msg "Failed to connect STDIO client"
                   :data {:error (.getMessage e)}})
        (reset! connected? false)
        (throw e))))
  
  (disconnect! [this]
    (when @connected?
      (log/log! {:level :info :msg "Disconnecting STDIO client"})
      
      (try
        (when @java-client
          (.close @java-client)
          (reset! java-client nil))
        (reset! connected? false)
        
        (log/log! {:level :info :msg "STDIO client disconnected"})
        
        (catch Exception e
          (log/log! {:level :error :msg "Error disconnecting STDIO client"
                     :data {:error (.getMessage e)}})
          (throw e))))
    this)
  
  (connected? [this]
    (and @connected? 
         @java-client
         (.isInitialized @java-client)))
  
  (call-tool [this tool-name args]
    (when-not (client/connected? this)
      (throw (ex-info "Client not connected" {})))
    
    (log/log! {:level :info :msg "Calling tool via STDIO"
               :data {:tool-name tool-name :args args}})
    
    (try
      (let [java-args (client/clojure-args->java-map args)
            request (McpSchema$CallToolRequest. (name tool-name) java-args)
            result (.callTool @java-client request)]
        
        (log/log! {:level :info :msg "Tool call completed"
                   :data {:tool-name tool-name :is-error (.isError result)}})
        
        (client/process-tool-result result))
      
      (catch Exception e
        (log/log! {:level :error :msg "Tool call failed"
                   :data {:tool-name tool-name :error (.getMessage e)}})
        (throw e))))
  
  (get-prompt [this prompt-name args]
    (when-not (client/connected? this)
      (throw (ex-info "Client not connected" {})))
    
    (log/log! {:level :info :msg "Getting prompt via STDIO"
               :data {:prompt-name prompt-name :args args}})
    
    (try
      (let [java-args (client/clojure-args->java-map args)
            request (McpSchema$GetPromptRequest. (name prompt-name) java-args)
            result (.getPrompt @java-client request)]
        
        (log/log! {:level :info :msg "Prompt request completed"
                   :data {:prompt-name prompt-name}})
        
        (client/process-prompt-result result))
      
      (catch Exception e
        (log/log! {:level :error :msg "Prompt request failed"
                   :data {:prompt-name prompt-name :error (.getMessage e)}})
        (throw e))))
  
  (list-tools [this]
    (when-not (client/connected? this)
      (throw (ex-info "Client not connected" {})))
    
    (log/log! {:level :info :msg "Listing tools via STDIO"})
    
    (try
      (let [result (.listTools @java-client)]
        (log/log! {:level :info :msg "Tools listed"
                   :data {:tool-count (count (.tools result))}})
        
        (client/process-tools-list result))
      
      (catch Exception e
        (log/log! {:level :error :msg "Failed to list tools"
                   :data {:error (.getMessage e)}})
        (throw e))))
  
  (list-prompts [this]
    (when-not (client/connected? this)
      (throw (ex-info "Client not connected" {})))
    
    (log/log! {:level :info :msg "Listing prompts via STDIO"})
    
    (try
      (let [result (.listPrompts @java-client)]
        (log/log! {:level :info :msg "Prompts listed"
                   :data {:prompt-count (count (.prompts result))}})
        
        (client/process-prompts-list result))
      
      (catch Exception e
        (log/log! {:level :error :msg "Failed to list prompts"
                   :data {:error (.getMessage e)}})
        (throw e))))
  
  (get-server-info [this]
    (when-not (client/connected? this)
      (throw (ex-info "Client not connected" {})))
    
    (try
      (let [server-info (.getServerInfo @java-client)
            capabilities (.getServerCapabilities @java-client)]
        {:server-info server-info
         :capabilities capabilities})
      
      (catch Exception e
        (log/log! {:level :error :msg "Failed to get server info"
                   :data {:error (.getMessage e)}})
        (throw e))))
  
  (client-info [this]
    {:client-type :stdio
     :connected? @connected?
     :config config
     :server-command (:server-command config)
     :transport-features #{:process-based :bidirectional}}))

;; =============================================================================
;; Constructor Function
;; =============================================================================

(defn create-stdio-client
  "Create STDIO MCP client with configuration"
  [config]
  (->StdioMcpClient config
                    (atom nil)   ; java-client
                    (atom false))) ; connected?

;; =============================================================================
;; Convenience Functions
;; =============================================================================

(defn connect-to-server
  "Connect to an MCP server via STDIO with given command"
  [server-command & {:keys [request-timeout init-timeout client-name]
                     :or {request-timeout 30 init-timeout 30 client-name "repl-mcp-stdio-client"}}]
  (let [config (client/create-client-config :stdio
                                           :server-command server-command
                                           :request-timeout request-timeout
                                           :init-timeout init-timeout
                                           :client-name client-name)
        client (create-stdio-client config)]
    (client/connect! client)
    client))

(defn with-stdio-client
  "Execute function with connected STDIO client, ensuring cleanup"
  [server-command f & {:keys [request-timeout init-timeout]
                       :or {request-timeout 30 init-timeout 30}}]
  (let [client (connect-to-server server-command
                                  :request-timeout request-timeout
                                  :init-timeout init-timeout)]
    (try
      (f client)
      (finally
        (client/disconnect! client)))))

;; =============================================================================
;; Registration
;; =============================================================================

;; Register this client with the client registry
(client/register-client! :stdio create-stdio-client)