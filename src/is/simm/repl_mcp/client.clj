(ns is.simm.repl-mcp.client
  "MCP client abstraction and implementations for connecting to MCP servers"
  (:require [taoensso.telemere :as log]
            [clojure.data.json :as json])
  (:import [java.time Duration]))

;; =============================================================================
;; Client Protocol
;; =============================================================================

(defprotocol McpClient
  "Protocol for MCP client implementations"
  (connect! [client]
    "Connect to the MCP server and perform initialization")
  (disconnect! [client]
    "Disconnect from the MCP server")
  (connected? [client]
    "Check if client is connected and initialized")
  (call-tool [client tool-name args]
    "Call a tool on the server with given arguments")
  (get-prompt [client prompt-name args]
    "Get a prompt from the server with given arguments")
  (list-tools [client]
    "List all available tools on the server")
  (list-prompts [client]
    "List all available prompts on the server")
  (get-server-info [client]
    "Get server information and capabilities")
  (client-info [client]
    "Get client information and status"))

;; =============================================================================
;; Client Configuration
;; =============================================================================

(defn create-client-config
  "Create client configuration map"
  [client-type & {:keys [server-command base-url request-timeout init-timeout
                         client-name client-version]
                  :or {request-timeout 30
                       init-timeout 30
                       client-name "repl-mcp-client"
                       client-version "1.0.0"}}]
  {:client-type client-type
   :server-command server-command
   :base-url base-url
   :request-timeout request-timeout
   :init-timeout init-timeout
   :client-name client-name
   :client-version client-version})

;; =============================================================================
;; Result Processing Helpers
;; =============================================================================

(defn process-tool-result
  "Process tool call result into Clojure-friendly format"
  [java-result]
  (when java-result
    {:is-error (.isError java-result)
     :content (mapv #(.text %) (.content java-result))}))

(defn process-prompt-result
  "Process prompt result into Clojure-friendly format"
  [java-result]
  (when java-result
    {:description (.description java-result)
     :messages (mapv #(.content %) (.messages java-result))}))

(defn process-tools-list
  "Process tools list result into Clojure-friendly format"
  [java-result]
  (when java-result
    (mapv (fn [tool]
            {:name (.name tool)
             :description (.description tool)
             :input-schema (try
                            (json/read-str (.inputSchema tool) :key-fn keyword)
                            (catch Exception e
                              (log/log! {:level :warn :msg "Failed to parse tool schema"
                                         :data {:tool (.name tool) :error (.getMessage e)}})
                              {}))})
          (.tools java-result))))

(defn process-prompts-list
  "Process prompts list result into Clojure-friendly format" 
  [java-result]
  (when java-result
    (mapv (fn [prompt]
            {:name (.name prompt)
             :description (.description prompt)
             :arguments (try
                         (when-let [args (.arguments prompt)]
                           (json/read-str (str args) :key-fn keyword))
                         (catch Exception e
                           (log/log! {:level :warn :msg "Failed to parse prompt arguments"
                                      :data {:prompt (.name prompt) :error (.getMessage e)}})
                           {}))})
          (.prompts java-result))))

;; =============================================================================
;; Client Registry
;; =============================================================================

(defonce ^:private client-registry (atom {}))

(defn register-client!
  "Register a client implementation"
  [client-type constructor-fn]
  (swap! client-registry assoc client-type constructor-fn)
  (log/log! {:level :info :msg "Client registered"
             :data {:client-type client-type}}))

(defn get-registered-clients
  "Get all registered client types"
  []
  (keys @client-registry))

(defn create-client
  "Create a client instance by type"
  [client-type config]
  (if-let [constructor-fn (get @client-registry client-type)]
    (constructor-fn config)
    (throw (ex-info "Unknown client type"
                    {:client-type client-type
                     :available-clients (get-registered-clients)}))))

;; =============================================================================
;; Utility Functions
;; =============================================================================

(defn clojure-args->java-map
  "Convert Clojure args map to Java HashMap for MCP calls"
  [args]
  (let [java-map (java.util.HashMap.)]
    (doseq [[k v] args]
      (.put java-map (name k) v))
    java-map))

(defn with-timeout
  "Execute function with timeout, returning result or :timeout"
  [timeout-ms f]
  (let [future-result (future (f))
        result (deref future-result timeout-ms :timeout)]
    (when (= result :timeout)
      (future-cancel future-result))
    result))
