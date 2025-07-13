# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Instructions

1. Use the interactive Clojure development tools provided through MCP in preference to your default text editing tools and system calls. `setup-clj-kondo` to optionally get linting support. To add dependencies on the fly you can use `add-libs` and `sync-libs`.
2. In particular use the `eval` MCP call to draft and test code before adding it to the project. Grow the code until you are confident it is behaving as intended. Use `lint-code` if available to spot problems early.
3. If you need to add new code you can use your normal `Edit` tool to add code, and then use the `load-file` to load the changed file and optionally use namespace to reset namespaces if needed. Use `lint-project` if available to check the new code is conform.
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
- **Code quality/linting**: Use `lint-code` for real-time feedback, `lint-project` for codebase analysis
- **Dependency management**: Use `add-libs` for runtime dependencies, `sync-deps` for project sync, `check-namespace` for availability
- **Debugging**: 
  - **Quick fixes**: Use direct approach for obvious issues (wrong operators, typos, clear logic errors)
  - **Systematic investigation**: Use `debug-function` prompt for complex bugs, unfamiliar code, or when stuck
  - **Learning/teaching**: Use structured workflows to demonstrate debugging techniques

### Code Quality Workflow

**Static Analysis Integration**: The server includes comprehensive clj-kondo integration for real-time code quality feedback.

**Available Tools:**
- **`lint-code`**: Lint code strings during interactive development
- **`lint-project`**: Analyze entire directories or projects for issues  
- **`setup-clj-kondo`**: Initialize or update project linting configuration

**Workflow Integration Patterns:**

1. **Interactive Development**: Use `lint-code` after `eval` to catch quality issues early
2. **Pre-commit Checks**: Run `lint-project` on modified files before committing
3. **Refactoring Validation**: Use linting after structural editing to ensure code quality
4. **Project Setup**: Run `setup-clj-kondo` when adding the MCP server to new projects or when dependencies change

**Configuration Management:**
- Project-specific rules are automatically loaded from `.clj-kondo/config.edn`
- Custom configurations can be passed per-invocation for specific use cases
- Use `setup-clj-kondo` with `copy-configs: true` to import library-specific linting rules

**Quality Gates:**
- Code strings with syntax errors return `isError: true`
- Unused bindings, imports, and variables are flagged as warnings
- Namespace mismatches and structural issues are detected
- Custom severity levels can be configured per linter rule

### Dependency Management Workflow

**Runtime Dependency Addition**: The server includes Clojure 1.12+ integration for hot-loading dependencies without REPL restart.

**Available Tools:**
- **`add-libs`**: Add new libraries to the running REPL classpath
- **`sync-deps`**: Synchronize dependencies from deps.edn that aren't yet loaded
- **`check-namespace`**: Verify if a namespace/library is available on the classpath

**Workflow Integration Patterns:**

1. **Interactive Development**: Use `add-libs` to try new libraries during development
2. **Project Synchronization**: Use `sync-deps` after updating deps.edn to load new dependencies
3. **Dependency Verification**: Use `check-namespace` to confirm library availability before use
4. **Hot Development**: Add dependencies without losing REPL state or stopping development

**Usage Requirements:**
- Requires Clojure 1.12+ for add-libs functionality
- Only works in REPL context (requires `*repl*` to be true)
- Dependencies must be available in Maven repositories
- Project must be using tools.deps (deps.edn) for dependency management

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

# Code quality commands (via MCP tools)
# Note: These require an active MCP session
# lint-code: Check code strings for issues
# lint-project: Analyze project files
# setup-clj-kondo: Initialize linting configuration
```

## Architecture

This is a **Model Context Protocol (MCP) server** for Clojure development with 47 built-in tools. The architecture is built around a unified server design with transport abstraction.

### Core Components

**Transport Layer**: Clean abstraction supporting both STDIO (for CLI tools like Claude Code) and HTTP+SSE (for web clients)
- `transport.clj`: Transport protocol and configuration
- `transports/stdio.clj`: STDIO JSON-RPC transport
- `transports/http_sse.clj`: HTTP Server-Sent Events transport

**Tool System**: Dynamic tool registration with runtime addition/removal
- `dispatch.clj`: Multimethod-based tool routing with registries
- `tools/`: 47 tools across 8 categories (evaluation, refactoring, cider-nrepl, structural editing, function refactoring, test generation, static analysis, dependency management)
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

### Tool Categories (47 total)

**Evaluation (2)**: Direct nREPL integration for code evaluation and file loading
**Refactoring (11)**: Namespace cleaning, symbol finding, code extraction, import organization
**Cider-nREPL (12)**: Development tools like formatting, testing, completion, documentation
**Structural Editing (10)**: Session-based code manipulation using rewrite-clj and zippers
**Function Refactoring (5)**: Project-wide function operations (rename, find usages, replace)
**Test Generation (1)**: Comprehensive test skeleton generation
**Static Analysis (3)**: clj-kondo integration for linting, code quality, and style enforcement
**Dependency Management (3)**: Runtime dependency addition, project synchronization, namespace availability checks

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
- All tests run with `clojure -X:test` (56 tests, 414 assertions)
- Test fixtures in `test_fixtures.clj` for common test patterns
- nREPL integration testing for dependency management and evaluation tools

### Project Integration

This project is designed to be added to other Clojure projects via the `:repl-mcp` alias in `deps.edn`. When users add it to their projects, they get access to all 47 tools for their specific codebase through the nREPL connection.

The MCP server connects to the project's nREPL server to provide tools that operate on the actual project code, making it context-aware for the specific codebase being worked on.