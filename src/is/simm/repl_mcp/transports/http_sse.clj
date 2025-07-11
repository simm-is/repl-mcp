(ns is.simm.repl-mcp.transports.http-sse
  "HTTP+SSE transport implementation for MCP server"
  (:require [is.simm.repl-mcp.transport :as transport]
            [is.simm.repl-mcp.dispatch :as dispatch]
            [is.simm.repl-mcp.util :as util]
            [taoensso.telemere :as log]
            [clojure.data.json :as json])
  (:import [org.eclipse.jetty.server Server ServerConnector]
           [org.eclipse.jetty.ee10.servlet ServletContextHandler ServletHolder]
           [io.modelcontextprotocol.server McpServer McpServerFeatures$SyncToolSpecification]
           [io.modelcontextprotocol.server.transport HttpServletSseServerTransportProvider]
           [io.modelcontextprotocol.spec McpSchema$ServerCapabilities McpSchema$Tool]
           [com.fasterxml.jackson.databind ObjectMapper]
           [java.util.function BiFunction]
           [io.modelcontextprotocol.spec McpSchema$CallToolResult McpSchema$TextContent]))

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn create-jetty-server
  "Create and configure Jetty server for SSE transport"
  [port servlet-transport]
  (let [server (Server.)
        connector (ServerConnector. server)
        context (ServletContextHandler. ServletContextHandler/SESSIONS)]
    
    ;; Configure connector
    (.setPort connector port)
    (.addConnector server connector)
    
    ;; Configure context
    (.setContextPath context "/")
    (.addServlet context 
                (ServletHolder. servlet-transport) 
                "/*")
    (.setHandler server context)
    
    server))

(defn create-tool-handler
  "Create tool handler for HTTP+SSE transport"
  [tool context]
  (reify BiFunction
    (apply [_this _exchange request]
      (try
        (let [tool-name (:name tool)
              ;; Convert Java HashMap to Clojure map for easier access
              args (into {} request)
              raw-tool-call {:name tool-name :arguments args}
              tool-call (dispatch/mcp-tool-call->clj raw-tool-call)
              result (dispatch/handle-tool-call tool-call context)]
          
          ;; Convert result to MCP format
          (if (= (:status result) :success)
            (McpSchema$CallToolResult. 
              (java.util.List/of 
                (McpSchema$TextContent. (str (:value result))))
              false)
            (McpSchema$CallToolResult. 
              (java.util.List/of 
                (McpSchema$TextContent. (str (:error result))))
              true)))
        
        (catch Exception e
          (log/log! {:level :error :msg "Error in tool handler" 
                     :data {:tool (:name tool) :error (.getMessage e)}})
          (McpSchema$CallToolResult. 
            (java.util.List/of 
              (McpSchema$TextContent. (.getMessage e)))
            true))))))

;; =============================================================================
;; HTTP+SSE Transport Implementation  
;; =============================================================================

(defrecord HttpSseTransport [config jetty-server mcp-server running?]
  
  transport/McpTransport
  (start! [this context]
    (when @running?
      (throw (ex-info "HTTP+SSE transport already running" {})))
    
    (log/log! {:level :info :msg "Starting HTTP+SSE MCP transport"
               :data {:http-port (:port config)}})
    
    (try
      ;; Create HTTP transport
      (let [object-mapper (ObjectMapper.)
            servlet-transport (-> (HttpServletSseServerTransportProvider/builder)
                                  (.objectMapper object-mapper)
                                  (.messageEndpoint "/message")
                                  (.sseEndpoint "/sse")
                                  (.build))

            ;; Create Jetty server
            jetty-srv (create-jetty-server (:port config) servlet-transport)

            ;; Create server capabilities
            capabilities (-> (McpSchema$ServerCapabilities/builder)
                             (.tools true)
                             (.prompts true)
                             (.resources false false)
                             (.logging)
                             (.build))

            ;; Create MCP server
            mcp-srv (-> (McpServer/sync servlet-transport)
                        (.serverInfo "repl-mcp-http-sse" "1.0.0")
                        (.capabilities capabilities)
                        (.build))]

        ;; Register tools from context
        (let [tools (vals (:tools context))]
          (doseq [tool tools]
            (let [tool-map (dispatch/tool-spec->mcp-tool tool)
                  mcp-tool-obj (McpSchema$Tool.
                                (:name tool-map)
                                (:description tool-map)
                                (json/write-str (:inputSchema tool-map)))
                  tool-handler (create-tool-handler tool context)
                  tool-spec (McpServerFeatures$SyncToolSpecification.
                             mcp-tool-obj tool-handler)]
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

        ;; Start Jetty server
        (.start jetty-srv)

        ;; Update state
        (reset! mcp-server mcp-srv)
        (reset! jetty-server jetty-srv)
        (reset! running? true)

        (log/log! {:level :info :msg "HTTP+SSE transport started successfully"
                   :data {:http-port (:port config)
                          :sse-endpoint (str "http://" (:host config) ":" (:port config) "/sse")
                          :message-endpoint (str "http://" (:host config) ":" (:port config) "/message")
                          :tools-count (count (:tools context))
                          :prompts-count (count (:prompts context))}}))
      
      (catch Exception e
        (log/log! {:level :error :msg "Failed to start HTTP+SSE transport"
                   :data {:error (.getMessage e)}})
        (throw e))))
  
  (stop! [this]
    (when @running?
      (log/log! {:level :info :msg "Stopping HTTP+SSE transport"})
      
      (try
        ;; Stop Jetty server
        (when @jetty-server
          (.stop @jetty-server)
          (reset! jetty-server nil))
        
        ;; Note: nREPL cleanup is handled by server layer, not transport
        
        (reset! mcp-server nil)
        (reset! running? false)
        
        (log/log! {:level :info :msg "HTTP+SSE transport stopped"})
        
        (catch Exception e
          (log/log! {:level :error :msg "Error stopping HTTP+SSE transport"
                     :data {:error (.getMessage e)}})
          (throw e))))
    this)
  
  (info [this]
    {:transport-type :http-sse
     :running? @running?
     :config config
     :http-port (:port config)
     :features #{:streaming :multiple-clients :remote-access :bidirectional}
     :endpoints (when @running?
                  {:sse (str "http://" (:host config) ":" (:port config) "/sse")
                   :message (str "http://" (:host config) ":" (:port config) "/message")})})
  
  (supports-feature? [this feature]
    (contains? #{:streaming :multiple-clients :remote-access :bidirectional} feature)))

;; =============================================================================
;; Constructor Function
;; =============================================================================

(defn create-http-sse-transport
  "Create HTTP+SSE transport with configuration"
  [config]
  (->HttpSseTransport config
                      (atom nil)  ; jetty-server
                      (atom nil)  ; mcp-server
                      (atom false))) ; running?

;; =============================================================================
;; Registration
;; =============================================================================

;; Register this transport with the transport registry
(transport/register-transport! :http-sse create-http-sse-transport)