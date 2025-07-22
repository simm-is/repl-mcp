(ns is.simm.repl-mcp.tools.structural-edit
  "Structural editing tools for Clojure code using rewrite-clj"
  (:require 
   [is.simm.repl-mcp.structural-edit :as edit]
   [rewrite-clj.zip :as z]
   [clojure.string :as str]))

;; =============================================================================
;; STRUCTURAL EDITING TOOLS - SESSION MANAGEMENT
;; =============================================================================

(defn structural-create-session-fn [_mcp-context arguments]
  (let [{:keys [session-id source from-file]} arguments
        result (edit/create-session session-id source :from-file? (if (nil? from-file) true from-file))]
    (if (= (:status result) :success)
      {:content [{:type "text" :text (format "✓ Session '%s' created successfully" session-id)}]}
      {:content [{:type "text" :text (format "✗ Failed to create session: %s" (:error result))}
                 {:type "text" :text (format "Status: %s" (:status result))}]})))

(defn structural-list-sessions-fn [_mcp-context _arguments]
  (let [sessions (edit/get-all-sessions)]
    (if (empty? sessions)
      {:content [{:type "text" :text "No active structural editing sessions."}]}
      {:content [{:type "text" :text (format "Active sessions (%d):" (count sessions))}
                 {:type "text" :text (str/join "\n" 
                   (for [[id info] sessions]
                     (format "- %s: %s (from-file: %s, history: %d operations)"
                            id (:original-source info) (:from-file? info) (:history-count info))))}
                 ]})))

(defn structural-save-session-fn [_mcp-context arguments]
  (let [{:keys [session-id file-path]} arguments
        result (if file-path
                  (edit/save-session session-id :file-path file-path)
                  (edit/save-session session-id))]
    (case (:status result)
      :success (if file-path
                 {:content [{:type "text" :text (format "✓ Session '%s' saved to %s" session-id (:file-path result))}]}
                 {:content [{:type "text" :text (format "Session content:\n%s" (or (:code result) ""))}]})
      :error   {:content [{:type "text" :text (format "✗ Failed to save session: %s" (:error result))}]})))

(defn structural-close-session-fn [_mcp-context arguments]
  (let [{:keys [session-id]} arguments
        _result (edit/close-session session-id)]
    {:content [{:type "text" :text (format "✓ Session '%s' closed successfully" session-id)}]}))

(defn structural-get-info-fn [_mcp-context arguments]
  (let [{:keys [session-id]} arguments
        result (edit/get-zipper-info session-id)]
    (case (:status result)
      :success (let [current (:current-node result)
                     parent (:parent result)
                     children (:children result)
                     siblings (:siblings result)
                     ops (:available-operations result)]
                 {:content [{:type "text" :text "Current zipper information:"}
                           {:type "text" :text (format "Position: %s" (:position current))}
                           {:type "text" :text (format "Node type: %s" (:node-type current))}
                           {:type "text" :text (format "Expression: %s" (:sexpr current))}
                           {:type "text" :text (format "Available operations: %s" (vec ops))}
                           {:type "text" :text (format "Has parent: %s" (boolean parent))}
                           {:type "text" :text (format "Children count: %s" (count children))}
                           {:type "text" :text (format "Has left sibling: %s" (boolean (:left siblings)))}
                           {:type "text" :text (format "Has right sibling: %s" (boolean (:right siblings)))}]})
      :error   {:content [{:type "text" :text (format "✗ Error getting info: %s" (:error result))}]})))

;; =============================================================================
;; STRUCTURAL EDITING TOOLS - CORE OPERATIONS
;; =============================================================================

(defn structural-navigate-fn [_mcp-context arguments]
  (let [{:keys [session-id direction steps]} arguments
        steps (or steps 1)
        result (edit/navigate session-id (keyword direction) :steps steps)]
    (case (:status result)
      :success {:content [{:type "text" :text (format "✓ Navigated %s (steps: %d)" direction steps)}
                         {:type "text" :text (format "Current position: %s" 
                           (get-in result [:info :current-node :sexpr]))}
                         {:type "text" :text (format "Available operations: %s" 
                           (vec (get-in result [:info :available-operations])))}]}
      :error   {:content [{:type "text" :text (format "✗ Navigation failed: %s" (:error result))}]})))

(defn structural-find-symbol-fn [_mcp-context arguments]
  (let [{:keys [session-id symbol-name exact-match case-sensitive]} arguments
        exact-match (if (nil? exact-match) false exact-match)
        case-sensitive (if (nil? case-sensitive) true case-sensitive)
        result (edit/find-by-symbol session-id symbol-name 
                  :exact-match? exact-match :case-sensitive? case-sensitive)]
    (case (:status result)
      :success {:content [{:type "text" :text (format "✓ Found symbol '%s'" (:found result))}
                         {:type "text" :text (format "Current expression: %s" 
                           (get-in result [:info :current-node :sexpr]))}
                         {:type "text" :text (format "Position: %s" 
                           (get-in result [:info :current-node :position]))}]}
      :error   {:content [{:type "text" :text (format "✗ Symbol not found: %s" (:error result))}]})))

