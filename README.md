# repl-mcp

**This is alpha software and feedback is very welcome! It is deliberately not well packaged and abstracted over yet, so you can easily interact with its pieces. The project is similar in spirit to [clojure-mcp](https://github.com/bhauman/clojure-mcp), but *liberally licensed* and aiming for minimal friction with the REPL by providing powerful IO and development tools from the perspective of autonomous agentic coding assistant tools like Claude code.**

A [Model Context Protocol](https://modelcontextprotocol.io/) (MCP) server for Clojure development that provides built-in tools for interactive coding assistance and structural editing via nREPL integration. You can use it during development, e.g. in Claude code, by just adding it to your project. Optionally you can also use the same functionality to export your own functions through the MCP interface and also run your own dedicated MCP server with a network transport (SSE) for production. The overall philosophy is to minimize friction and speed up the feedback loop with the Clojure REPL during development. MCP supports dynamic exports of tools and prompts, so you can even create your own tools/workflows and use them in your coding assistant without restarts (this [does not work with Claude code as a MCP client yet](https://github.com/anthropics/claude-code/issues/2722).)

## Key Features

- **51 Development Tools**: Comprehensive toolset for evaluation, refactoring, structural editing, testing, profiling, and more
- **Pure Clojure Implementation**: Built on mcp-toolkit for simplicity and reliability
- **Dual Transport Support**: STDIO (for local development) and HTTP+SSE (for production)
- **nREPL Integration**: Full cider-nrepl and refactor-nrepl middleware support
- **Hot Reloading**: Add dependencies at runtime without REPL restart (Clojure 1.12+)
- **Performance Profiling**: CPU and memory profiling with clj-async-profiler integration

## Quick Start

### Add to Your Project

Add the `:repl-mcp` alias to your `deps.edn`:

```clojure
{:deps {is.simm/repl-mcp {:git/url "https://github.com/simm-is/repl-mcp"
                          :git/sha "latest-sha"}}
 :aliases
 {:repl-mcp {:main-opts ["-m" "is.simm.repl-mcp"]
             :extra-paths ["test"] ;; If you want it to be able to run the tests
             ;; Option for clj-async-profiler
             :jvm-opts ["-Djdk.attach.allowAttachSelf"
                        "-XX:+UnlockDiagnosticVMOptions"
                        "-XX:+DebugNonSafepoints"
                        "-XX:+EnableDynamicAgentLoading"
                        "--enable-native-access=ALL-UNNAMED"]
             :extra-deps {nrepl/nrepl {:mvn/version "1.0.0"}
                          cider/cider-nrepl {:mvn/version "0.47.1"}
                          refactor-nrepl/refactor-nrepl {:mvn/version "3.10.0"}
                          clj-kondo/clj-kondo {:mvn/version "2025.06.05"}
                          rewrite-clj/rewrite-clj {:mvn/version "1.1.47"}
                          dev.weavejester/cljfmt {:mvn/version "0.13.1"}
                          ;; Performance analysis dependencies
                          criterium/criterium {:mvn/version "0.4.6"}
                          com.clojure-goes-fast/clj-async-profiler {:mvn/version "1.6.1"}

                          org.clojure/tools.cli {:mvn/version "1.1.230"}

                          org.slf4j/slf4j-api {:mvn/version "2.0.17"}
                          org.slf4j/slf4j-simple {:mvn/version "2.0.17"}}}}}
```

### Start the MCP Server

```bash
# STDIO transport (default, for development)
clojure -M:repl-mcp

# HTTP+SSE transport (for production)
clojure -M:repl-mcp --transport sse --http-port 8080

# Custom nREPL port
clojure -M:repl-mcp --nrepl-port 27889
```

This starts:
- **nREPL server** on localhost:17888 (default) with cider-nrepl and refactor-nrepl middleware
- **MCP server** with your chosen transport (STDIO or HTTP+SSE)

### Assistant Integration

#### Claude Desktop

```bash
# Add with STDIO transport
claude mcp add repl-mcp -- clojure -M:repl-mcp

# Or with HTTP+SSE transport (specify unique port per project)
claude mcp add --transport sse repl-mcp http://localhost:8080/sse
```

#### VS Code

Add `.vscode/mcp.json` to your project:

```json
{
  "servers": {
    "repl-mcp": {
      "type": "stdio",
      "command": "clojure",
      "args": ["-M:repl-mcp"]
    }
  }
}
```

## Available Tools (51 Total)

### Core Development
- **eval** - Evaluate Clojure code in the nREPL session
- **load-file** - Load Clojure files into the REPL
- **format-code** - Format code using cljfmt
- **lint-code** - Lint code with clj-kondo
- **lint-project** - Lint entire directories
- **setup-clj-kondo** - Initialize clj-kondo configuration

### Code Analysis with clj-kondo
- **analyze-project** - Get full AST analysis data for projects
- **find-unused-vars** - Find all unused variables and functions
- **find-var-definitions** - Find variable and function definitions
- **find-var-usages** - Find all usages of variables and functions

### Refactoring Tools
- **clean-ns** - Clean and organize namespaces
- **rename-function-across-project** - Rename functions project-wide
- **extract-function** - Extract code into new functions
- **inline-function** - Inline function calls
- **find-symbol** - Find symbol occurrences

### Navigation & Analysis
- **call-hierarchy** - Analyze function call relationships
- **usage-finder** - Find all symbol usages with context
- **find-function-definition** - Locate function definitions

### Testing & Quality
- **create-test-skeleton** - Generate comprehensive test templates
- **test-all** - Run all project tests
- **test-var-query** - Run specific tests

### Performance Profiling
- **profile-cpu** - Profile CPU usage with flamegraphs
- **profile-alloc** - Profile memory allocations

### Dependency Management
- **add-libs** - Hot-load dependencies (Clojure 1.12+)
- **sync-deps** - Sync deps.edn dependencies
- **check-namespace** - Verify namespace availability

## Usage Examples

### Basic Evaluation
```clojure
;; Use the eval tool
(+ 1 2 3)
;; => 6

;; Define functions
(defn greet [name]
  (str "Hello, " name "!"))
```

### Refactoring
```clojure
;; Clean namespace
clean-ns: "src/myapp/core.clj"

;; Rename function project-wide
rename-function-across-project:
  project-root: "."
  old-name: "calculate-total"
  new-name: "compute-sum"
```

### Code Analysis
```clojure
;; Find unused variables
find-unused-vars:
  paths: ["src"]

;; Analyze entire project  
analyze-project:
  paths: ["src" "test"]

;; Find all usages of a variable
find-var-usages:
  paths: ["src"]
  namespace-filter: "myapp.core"
```

### Profiling
```clojure
;; Profile CPU usage
profile-cpu:
  code: "(reduce + (range 1000000))"
  duration: 5000

;; Profile memory allocations
profile-alloc:
  code: "(repeatedly 1000 #(str \"test\" (rand-int 100)))"
```

## Architecture

Built on **mcp-toolkit** for a clean, maintainable architecture:

- **Transport Layer**: Unified abstraction for STDIO and HTTP+SSE
- **Tool System**: Simple tool registration with consistent patterns
- **nREPL Integration**: Direct communication with your project's REPL
- **Error Handling**: Robust error handling throughout

### Tool Implementation Pattern

All tools follow a consistent pattern:

```clojure
(defn my-tool [mcp-context arguments]
  (let [{:keys [param1 param2]} arguments
        nrepl-client (:nrepl-client mcp-context)]
    ;; Tool implementation
    {:content [{:type "text" :text "Result"}]}))

(def tools
  [{:name "my-tool"
    :description "Tool description"
    :inputSchema {:type "object"
                  :properties {:param1 {:type "string"}}
                  :required ["param1"]}
    :tool-fn my-tool}])
```

## Development

### Running Tests
```bash
clojure -X:test
```

### Adding New Tools

1. Create a namespace in `src/is/simm/repl_mcp/tools/`
2. Define your tool functions following the pattern above
3. Export a `tools` vector with tool definitions

Tools are automatically discovered and registered when the namespace loads.

## Configuration

Configure your assistant by creating a `CLAUDE.md` file in your project with tool usage instructions and workflow preferences.

## License

Copyright 2025 Christian Weilbach

Distributed under the MIT license.

## Acknowledgments

Built on [mcp-toolkit](https://github.com/metosin/mcp-toolkit) by Metosin for simplified MCP server implementation.