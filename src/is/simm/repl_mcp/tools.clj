(ns is.simm.repl-mcp.tools
  "Aggregated tool functions from all tool namespaces"
  (:require 
   [is.simm.repl-mcp.tools.eval :as eval-tools]
   [is.simm.repl-mcp.tools.clj-kondo :as clj-kondo-tools]
   [is.simm.repl-mcp.tools.deps-management :as deps-tools]
   [is.simm.repl-mcp.tools.test-generation :as test-tools]
   [is.simm.repl-mcp.tools.cider-nrepl :as cider-tools]
   [is.simm.repl-mcp.tools.navigation :as nav-tools]
   [is.simm.repl-mcp.tools.refactor :as refactor-tools]
   [is.simm.repl-mcp.tools.function-refactor :as function-refactor-tools]
   [is.simm.repl-mcp.tools.structural-edit :as structural-edit-tools]
   [is.simm.repl-mcp.tools.profiling :as profiling-tools]))

;; ===============================================
;; Tool Aggregation
;; ===============================================

(def all-tools
  "Aggregated tool definitions from all tool namespaces"
  (concat
    eval-tools/tools
    clj-kondo-tools/tools
    deps-tools/tools
    test-tools/tools
    cider-tools/tools
    nav-tools/tools
    refactor-tools/tools
    function-refactor-tools/tools
    structural-edit-tools/tools
    profiling-tools/tools))

(defn get-tool-definitions
  "Returns aggregated tool definitions from all tool namespaces"
  []
  all-tools)