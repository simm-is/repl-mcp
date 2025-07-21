(ns is.simm.repl-mcp.server
  "Multi-instance MCP server API working directly with mcp-toolkit"
  (:require 
   [is.simm.repl-mcp.tools :as tools]
   [mcp-toolkit.server :as mcp-server]
   [mcp-toolkit.json-rpc :as json-rpc]
   [promesa.core :as p]
   [jsonista.core :as j]
   [nrepl.core :as nrepl]
   [taoensso.telemere :as log]
   [org.httpkit.server :as http-kit]
   [reitit.ring :as reitit]
   [clojure.spec.alpha :as s]))

;; ===============================================
;; Configuration Specs
;; ===============================================

(s/def ::instance-config map?) ; Allow any config map for flexibility

;; ===============================================
;; nREPL Client Management
;; ===============================================

(defonce nrepl-clients (atom {}))

(defn create-or-get-nrepl-client
  "Create or reuse nREPL client for given configuration"
  [{:keys [port ip] :or {port 47888 ip "127.0.0.1"}}]
  (let [client-key [port ip]]
    (if-let [existing-client (get @nrepl-clients client-key)]
      (do
        (log/log! {:level :info :msg "Reusing existing nREPL client" 
                   :data {:port port :ip ip}})
        existing-client)
      (try
        (log/log! {:level :info :msg "Creating new nREPL client" 
                   :data {:port port :ip ip}})
        (let [transport (nrepl/connect :port port :host ip)
              client (nrepl/client transport Long/MAX_VALUE)]
          (swap! nrepl-clients assoc client-key client)
          (log/log! {:level :info :msg "nREPL client created successfully" 
                     :data {:port port :ip ip}})
          client)
        (catch Exception e
          (log/log! {:level :error :msg "Failed to create nREPL client" 
                     :data {:port port :ip ip :error (.getMessage e)}})
          (throw e))))))

;; ===============================================
;; Core Instance API
;; ===============================================

