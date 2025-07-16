(ns is.simm.repl-mcp.server
  "Simplified MCP server using transport abstraction"
  (:require [is.simm.repl-mcp.transport :as transport]
            [nrepl.core :as nrepl]
            [nrepl.server :as nrepl-server]
            [taoensso.telemere :as log]
            [cider.nrepl :refer [cider-nrepl-handler]]
            [refactor-nrepl.middleware :refer [wrap-refactor]]
            ;; Load transport implementations
            [is.simm.repl-mcp.transports.stdio]
            [is.simm.repl-mcp.transports.http-sse]))

;; =============================================================================
;; Server State
;; =============================================================================

(defonce server-state (atom {:multi-transport-server nil
                            :nrepl-server nil
                            :nrepl-client nil}))

;; =============================================================================
;; nREPL Setup (unchanged from original)
;; =============================================================================

(defn start-nrepl-server!
  "Start nREPL server with cider and refactor middleware"
  [port]
  (let [middleware-stack (-> cider-nrepl-handler
                             (wrap-refactor))
        server (nrepl-server/start-server 
                :port port
                :handler middleware-stack
                :bind "127.0.0.1")]
    (log/log! {:level :info :msg "nREPL server started" :data {:port port}})
    server))

(defn connect-to-nrepl
  "Connect to nREPL server and return client"
  [port]
  (let [client (nrepl/client (nrepl/connect :port port) Long/MAX_VALUE)]
    (log/log! {:level :info :msg "Connected to nREPL server" :data {:port port}})
    client))

(defn stop-nrepl-server!
  "Stop nREPL server"
  [server]
  (when server
    (nrepl-server/stop-server server)
    (log/log! {:level :info :msg "nREPL server stopped"})))

;; =============================================================================
;; Simplified Server Functions
;; =============================================================================

(defn start-mcp-server!
  "Start MCP server with configurable transports using transport abstraction"
  [& {:keys [nrepl-port http-port transports] 
      :or {nrepl-port 17888 http-port 18080 transports #{:stdio}}}]
  (try
    ;; Start nREPL server
    (let [nrepl-server (start-nrepl-server! nrepl-port)
          _ (Thread/sleep 1000) ; Give nREPL time to start
          nrepl-client (connect-to-nrepl nrepl-port)
          
          ;; Create transport configurations
          transport-configs (cond-> {}
                              (contains? transports :stdio)
                              (assoc :stdio (transport/create-transport-config :stdio))
                              
                              (contains? transports :http)
                              (assoc :http-sse (transport/create-transport-config :http-sse 
                                                                                  :port http-port
                                                                                  :host "localhost")))
          
          ;; Create context with tools and prompts
          context (transport/create-context)
          
          ;; Add nREPL client to context for tools that need it
          enhanced-context (assoc context :nrepl-client nrepl-client
                                         :nrepl-port nrepl-port)
          
          ;; Start multi-transport server
          multi-server (transport/start-mcp-server! transport-configs enhanced-context)]
      
      ;; Update server state
      (swap! server-state assoc
             :multi-transport-server multi-server
             :nrepl-server nrepl-server
             :nrepl-client nrepl-client)
      
      ;; Log startup info
      (doseq [transport-type (keys transport-configs)]
        (log/log! {:level :info :msg "MCP transport started" 
                   :data {:transport-type transport-type}}))
      
      (log/log! {:level :info :msg "nREPL server running" :data {:port nrepl-port}})
      (log/log! {:level :info :msg "MCP server started successfully" 
                 :data {:transports (keys transport-configs)
                        :tools-count (count (:tools enhanced-context))
                        :prompts-count (count (:prompts enhanced-context))}})
      
      multi-server)
    
    (catch Exception e
      (log/log! {:level :error :msg "Failed to start MCP server" 
                 :data {:error (.getMessage e)}})
      (throw e))))

(defn stop-mcp-server!
  "Stop MCP server and nREPL server"
  []
  (let [{:keys [multi-transport-server nrepl-server]} @server-state]
    
    ;; Stop multi-transport server
    (when multi-transport-server
      (transport/stop! multi-transport-server)
      (log/log! {:level :info :msg "Multi-transport MCP server stopped"}))
    
    ;; Stop nREPL server
    (stop-nrepl-server! nrepl-server)
    
    ;; Clear state
    (reset! server-state {:multi-transport-server nil
                          :nrepl-server nil
                          :nrepl-client nil})
    
    (log/log! {:level :info :msg "All servers stopped"})))

(defn restart-mcp-server!
  "Restart MCP server with same configuration"
  []
  (log/log! {:level :info :msg "Restarting MCP server"})
  (stop-mcp-server!)
  (Thread/sleep 1000)
  (start-mcp-server!))

(defn get-server-info
  "Get information about running servers"
  []
  (let [{:keys [multi-transport-server nrepl-server]} @server-state]
    {:mcp-server (when multi-transport-server
                   (transport/info multi-transport-server))
     :nrepl-running? (some? nrepl-server)}))

(defn server-info
  "Alias for get-server-info for compatibility"
  []
  (get-server-info))

;; =============================================================================
;; Dynamic Tool Management
;; =============================================================================

(defn add-tool-to-running-server!
  "Add a registered tool to the running MCP server instances.
  This would require extending the transport protocol to support dynamic tool addition."
  [tool-name]
  (log/log! {:level :warn :msg "Dynamic tool addition not yet implemented in transport abstraction"
             :data {:tool-name tool-name}})
  ;; TODO: Implement dynamic tool addition in transport protocol
  false)