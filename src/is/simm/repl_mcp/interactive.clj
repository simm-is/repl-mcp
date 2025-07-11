(ns is.simm.repl-mcp.interactive
  (:require [is.simm.repl-mcp.dispatch :as dispatch]
            [taoensso.telemere :as log]))

(defn register-tool!
  "Register a new MCP tool with the running server.
  
  Usage:
  (register-tool! :my-tool
    \"Description of what this tool does\"
    {:param1 {:type \"string\" :description \"First parameter\"}
     :param2 {:type \"number\" :optional true :description \"Optional second parameter\"}}
    (fn [tool-call context]
      ;; Tool implementation
      {:result \"some result\" :status :success})
    :tags #{:development :production}       ; Optional tags
    :dependencies #{:nrepl})                ; Optional dependencies
  
  Or with all parameters as a map:
  (register-tool! :my-tool
    {:description \"Description of what this tool does\"
     :parameters {:param1 {:type \"string\" :description \"First parameter\"}}
     :handler (fn [tool-call context] {:result \"some result\" :status :success})
     :tags #{:development}
     :dependencies #{:nrepl}})"
  [tool-name & args]
  (let [tool-spec (if (= 1 (count args))
                    ;; Single map argument
                    (let [spec (first args)]
                      {:name tool-name
                       :description (:description spec)
                       :parameters (:parameters spec)
                       :handler (:handler spec)
                       :tags (or (:tags spec) #{})
                       :dependencies (or (:dependencies spec) #{})})
                    ;; Multiple arguments (backward compatibility)
                    (let [[docstring params handler-fn & {:keys [tags dependencies]}] args]
                      {:name tool-name
                       :description docstring
                       :parameters params
                       :handler handler-fn
                       :tags (or tags #{})
                       :dependencies (or dependencies #{})}))]
    
    ;; Define the multimethod implementation
    (defmethod dispatch/handle-tool-call tool-name
      [tool-call context]
      ((:handler tool-spec) tool-call context))
    
    ;; Register the tool with tags and dependencies
    (dispatch/register-tool! tool-spec)
    
    ;; Note: Server update would need to be handled externally
    ;; when server namespace is available
    
    (log/log! {:level :info :msg "Defined and registered tool" 
               :data {:tool-name tool-name
                      :tags (:tags tool-spec)
                      :dependencies (:dependencies tool-spec)}})
    tool-name))

(defn unregister-tool!
  "Remove a tool definition and unregister it from the server"
  [tool-name]
  ;; Remove the multimethod implementation
  (remove-method dispatch/handle-tool-call tool-name)
  
  ;; Unregister the tool
  (dispatch/unregister-tool! tool-name)
  
  ;; Note: Server update would need to be handled externally
  ;; when server namespace is available
  
  (log/log! {:level :info :msg "Removed tool" :data {:tool-name tool-name}})
  tool-name)

(defn list-tools
  "List all currently registered tools"
  []
  (let [tools (dispatch/get-registered-tools)]
    (doseq [[tool-name tool-spec] tools]
      (println (format "%-20s %s" tool-name (:description tool-spec))))
    (keys tools)))

(defn tool-info
  "Get detailed information about a specific tool"
  [tool-name]
  (dispatch/get-tool-spec tool-name))