(defn structural-replace-node-fn [_mcp-context arguments]
  (let [{:keys [session-id new-expression]} arguments
        ;; Parse the new expression using rewrite-clj
        parsed-node (try (z/node (z/of-string new-expression))
                        (catch Exception e
                          (throw (ex-info "Invalid expression syntax" {:expression new-expression :error (.getMessage e)}))))
        result (edit/replace-node session-id parsed-node)]
    (case (:status result)
      :success {:content [{:type "text" :text "✓ Node replaced successfully"}
                         {:type "text" :text (format "New expression: %s" 
                           (get-in result [:info :current-node :sexpr]))}]}
      :error   {:content [{:type "text" :text (format "✗ Replace failed: %s" (:error result))}]})))

(defn structural-insert-after-fn [_mcp-context arguments]
  (let [{:keys [session-id new-expression]} arguments
        parsed-node (try (z/node (z/of-string new-expression))
                        (catch Exception e
                          (throw (ex-info "Invalid expression syntax" {:expression new-expression :error (.getMessage e)}))))
        result (edit/insert-after session-id parsed-node)]
    (case (:status result)
      :success {:content [{:type "text" :text "✓ Expression inserted after current node"}
                         {:type "text" :text (format "Inserted: %s" new-expression)}]}
      :error   {:content [{:type "text" :text (format "✗ Insert failed: %s" (:error result))}]})))

(defn structural-insert-before-fn [_mcp-context arguments]
  (let [{:keys [session-id new-expression]} arguments
        parsed-node (try (z/node (z/of-string new-expression))
                        (catch Exception e
                          (throw (ex-info "Invalid expression syntax" {:expression new-expression :error (.getMessage e)}))))
        result (edit/insert-before session-id parsed-node)]
    (case (:status result)
      :success {:content [{:type "text" :text "✓ Expression inserted before current node"}
                         {:type "text" :text (format "Inserted: %s" new-expression)}]}
      :error   {:content [{:type "text" :text (format "✗ Insert failed: %s" (:error result))}]})))

;; =============================================================================
;; TOOL DEFINITIONS
;; =============================================================================

(def tools
  "Structural editing tool definitions for mcp-toolkit"
  [{:name "structural-create-session"
    :description "Create a new structural editing session from file or code string"
    :inputSchema {:type "object"
                  :properties {:session-id {:type "string" :description "Unique identifier for the session"}
                              :source {:type "string" :description "File path or code string to edit"}
                              :from-file {:type "boolean" :description "Whether source is a file path (true) or code string (false)"}}
                  :required ["session-id" "source"]}
    :tool-fn structural-create-session-fn}
   
   {:name "structural-save-session"
    :description "Save structural editing session to file or get as string"
    :inputSchema {:type "object"
                  :properties {:session-id {:type "string" :description "Session identifier"}
                              :file-path {:type "string" :description "Optional file path to save to"}}
                  :required ["session-id"]}
    :tool-fn structural-save-session-fn}
   
   {:name "structural-close-session"
    :description "Close a structural editing session"
    :inputSchema {:type "object"
                  :properties {:session-id {:type "string" :description "Session identifier"}}
                  :required ["session-id"]}
    :tool-fn structural-close-session-fn}
   
   {:name "structural-get-info"
    :description "Get comprehensive information about current zipper position"
    :inputSchema {:type "object"
                  :properties {:session-id {:type "string" :description "Session identifier"}}
                  :required ["session-id"]}
    :tool-fn structural-get-info-fn}
   
   {:name "structural-list-sessions"
    :description "List all active structural editing sessions"
    :inputSchema {:type "object"
                  :properties {}}
    :tool-fn structural-list-sessions-fn}
   
   {:name "structural-find-symbol"
    :description "Find symbols with matching including keywords and flexible patterns"
    :inputSchema {:type "object"
                  :properties {:session-id {:type "string" :description "Session identifier"}
                              :symbol-name {:type "string" :description "Symbol name to find"}
                              :exact-match {:type "boolean" :description "Whether to use exact matching"}
                              :case-sensitive {:type "boolean" :description "Whether to use case-sensitive matching"}}
                  :required ["session-id" "symbol-name"]}
    :tool-fn structural-find-symbol-fn}
   
   {:name "structural-replace-node"
    :description "Replace current node with new expression"
    :inputSchema {:type "object"
                  :properties {:session-id {:type "string" :description "Session identifier"}
                              :new-expression {:type "string" :description "New expression to replace current node"}}
                  :required ["session-id" "new-expression"]}
    :tool-fn structural-replace-node-fn}
   
   {:name "structural-insert-after"
    :description "Insert expression after current node with proper formatting"
    :inputSchema {:type "object"
                  :properties {:session-id {:type "string" :description "Session identifier"}
                              :new-expression {:type "string" :description "New expression to insert after current node"}}
                  :required ["session-id" "new-expression"]}
    :tool-fn structural-insert-after-fn}
   
   {:name "structural-insert-before"
    :description "Insert expression before current node with proper formatting"
    :inputSchema {:type "object"
                  :properties {:session-id {:type "string" :description "Session identifier"}
                              :new-expression {:type "string" :description "New expression to insert before current node"}}
                  :required ["session-id" "new-expression"]}
    :tool-fn structural-insert-before-fn}
   
   {:name "structural-navigate"
    :description "Navigate to different positions in the code structure"
    :inputSchema {:type "object"
                  :properties {:session-id {:type "string" :description "Session identifier"}
                              :direction {:type "string" :description "Navigation direction: up, down, left, right, next, prev"}
                              :steps {:type "number" :description "Number of steps to move (default: 1)"}}
                  :required ["session-id" "direction"]}
    :tool-fn structural-navigate-fn}])