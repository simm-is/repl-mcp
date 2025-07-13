(ns is.simm.repl-mcp.transport
  "Unified transport abstraction for MCP server supporting multiple transports"
  (:require [taoensso.telemere :as log]
            [is.simm.repl-mcp.dispatch :as dispatch]))

;; =============================================================================
;; Transport Protocol
;; =============================================================================

(defprotocol McpTransport
  "Protocol for MCP transport implementations"
  (start! [transport context]
    "Start the transport server with given context (tools, prompts, etc.)")
  (stop! [transport]
    "Stop the transport server")
  (info [transport]
    "Get transport information (status, endpoints, etc.)")
  (supports-feature? [transport feature]
    "Check if transport supports a feature (:streaming, :multiple-clients, :sessions, etc.)"))

;; =============================================================================
;; Transport Configuration
;; =============================================================================

(defn create-transport-config
  "Create transport configuration map"
  [transport-type & {:keys [port host endpoints features]
                     :or {port 58080  ; Use high port to avoid conflicts with popular services
                          host "localhost" 
                          endpoints {}
                          features #{}}}]
  {:transport-type transport-type
   :port port
   :host host
   :endpoints endpoints
   :features features})

;; =============================================================================
;; Transport Context
;; =============================================================================

(defn create-server-context
  "Create server context with tools and prompts"
  [& {:keys [tools prompts]
      :or {tools {}
           prompts {}}}]
  {:tools tools
   :prompts prompts
   :created-at (java.time.Instant/now)})

;; =============================================================================
;; Transport Registry
;; =============================================================================

(defonce ^:private transport-registry (atom {}))

(defn register-transport!
  "Register a transport implementation"
  [transport-type constructor-fn]
  (swap! transport-registry assoc transport-type constructor-fn))

(defn get-registered-transports
  "Get all registered transport types"
  []
  (keys @transport-registry))

(defn create-transport
  "Create a transport instance by type"
  [transport-type config]
  (if-let [constructor-fn (get @transport-registry transport-type)]
    (constructor-fn config)
    (throw (ex-info "Unknown transport type" 
                    {:transport-type transport-type
                     :available-transports (get-registered-transports)}))))

;; =============================================================================
;; Multi-Transport Server
;; =============================================================================

(defrecord MultiTransportServer [transports context running?]
  
  McpTransport
  (start! [this new-context]
    (when @running?
      (throw (ex-info "Server already running" {})))
    
    (log/log! {:level :info :msg "Starting multi-transport MCP server"
               :data {:transport-count (count transports)}})
    
    ;; Start all transports
    (doseq [[transport-type transport] transports]
      (try
        (start! transport new-context)
        (log/log! {:level :info :msg "Transport started"
                   :data {:transport-type transport-type}})
        (catch Exception e
          (log/log! {:level :error :msg "Failed to start transport"
                     :data {:transport-type transport-type
                            :error (.getMessage e)}})
          (throw e))))
    
    (reset! running? true)
    (reset! context new-context)
    this)
  
  (stop! [this]
    (when @running?
      (log/log! {:level :info :msg "Stopping multi-transport MCP server"})
      
      ;; Stop all transports
      (doseq [[transport-type transport] transports]
        (try
          (stop! transport)
          (log/log! {:level :info :msg "Transport stopped"
                     :data {:transport-type transport-type}})
          (catch Exception e
            (log/log! {:level :warn :msg "Error stopping transport"
                       :data {:transport-type transport-type
                              :error (.getMessage e)}}))))
      
      (reset! running? false)
      (reset! context nil))
    this)
  
  (info [_this]
    {:running? @running?
     :context @context
     :transports (into {} (map (fn [[type transport]]
                                [type (info transport)])
                              transports))})
  
  (supports-feature? [_this feature]
    ;; Return true if any transport supports the feature
    (some #(supports-feature? (second %) feature) transports)))

(defn create-multi-transport-server
  "Create a multi-transport server with specified transports"
  [transport-configs]
  (let [transports (into {} (map (fn [[transport-type config]]
                                  [transport-type (create-transport transport-type config)])
                                transport-configs))]
    (->MultiTransportServer transports (atom nil) (atom false))))

;; =============================================================================
;; Convenience Functions
;; =============================================================================

(defn start-mcp-server!
  "Start MCP server with specified transports and context"
  [transport-configs context]
  (let [server (create-multi-transport-server transport-configs)]
    (start! server context)
    server))

(defn create-context
  "Create context with all registered tools and prompts"
  []
  (create-server-context 
    :tools (dispatch/get-registered-tools)
    :prompts (dispatch/get-registered-prompts)))
