(ns is.simm.repl-mcp
  (:require [is.simm.repl-mcp.server :as server]
            [is.simm.repl-mcp.api :as api]
            [is.simm.repl-mcp.logging :as logging]
            [taoensso.telemere :as log]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:gen-class))

;; Setup file-only logging immediately to avoid stdout contamination
;; This must happen before any tool loading that might trigger logging
(logging/setup-file-logging!)

;; Now require tools AFTER logging is configured for file-only output
(require '[is.simm.repl-mcp.tools.eval])
(require '[is.simm.repl-mcp.tools.refactor])
(require '[is.simm.repl-mcp.tools.structural-edit])
(require '[is.simm.repl-mcp.tools.cider-nrepl])
(require '[is.simm.repl-mcp.tools.function-refactor])
(require '[is.simm.repl-mcp.tools.test-generation])

(defn get-prompt-args
  "Get argument definitions for specific prompts"
  [prompt-name]
  (case prompt-name
    :tdd_workflow {"function-name" {"description" "Name of the function to implement" "required" true}
                   "namespace" {"description" "Namespace for the function" "required" true}
                   "description" {"description" "Description of what the function should do" "required" true}}
    :debug_function {"function-name" {"description" "Name of the function to debug" "required" true}
                     "namespace" {"description" "Namespace containing the function" "required" true}
                     "issue" {"description" "Description of the issue" "required" true}
                     "expected" {"description" "Expected behavior" "required" true}
                     "actual" {"description" "Actual behavior" "required" true}
                     "file-path" {"description" "Path to the file containing the function" "required" false}
                     "project-root" {"description" "Root directory of the project" "required" false}}
    :refactor_extract_function {"source-function" {"description" "Name of the source function" "required" true}
                                "namespace" {"description" "Namespace containing the function" "required" true}
                                "new-function-name" {"description" "Name for the new extracted function" "required" true}
                                "code-to-extract" {"description" "Code snippet to extract" "required" true}
                                "file-path" {"description" "Path to the source file" "required" true}}
    {}))

(defn load-prompts-from-resources!
  "Load workflow prompts from resources/prompts/workflow directory"
  []
  (let [prompts-dir "prompts/workflow/"
        prompts-resource (io/resource prompts-dir)]
    (when prompts-resource
      (let [prompt-files (-> prompts-resource
                            io/file
                            .listFiles
                            seq)]
        (when prompt-files
          (doseq [file prompt-files
                  :when (str/ends-with? (.getName file) ".mustache")]
            (let [prompt-name (-> (.getName file)
                                 (str/replace #"\.mustache$" "")
                                 (str/replace #"-" "_")
                                 keyword)
                  template (slurp file)
                  prompt-args (get-prompt-args prompt-name)]
              (api/register-prompt! prompt-name
                                   (str "Workflow prompt: " (.getName file))
                                   prompt-args
                                   template)
              (log/log! {:level :info :msg "Loaded workflow prompt" :data {:prompt-name prompt-name :file (.getName file) :args (keys prompt-args)}}))))))))

;; Load workflow prompts after tools are loaded
(load-prompts-from-resources!)

(defn start-server!
  "Start the MCP server with transport configuration"
  [& {:keys [nrepl-port transports] :or {nrepl-port 17888 transports #{:stdio}}}]
  (log/log! {:level :info :msg "Starting repl-mcp server" :data {:nrepl-port nrepl-port :transports transports}})
  (server/start-mcp-server! :nrepl-port nrepl-port :transports transports))

(defn stop-server!
  "Stop the MCP server"
  []
  (log/log! {:level :info :msg "Stopping repl-mcp server"})
  (server/stop-mcp-server!))

(defn restart-server!
  "Restart the MCP server"
  []
  (log/log! {:level :info :msg "Restarting repl-mcp server"})
  (server/restart-mcp-server!))

(defn server-info
  "Get current server information"
  []
  (server/server-info))

(defn list-tools
  "List all available tools"
  []
  (api/list-tools))

(defn tool-info
  "Get information about a specific tool"
  [tool-name]
  (api/get-tool tool-name))

(defn tool-help
  "Get basic information for a specific tool"
  [tool-name]
  (when-let [tool-info (api/get-tool tool-name)]
    (:description tool-info)))

(defn list-prompts
  "List available MCP workflow prompts"
  []
  (api/list-prompts))

(defn parse-args
  "Parse command line arguments"
  [args]
  (let [parsed-args (loop [remaining args
                          opts {:nrepl-port nil :transports #{:stdio} :command :start}]
                     (if (empty? remaining)
                       opts
                       (let [arg (first remaining)
                             rest-args (rest remaining)]
                         (cond
                           ;; nREPL port flag
                           (= arg "--nrepl-port")
                           (if (and (seq rest-args) (re-matches #"\d+" (first rest-args)))
                             (recur (rest rest-args) (assoc opts :nrepl-port (Integer/parseInt (first rest-args))))
                             (recur rest-args (assoc opts :nrepl-port-missing-value true)))
                           
                           ;; Transport options
                           (= arg "--stdio-only")
                           (recur rest-args (assoc opts :transports #{:stdio}))
                           
                           (= arg "--http-only") 
                           (recur rest-args (assoc opts :transports #{:http}))
                           
                           (= arg "--dual-transport")
                           (recur rest-args (assoc opts :transports #{:stdio :http}))
                           
                           ;; Introspection commands
                           (= arg "--list-tools")
                           (recur rest-args (assoc opts :command :list-tools))
                           
                           (= arg "--list-prompts")
                           (recur rest-args (assoc opts :command :list-prompts))
                           
                           (= arg "--tool-help")
                           (if (seq rest-args)
                             (recur (rest rest-args) (assoc opts :command :tool-help :tool-name (first rest-args)))
                             (recur rest-args (assoc opts :command :tool-help-missing-name)))
                           
                           (= arg "--help")
                           (recur rest-args (assoc opts :command :help))
                           
                           ;; Skip unknown arguments
                           :else
                           (recur rest-args opts)))))]
    parsed-args))

(defn print-help
  "Print help information"
  []
  (println "repl-mcp - Model Context Protocol server for Clojure development")
  (println)
  (println "Usage:")
  (println "  clojure -M:run [options]")
  (println)
  (println "Server Options:")
  (println "  --nrepl-port NUM Start nREPL server on specific port (default: 17888)")
  (println "  --stdio-only     Start with STDIO transport only (default)")
  (println "  --http-only      Start with HTTP+SSE transport only (port 18080)")
  (println "  --dual-transport Start with both STDIO and HTTP+SSE transports")
  (println)
  (println "Introspection Options:")
  (println "  --list-tools     List all available tools")
  (println "  --list-prompts   List all available prompts")
  (println "  --tool-help NAME Show help for specific tool")
  (println "  --help           Show this help message")
  (println)
  (println "Examples:")
  (println "  clojure -M:run                         # Start with STDIO transport")
  (println "  clojure -M:run --http-only              # Start with HTTP transport only")
  (println "  clojure -M:run --nrepl-port 7890 --dual-transport  # Custom nREPL port with both transports")
  (println "  clojure -M:run --list-tools             # List all available tools")
  (println "  clojure -M:run --tool-help eval         # Show help for eval tool"))

(defn print-tools
  "Print all available tools"
  []
  (println "Available Tools:")
  (println)
  (let [tools (list-tools)]
    (doseq [[tool-name tool-info] tools]
      (println (format "  %-25s %s" (name tool-name) (:description tool-info))))))

(defn print-prompts  
  "Print all available prompts"
  []
  (println "Available Prompts:")
  (println)
  (let [prompts (list-prompts)]
    (if (empty? prompts)
      (println "  No prompts currently registered")
      (doseq [[prompt-name prompt-info] prompts]
        (println (format "  %-25s %s" (name prompt-name) (:description prompt-info)))))))

(defn print-tool-help
  "Print help for a specific tool"
  [tool-name]
  (if-let [tool-data (tool-info (keyword tool-name))]
    (do
      (println (format "Tool: %s" tool-name))
      (println (format "Description: %s" (:description tool-data)))
      (when-let [params (:parameters tool-data)]
        (println "Parameters:")
        (doseq [[param-name param-info] params]
          (println (format "  %-15s %s (%s)" 
                          (name param-name) 
                          (get param-info "description" "No description") 
                          (get param-info "type" "unknown"))))))
    (println (format "Tool '%s' not found. Use --list-tools to see available tools." tool-name))))

(defn -main
  "Main entry point - starts the server or runs introspection commands
  
  Usage:
    clojure -M:run [options]
    
  See --help for full usage information."
  [& args]
  (let [{:keys [command nrepl-port transports tool-name nrepl-port-missing-value]} (parse-args args)
        nrepl-port (or nrepl-port 17888)]
    
    ;; Check for argument parsing errors
    (when nrepl-port-missing-value
      (println "Error: --nrepl-port requires a numeric value")
      (println "Usage: clojure -M:run --nrepl-port PORT")
      (System/exit 1))
    
    (case command
      :help
      (print-help)
      
      :list-tools
      (do 
        ;; Need to initialize tools for introspection
        (print-tools))
      
      :list-prompts
      (do
        ;; Need to initialize tools for introspection
        (print-prompts))
      
      :tool-help
      (if tool-name
        (print-tool-help tool-name)
        (do
          (println "Error: --tool-help requires a tool name")
          (println "Usage: clojure -M:run --tool-help TOOL_NAME")
          (println "Use --list-tools to see available tools")
          (System/exit 1)))
      
      :tool-help-missing-name
      (do
        (println "Error: --tool-help requires a tool name")
        (println "Usage: clojure -M:run --tool-help TOOL_NAME")
        (System/exit 1))
      
      :start
      (do
        ;; Logging is already setup at namespace load time
        (start-server! :nrepl-port nrepl-port :transports transports)
        
        ;; Do not print to stdout when using STDIO transport - 
        ;; stdout is reserved for JSON-RPC messages only
        ;; All status/debug info goes to log file instead
        (log/log! {:level :info :msg "MCP server started successfully"})
        (log/log! {:level :info :msg "Available tools" :data {:tools (keys (list-tools))}})
        (log/log! {:level :info :msg "Available prompts" :data {:prompts (keys (list-prompts))}})
        (log/log! {:level :info :msg "Server ready for connections" :data {:transports transports}})
        
        ;; Keep the main thread alive
        (while true
          (Thread/sleep 1000))))))
