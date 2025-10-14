(ns is.simm.repl-mcp
  "Final simplified MCP server using direct mcp-toolkit integration"
  (:require 
   [is.simm.repl-mcp.server :as server]
   [is.simm.repl-mcp.tools :as tools]
   [is.simm.repl-mcp.logging :as logging]
   [is.simm.repl-mcp.transport.sse :as sse]
   [nrepl.server :as nrepl-server]
   [taoensso.telemere :as log]
   [clojure.tools.cli :as cli]
   [clojure.string :as str]
   [mcp-toolkit.json-rpc :as json-rpc]
   [jsonista.core :as j]
   [cider.nrepl :refer [cider-nrepl-handler]]
   [refactor-nrepl.middleware :refer [wrap-refactor]])
  (:gen-class)
  (:import (clojure.lang LineNumberingPushbackReader)
           (java.io OutputStreamWriter)))

;; ===============================================
;; Configuration and CLI
;; ===============================================

(def cli-options
  [["-p" "--nrepl-port PORT" "nREPL server port"
    :default 47888
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
   
   ["-h" "--http-port PORT" "HTTP server port for SSE transport"
    :default 18080
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
   
   ["-t" "--transport TRANSPORT" "Transport type (stdio or sse)"
    :default :stdio
    :parse-fn keyword
    :validate [#{:stdio :sse} "Must be either 'stdio' or 'sse'"]]
   
   ["-v" "--verbose" "Enable verbose logging"]
   
   ["-?" "--help" "Show help"]])

(defn usage [options-summary]
  (->> ["Final simplified repl-mcp server using direct mcp-toolkit integration"
        ""
        "Usage: repl-mcp-simple [options]"
        ""
        "Options:"
        options-summary
        ""
        "Examples:"
        "  repl-mcp-simple                    # Start with STDIO transport"
        "  repl-mcp-simple -t sse -p 19888   # Start with SSE transport (when implemented)"
        ""]
       (clojure.string/join "\n")))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (clojure.string/join "\n" errors)))

(defn validate-args
  "Validate command line arguments."
  [args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args cli-options)]
    (cond
      (:help options) ; help => exit OK with usage summary
      {:exit-message (usage summary) :ok? true}
      
      errors ; errors => exit with description of errors
      {:exit-message (error-msg errors)}
      
      :else ; success => run program with options
      {:options options})))

;; ===============================================
;; Server Lifecycle (Simplified)
;; ===============================================

(defonce server-state (atom nil))

(defn listen-messages 
  "Listen for JSON-RPC messages on stdin and handle them using mcp-toolkit"
  [context ^LineNumberingPushbackReader reader]
  (let [{:keys [send-message]} context
        json-mapper (j/object-mapper {:decode-key-fn keyword})]
    (loop []
      ;; line = nil means that the reader is closed
      (when-some [line (.readLine reader)]
        (let [message (try
                        ;; Parse JSON message per line
                        (log/log! {:level :debug :msg "Received STDIO line" :data {:line line}})
                        (j/read-value line json-mapper)
                        (catch Exception e
                          (log/log! {:level :error :msg "JSON parse error" 
                                     :data {:line line :error (.getMessage e)}})
                          (send-message json-rpc/parse-error-response)
                          nil))]
          (if (nil? message)
            (recur)
            (do
              (log/log! {:level :debug :msg "Parsed JSON message" :data {:message message}})
              (log/log! {:level :debug :msg "Calling json-rpc/handle-message" :data {:context-keys (keys context)}})
              (try
                (json-rpc/handle-message context message)
                (log/log! {:level :debug :msg "Finished json-rpc/handle-message"})
                (catch Exception e
                  (log/log! {:level :error :msg "Error handling message"
                             :data {:error (.getMessage e)
                                    :message message
                                    :exception-type (.getName (class e))}})
                  ;; Send error response if message has an ID
                  (when-let [msg-id (:id message)]
                    (send-message {:jsonrpc "2.0"
                                   :id msg-id
                                   :error {:code -32603
                                          :message "Internal error"
                                          :data {:error (.getMessage e)}}}))))
              (recur))))))))

