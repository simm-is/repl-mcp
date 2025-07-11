# Structural Editing Roadmap

## Overview

This document outlines the roadmap for enhanced structural editing capabilities in the repl-mcp project. The goal is to provide LLMs with safe, powerful, and declarative tools for editing Clojure code without the risk of syntax errors that plague traditional text-based approaches.

## Current Status

### ✅ **Phase 1: Foundation (Completed)**

**Core Infrastructure**
- ✅ Session-based zipper management with `structural_edit.clj`
- ✅ 25+ structural editing tools registered through MCP interface
- ✅ Comprehensive test suite with complex nested structures
- ✅ Template-based prompts for major operations
- ✅ Basic navigation and paredit operations

**Key Tools Implemented**
- Session management (`create-session`, `save-session`, `close-session`)
- Navigation (`navigate`, `find-value`)
- Paredit operations (`slurp-forward`, `barf-forward`, `wrap-around`, `unwrap`)
- Direct editing (`replace-node`, `insert-before/after`, `remove-node`)
- Collection operations (`assoc-in-map`, `append-to-vector`)

**Proven Capabilities**
- ✅ Syntax safety - all operations preserve valid parentheses
- ✅ File system integration - read from and write to files
- ✅ Complex nested structure handling
- ✅ MCP protocol integration with 32 total tools

## Pain Points Identified

### 🔥 **Critical Issues**

1. **Whitespace & Formatting**
   - Insertions get compressed: `{:id 2 :name "Bob"} {:id 3, :name "Charlie"}`
   - Missing line breaks and proper indentation
   - No integration with cljfmt for consistent formatting

2. **Manual Navigation Complexity**
   - Requires verbose step-by-step navigation
   - Example: `navigate down → navigate right → navigate right → find-value`
   - No direct semantic path addressing

3. **Limited MCP Output Visibility**
   - Tools only return "Success" instead of actual values
   - Difficult to debug and follow edit progress
   - No preview of changes before committing

4. **Order Dependencies**
   - Code inserted in wrong order causing undefined symbols
   - No semantic understanding of insertion points
   - No validation of edit sequences

## Roadmap

### 🚧 **Phase 2: Critical Foundations (In Progress)**

**Priority 1: Formatting & Whitespace**
```clojure
;; Integrate cljfmt for proper formatting
(defn format-session [session-id]
  (update-session! session-id 
    #(assoc % :zipper (format-zipper (:zipper %)))))

;; Add whitespace control
(structural-insert-with-formatting session-id
  :code "(defn hello [] \"world\")"
  :preserve-spacing true
  :add-newlines {:before 1 :after 1})
```

**Priority 2: Better MCP Output**
```clojure
;; Return actual values instead of "Success"
(structural-get-info session-id) → {:current-node {...} :path [...]}
(structural-save-session session-id) → {:content "formatted code"}
```

**Priority 3: Enhanced Logging & Debugging**
- ✅ Telemere file logging setup
- ✅ Detailed tool call logging
- ✅ nREPL interaction debugging
- ✅ Server startup issues resolved
- ✅ Transport abstraction implemented
- ✅ Dynamic tool registration with client notifications

### 🎯 **Phase 3: Path-Based Operations (Next)**

**Semantic Path Navigation**
```clojure
;; Instead of manual navigation
(structural-edit-at-path session-id 
  [:ns :def :users :vector :map 0 :key :email]
  :operation :replace
  :value "new-email@example.com")

;; Query-based finding
(structural-find-all session-id 
  {:query {:type :defn :name #"find-.*"}})
```

**Specter Integration**
```clojure
;; Powerful data transformations
(structural-specter-transform session-id
  "[sp/ALL :users sp/ALL :profile]"
  "(fn [profile] (assoc profile :updated-at (java.time.Instant/now)))")
```

**Path Discovery**
```clojure
;; Automatic path generation
(structural-get-path-to session-id 'my-function)
→ [:ns :def :my-function]
```

### 🎨 **Phase 4: Declarative Operations (Future)**

**Template System**
```clojure
;; High-level code generation
(structural-insert-template session-id
  :template :function
  :data {:name "process-users" 
         :params "[users options]"
         :body "(map process-user users)"}
  :position :after-similar-functions)
```

