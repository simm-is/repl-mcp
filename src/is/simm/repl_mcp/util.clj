(ns is.simm.repl-mcp.util
  "Utility functions for MCP server components"
  (:require [is.simm.repl-mcp.dispatch :as dispatch]
            [is.simm.repl-mcp.logging :as logging]
            [taoensso.telemere :as log]
            [clojure.data.json :as json])
  (:import [io.modelcontextprotocol.server McpServerFeatures$SyncToolSpecification McpServerFeatures$SyncPromptSpecification]
           [io.modelcontextprotocol.spec McpSchema$TextContent McpSchema$Tool McpSchema$CallToolResult 
                                         McpSchema$Prompt McpSchema$GetPromptResult McpSchema$PromptArgument]
           [java.util.function BiFunction]))

;; =============================================================================
;; MCP Specification Utility Functions
;; =============================================================================

(defn create-tool-specification
  "Create MCP SyncToolSpecification for a tool"
  [tool context]
  (let [mcp-tool (dispatch/tool-spec->mcp-tool tool)
        tool-name (:name mcp-tool)
        tool-desc (:description mcp-tool)
        tool-schema (:inputSchema mcp-tool)
        
        ;; Create the tool handler function
        tool-handler (reify BiFunction
                       (apply [_this exchange args]
                         (try
                           (log/log! {:level :info :msg "HTTP+SSE tool handler invoked" 
                                      :data {:tool-name tool-name 
                                             :exchange-type (str (type exchange))
                                             :args-type (str (type args))
                                             :args-keys (when (instance? java.util.Map args) (keys args))
                                             :thread-id (.getId (Thread/currentThread))}})
                           (let [clj-tool-call {:tool-name (keyword tool-name)
                                                :args (into {} args)}
                                 _ (log/log! {:level :debug :msg "Converted tool call" :data {:tool-name tool-name :clj-tool-call clj-tool-call}})
                                 result (dispatch/handle-tool-call clj-tool-call context)
                                 _ (log/log! {:level :info :msg "Tool execution completed" 
                                              :data {:tool-name tool-name 
                                                     :result-status (:status result)
                                                     :result-keys (when (map? result) (keys result))
                                                     :success? (= (:status result) :success)}})
                                 _ (logging/log-tool-call tool-name args result)]
                             (log/log! {:level :info :msg "Creating MCP result object" 
                                        :data {:tool-name tool-name :result-status (:status result)}})
                             (let [mcp-result (if (= (:status result) :error)
                                                (McpSchema$CallToolResult.
                                                 (java.util.List/of
                                                  (McpSchema$TextContent. (:error result)))
                                                 true)
                                                (McpSchema$CallToolResult.
                                                 (java.util.List/of
                                                  (McpSchema$TextContent.
                                                   (str (cond
                                                          ;; Handle plain string results
                                                          (string? result) result
                                                          ;; Handle map results with summary/value/output
                                                          (map? result) (cond
                                                                         (:summary result) (:summary result)
                                                                         (contains? result :value) (pr-str (:value result))
                                                                         (contains? result :output) (str (:output result))
                                                                         :else (json/write-str result))
                                                          ;; Default fallback
                                                          :else (str result)))))
                                                 false))]
                               (log/log! {:level :info :msg "MCP result created successfully" 
                                          :data {:tool-name tool-name :result-type (str (type mcp-result))}})
                               mcp-result))
                           (catch Exception e
                             (log/log! {:level :error :msg "Error in HTTP+SSE tool handler" 
                                        :data {:tool-name tool-name :error (.getMessage e) :stack-trace (ex-data e)}})
                             (McpSchema$CallToolResult. 
                               (java.util.List/of 
                                 (McpSchema$TextContent. (.getMessage e)))
                               true)))))
        
        ;; Create the MCP Tool schema with all required fields
        mcp-tool-obj (McpSchema$Tool. tool-name tool-desc (json/write-str tool-schema))]
    
    (McpServerFeatures$SyncToolSpecification. mcp-tool-obj tool-handler)))

(defn create-workflow-prompt-specification
  "Create MCP SyncPromptSpecification for a workflow prompt"
  [prompt-name prompt-desc prompt-args prompt-template]
  (let [;; Create the prompt handler function
        prompt-handler (reify BiFunction
                         (apply [_this _exchange request]
                           (try
                             (let [;; Extract arguments from the request (if any)
                                   args (or (get request "params") {})
                                   ;; Render the template with arguments
                                   rendered-prompt (dispatch/render-prompt prompt-template args)]
                               (McpSchema$GetPromptResult. 
                                 "Prompt rendered successfully"
                                 (java.util.List/of 
                                   (McpSchema$TextContent. rendered-prompt))))
                             (catch Exception e
                               (log/log! {:level :error :msg "Error handling workflow prompt request" 
                                         :data {:error (.getMessage e) :prompt-name prompt-name}})
                               (McpSchema$GetPromptResult. 
                                 "Error rendering prompt"
                                 (java.util.List/of 
                                   (McpSchema$TextContent. (.getMessage e))))))))
        
        ;; Create the MCP Prompt schema with arguments
        prompt-args-list (java.util.ArrayList.)
        _ (doseq [[arg-name arg-spec] prompt-args]
            (let [arg-obj (McpSchema$PromptArgument. 
                            (name arg-name)
                            (get arg-spec "description" "")
                            (get arg-spec "required" false))]
              (.add prompt-args-list arg-obj)))
        mcp-prompt-obj (McpSchema$Prompt. (name prompt-name) prompt-desc prompt-args-list)]
    
    (McpServerFeatures$SyncPromptSpecification. mcp-prompt-obj prompt-handler)))