(defn start-nrepl-server!
  "Start nREPL server with cider and refactor middleware"
  [port]
  (try
    (log/log! {:level :info :msg "Starting nREPL server" :data {:port port}})
    (let [middleware-stack (-> cider-nrepl-handler
                               (wrap-refactor))
          server (nrepl-server/start-server 
                  :port port
                  :handler middleware-stack
                  :bind "127.0.0.1")]
      (log/log! {:level :info :msg "nREPL server started" :data {:port port}})
      server)
    (catch Exception e
      (if (re-find #"Address already in use" (.getMessage e))
        (do
          (log/log! {:level :info :msg "nREPL server already running" :data {:port port}})
          nil) ; Server already running
        (throw e)))))

(defn start-mcp-server!
  "Start the simplified MCP server"
  [config]
  (log/log! {:level :info :msg "Starting simplified repl-mcp server" :data config})
  
  ;; Start nREPL server if needed
  (let [nrepl-server (start-nrepl-server! (:nrepl-port config))]
    
    ;; Give nREPL server time to start
    (Thread/sleep 1000)
    
    ;; Create instance using simplified API
    (let [instance-config {:tools (tools/get-tool-definitions)
                          :nrepl-config {:port (:nrepl-port config)
                                        :ip "127.0.0.1"}
                          :server-info {:name "repl-mcp-simple" :version "1.0.0"}}
          
          ;; Create instance directly - no complex lifecycle management
          instance (server/create-mcp-server-instance! instance-config)]
      
      ;; Store state for cleanup
      (reset! server-state {:nrepl-server nrepl-server
                           :instance instance
                           :config config})
      
      (log/log! {:level :info :msg "Simplified repl-mcp server started successfully" 
                 :data {:transport (:transport config)
                        :nrepl-port (:nrepl-port config)
                        :tool-count (count (tools/get-tool-definitions))}})
      
      {:nrepl-server nrepl-server :instance instance})))

(defn stop-mcp-server!
  "Stop the simplified MCP server"
  []
  (when-let [state @server-state]
    (log/log! {:level :info :msg "Stopping simplified repl-mcp server"})
    
    ;; Simple cleanup - instance is self-contained, no complex lifecycle
    (when-let [nrepl-server (:nrepl-server state)]
      (try
        (nrepl-server/stop-server nrepl-server)
        (log/log! {:level :info :msg "nREPL server stopped"})
        (catch Exception e
          (log/log! {:level :warn :msg "Error stopping nREPL server" 
                     :data {:error (.getMessage e)}}))))
    
    ;; Stop HTTP server if running
    (when-let [http-server (:http-server state)]
      (try
        (sse/stop-http-server! http-server)
        (log/log! {:level :info :msg "HTTP server stopped"})
        (catch Exception e
          (log/log! {:level :warn :msg "Error stopping HTTP server" 
                     :data {:error (.getMessage e)}}))))
    
    ;; Close nREPL clients
    (when-let [instance (:instance state)]
      (when-let [nrepl-config (get-in instance [:config :nrepl-config])]
        (log/log! {:level :info :msg "Server stopped"})))
    
    (reset! server-state nil)
    (log/log! {:level :info :msg "Simplified repl-mcp server stopped"})))

;; ===============================================
;; Main Entry Point (Simplified)
;; ===============================================

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn -main [& args]
  ;; Parse and validate arguments
  (let [{:keys [options exit-message ok?]} (validate-args args)]
    ;; Set up logging
    (logging/setup-file-logging! (= (:transport options) :stdio))

    (if exit-message
      (exit (if ok? 0 1) exit-message)

      ;; Configure logging level
      (do
        (when (:verbose options)
          (log/set-min-level! :debug))

        ;; Add shutdown hook
        (.addShutdownHook (Runtime/getRuntime)
                          (Thread. #(stop-mcp-server!)))

        ;; Start server
        (try
          (let [config {:nrepl-port (:nrepl-port options)
                        :http-port (:http-port options)
                        :transport (:transport options)}]

            (start-mcp-server! config)

            ;; For STDIO transport, we're ready for MCP communication
            ;; For SSE transport, we would need to implement the HTTP server
            (case (:transport options)
              :stdio
              (do
                (log/log! {:level :info :msg "STDIO MCP server ready for connections"})
                ;; Start STDIO JSON-RPC transport using mcp-toolkit pattern
                (let [instance (:instance @server-state)
                      session (:session instance)
                      nrepl-client (:nrepl-client instance)
                      context {:session session
                               :nrepl-client nrepl-client
                               :send-message (let [^OutputStreamWriter writer *out*
                                                   json-mapper (j/object-mapper {:encode-key-fn name})]
                                               (fn [message]
                                                 (.write writer (j/write-value-as-string message json-mapper))
                                                 (.write writer "\n")
                                                 (.flush writer)))}
                      reader (LineNumberingPushbackReader. *in*)]
                  (listen-messages context reader)))

              :sse
              (do
                (log/log! {:level :info :msg "SSE MCP server ready" :data {:http-port (:http-port options)}})
                ;; Start HTTP+SSE server
                (let [instance (:instance @server-state)
                      mcp-context {:session (:session instance)
                                   :nrepl-client (:nrepl-client instance)}
                      http-server (sse/start-http-server! mcp-context (:http-port options))]
                  (swap! server-state assoc :http-server http-server)
                  (log/log! {:level :info :msg "HTTP+SSE server started" 
                             :data {:port (:http-port options) :url (str "http://127.0.0.1:" (:http-port options) "/sse")}})
                  ;; Keep the main thread alive
                  @(promise)))))
          (catch Exception e
            (log/log! {:level :error :msg "Failed to start simplified server"
                       :data {:error (.getMessage e)}})
            (exit 1 (str "Error: " (.getMessage e)))))))))

;; ===============================================
;; REPL Development Helpers
;; ===============================================

(comment
  ;; Start server for development
  (start-mcp-server! {:nrepl-port 47888
                     :transport :stdio})
  
  ;; Stop server
  (stop-mcp-server!)
  
  ;; Test dynamic tool management
  (let [instance (:instance @server-state)]
    (println "Current tools:" (count (tools/get-tool-definitions)))
    
    ;; Add a custom tool
    (server/add-tool! instance
      {:name "hello"
       :description "Say hello"
       :inputSchema {:type "object"
                    :properties {:name {:type "string"}}
                    :required ["name"]}
       :tool-fn (fn [context {:keys [name]}]
                 {:content [{:type "text" :text (str "Hello, " name "!")}]})})
    
    (println "After adding hello tool: tool added successfully")
    
    ;; Remove the tool
    (server/remove-tool! instance "hello")
    
    (println "After removing hello tool: tool removed successfully")))