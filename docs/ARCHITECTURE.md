# repl-mcp Architecture

## Current Unified Server Architecture ✅

**Status**: Implemented and Working

The current implementation uses a **unified MCP server** with dual transport support:

### Core Components

- **Unified Server** (`server.clj`): Single server instance supporting both STDIO and HTTP+SSE transports
- **Dynamic Tool System**: Java SDK integration with runtime tool addition/removal via `addTool()`/`removeTool()`
- **Multimethod Dispatch**: Extensible tool routing system (`dispatch.clj`)
- **Clean API**: Simplified interface (`api.clj`) with 8 core functions vs previous 50+
- **nREPL Integration**: Development tools requiring nREPL connection
- **Production Filtering**: `create-production-config()` filters development tools

```
Current Unified Structure:
└── repl-mcp (41 tools total)
    ├── MCP Server (dual transport: STDIO + HTTP+SSE)
    │   ├── Dynamic tool registration via Java SDK
    │   ├── Real-time tool/prompt notifications
    │   └── Session management for HTTP+SSE
    ├── Tool Categories:
    │   ├── Evaluation tools (2) - eval, load-file
    │   ├── Refactoring tools (11) - clean-ns, find-symbol, etc.
    │   ├── Cider-nREPL tools (12) - format-code, test-all, etc.
    │   ├── Structural editing (10) - session-based code manipulation
    │   └── Function refactoring (5) - project-wide renaming
    ├── Templated Prompts (3) - TDD, debug, refactor workflows
    └── Clean API (8 functions) - simplified from 50+ functions
```

### Key Achievements

1. **✅ Unified Server Design**: Single server handles both STDIO and HTTP+SSE transports simultaneously
2. **✅ Dynamic Tool Registration**: Runtime tool addition/removal using Java SDK `addTool()`/`removeTool()` methods
3. **✅ Simplified API**: Reduced from 50+ functions to 8 core functions for better maintainability
4. **✅ Production Ready**: `create-production-config()` filters out development tools (eval, test, etc.)
5. **✅ Rich Tool Ecosystem**: 41 tools across 5 categories for comprehensive Clojure development
6. **✅ Testing**: Complete test suite with 43 tests, 328 assertions, 0 failures

### Transport Status

- **✅ STDIO Transport**: Fully functional - sends 41 tool + 3 prompt notifications correctly
- **⚠️ HTTP+SSE Transport**: SSE connection works, but message endpoint has blocking issue (under investigation)

## Future Architecture (On Hold)

**Note**: The core library + middleware split is **on hold** as the current unified approach is working well and meeting all requirements.

Moving to a **core library + middleware** approach would cleanly separate concerns:

### Core Library (repl-mcp-core)

Production-ready MCP server with tool infrastructure:

```
repl-mcp-core/
├── src/is/simm/repl_mcp/
│   ├── server.clj           # MCP server (STDIO transport)
│   ├── dispatch.clj         # Tool dispatch system
│   ├── interactive.clj      # register-tool! function
│   ├── schema.clj          # MCP protocol schemas
│   └── tools/
│       └── domain/         # Domain-specific tools (no nREPL deps)
│           ├── validation.clj
│           ├── transform.clj
│           └── analysis.clj
└── deps.edn                # No nREPL dependencies
```

### nREPL Middleware (repl-mcp-middleware)

Development-only enhancement providing eval/refactor capabilities:

```
repl-mcp-middleware/
├── src/is/simm/repl_mcp/middleware/
│   ├── core.clj            # nREPL middleware implementation
│   └── tools/
│       ├── eval.clj        # Eval tools (uses nREPL session)
│       └── refactor.clj    # Refactor tools (uses cider-nrepl)
└── deps.edn               # Depends on nREPL, cider-nrepl, refactor-nrepl
```

## Benefits

### 1. Clean Dev/Prod Separation

**Production Deployment:**
```clojure
;; deps.edn
{:deps {repl-mcp/core {:mvn/version "1.0.0"}}}

;; Application code
(require '[is.simm.repl-mcp.core :as mcp])

(mcp/register-tool! :validate-order
  "Validate customer order" 
  {:order {:type "object"}}
  (fn [tool-call context]
    (validate-order (:order (:args tool-call)))))

(mcp/start-server!)  ; Lightweight MCP server, no nREPL
```

