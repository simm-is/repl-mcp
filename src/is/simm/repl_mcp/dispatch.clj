(ns is.simm.repl-mcp.dispatch
  (:require [taoensso.telemere :as log]
            [clojure.data.json :as json]
            [pogonos.core :as pogonos]))


(defmulti handle-tool-call 
  "Core dispatch for MCP tool calls. Dispatch on :tool-name"
  (fn [tool-call _context] (:tool-name tool-call)))

(defmethod handle-tool-call :default
  [tool-call _context]
  (log/log! {:level :warn :msg "Unknown tool" :data {:tool-name (:tool-name tool-call)}})
  {:error "Unknown tool" 
   :tool-name (:tool-name tool-call)
   :status :error})


;; Tool registry for dynamic MCP server updates
(defonce tool-registry (atom {}))
;; Prompt registry for workflow prompts
(defonce prompt-registry (atom {}))

(defn register-tool!
  "Register a new tool with the MCP server
  
  Tool spec can include:
  :name - Tool name (required)
  :description - Tool description (required)
  :parameters - Tool parameters (required)
  :handler - Tool handler function (required)
  :tags - Set of tags for categorization (optional)
  :dependencies - Set of dependencies required (optional)"
  [tool-spec]
  (let [enhanced-spec (merge tool-spec
                             {:tags (or (:tags tool-spec) #{})
                              :dependencies (or (:dependencies tool-spec) #{})
                              :registered-at (java.time.Instant/now)})]
    (swap! tool-registry assoc (:name enhanced-spec) enhanced-spec)
    (log/log! {:level :info :msg "Registered tool" 
               :data {:tool-name (:name enhanced-spec)
                      :tags (:tags enhanced-spec)
                      :dependencies (:dependencies enhanced-spec)}})
    enhanced-spec))

(defn unregister-tool!
  "Unregister a tool from the MCP server"
  [tool-name]
  (swap! tool-registry dissoc tool-name)
  (log/log! {:level :info :msg "Unregistered tool" :data {:tool-name tool-name}}))

(defn get-registered-tools
  "Get all currently registered tools"
  []
  @tool-registry)

(defn register-prompt!
  "Register a new workflow prompt"
  [prompt-name description args template]
  (let [prompt-spec {:name prompt-name
                     :description description
                     :args args
                     :template template}]
    (swap! prompt-registry assoc prompt-name prompt-spec)
    (log/log! {:level :info :msg "Registered prompt" :data {:prompt-name prompt-name}})
    prompt-spec))

(defn unregister-prompt!
  "Unregister a workflow prompt"
  [prompt-name]
  (swap! prompt-registry dissoc prompt-name)
  (log/log! {:level :info :msg "Unregistered prompt" :data {:prompt-name prompt-name}}))

(defn get-registered-prompts
  "Get all currently registered prompts"
  []
  @prompt-registry)

(defn get-tool-spec
  "Get specification for a specific tool"
  [tool-name]
  (get @tool-registry tool-name))

;; =============================================================================
;; Tag-Based Tool Filtering
;; =============================================================================

(defn get-tool-tags
  "Get tags for a specific tool"
  [tool-name]
  (get-in @tool-registry [tool-name :tags]))

(defn get-tool-dependencies
  "Get dependencies for a specific tool"
  [tool-name]
  (get-in @tool-registry [tool-name :dependencies]))

(defn filter-tools-by-tags
  "Get tools that have any of the specified tags"
  [active-tags]
  (let [tag-set (set active-tags)
        tools @tool-registry]
    (->> tools
         (filter (fn [[_name spec]]
                   (some (:tags spec) tag-set)))
         (into {}))))

(defn filter-tools-by-dependencies
  "Get tools where all dependencies are satisfied"
  [available-dependencies]
  (let [dep-set (set available-dependencies)
        tools @tool-registry]
    (->> tools
         (filter (fn [[_name spec]]
                   (every? dep-set (:dependencies spec))))
         (into {}))))

(defn filter-tools
  "Filter tools by multiple criteria
  
  Options:
  :include-tags - Include tools with any of these tags
  :exclude-tags - Exclude tools with any of these tags  
  :dependencies - Only include tools where all dependencies are satisfied
  :require-all-tags - If true, require ALL include-tags (AND logic vs OR)"
  [& {:keys [include-tags exclude-tags dependencies require-all-tags]
      :or {include-tags #{} exclude-tags #{} dependencies #{} require-all-tags false}}]
  
  (let [tools @tool-registry
        include-set (set include-tags)
        exclude-set (set exclude-tags)
        dep-set (set dependencies)]
    
    (->> tools
         ;; Filter by include tags
         (filter (fn [[_name spec]]
                   (or (empty? include-set)
                       (if require-all-tags
                         (every? (:tags spec) include-set)  ; AND logic
                         (some (:tags spec) include-set))))) ; OR logic
         
         ;; Filter by exclude tags
         (filter (fn [[_name spec]]
                   (not (some (:tags spec) exclude-set))))
         
         ;; Filter by dependencies
         (filter (fn [[_name spec]]
                   (or (empty? (:dependencies spec))
                       (every? dep-set (:dependencies spec)))))
         
         (into {}))))

(defn list-tags
  "List all tags currently in use"
  []
  (let [all-tools @tool-registry]
    (->> all-tools
         vals
         (mapcat :tags)
         set
         sort)))

(defn list-dependencies
  "List all dependencies currently in use"
  []
  (let [all-tools @tool-registry]
    (->> all-tools
         vals  
         (mapcat :dependencies)
         set
         sort)))

(defn tool-spec->mcp-tool
  "Convert our tool spec to MCP tool format"
  [tool-spec]
  {:name (name (:name tool-spec))
   :description (:description tool-spec)
   :inputSchema {:type "object"
                 :properties (:parameters tool-spec)
                 :required (vec (filter #(not (get-in (:parameters tool-spec) [% :optional])) 
                                       (keys (:parameters tool-spec))))}})

;; Prompt functions

;; TODO: Add real MCP workflow prompt functions here later
;; These will be for user-invokable development workflows, not tool help

(defn render-prompt
  "Render a prompt template with parameters (kept for future MCP workflow prompts)"
  [template context]
  (try
    (pogonos/render-string template context)
    (catch Exception e
      (log/log! {:level :error :msg "Error rendering prompt template" :data {:error (.getMessage e)}})
      (str "Error rendering prompt: " (.getMessage e)))))

(defn mcp-tool-call->clj
  "Convert MCP tool call to our internal format"
  [tool-call]
  {:tool-name (keyword (:name tool-call))
   :args (:arguments tool-call)})

(defn clj-result->mcp-result
  "Convert our result format to MCP result format"
  [result]
  (cond
    (= (:status result) :error)
    {:error (:error result)
     :isError true}
    
    :else
    {:content [{:type "text" 
                :text (json/write-str result)}]
     :isError false}))