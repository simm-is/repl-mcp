# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

This MCP server is built on [mcp-toolkit](https://github.com/metosin/mcp-toolkit) providing a clean, simplified architecture with minimal dependencies. All 51 tools follow consistent patterns for reliability and ease of use.

## Instructions

1. **Interactive Development**: Use the MCP tools for Clojure development in preference to default text editing. Start with `eval` to test code interactively before committing changes.
2. **Code Quality**: Use `lint-code` for immediate feedback during development, `lint-project` for comprehensive checks, and `setup-clj-kondo` to initialize linting in new projects.
3. **File Management**: When adding new code, use the standard `Edit` tool followed by `load-file` to reload changes into the REPL. This maintains REPL state while updating code.
4. **Refactoring**: Use structural editing tools for complex refactoring to maintain code integrity. Fall back to `Edit` + `load-file` if issues arise.
5. **Testing**: Use `create-test-skeleton` for comprehensive test generation, `test-all` for full test runs, and `test-var-query` for targeted testing.
6. **Debugging**: Apply direct fixes for obvious issues. For complex problems, use systematic investigation with appropriate tools.
7. **Dependencies**: Use `add-libs` for runtime dependency addition (Clojure 1.12+), `sync-deps` after updating deps.edn, and `check-namespace` to verify availability.

### Workflow Philosophy

**Simple, Direct, Effective**: The mcp-toolkit foundation enables straightforward tool usage with consistent patterns and reliable error handling.

**Tool Selection Principles:**
- Use the most direct tool for the task
- All tools follow the same pattern: input → processing → structured output
- Error handling is built-in - tools return clear error messages
- No complex state management - each tool call is independent

### Tool Categories

**Evaluation (2 tools)**
- `eval` - Execute code in REPL with proper namespace context
- `load-file` - Load files to update REPL state

**Refactoring (11 tools)**
- `clean-ns` - Organize imports and namespaces
- `rename-function-*` - Rename across files or projects
- `extract-function` - Extract code into new functions
- `find-symbol` - Locate symbol definitions and usages

**Navigation & Analysis (14 tools)**
- `call-hierarchy` - Trace function relationships
- `usage-finder` - Find all usages with context
- `ns-vars`, `ns-list` - Explore namespaces
- `info`, `eldoc` - Get documentation

**Testing (3 tools)**
- `create-test-skeleton` - Generate test templates
- `test-all` - Run entire test suite
- `test-var-query` - Run specific tests

**Structural Editing (10 tools)**
- Session-based editing with zipper navigation
- Safe code transformations preserving structure

**Performance (2 tools)**
- `profile-cpu` - CPU usage analysis with flamegraphs
- `profile-alloc` - Memory allocation profiling

**Code Quality (3 tools)**
- `lint-code` - Check code strings
- `lint-project` - Analyze entire codebases
- `setup-clj-kondo` - Initialize linting

**Dependencies (3 tools)**
- `add-libs` - Hot-load dependencies
- `sync-deps` - Sync from deps.edn
- `check-namespace` - Verify availability

### Code Quality Workflow

**Integrated Linting**: clj-kondo provides real-time feedback with zero configuration required.

**Quick Start:**
1. Run `setup-clj-kondo` once per project to initialize
2. Use `lint-code` during development for immediate feedback
3. Run `lint-project` before commits for comprehensive checks

**Key Features:**
- Automatic config detection from `.clj-kondo/config.edn`
- Library-specific rules via `copy-configs: true`
- Clear error/warning distinction
- Fast performance even on large codebases

### Dependency Management

**Hot-loading Support**: Add dependencies without restarting your REPL (Clojure 1.12+).

**Simple Workflow:**
```clojure
;; Check if library is available
check-namespace: "hiccup.core"

;; Add new dependency
add-libs: {hiccup/hiccup {:mvn/version "1.0.5"}}

;; Sync after editing deps.edn
sync-deps
```

**Requirements:**
- Clojure 1.12+ for `add-libs`
- Maven/Clojars availability
- tools.deps project structure

### Navigation & Analysis

**Smart Navigation**: AST-based analysis provides accurate code understanding.

**Key Tools:**
- `call-hierarchy` - Who calls this function?
- `usage-finder` - Where is this symbol used?
- `find-function-definition` - Jump to definition
- `find-symbol` - Locate symbol at position

**Usage Example:**
```clojure
;; Find all callers of a function
call-hierarchy:
  namespace: "myapp.core"
  function: "process-data"

;; Find all usages with context
usage-finder:
  namespace: "myapp.core" 
  symbol: "config"
  include-context: true
```

### Performance Profiling

**Integrated Profiling**: Built-in CPU and memory profiling with clj-async-profiler.

**Simple Usage:**
```clojure
;; Profile CPU usage
profile-cpu:
  code: "(reduce + (range 1000000))"
  duration: 5000
  generate-flamegraph: true

;; Profile memory allocations  
profile-alloc:
  code: "(repeatedly 1000 #(vec (range 100)))"
  duration: 3000
```

**Tips:**
- Default 5-second duration is usually sufficient
- Flamegraphs help visualize hot paths
- For accurate CPU profiling of hot code, warm up JIT first:
  ```clojure
  eval: "(dotimes [_ 1000] (your-function))"
  ```

## Commands

### Quick Start
```bash
# Start MCP server (STDIO + nREPL on 17888)
clojure -M:repl-mcp

# HTTP+SSE transport  
clojure -M:repl-mcp --http-port 8080

# Custom nREPL port
clojure -M:repl-mcp --nrepl-port 27889
```

### Development
```bash
# Run tests
clojure -X:test

# List available tools
clojure -M:repl-mcp --list-tools

# Get tool help
clojure -M:repl-mcp --tool-help profile-cpu
```

## Architecture

**Built on mcp-toolkit**: A clean, maintainable MCP server with 51 tools for comprehensive Clojure development support.

### Simple Design

1. **Transport Layer**: Pluggable transports (STDIO, HTTP+SSE) via mcp-toolkit
2. **Tool System**: Consistent tool pattern with automatic registration
3. **nREPL Integration**: Direct connection to project REPL for all operations

### Tool Implementation Pattern

Every tool follows the same simple pattern:

```clojure
;; Tool function receives MCP context and arguments
(defn my-tool-fn [mcp-context arguments]
  (let [{:keys [param1 param2]} arguments
        nrepl-client (:nrepl-client mcp-context)]
    ;; Implementation
    {:content [{:type "text" :text "Result"}]}))

;; Tool definition for mcp-toolkit
(def tools
  [{:name "my-tool"
    :description "What the tool does"
    :inputSchema {:type "object"
                  :properties {:param1 {:type "string"}}
                  :required ["param1"]}
    :tool-fn my-tool-fn}])
```

### Key Benefits

- **Minimal Dependencies**: Just mcp-toolkit, nREPL, and tool-specific libs
- **Consistent Patterns**: All tools work the same way
- **Robust Error Handling**: Built into every tool
- **Easy Testing**: Simple functions with clear inputs/outputs
- **Extensible**: Add new tools by following the pattern

## Development

### Adding New Tools

1. Create a file in `src/is/simm/repl_mcp/tools/`
2. Implement tool function and definitions:

```clojure
(ns is.simm.repl-mcp.tools.my-tools
  (:require [nrepl.core :as nrepl]))

(defn my-tool-fn [mcp-context arguments]
  (let [{:keys [input]} arguments]
    {:content [{:type "text" 
                :text (str "Processed: " input)}]}))

(def tools
  [{:name "my-tool"
    :description "Process input"
    :inputSchema {:type "object"
                  :properties {:input {:type "string"}}
                  :required ["input"]}
    :tool-fn my-tool-fn}])
```

3. Tools auto-register on namespace load

### Testing

```bash
# Run all tests
clojure -X:test

# Test specific namespace
clojure -X:test :nses '[is.simm.repl-mcp.tools.my-tools-test]'
```

### Project Integration

Add to any Clojure project's `deps.edn`:

```clojure
{:aliases
 {:repl-mcp {:git/url "https://github.com/simm-is/repl-mcp"
             :git/sha "latest"
             :main-opts ["-m" "is.simm.repl-mcp"]}}}
```

The MCP server connects to your project's nREPL for context-aware tooling.