# repl-mcp

**This is alpha software and feedback is wanted! The project is similar in spirit to [clojure-mcp](https://github.com/bhauman/clojure-mcp), but *liberally licensed* and aiming for minimal friction with the REPL from the perspective of agentic coding assistant tools like Claude code.**

A [Model Context Protocol](https://modelcontextprotocol.io/) (MCP) server for Clojure development that provides built-in tools for interactive coding assistance and structural editing via nREPL integration. You can use it during development, e.g. in Claude code, by just adding it to your project. Optionally you can also use the same functionality to export your own functions through the MCP interface and also run your own dedicated MCP server with a network transport (SSE) for production. The overall philosophy is to minimize friction and speed up the feedback loop with the Clojure REPL during development. MCP supports dynamic exports of tools and prompts, so you can even create your own tools/workflows and use them in your coding assistant without restarts (this [does not work with Claude code as a MCP client yet](https://github.com/anthropics/claude-code/issues/2722).)

**Key Features:**
- **41 Built-in Tools**: Evaluation, refactoring, cider-nrepl, structural editing, and test generation
- **Dynamic Tool Registration**: Add new tools while the server is running
- **Transport Abstraction**: STDIO and HTTP+SSE transport support
- **nREPL Integration**: Full cider-nrepl and refactor-nrepl middleware support

Create project-specific tools interactively for reliable, predictable workflows. Configure your coding assistant to prefer these tools via [CLAUDE.md](./CLAUDE.md) in your project.

## Quick Start

### Add to Your Project

Add repl-mcp to your `deps.edn` dependencies and include the `:repl-mcp` alias (copy it from our deps.edn):

```clojure
{:deps {is.simm/repl-mcp {:git/url "https://github.com/simm-is/repl-mcp"
                          :git/sha "latest-sha"}}
 :aliases
 {:repl-mcp {:main-opts ["-m" "is.simm.repl-mcp"]
             :extra-deps {nrepl/nrepl {:mvn/version "1.0.0"}
                          cider/cider-nrepl {:mvn/version "0.47.1"}
                          rewrite-clj/rewrite-clj {:mvn/version "1.1.47"}
                          refactor-nrepl/refactor-nrepl {:mvn/version "3.10.0"}
                          dev.weavejester/cljfmt {:mvn/version "0.13.1"}
                          org.slf4j/slf4j-api {:mvn/version "2.0.17"}
                          org.slf4j/slf4j-nop {:mvn/version "2.0.17"}
                          org.eclipse.jetty.ee10/jetty-ee10-servlet {:mvn/version "12.0.5"}
                          org.eclipse.jetty/jetty-server {:mvn/version "12.0.5"}
                          jakarta.servlet/jakarta.servlet-api {:mvn/version "6.0.0"}}}}}
```

Also make sure to provide the `Instructions` section from `CLAUDE.md` to your assistant to be aware of and prioritize the tools and prompts.

### Start the MCP Server

```bash
# In your project directory
clojure -M:repl-mcp
```

This starts:
- **nREPL server** on localhost:17888 (for coding operations on your project, you can also connect to it)
- **STDIO transport** for MCP communication via stdin/stdout (for local tools) 
- **HTTP+SSE server** on localhost:18080 (for web-based MCP clients)

### Claude Code Integration

```bash
# Add to Claude Code
claude mcp add repl-mcp -- clojure -M:repl-mcp
```

TODO: Add other integration instructions here, please open a PR.

## Custom Ports

To avoid port conflicts:

```bash
# Start with custom nREPL port
clojure -M:repl-mcp 19888
```

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

## Available Tools (41 Total)

**Evaluation (2)**: `:eval`, `:load-file`  
**Refactoring (11)**: `:clean-ns`, `:find-symbol`, `:rename-file-or-dir`, `:resolve-missing`, `:find-used-locals`, `:extract-function`, `:extract-variable`, `:add-function-parameter`, `:organize-imports`, `:inline-function`, `:rename-local-variable`  
**Cider-nREPL (12)**: `:format-code`, `:macroexpand`, `:eldoc`, `:complete`, `:apropos`, `:test-all`, `:enhanced-info`, `:ns-list`, `:ns-vars`, `:classpath`, `:refresh`, `:test-var-query`  
**Structural Editing (10)**: `:structural-create-session`, `:structural-save-session`, `:structural-close-session`, `:structural-get-info`, `:structural-list-sessions`, `:structural-find-symbol-enhanced`, `:structural-replace-node`, `:structural-bulk-find-and-replace`, `:structural-extract-to-let`, `:structural-thread-first`  
**Function Refactoring (5)**: `:find-function-definition`, `:rename-function-in-file`, `:find-function-usages-in-project`, `:rename-function-across-project`, `:replace-function-definition`  
**Test Generation (1)**: `:create-test-skeleton`

## Development

### Adding New Tools

```clojure
(require '[is.simm.repl-mcp.api :as mcp])

(mcp/register-tool! 
  :my-new-tool
  "Description of what the tool does"
  {:param1 {:type "string" :description "First parameter"}}
  (fn [tool-call context]
    {:result "Processed" :status :success}))
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
    {:result "Custom tool result" :status :success}))

;; Create and start server (require Jetty)
(defn start-custom-server! []
  (let [transport-configs {:http-sse (transport/create-transport-config :http-sse)}
        ;; this collects all registered tools and prompts
        context (transport/create-context)]
    (transport/start-mcp-server! transport-configs context)))
```

## License

Copyright © 2025 Christian-weilbach

Distributed under the MIT license.