**Batch Operations**
```clojure
;; Atomic multi-operation transactions
(structural-batch-edit session-id
  [{:type :add-function :name "validate-user" :params "[user]"}
   {:type :add-require :namespace "clojure.spec.alpha" :alias "s"}
   {:type :transform-collection :path [:users] :to :vector}])
```

**Smart Semantic Insertion**
```clojure
;; Context-aware code placement
(structural-smart-add-function session-id
  :name "helper-fn"
  :code "(defn helper-fn [] ...)"
  :position :auto) ; Finds best location automatically
```

### 🧠 **Phase 5: Advanced Intelligence (Future)**

**Code Analysis & Suggestions**
```clojure
(structural-analyze-code session-id)
→ {:suggestions ["Add docstring to process-user"
                 "Extract nested map to let binding"
                 "Use threading macro for clarity"]}
```

**Refactoring Workflows**
```clojure
;; High-level refactoring operations
(structural-extract-function session-id
  :selection {:start-line 15 :end-line 20}
  :name "extracted-fn")

(structural-convert-to-threading session-id
  :expression "(reduce + (map inc (filter even? coll)))"
  :style :thread-last)
```

**Validation & Safety**
```clojure
;; Comprehensive validation
(structural-validate-changes session-id)
→ {:syntax :ok
   :semantics {:unresolved-symbols []
               :unused-vars []
               :type-errors []}}
```

## Implementation Strategy

### **Week 1-2: Foundation**
- ✅ Telemere logging setup
- ✅ Fix server startup issues
- 🚧 Fix MCP output visibility  
- 🚧 Add cljfmt integration for formatting

### **Week 3-4: Path Operations**
- Implement semantic path navigation
- Add Specter integration for complex transforms
- Create path discovery utilities
- Build query-based finding system

### **Week 5-6: Declarative Operations**
- Template system for code generation
- Batch operations with rollback
- Smart semantic insertion points
- Context-aware code placement

### **Week 7-8: Advanced Features**
- Code analysis and suggestions
- High-level refactoring workflows
- Comprehensive validation system
- Performance optimization

## Success Metrics

### **Phase 2 Success Criteria**
- [ ] All code insertions properly formatted with line breaks
- [ ] MCP tools return actual values instead of "Success"
- [x] Server starts without errors and logs to file
- [ ] Complex edits complete 3x faster than manual navigation

### **Phase 3 Success Criteria**
- [ ] Direct path-based editing: `edit-at-path [:def :users :vector :append]`
- [ ] Specter transforms work on complex nested structures
- [ ] Query system finds all matching patterns in codebase
- [ ] Edit operations 5x faster than current approach

### **Phase 4 Success Criteria**
- [ ] Template system generates idiomatic Clojure code
- [ ] Batch operations handle complex multi-step refactors
- [ ] Smart insertion automatically finds best code placement
- [ ] Declarative operations enable high-level code transformations

## Technical Architecture

### **Current Stack**
- **Core**: `rewrite-clj` for AST manipulation
- **Session Management**: Zipper-based state tracking
- **Transport**: MCP protocol via Java SDK
- **Integration**: nREPL for evaluation and testing

### **Planned Enhancements**
- **Formatting**: `cljfmt` integration
- **Transforms**: Specter for complex data operations
- **Logging**: Telemere for debugging and monitoring
- **Validation**: Syntax and semantic checking
- **Templates**: Mustache-based code generation

## Key Benefits

1. **Safety**: Impossible to create unbalanced parentheses
2. **Power**: Complex transformations via Specter integration
3. **Speed**: Declarative operations vs manual navigation
4. **Clarity**: Better output and debugging capabilities
5. **Intelligence**: Context-aware code placement and suggestions

## Conclusion

This roadmap transforms structural editing from a low-level zipper manipulation tool into a high-level, intelligent code transformation system. The phased approach ensures incremental value delivery while building toward a comprehensive solution that dramatically improves LLM effectiveness for Clojure code editing.

**Next Immediate Action**: Complete Phase 2 by implementing proper formatting and better MCP output visibility, then proceed to Phase 3 path-based operations.