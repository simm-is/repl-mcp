(ns is.simm.repl-mcp.api
  "Clean API for repl-mcp library - essential functions for Clojure consumers"
  (:require [is.simm.repl-mcp.server :as server]
            [is.simm.repl-mcp.dispatch :as dispatch]
            [is.simm.repl-mcp.interactive :as interactive]
            [taoensso.telemere :as log]))

;; =============================================================================
;; Tool Registration API
;; =============================================================================

(defn register-tool!
  "Register a new MCP tool.
  
  Usage:
    (register-tool! :my-tool
                    \"Description of my tool\"
                    {:param1 {:type \"string\" :description \"Parameter description\"}}
                    (fn [tool-call context]
                      {:result \"tool output\" :status :success}))
  
  The handler function receives tool-call (with :name and :args) and context."
  [tool-name description parameters handler-fn]
  (interactive/register-tool! tool-name description parameters handler-fn))

(defn list-tools
  "List all registered tools."
  []
  (dispatch/get-registered-tools))

(defn get-tool
  "Get information about a specific tool."
  [tool-name]
  (get (dispatch/get-registered-tools) tool-name))

;; =============================================================================
;; Prompt Registration API
;; =============================================================================

(defn register-prompt!
  "Register a workflow prompt.
  
  Usage:
    (register-prompt! :my-workflow
                      \"Description\"
                      {:param1 {:type \"string\"}}
                      \"Prompt template with {{param1}}\")"
  [prompt-name description args template]
  (dispatch/register-prompt! prompt-name description args template))

(defn list-prompts
  "List all registered prompts."
  []
  (dispatch/get-registered-prompts))