(defn create-mcp-server-instance!
  "Create an MCP server instance that works directly with mcp-toolkit.
   Returns an instance object that can be used directly with tool functions.
   
   Config map should contain:
   - :tools - Vector of tool definitions (optional, defaults to built-in tools)
   - :prompts - Vector of prompt definitions (optional) 
   - :resources - Vector of resource definitions (optional)
   - :server-info - Server info map (optional)
   - :nrepl-client - nREPL client for tools that need it (optional)
   - :nrepl-config - Configuration for nREPL client (optional)
   
   Example:
   (create-mcp-server-instance! 
     {:tools [{:name \"echo\" :description \"Echo tool\" 
               :inputSchema {...} :tool-fn (fn [ctx args] ...)}]
      :nrepl-config {:port 47888}
      :server-info {:name \"my-server\" :version \"1.0.0\"}})"
  [config]
  (log/log! {:level :info :msg "Creating MCP server instance" :data {:config config}})
  
  (try
    (let [;; Get or create nREPL client if needed
          nrepl-client (when-let [nrepl-config (:nrepl-config config)]
                        (create-or-get-nrepl-client nrepl-config))
          
          ;; Prepare session config with defaults
          session-config {:tools (or (:tools config) (tools/get-tool-definitions))
                         :prompts (or (:prompts config) [])
                         :resources (or (:resources config) [])
                         :server-info (or (:server-info config) 
                                         {:name "repl-mcp" :version "1.0.0"})}
          
          ;; Create session state using mcp-toolkit
          session-state (mcp-server/create-session session-config)
          
          ;; Wrap in atom as expected by mcp-toolkit functions
          session-atom (atom session-state)
          
          ;; Create the context that mcp-toolkit functions expect
          mcp-context {:session session-atom
                       :nrepl-client nrepl-client}
          
          ;; Create instance object
          instance {:context mcp-context
                   :session session-atom
                   :config config
                   :nrepl-client nrepl-client
                   :created-at (java.time.Instant/now)}]
      
      (log/log! {:level :info :msg "MCP server instance created successfully" 
                 :data {:has-nrepl-client (some? nrepl-client)
                        :tool-count (count (:tools session-config))}})
      
      instance)
    
    (catch Exception e
      (log/log! {:level :error :msg "Failed to create MCP server instance" 
                 :data {:error (.getMessage e)}})
      (throw e))))

;; ===============================================
;; Dynamic Tool Management (Direct mcp-toolkit)
;; ===============================================

(defn add-tool!
  "Add a tool to the MCP server instance.
   
   instance: Instance object returned by create-mcp-server-instance!
   tool: Tool definition map with :name, :description, :inputSchema, :tool-fn
   
   Example:
   (add-tool! instance
     {:name \"greet\" 
      :description \"Greet someone\"
      :inputSchema {:type \"object\"
                   :properties {:name {:type \"string\"}}
                   :required [\"name\"]}
      :tool-fn (fn [context args]
                {:content [{:type \"text\" :text (str \"Hello, \" (:name args) \"!\")}]})})"
  [instance tool]
  (try
    (log/log! {:level :info :msg "Adding tool to instance" 
               :data {:tool-name (:name tool)}})
    
    ;; Use mcp-toolkit's add-tool function directly
    (mcp-server/add-tool (:context instance) tool)
    
    (log/log! {:level :info :msg "Tool added successfully" 
               :data {:tool-name (:name tool)}})
    :added
    
    (catch Exception e
      (log/log! {:level :error :msg "Failed to add tool" 
                 :data {:tool-name (:name tool) :error (.getMessage e)}})
      (throw e))))

(defn remove-tool!
  "Remove a tool from the MCP server instance.
   
   instance: Instance object returned by create-mcp-server-instance!
   tool-name: String name of tool to remove"
  [instance tool-name]
  (try
    (log/log! {:level :info :msg "Removing tool from instance" 
               :data {:tool-name tool-name}})
    
    ;; Use mcp-toolkit's remove-tool function directly
    (mcp-server/remove-tool (:context instance) {:name tool-name})
    
    (log/log! {:level :info :msg "Tool removed successfully" 
               :data {:tool-name tool-name}})
    :removed
    
    (catch Exception e
      (log/log! {:level :error :msg "Failed to remove tool" 
                 :data {:tool-name tool-name :error (.getMessage e)}})
      (throw e))))

(defn notify-tool-list-changed!
  "Notify clients that the tool list has changed.
   
   instance: Instance object returned by create-mcp-server-instance!
   
   Note: This is automatically called by add-tool! and remove-tool!"
  [instance]
  (try
    (log/log! {:level :info :msg "Notifying tool list changed"})
    
    ;; Use mcp-toolkit's notify function directly
    (mcp-server/notify-tool-list-changed (:context instance))
    
    (log/log! {:level :info :msg "Tool list change notification sent"})
    :notified
    
    (catch Exception e
      (log/log! {:level :error :msg "Failed to notify tool list changed" 
                 :data {:error (.getMessage e)}})
      (throw e))))

;; ===============================================
;; Instance Information
;; ===============================================

(defn get-tools
  "Get all tools from an instance"
  [instance]
  (-> @(:session instance) :tool-by-name vals vec))

(defn get-tool
  "Get a specific tool by name from an instance"
  [instance tool-name]
  (-> @(:session instance) :tool-by-name (get tool-name)))

(defn list-tool-names
  "List all tool names in an instance"
  [instance]
  (-> @(:session instance) :tool-by-name keys vec))

(defn instance-info
  "Get information about an instance"
  [instance]
  (select-keys instance [:config :created-at :nrepl-client]))

;; ===============================================
;; Convenience Functions
;; ===============================================

(defn create-stdio-instance!
  "Create an instance configured for STDIO transport"
  [& {:keys [tools nrepl-config]
      :or {tools (tools/get-tool-definitions)
           nrepl-config {:port 47888}}}]
  (create-mcp-server-instance!
    {:tools tools
     :nrepl-config nrepl-config
     :server-info {:name "repl-mcp-stdio" :version "1.0.0"}}))

(defn create-sse-instance!
  "Create an instance configured for SSE transport"
  [& {:keys [tools nrepl-config http-port]
      :or {tools (tools/get-tool-definitions)
           nrepl-config {:port 47888}
           http-port 18080}}]
  (create-mcp-server-instance!
    {:tools tools
     :nrepl-config nrepl-config
     :transport-config {:type :sse :port http-port :ip "127.0.0.1"}
     :server-info {:name "repl-mcp-sse" :version "1.0.0"}}))

;; ===============================================
;; Cleanup
;; ===============================================

(defn close-nrepl-client!
  "Close a specific nREPL client"
  [{:keys [port ip] :or {port 47888 ip "127.0.0.1"}}]
  (let [client-key [port ip]]
    (when-let [client (get @nrepl-clients client-key)]
      (try
        (log/log! {:level :info :msg "Closing nREPL client" 
                   :data {:port port :ip ip}})
        (.close client)
        (swap! nrepl-clients dissoc client-key)
        (log/log! {:level :info :msg "nREPL client closed" 
                   :data {:port port :ip ip}})
        (catch Exception e
          (log/log! {:level :warn :msg "Error closing nREPL client" 
                     :data {:port port :ip ip :error (.getMessage e)}}))))))

(defn close-all-nrepl-clients!
  "Close all nREPL clients"
  []
  (doseq [[client-key client] @nrepl-clients]
    (try
      (.close client)
      (catch Exception e
        (log/log! {:level :warn :msg "Error closing nREPL client during shutdown" 
                   :data {:client-key client-key :error (.getMessage e)}}))))
  (reset! nrepl-clients {}))