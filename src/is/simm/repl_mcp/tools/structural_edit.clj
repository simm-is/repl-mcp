(ns is.simm.repl-mcp.tools.structural-edit
  (:require [is.simm.repl-mcp.interactive :refer [register-tool!]]
            [is.simm.repl-mcp.structural-edit :as edit]
            [clojure.edn :as edn]))

;; =============================================================================
;; STRUCTURAL EDITING TOOLS - ESSENTIAL ONLY
;; =============================================================================

;; =============================================================================
;; SESSION MANAGEMENT TOOLS
;; =============================================================================

(register-tool! :structural-create-session
  "Create a new structural editing session from file or code string"
  {:session-id {:type "string" :description "Unique identifier for the session"}
   :source {:type "string" :description "File path or code string to edit"}
   :from-file {:type "boolean" :default true :optional true :description "Whether source is a file path (true) or code string (false)"}}
  (fn [tool-call context]
    (let [{:strs [session-id source from-file]} (:args tool-call)
          result (edit/create-session session-id source :from-file? from-file)]
      (if (= (:status result) :success)
        (format "✓ Session '%s' created" session-id)
        result))))

(register-tool! :structural-save-session
  "Save structural editing session to file or get as string"
  {:session-id {:type "string" :description "Session identifier"}
   :file-path {:type "string" :optional true :description "Optional file path to save to (if not provided, returns as string)"}}
  (fn [tool-call context]
    (let [{:strs [session-id file-path]} (:args tool-call)
          result (if file-path
                   (edit/save-session session-id :file-path file-path)
                   (edit/save-session session-id))]
      result)))

(register-tool! :structural-close-session
  "Close a structural editing session"
  {:session-id {:type "string" :description "Session identifier"}}
  (fn [tool-call context]
    (let [{:strs [session-id]} (:args tool-call)]
      (edit/close-session session-id))))

(register-tool! :structural-get-info
  "Get comprehensive information about current zipper position"
  {:session-id {:type "string" :description "Session identifier"}}
  (fn [tool-call context]
    (let [{:strs [session-id]} (:args tool-call)]
      (edit/get-zipper-info session-id))))

(register-tool! :structural-list-sessions
  "List all active structural editing sessions"
  {}
  (fn [tool-call _context]
    {:status :success :sessions (edit/get-all-sessions)}))

;; =============================================================================
;; CORE STRUCTURAL EDITING TOOLS
;; =============================================================================

(register-tool! :structural-find-symbol-enhanced
  "Find symbols with enhanced matching including keywords and flexible patterns"
  {:session-id {:type "string" :description "Session identifier"}
   :symbol-name {:type "string" :description "Symbol name to find"}
   :exact-match {:type "boolean" :default false :optional true :description "Whether to use exact matching"}
   :case-sensitive {:type "boolean" :default true :optional true :description "Whether to use case-sensitive matching"}}
  (fn [tool-call context]
    (let [{:strs [session-id symbol-name exact-match case-sensitive]} (:args tool-call)]
      (edit/find-by-symbol session-id symbol-name 
                          :exact-match? exact-match 
                          :case-sensitive? case-sensitive))))

(register-tool! :structural-replace-node
  "Replace current node with new expression"
  {:session-id {:type "string" :description "Session identifier"}
   :new-expression {:type "string" :description "New expression to replace current node"}}
  (fn [tool-call context]
    (let [{:strs [session-id new-expression]} (:args tool-call)]
      (edit/replace-node session-id (edn/read-string new-expression)))))

(register-tool! :structural-bulk-find-and-replace
  "Find and replace all occurrences of a pattern with enhanced symbol matching"
  {:session-id {:type "string" :description "Session identifier"}
   :find-pattern {:type "string" :description "Pattern to find"}
   :replace-with {:type "string" :description "Replacement text"}
   :exact-match {:type "boolean" :default false :optional true :description "Whether to use exact matching"}}
  (fn [tool-call context]
    (let [{:strs [session-id find-pattern replace-with exact-match]} (:args tool-call)]
      (edit/bulk-find-and-replace session-id find-pattern replace-with 
                                 :exact-match? exact-match))))

;; =============================================================================
;; EXTRACT/TRANSFORM TOOLS
;; =============================================================================

(register-tool! :structural-extract-to-let
  "Extract current expression to a let binding"
  {:session-id {:type "string" :description "Session identifier"}
   :binding-name {:type "string" :description "Name for the new binding"}}
  (fn [tool-call context]
    (let [{:strs [session-id binding-name]} (:args tool-call)]
      (edit/extract-to-let session-id binding-name))))

(register-tool! :structural-thread-first
  "Convert expression to thread-first macro"
  {:session-id {:type "string" :description "Session identifier"}}
  (fn [tool-call context]
    (let [{:strs [session-id]} (:args tool-call)]
      (edit/thread-first session-id))))

;; Tools are automatically registered by register-tool! function