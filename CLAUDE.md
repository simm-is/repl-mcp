# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Instructions

1. Use the interactive Clojure development tools provided through MCP in preference to your default text editing tools and system calls.
2. In particular use the `eval` MCP call to draft and test code before adding it to the project. Grow the code until you are confident it is behaving as intended.
3. If you need to add new code you can use your normal `Edit` tool to add code, and then use the `load-file` to load the changed file and optionally use namespace to reset namespaces if needed.
4. If you need to refactor code prefer to use the structural editing tools to avoid breaking parentheses and existing bindings.
5. For bigger chunks of functionality draft a test case to cover important invariants of the code you have established. For new functionality you can add it with the `tdd-workflow` prompt template.
6. For debugging, use your expertise first - if the issue is obvious, fix it directly. Use the `debug-function` prompt for complex/unfamiliar issues or when systematic investigation is needed.
7. If structural editing tools malfunction, fall back to regular Edit tool and reload with `load-file`.

### Workflow Philosophy

**Balance efficiency with thoroughness**: Use your expertise and pattern recognition first, then fall back to structured workflows when needed.

**When to use direct approaches:**
- Obvious bugs (wrong operators, typos, clear logic errors)
- Familiar code patterns and domains
- Time-critical situations
- Simple, isolated functions

**When to use structured workflows (prompts):**
- Complex, unfamiliar codebases
- When stuck or unsure of approach
- Learning/teaching scenarios
- Critical systems requiring audit trails
- Team environments needing consistent practices

### Tool Selection Guide

- **Code development/testing**: Use `eval` for interactive development
- **Simple file edits**: Use `Edit` tool for straightforward additions
- **Complex refactoring**: Use `structural-*` tools for sophisticated code transformations
- **File reloading**: Use `load-file` after making changes
- **Debugging**: 
  - **Quick fixes**: Use direct approach for obvious issues (wrong operators, typos, clear logic errors)
  - **Systematic investigation**: Use `debug-function` prompt for complex bugs, unfamiliar code, or when stuck
  - **Learning/teaching**: Use structured workflows to demonstrate debugging techniques

## Commands

### Development Commands
```bash
# Start the MCP server (default: STDIO transport, nREPL on 17888)
clojure -M:repl-mcp

# Start with both STDIO and HTTP+SSE transports
clojure -M:repl-mcp --dual-transport

# Start with custom nREPL port
clojure -M:repl-mcp --nrepl-port 19888

# Run all tests
clojure -X:test

# Build JAR and run CI pipeline
clojure -M:build ci

# Introspection commands
clojure -M:repl-mcp --list-tools
clojure -M:repl-mcp --tool-help eval
clojure -M:repl-mcp --list-prompts
```

## Architecture

This is a **Model Context Protocol (MCP) server** for Clojure development with 41 built-in tools. The architecture is built around a unified server design with transport abstraction.

### Core Components

**Transport Layer**: Clean abstraction supporting both STDIO (for CLI tools like Claude Code) and HTTP+SSE (for web clients)
- `transport.clj`: Transport protocol and configuration
- `transports/stdio.clj`: STDIO JSON-RPC transport
- `transports/http_sse.clj`: HTTP Server-Sent Events transport

**Tool System**: Dynamic tool registration with runtime addition/removal
- `dispatch.clj`: Multimethod-based tool routing with registries
- `tools/`: 41 tools across 6 categories (evaluation, refactoring, cider-nrepl, structural editing, function refactoring, test generation)
- `interactive.clj`: Runtime tool definition using `register-tool!` function

**Server Core**: Unified server managing both transports simultaneously
- `server.clj`: Main server using transport abstraction with nREPL integration
- `api.clj`: Clean API with 8 core functions for tool/prompt management
- `util.clj`: Shared MCP specification utilities

### Key Data Flow

1. **Server Startup**: `is.simm.repl-mcp/-main` -> `server/start-mcp-server!` -> transport abstraction
2. **Tool Registration**: Tools auto-register via `dispatch/register-tool!` during namespace loading
3. **MCP Requests**: Transport -> `dispatch/handle-tool-call` multimethod -> tool handler -> response
4. **Dynamic Tools**: `interactive/register-tool!` -> `defmethod` -> `dispatch/register-tool!` -> client notifications

### Tool Categories (41 total)

**Evaluation (2)**: Direct nREPL integration for code evaluation and file loading
**Refactoring (11)**: Namespace cleaning, symbol finding, code extraction, import organization
**Cider-nREPL (12)**: Development tools like formatting, testing, completion, documentation
**Structural Editing (10)**: Session-based code manipulation using rewrite-clj and zippers
**Function Refactoring (5)**: Project-wide function operations (rename, find usages, replace)
**Test Generation (1)**: Comprehensive test skeleton generation

### nREPL Integration

The server starts an nREPL server (default port 17888) with cider-nrepl and refactor-nrepl middleware. Tools requiring nREPL access receive an `:nrepl-client` in their context. The nREPL connection is separate from MCP transport - MCP handles tool calls while nREPL handles code evaluation.

### Logging Strategy

File-only logging via Telemere to avoid stdout contamination (stdout reserved for JSON-RPC with STDIO transport). Logs go to `repl-mcp.log` in the current directory.

## Development Notes

### Adding New Tools

Use the interactive registration system:
```clojure
(require '[is.simm.repl-mcp.interactive :as interactive])

(interactive/register-tool! 
  :my-tool
  "Description of what the tool does"
  {:param {:type "string" :description "Parameter description"}}
  (fn [tool-call context]
    {:result "Tool result" :status :success}))
```

For permanent tools, create a file in `src/is/simm/repl_mcp/tools/` following the pattern of existing tools.

### Transport Abstraction

The transport system uses protocols (`McpTransport`) for pluggable implementations. Each transport registers itself in the transport registry during namespace loading. The server creates transport instances via `create-transport` and manages them through `MultiTransportServer`.

### Testing Strategy

- Unit tests for individual tools in `test/is/simm/repl_mcp/tools/`
- Integration tests in `test/is/simm/repl_mcp/`
- All tests run with `clojure -X:test` (43 tests, 328 assertions)
- Test fixtures in `test_fixtures.clj` for common test patterns

### Project Integration

This project is designed to be added to other Clojure projects via the `:repl-mcp` alias in `deps.edn`. When users add it to their projects, they get access to all 41 tools for their specific codebase through the nREPL connection.

The MCP server connects to the project's nREPL server to provide tools that operate on the actual project code, making it context-aware for the specific codebase being worked on.