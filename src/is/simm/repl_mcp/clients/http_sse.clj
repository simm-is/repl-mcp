(ns is.simm.repl-mcp.clients.http-sse
  "HTTP+SSE MCP client implementation"
  (:require [is.simm.repl-mcp.client :as client]
            [taoensso.telemere :as log])
  (:import [io.modelcontextprotocol.client McpClient]
           [io.modelcontextprotocol.client.transport HttpClientSseClientTransport]
           [io.modelcontextprotocol.spec McpSchema$CallToolRequest McpSchema$GetPromptRequest]
           [java.time Duration]))

;; =============================================================================
;; HTTP+SSE Client Implementation
;; =============================================================================

(defrecord HttpSseMcpClient [config java-client connected?]
  
  client/McpClient
  (connect! [this]
    (when @connected?
      (throw (ex-info "Client already connected" {})))
    
    (log/log! {:level :info :msg "Connecting HTTP+SSE MCP client"
               :data {:base-url (:base-url config)}})
    
    (try
      ;; Create HTTP+SSE transport
      (let [transport (-> (HttpClientSseClientTransport/builder (:base-url config))
                         (.sseEndpoint "/sse")
                         (.build))
            
            ;; Create MCP client
            mcp-client (-> (McpClient/sync transport)
                          (.requestTimeout (Duration/ofSeconds (:request-timeout config)))
                          (.initializationTimeout (Duration/ofSeconds (:init-timeout config)))
                          (.clientInfo (io.modelcontextprotocol.spec.McpSchema$Implementation.
                                       (:client-name config)
                                       (:client-version config)))
                          (.loggingConsumer 
                           (fn [log-notification]
                             (log/log! {:level :info 
                                        :msg "MCP Server Log" 
                                        :data {:level (.level log-notification)
                                               :message (.message log-notification)
                                               :logger (.logger log-notification)}})))
                          (.build))]
        
        ;; Initialize connection
        (let [init-result (.initialize mcp-client)]
          (log/log! {:level :info :msg "HTTP+SSE client initialized"
                     :data {:server-info (.serverInfo init-result)
                            :capabilities (.capabilities init-result)
                            :base-url (:base-url config)}})
          
          (reset! java-client mcp-client)
          (reset! connected? true)
          
          {:status :success
           :server-info (.serverInfo init-result)
           :capabilities (.capabilities init-result)
           :base-url (:base-url config)}))
      
      (catch Exception e
        (log/log! {:level :error :msg "Failed to connect HTTP+SSE client"
                   :data {:error (.getMessage e)
                          :base-url (:base-url config)}})
        (reset! connected? false)
        (throw e))))
  
  (disconnect! [this]
    (when @connected?
      (log/log! {:level :info :msg "Disconnecting HTTP+SSE client"})
      
      (try
        (when @java-client
          (.close @java-client)
          (reset! java-client nil))
        (reset! connected? false)
        
        (log/log! {:level :info :msg "HTTP+SSE client disconnected"})
        
        (catch Exception e
          (log/log! {:level :error :msg "Error disconnecting HTTP+SSE client"
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
    
    (log/log! {:level :info :msg "Calling tool via HTTP+SSE"
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
    
    (log/log! {:level :info :msg "Getting prompt via HTTP+SSE"
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
    
    (log/log! {:level :info :msg "Listing tools via HTTP+SSE"})
    
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
    
    (log/log! {:level :info :msg "Listing prompts via HTTP+SSE"})
    
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
    {:client-type :http-sse
     :connected? @connected?
     :config config
     :base-url (:base-url config)
     :transport-features #{:streaming :multiple-clients :remote-access :bidirectional}}))

;; =============================================================================
;; Constructor Function
;; =============================================================================

(defn create-http-sse-client
  "Create HTTP+SSE MCP client with configuration"
  [config]
  (->HttpSseMcpClient config
                      (atom nil)   ; java-client
                      (atom false))) ; connected?

;; =============================================================================
;; Convenience Functions
;; =============================================================================

(defn connect-to-server
  "Connect to an MCP server via HTTP+SSE"
  [base-url & {:keys [request-timeout init-timeout client-name]
               :or {request-timeout 30 init-timeout 30 client-name "repl-mcp-http-sse-client"}}]
  (let [config (client/create-client-config :http-sse
                                           :base-url base-url
                                           :request-timeout request-timeout
                                           :init-timeout init-timeout
                                           :client-name client-name)
        client (create-http-sse-client config)]
    (client/connect! client)
    client))

(defn with-http-sse-client
  "Execute function with connected HTTP+SSE client, ensuring cleanup"
  [base-url f & {:keys [request-timeout init-timeout]
                 :or {request-timeout 30 init-timeout 30}}]
  (let [client (connect-to-server base-url
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
(client/register-client! :http-sse create-http-sse-client)