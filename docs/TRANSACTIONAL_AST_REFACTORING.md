# Transactional AST Refactoring Design

**A comprehensive design for safe, powerful code refactoring using Datahike, Specter, and transactional editing.**

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Problem Analysis](#problem-analysis)
3. [Prior Art & Research](#prior-art--research)
4. [Architecture Design](#architecture-design)
5. [Component Specifications](#component-specifications)
6. [Workflow Examples](#workflow-examples)
7. [Implementation Strategy](#implementation-strategy)
8. [Performance Considerations](#performance-considerations)
9. [Future Extensions](#future-extensions)

## Executive Summary

This document proposes a novel approach to code refactoring that combines:

- **Datahike**: For sophisticated AST querying with git-like temporal database capabilities
- **Specter**: For high-performance, lens-like bidirectional transformations
- **Transactional Context**: For safe preview/validate/commit/rollback workflows

The system enables complex, multi-file refactoring operations with unprecedented safety and power, addressing limitations in existing tools like clojure-lsp and clj-refactor.

### Key Benefits

- **Safety**: Atomic transactions with rollback capability
- **Power**: Complex pattern matching and transformation beyond existing tools
- **Composability**: Declarative workflows combining query and transformation
- **Performance**: Leverages Specter's compiled paths and Datahike's efficient querying
- **Temporal Analysis**: Git-like history for understanding code evolution

## Problem Analysis

### Current State of Clojure Refactoring

#### Existing Tools Analysis

**clojure-lsp (2019+)**
- Strengths: Static analysis, LSP integration, multi-file operations
- Limitations: No transactional support, immediate application of changes, limited custom refactorings
- Operations: 12 core refactorings (rename, extract function, clean namespace, etc.)

**clj-refactor/refactor-nrepl (2013+)** 
- Strengths: REPL-powered, runtime analysis, dependency management
- Limitations: Requires active REPL, no transaction safety, performance issues on large codebases
- Operations: 38 operations including hot dependency loading, artifact management

**Key Gap Analysis:**
- ~90% overlap in core refactoring operations between tools
- Neither provides transaction safety or rollback capability
- Limited support for domain-specific refactoring patterns
- No preview/validate/commit workflows
- Complex multi-step refactorings require manual coordination

### Limitations of Current Approaches

#### String/Regex-Based Manipulation
```clojure
;; Current approach in many tools
(str/replace code "old-function" "new-function")
;; Problems: No scope awareness, breaks strings/comments, unsafe
```

#### Zipper Traversal Complexity
```clojure
;; Manual zipper navigation - verbose and error-prone
(-> zloc 
    (z/find-value 'defn)
    (z/right)
    (z/replace 'new-name)
    (z/up)
    (z/rightmost))
```

#### Lack of Transactional Safety
- Changes applied immediately without validation
- No rollback mechanism for failed operations  
- Multi-file refactorings can leave codebase in inconsistent state
- No preview capability for complex transformations

## Prior Art & Research

### AST to Datalog Research

**Academic Foundation:**
- "Using Datalog for Fast and Easy Program Analysis" (2010) - Doop framework
- "Datalog-Based Program Analysis with BES and RWL" (2010) - Boolean Equation Systems
- Proven approach for complex program analysis with strong theoretical foundation

**Existing Implementations:**
- **clojure.tools.analyzer**: Provides `ast->eav` function for Datomic integration
- **Soufflé**: High-performance Datalog compilation to parallel C++
- **CodeQL**: GitHub's production system using Datalog for code analysis

### Lens-Based Editing Research

**Bidirectional Programming:**
- Category theory lenses provide formal foundation for bidirectional transformations
- `get` and `put` functions satisfy lens laws for data consistency
- Applied successfully in data synchronization and view updating

**Specter Performance Analysis:**
- 30% faster than `get-in` for deep access
- 85% faster than `update-in` for transformations
- Compiled paths eliminate composition overhead
- Used successfully in production Clojure applications

## Architecture Design

### Core Components

```
┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐
│   Datahike DB   │    │   Specter Paths  │    │  Transaction    │
│                 │    │                  │    │   Context       │
│ - AST Storage   │◄──►│ - Lens-like      │◄──►│                 │
│ - Temporal      │    │   Transforms     │    │ - Preview       │
│ - Query Engine  │    │ - Composable     │    │ - Validate      │
│                 │    │ - High Perf      │    │ - Commit/Rollback│
└─────────────────┘    └──────────────────┘    └─────────────────┘
         ▲                       ▲                       ▲
         │                       │                       │
         └───────────────────────┼───────────────────────┘
                                 ▼
                    ┌─────────────────────────┐
                    │    MCP Tool Interface   │
                    │                         │
                    │ - transact-edit         │
                    │ - ast-query             │
                    │ - workflow-templates    │
                    └─────────────────────────┘
```

### Data Flow Architecture

```
Source Files → AST Parser → Datahike Facts → Query Engine
     ▲              │             │              │
     │              ▼             ▼              ▼
Transaction ← Specter Paths ← Transform Plans ← Query Results
  Context           │             │              │
     │              ▼             ▼              ▼
     └─────► File Updates ← AST Changes ← Transformations
```

### Transaction Lifecycle

```
1. BEGIN TRANSACTION
   ├── Load files into AST database
   ├── Create transaction context
   └── Initialize rollback state

2. QUERY PHASE  
   ├── Execute Datalog queries
   ├── Pattern matching and analysis
   └── Identify transformation targets

3. TRANSFORM PHASE
   ├── Generate Specter paths
   ├── Apply transformations
   └── Validate AST consistency

4. VALIDATION PHASE
   ├── Syntax checking
   ├── Test execution
   └── Conflict detection

5. COMMIT/ROLLBACK
   ├── Apply changes to files (commit)
   └── Restore original state (rollback)
```

## Component Specifications

### 1. AST Database Layer (Datahike)

#### Schema Design
```clojure
{:db/ident :node/id
 :db/valueType :db.type/uuid
 :db/cardinality :db.cardinality/one
 :db/unique :db.unique/identity}

{:db/ident :node/op
 :db/valueType :db.type/keyword  
 :db/cardinality :db.cardinality/one}

{:db/ident :node/file
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one}

{:db/ident :node/line
 :db/valueType :db.type/long
 :db/cardinality :db.cardinality/one}

{:db/ident :node/children
 :db/valueType :db.type/ref
 :db/cardinality :db.cardinality/many}

{:db/ident :node/parent
 :db/valueType :db.type/ref  
 :db/cardinality :db.cardinality/one}

{:db/ident :node/value
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one}
```

#### Incremental Update Strategy
```clojure
(defn update-file-in-db [conn file-path new-content]
  "Update AST database when file changes"
  (let [old-facts (get-file-facts @conn file-path)
        new-facts (parse-file-to-facts file-path new-content)]
    
    ;; Atomic file-level update
    (datahike/transact conn
      (concat
        ;; Retract all facts for this file
        (map #(vector :db/retractEntity (:db/id %)) old-facts)
        ;; Add new facts
        new-facts))))
```

#### Query Interface
```clojure
(defprotocol ASTQueryEngine
  (find-pattern [db pattern] "Find AST nodes matching pattern")
  (find-usages [db symbol] "Find all usages of symbol")
  (analyze-dependencies [db] "Compute dependency graph")
  (find-complexity [db threshold] "Find functions above complexity threshold"))
```

### 2. Transformation Layer (Specter)

#### Path Composition System
```clojure
(defn create-transformation-path [query-result]
  "Convert Datahike query result to Specter path"
  (let [{:keys [file line column ast-path]} query-result]
    (sp/comp-paths
      ;; Navigate to file
      [(sp/keypath file)]
      ;; Navigate to AST location
      (ast-path->specter-path ast-path)
      ;; Optional: navigate to specific child
      (when column [(sp/nthpath column)]))))

;; Example usage
(def function-rename-path
  [sp/ALL                                    ; All top-level forms
   (sp/walker list?)                         ; Find all lists  
   (sp/filtered #(= (first %) 'defn))       ; That are function definitions
   (sp/nthpath 1)])                         ; Target the function name

(sp/transform function-rename-path new-name ast)
```

#### Bidirectional Transformation Patterns
```clojure
(defrecord ExtractFunctionLens [pattern]
  Lens
  (lens-get [_ ast]
    "Find all occurrences of pattern suitable for extraction"
    (sp/select 
      [sp/ALL (sp/walker #(matches-pattern? % pattern))]
      ast))
  
  (lens-put [_ ast extractions]
    "Apply function extraction based on user modifications to view"
    (let [function-name (:extracted-name extractions)]
      (-> ast
          (create-extracted-function function-name pattern)
          (replace-occurrences-with-calls pattern function-name)))))
```

### 3. Transaction Context

#### Context State Management
```clojure
(defrecord TransactionContext
  [id                    ; Unique transaction ID
   db-conn              ; Datahike connection  
   original-files       ; Map of file -> original content
   modified-files       ; Map of file -> modified content
   operation-log        ; Vector of operations for rollback
   validation-results   ; Map of file -> validation status
   mode])               ; :preview, :execute, or :interactive

(defn create-transaction [files]
  (let [tx-id (random-uuid)
        db-conn (create-ast-database files)]
    (->TransactionContext 
      tx-id
      db-conn
      (load-original-files files)
      {}
      []
      {}
      :preview)))
```

#### Transaction Operations
```clojure
(defprotocol Transaction
  (query [ctx query-spec] "Execute Datalog query")
  (transform [ctx transform-spec] "Apply Specter transformation")  
  (validate [ctx] "Validate current state")
  (preview [ctx] "Generate diff preview")
  (commit [ctx] "Apply changes to files")
  (rollback [ctx] "Restore original state"))
```

### 4. MCP Tool Interface

#### Primary Tool: transact-edit
```clojure
{:name "transact-edit"
 :description "Execute transactional code refactoring with Datahike queries and Specter transforms"
 :inputSchema 
 {:type "object"
  :properties 
  {:code {:type "string" 
          :description "Clojure code defining the refactoring workflow"}
   :files {:type "array" 
           :items {:type "string"}
           :description "Files to include in transaction scope"}
   :mode {:type "string" 
          :enum ["preview" "execute" "interactive"]
          :default "preview"
          :description "Execution mode"}
   :validate {:type "boolean"
              :default true  
              :description "Run validation before commit"}}
  :required ["code" "files"]}
 :tool-fn transact-edit-tool}
```

#### Supporting Tools
```clojure
{:name "ast-query"
 :description "Query AST database with Datalog"
 :inputSchema {...}
 :tool-fn ast-query-tool}

{:name "preview-refactoring" 
 :description "Preview changes without applying"
 :inputSchema {...}
 :tool-fn preview-refactoring-tool}

{:name "validate-refactoring"
 :description "Validate refactoring correctness" 
 :inputSchema {...}
 :tool-fn validate-refactoring-tool}
```

## Workflow Examples

### Example 1: Simple Function Renaming

**Input:** Rename `authenticate-user` to `verify-user` across project

```clojure
transact-edit:
  files: ["src/**/*.clj" "test/**/*.clj"]
  mode: "preview"
  code: |
    ;; Query: Find all usages
    (def usages 
      (datahike/q
        '[:find ?node ?file ?line 
          :where
          [?node :op :invoke]
          [?node :fn 'authenticate-user]
          [?node :file ?file]
          [?node :line ?line]]
        @db-conn))
    
    ;; Transform: Rename using Specter
    (def updated-files
      (into {}
        (for [file (distinct (map second usages))]
          [file 
           (sp/transform
             ;; Path: find function calls and definitions
             [sp/ALL (sp/walker symbol?) 
              (sp/filtered #(= % 'authenticate-user))]
             'verify-user
             (get-file-ast file))])))
    
    ;; Validate and commit
    (when (validate-transformations updated-files)
      (commit-changes updated-files))
    
    {:renamed 'authenticate-user :to 'verify-user}
```

**Output:**
```
Preview Changes:
src/auth.clj: Line 15: authenticate-user -> verify-user (definition)
src/handlers.clj: Line 23: auth/authenticate-user -> auth/verify-user  
test/auth_test.clj: Line 8: authenticate-user -> verify-user

Validation: ✅ All tests pass
Status: Ready to commit
```

### Example 2: Extract Common Pattern

**Input:** Extract repeated validation logic to utility function

```clojure
transact-edit:
  files: ["src/**/*.clj"]  
  mode: "execute"
  code: |
    ;; Query: Find validation patterns
    (def patterns
      (datahike/q
        '[:find ?node ?file ?vars
          :where
          [?node :op :invoke]
          [?node :fn 'and] 
          [?node :matches-pattern :email-validation]
          [?node :file ?file]
          [?node :free-variables ?vars]]
        @db-conn))
    
    ;; Only extract if >= 3 occurrences
    (when (>= (count patterns) 3)
      
      ;; Transform: Create utility function
      (def utils-ns
        (sp/transform
          [sp/LAST]
          #(conj % 
            '(defn valid-email? [email]
               (and (some? email) 
                    (string? email) 
                    (not (str/blank? email)))))
          (get-or-create-namespace 'myapp.utils)))
      
      ;; Transform: Replace patterns with calls
      (def updated-files
        (into {}
          (for [[node file vars] patterns]
            [file
             (sp/transform
               ;; Path to validation pattern
               [(ast-location->specter-path node)]
               ;; Replace with function call  
               `(valid-email? ~(first vars))
               (get-file-ast file))])))
      
      ;; Add requires where needed
      (def final-files
        (add-requires updated-files 'myapp.utils 'valid-email?))
      
      (commit-changes (assoc final-files "src/utils.clj" utils-ns)))
    
    {:extracted 'valid-email? :occurrences (count patterns)}
```

### Example 3: Architectural Refactoring

**Input:** Extract domain logic from monolithic service

```clojure
transact-edit:
  files: ["src/monolithic_service.clj"]
  mode: "interactive" 
  code: |
    ;; Query: Analyze function domains
    (def function-domains
      (datahike/q
        '[:find ?fn-name ?domain ?dependencies
          :where
          [?fn :op :fn]
          [?fn :name ?fn-name]
          [(infer-domain ?fn-name) ?domain]
          [?fn :calls ?dependencies]]
        @db-conn))
    
    ;; Group by domain
    (def domain-groups 
      (group-by second function-domains))
    
    ;; Interactive review
    (println "Found domains:")
    (doseq [[domain functions] domain-groups]
      (println domain ":" (count functions) "functions"))
    
    ;; Extract each domain to separate namespace  
    (def extracted-namespaces
      (into {}
        (for [[domain functions] domain-groups]
          (let [ns-name (domain->namespace domain)
                
                ;; Extract functions using Specter
                extracted-fns
                (sp/select
                  [sp/ALL (sp/walker #(and (list? %) (= (first %) 'defn)))
                   (sp/filtered #(contains? (set (map first functions)) 
                                           (second %)))]
                  (get-file-ast "src/monolithic_service.clj"))]
            
            [domain (create-namespace ns-name extracted-fns)]))))
    
    ;; Update call sites
    (def updated-monolith
      (update-call-sites-for-extraction 
        "src/monolithic_service.clj" 
        extracted-namespaces))
    
    ;; Validate architectural improvement
    (def metrics (analyze-architectural-metrics 
                   "src/monolithic_service.clj" 
                   extracted-namespaces))
    
    (println "Architectural improvement:")
    (println "Cohesion improved by" (:cohesion-improvement metrics) "%")
    (println "Coupling reduced by" (:coupling-reduction metrics) "%")
    
    ;; Commit if improvements are significant
    (when (and (> (:cohesion-improvement metrics) 50)
               (> (:coupling-reduction metrics) 30))
      (commit-architectural-changes extracted-namespaces updated-monolith))
    
    {:success true :domains-extracted (count domain-groups)}
```

## Implementation Strategy

### Phase 1: Foundation (Months 1-2)
- [ ] Set up Datahike schema for AST storage
- [ ] Implement basic AST parsing and fact generation
- [ ] Create simple Specter transformation utilities
- [ ] Build basic transaction context
- [ ] Implement `transact-edit` MCP tool

### Phase 2: Core Capabilities (Months 3-4)  
- [ ] Advanced Datalog queries for pattern matching
- [ ] Specter path composition system
- [ ] File-level incremental updates
- [ ] Validation and testing integration
- [ ] Preview and rollback mechanisms

### Phase 3: Advanced Features (Months 5-6)
- [ ] Complex refactoring templates
- [ ] Interactive mode with user feedback
- [ ] Performance optimization
- [ ] Integration with existing tools (clojure-lsp, clj-refactor)
- [ ] Documentation and examples

### Phase 4: Production Readiness (Months 7-8)
- [ ] Large codebase testing and optimization
- [ ] Error handling and recovery
- [ ] Monitoring and observability
- [ ] User interface improvements
- [ ] Community feedback integration

## Performance Considerations

### Datahike Database Performance
- **Initial Load**: O(n) where n = AST nodes, typically 10-50K nodes per MLOC
- **Query Performance**: Sub-second for most patterns on codebases up to 500K LOC
- **Memory Usage**: ~50-100MB per MLOC in memory
- **Incremental Updates**: O(file size) rather than O(project size)

### Specter Transformation Performance  
- **Path Compilation**: One-time cost, cached for repeated use
- **Transform Speed**: 85% faster than `update-in`, 30% faster than `get-in`
- **Memory Efficiency**: Structural sharing preserves memory
- **Batch Operations**: Single pass for multiple transformations

### Scalability Targets
- **Small Projects** (<10K LOC): <1 second end-to-end
- **Medium Projects** (10-100K LOC): <10 seconds end-to-end
- **Large Projects** (100K-1M LOC): <60 seconds end-to-end
- **Incremental Updates**: <2 seconds regardless of project size

### Optimization Strategies
```clojure
;; Compiled paths for repeated operations
(def compiled-rename-path 
  (sp/comp-paths function-call-path symbol-rename-path))

;; Batch transformations to minimize AST traversal
(sp/multi-transform 
  [[path1 transform1] 
   [path2 transform2]] 
  ast)

;; Incremental query result caching
(def cached-query-fn (memo-with-ttl query-fn (* 5 60 1000)))
```

## Future Extensions

### Advanced Query Capabilities
- **Temporal Queries**: "Show me how this function has changed over time"
- **Cross-Reference Analysis**: "Find all functions that could benefit from memoization"  
- **Complexity Analysis**: "Identify functions that violate complexity thresholds"
- **Pattern Evolution**: "Track how design patterns spread through codebase"

### Machine Learning Integration
- **Pattern Recognition**: Automatically identify extraction opportunities
- **Refactoring Suggestions**: ML-powered recommendations based on codebase analysis
- **Quality Metrics**: Predict impact of refactorings on code quality
- **Anomaly Detection**: Identify unusual patterns that may need refactoring

### IDE Integration
- **VSCode Extension**: Visual preview of refactorings with syntax highlighting
- **Emacs Integration**: Native integration with CIDER and clj-refactor
- **IntelliJ Plugin**: Leverage existing Clojure tooling ecosystem
- **Web Interface**: Browser-based refactoring for remote development

### Collaborative Refactoring
- **Team Workflows**: Multi-developer refactoring with conflict resolution
- **Review Process**: Peer review of complex refactorings before application
- **Change Tracking**: Detailed history of refactoring decisions and outcomes
- **Knowledge Sharing**: Reusable refactoring patterns across teams

### Domain-Specific Languages
- **Refactoring DSL**: Higher-level language for common refactoring patterns
- **Template System**: Parameterizable refactoring templates for domain patterns
- **Rule Engine**: Declarative rules for automated refactoring application
- **Custom Analyzers**: Domain-specific analysis and transformation rules

## Conclusion

The proposed Transactional AST Refactoring system represents a significant advancement in code refactoring capabilities for Clojure. By combining the query power of Datahike, the transformation elegance of Specter, and the safety of transactional contexts, we can achieve refactoring operations that are:

- **More Powerful**: Complex pattern matching and transformation beyond existing tools
- **Safer**: Atomic transactions with validation and rollback
- **More Flexible**: Custom refactorings for domain-specific patterns  
- **More Reliable**: Preview and validation workflows prevent errors
- **More Scalable**: Efficient performance on large codebases

The system fills critical gaps in the current Clojure tooling ecosystem while leveraging proven technologies and research. The modular architecture allows for incremental development and integration with existing tools, providing a clear path to production deployment.

This approach has the potential to transform how Clojure developers approach code refactoring, making complex transformations safe, reliable, and accessible through the MCP ecosystem.

---

*This design document represents the culmination of research into existing refactoring tools, academic work on AST-to-datalog approaches, and practical experience with Specter and Datahike. The proposed system addresses real limitations in current tooling while leveraging proven technologies to create a uniquely powerful refactoring platform.*