**Development Environment:**
```clojure
;; deps.edn
{:deps {repl-mcp/core {:mvn/version "1.0.0"}}
 :aliases
 {:dev {:extra-deps {repl-mcp/middleware {:mvn/version "1.0.0"}}}}}

;; .nrepl.edn
{:middleware [cider.nrepl/cider-middleware
              refactor-nrepl.middleware/wrap-refactor  
              repl-mcp.middleware/wrap-mcp]}

;; Gets domain tools + eval tools + refactor tools automatically
```

### 2. Follows Clojure Ecosystem Patterns

- **Core library:** Like `clojure.spec.alpha` - pure, lightweight
- **Middleware:** Like `cider-nrepl` - development enhancement
- **Tool loading:** Like `mount` or `component` - optional components

### 3. Flexible Integration Options

**Option A: Pure Production MCP Server**
```clojure
;; Web service exposing domain tools to AI assistants
(mcp/start-server! {:tools [:validate-user :process-order :analyze-data]})
```

**Option B: nREPL-Enhanced Development**
```clojure
;; Full development environment with eval + refactor + domain tools
;; Middleware automatically provides eval/refactor capabilities
```

**Option C: Custom Tool Development**
```clojure
;; Library users can easily add domain-specific tools
(mcp/register-tool! :my-domain-tool ...)
```

## Migration Plan

### Phase 1: Extract Core Library

1. Move MCP server implementation to core
2. Move tool infrastructure (dispatch, interactive) to core  
3. Keep current eval/refactor tools as-is temporarily
4. Ensure backward compatibility

### Phase 2: Create Middleware Package

1. Extract eval/refactor tools to middleware package
2. Implement nREPL middleware wrapper
3. Add middleware auto-registration of coding tools
4. Test dev/prod scenarios

### Phase 3: Split Packages

1. Publish separate `repl-mcp-core` and `repl-mcp-middleware` artifacts
2. Update documentation and examples
3. Create migration guide for existing users
4. Deprecate monolithic package

## Technical Details

### Core Library API

```clojure
(ns is.simm.repl-mcp.core)

(defn start-server! 
  "Start MCP server with registered tools"
  [& {:keys [transport tools] :or {transport :stdio}}])

(defn stop-server! 
  "Stop running MCP server")

(defn register-tool! 
  "Register a tool with the MCP server"
  [tool-name description params handler-fn])

(defn list-tools 
  "List all registered tools")
```

### Middleware Integration

```clojure
(ns is.simm.repl-mcp.middleware.core
  (:require [nrepl.middleware :refer [set-descriptor!]]))

(defn wrap-mcp 
  "nREPL middleware providing MCP integration"
  [handler]
  ;; Enhance MCP server with nREPL session context
  ;; Auto-register eval/refactor tools
  )

(set-descriptor! #'wrap-mcp
  {:requires #{"clone" "eval"}
   :expects #{"close"}
   :handles {"mcp-tool-call" {:doc "Execute MCP tool call"}}})
```

### Tool Registration

Tools remain the same but gain access to nREPL session when middleware is present:

```clojure
;; Core tools (no nREPL dependency)
(register-tool! :validate-data
  "Validate data structure"
  {:data {:type "object"}}
  (fn [tool-call context]
    (validate (:data (:args tool-call)))))

;; Middleware tools (nREPL-enhanced)  
(register-tool! :eval
  "Evaluate Clojure code"
  {:code {:type "string"}}
  (fn [tool-call context]
    (let [nrepl-session (:nrepl-session context)]
      (eval-in-session nrepl-session (:code (:args tool-call))))))
```

## Compatibility

- **Backward compatible:** Existing code continues to work
- **Gradual migration:** Users can adopt new architecture incrementally
- **Optional middleware:** Core library works standalone
- **Tool API unchanged:** `register-tool!` function signature preserved

This architecture provides a clean foundation for both production MCP servers and rich development environments while following established Clojure ecosystem patterns.