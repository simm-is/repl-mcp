# repl-mcp

**This is alpha software and feedback is very welcome! It is deliberately not well packaged and abstracted over yet, so you can easily interact with its pieces. The project is similar in spirit to [clojure-mcp](https://github.com/bhauman/clojure-mcp), but *liberally licensed* and aiming for minimal friction with the REPL by providing powerful IO and development tools from the perspective of autonomous agentic coding assistant tools like Claude code.**

A [Model Context Protocol](https://modelcontextprotocol.io/) (MCP) server for Clojure development that provides built-in tools for interactive coding assistance and structural editing via nREPL integration. You can use it during development, e.g. in Claude code, by just adding it to your project. Optionally you can also use the same functionality to export your own functions through the MCP interface and also run your own dedicated MCP server with a network transport (SSE) for production. The overall philosophy is to minimize friction and speed up the feedback loop with the Clojure REPL during development. MCP supports dynamic exports of tools and prompts, so you can even create your own tools/workflows and use them in your coding assistant without restarts (this [does not work with Claude code as a MCP client yet](https://github.com/anthropics/claude-code/issues/2722).)

**Key Features:**
- **53 Development Tools**: Evaluation, refactoring, cider-nrepl, structural editing, function refactoring, test generation, static analysis, dependency management, advanced navigation, and profiling
- **Dynamic Tool Registration**: Add new tools while the server is running
- **Transport Abstraction**: STDIO and HTTP+SSE transport support
- **nREPL Integration**: Full cider-nrepl and refactor-nrepl middleware support
- **Code Quality**: Integrated clj-kondo linting for real-time feedback
- **Runtime Dependencies**: Hot-load dependencies without REPL restart (Clojure 1.12+)
- **Performance Analysis**: CPU and memory profiling with flame graph generation

Create project-specific tools interactively for reliable, predictable workflows. Configure your coding assistant to prefer these tools via [CLAUDE.md](./CLAUDE.md) in your project.

## Quick Start

### Add to Your Project

Add repl-mcp to your `deps.edn` dependencies and include the `:repl-mcp` alias (copy it from our deps.edn):

```clojure
{:deps {is.simm/repl-mcp {:git/url "https://github.com/simm-is/repl-mcp"
                          :git/sha "latest-sha"}}
 :aliases
 {:repl-mcp {:main-opts ["-m" "is.simm.repl-mcp"]
             ;; Optional, for profiling:
             :jvm-opts ["-Djdk.attach.allowAttachSelf"
                        "-XX:+UnlockDiagnosticVMOptions"
                        "-XX:+DebugNonSafepoints"
                        "-XX:+EnableDynamicAgentLoading"
                        "--enable-native-access=ALL-UNNAMED"]
             :extra-deps {nrepl/nrepl {:mvn/version "1.0.0"}
                          cider/cider-nrepl {:mvn/version "0.47.1"}
                          rewrite-clj/rewrite-clj {:mvn/version "1.1.47"}
                          refactor-nrepl/refactor-nrepl {:mvn/version "3.10.0"}
                          dev.weavejester/cljfmt {:mvn/version "0.13.1"}
                          clj-kondo/clj-kondo {:mvn/version "2025.06.05"}
                          ;; Optional for profiling tools
                          criterium/criterium {:mvn/version "0.4.6"}
                          com.clojure-goes-fast/clj-async-profiler {:mvn/version "1.6.1"}

                          org.slf4j/slf4j-api {:mvn/version "2.0.17"}
                          ;; TODO figure out how to redirect SLF4J to telemere
                          ;; Use nop logging to not accidentally log to STDOUT
                          org.slf4j/slf4j-nop {:mvn/version "2.0.17"}
                          ;; Or for SSE you can use
                          #_#_org.slf4j/slf4j-simple {:mvn/version "2.0.17"}
                          ;; For SSE support in REPL server
                          org.eclipse.jetty.ee10/jetty-ee10-servlet {:mvn/version "12.0.5"}
                          org.eclipse.jetty/jetty-server {:mvn/version "12.0.5"}
                          jakarta.servlet/jakarta.servlet-api {:mvn/version "6.0.0"}}}}}
```

Also make sure to provide the `Instructions` section from `CLAUDE.md` to your assistant to be aware of and prioritize the tools and prompts.

### Start the MCP Server

```bash
# HTTP SSE transport (recommended for production use)
clojure -M:repl-mcp --http-only --http-port 8080
# With custom nREPL port to avoid conflicts
clojure -M:repl-mcp --nrepl-port 27889 --http-port 8080 --http-only 
# STDIO (default, good for development)
clojure -M:repl-mcp
```

This starts:
- **nREPL server** on localhost:17888 (default) or custom port (for coding operations on your project)
- **STDIO transport** for MCP communication via stdin/stdout (for local tools) 

### Assistant Integration

#### Claude Code

```bash
# HTTP SSE transport (recommended - specify a unique port for each project)
claude mcp add --transport sse repl-mcp http://localhost:18080/sse
# Or STDIO (can be too slow in startup for 30s timeout window)
claude mcp add repl-mcp -- clojure -M:repl-mcp
```

**Important**: When using HTTP SSE transport, specify a unique port for each project to avoid conflicts. Use `--http-port` when starting the server:

```bash
# Start server with custom HTTP port
clojure -M:repl-mcp --http-only --http-port 8080
```

#### VS Code

You can manually add it through the UI, or add `.vscode/mcp.json` like this to your project:

```json
{
	"servers": {
		"repl-mcp": {
			"type": "stdio",
			"command": "clojure",
			"args": [
				"-M:repl-mcp",
				"--nrepl-port 37888",
				"--http-port 18080"
			]
		}
	},
	"inputs": []
}
```

**Note**: Specify unique ports for both nREPL and HTTP to avoid conflicts when running multiple projects simultaneously.

TODO: Add other integration instructions here, please open a PR.

## Interactive Usage

```clojure
(require '[is.simm.repl-mcp :as repl-mcp])

;; Server management
(repl-mcp/start-server!)  ; Start server
(repl-mcp/list-tools)     ; List all tools
(repl-mcp/tool-info :eval) ; Get tool details
(repl-mcp/tool-help :eval) ; Get usage guidance
(repl-mcp/stop-server!)   ; Stop server
```

## Available Tools (53 Total)

The MCP server provides 53 specialized tools organized into 9 categories for comprehensive Clojure development support:

### Evaluation (2 tools)

- **`:eval`** - Evaluate Clojure code in the connected nREPL session
- **`:load-file`** - Load a Clojure file into the nREPL session

### Refactoring (11 tools)

- **`:clean-ns`** - Clean and organize namespace declarations using refactor-nrepl
- **`:find-symbol`** - Find all occurrences of a symbol in the codebase
- **`:rename-file-or-dir`** - Rename a file or directory and update all references
- **`:resolve-missing`** - Resolve missing or unresolved symbols
- **`:find-used-locals`** - Find locally used variables at a specific location
- **`:extract-function`** - Extract selected code into a new function
- **`:extract-variable`** - Extract current expression into a let binding
- **`:add-function-parameter`** - Add a parameter to a function definition
- **`:organize-imports`** - Organize and clean up namespace imports
- **`:inline-function`** - Inline a function call by replacing it with the function body
- **`:rename-local-variable`** - Rename a local variable within its scope

### Cider-nREPL (12 tools)

- **`:format-code`** - Format Clojure code using cider-nrepl's format-code operation
- **`:macroexpand`** - Expand Clojure macros using cider-nrepl's macroexpand operation
- **`:eldoc`** - Get function documentation and signatures using cider-nrepl's eldoc operation
- **`:complete`** - Get code completion candidates using cider-nrepl's complete operation
- **`:apropos`** - Search for symbols matching a pattern using cider-nrepl's apropos operation
- **`:test-all`** - Run all tests in the project using cider-nrepl's test-all operation
- **`:info`** - Get enhanced symbol information using cider-nrepl's info operation
- **`:ns-list`** - Browse all available namespaces for rapid codebase exploration
- **`:ns-vars`** - Explore namespace contents - get all vars in a namespace
- **`:classpath`** - Understand available dependencies and classpath entries
- **`:refresh`** - Safely refresh user namespaces without killing server infrastructure
- **`:test-var-query`** - Run specific tests instead of all tests for rapid iteration

### Structural Editing (12 tools)
Session-based structural editing using rewrite-clj zippers for precise code manipulation:

#### Session Management

- **`:structural-create-session`** - Create a new structural editing session from file or code string
- **`:structural-save-session`** - Save structural editing session to file or get as string
- **`:structural-close-session`** - Close a structural editing session
- **`:structural-get-info`** - Get comprehensive information about current zipper position
- **`:structural-list-sessions`** - List all active structural editing sessions

#### Code Navigation & Search

- **`:structural-find-symbol-enhanced`** - Find symbols with enhanced matching including keywords and flexible patterns

#### Code Modification

- **`:structural-replace-node`** - Replace current node with new expression
- **`:structural-bulk-find-and-replace`** - Find and replace all occurrences of a pattern with enhanced symbol matching
- **`:structural-extract-to-let`** - Extract current expression to a let binding
- **`:structural-thread-first`** - Convert expression to thread-first macro
- **`:structural-insert-after`** - Insert expression after current node with proper formatting
- **`:structural-insert-before`** - Insert expression before current node with proper formatting

**Structural Editing Workflow**: Create a session → Navigate/Search → Modify code → Save/Close session. All modifications are performed using rewrite-clj zippers for precise AST manipulation while preserving formatting.

### Function Refactoring (5 tools)

- **`:find-function-definition`** - Find the definition of a function in a file
- **`:rename-function-in-file`** - Rename a function and all its invocations within a single file
- **`:find-function-usages-in-project`** - Find all usages of a function across the entire project
- **`:rename-function-across-project`** - Rename a function and all its usages across an entire project
- **`:replace-function-definition`** - Replace an entire function definition with a new implementation

### Test Generation (1 tool)

- **`:create-test-skeleton`** - Generate a comprehensive test skeleton for a Clojure function with multiple test cases and documentation

### Static Analysis (3 tools)

- **`:lint-code`** - Lint Clojure code string for errors and style issues
- **`:lint-project`** - Lint entire project or specific paths for errors and style issues
- **`:setup-clj-kondo`** - Initialize or update clj-kondo configuration for the project

### Dependency Management (3 tools)

- **`:add-libs`** - Add libraries to the running REPL without restart (Clojure 1.12+)
- **`:sync-deps`** - Sync dependencies from deps.edn that aren't yet on the classpath
- **`:check-namespace`** - Check if a library/namespace is available on the classpath

### Advanced Navigation (2 tools)

- **`:call-hierarchy`** - Analyze function call hierarchy (callers) in a Clojure project
- **`:usage-finder`** - Find all usages of a symbol across the project with detailed analysis

### Profiling (2 tools)
Performance analysis tools using clj-async-profiler for detailed profiling:

- **`:profile-cpu`** - Profile CPU usage of Clojure code with comprehensive analysis
- **`:profile-alloc`** - Profile memory allocation of Clojure code with comprehensive analysis

**Profiling Requirements**: JVM must be started with profiling JVM options (see Quick Start section). Profiling tools require clj-async-profiler dependency.

## Tool Usage Examples

### Basic Evaluation
```clojure
;; Evaluate code in the current namespace
eval: (+ 1 2 3)

;; Evaluate in a specific namespace
eval: (defn greet [name] (str "Hello, " name "!"))
namespace: "my.app.core"
```

### Structural Editing Workflow
```clojure
;; 1. Create a session for a file
structural-create-session: 
  session-id: "edit-session-1"
  source: "/path/to/file.clj"
  from-file: true

;; 2. Navigate to a symbol
structural-find-symbol-enhanced:
  session-id: "edit-session-1"
  symbol-name: "my-function"

;; 3. Replace the current node
structural-replace-node:
  session-id: "edit-session-1"
  new-expression: "(defn my-function [x y] (+ x y))"

;; 4. Save changes
structural-save-session:
  session-id: "edit-session-1"
  file-path: "/path/to/file.clj"
```

### Function Refactoring
```clojure
;; Find function definition
find-function-definition:
  file-path: "/path/to/file.clj"
  function-name: "calculate-total"

;; Rename function across entire project
rename-function-across-project:
  project-root: "/path/to/project"
  old-name: "calculate-total"
  new-name: "compute-sum"
```

### Dependency Management
```clojure
;; Add a new library at runtime
add-libs:
  coordinates: {"hiccup/hiccup" {:mvn/version "1.0.5"}}

;; Check if namespace is available
check-namespace:
  namespace: "hiccup.core"
```

### Code Quality
```clojure
;; Lint code string
lint-code:
  code: "(defn broken-fn [x] (if x x))"

;; Lint entire project
lint-project:
  paths: ["src", "test"]
```

## Development

### Adding New Tools

```clojure
(require '[is.simm.repl-mcp.api :as mcp])

(mcp/register-tool! 
  :my-new-tool
  "Description of what the tool does"
  {:param1 {:type "string" :description "First parameter"}}
  (fn [tool-call context]
    {:value "Processed" :status :success}))
```

Tools are immediately available via MCP with client notifications. For permanent tools, create a namespace in `src/is/simm/repl_mcp/tools/`.

## Testing

```bash
clojure -X:test
```

## Library Usage

Use repl-mcp as a library to build custom MCP servers:

```clojure
(ns my-mcp-server
  (:require [is.simm.repl-mcp.transport :as transport]
            [is.simm.repl-mcp.api :as mcp]))

;; Define custom tools
(mcp/register-tool! 
  :my-tool "Custom tool description"
  {:param {:type "string" :description "Parameter description"}}
  (fn [tool-call context]
    {:value "Custom tool result" :status :success}))

;; Create and start server (requires Jetty)
(defn start-custom-server! []
  (let [transport-configs {:http-sse (transport/create-transport-config :http-sse)}
        ;; This collects all registered tools and prompts
        context (transport/create-context)]
    (transport/start-mcp-server! transport-configs context)))
```

## License

Copyright © 2025 Christian-weilbach

Distributed under the MIT license.
