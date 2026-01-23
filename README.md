# MCP Toolkit

[![Clojars Project](https://img.shields.io/clojars/v/fi.metosin/mcp-toolkit.svg)](https://clojars.org/fi.metosin/mcp-toolkit)
[![Slack](https://img.shields.io/badge/slack-mcp--toolkit-orange.svg?logo=slack)](https://clojurians.slack.com/app_redirect?channel=mcp-toolkit)
[![cljdoc badge](https://cljdoc.org/badge/fi.metosin/mcp-toolkit)](https://cljdoc.org/d/fi.metosin/mcp-toolkit)

This library is a very unofficial MCP SDK in Clojure.

It handles the communication between MCP clients and MCP servers, and attempts to provide
a Clojure-ish experience to developers working on expending the MCP ecosystem.

Status: **alpha quality**

Tested on Claude Desktop and Claude Code, no problems found for the features implemented.

## Protocol Version Support

MCP Toolkit supports automatic protocol version negotiation between clients and servers:

- **2025-11-25** (latest) - Full support with all new features
- **2025-06-18** - Full support
- **2025-03-26** - Full backward compatibility
- **2024-11-05** - Legacy support

### New in 2025-11-25

- **Elicitation** - Request additional information from users via forms or URLs
- **Tasks (Experimental)** - Durable state machines for long-running operations
- **Sampling with tools** - LLMs can use tools during sampling requests
- **Icons** - Visual icons for prompts, resources, tools, and templates
- **Server description** - Human-readable server descriptions in initialization
- **JSON Schema 2020-12** - Standard dialect for tool input/output schemas

### New in 2025-06-18

- **Title fields** - Human-readable display names for better UI
- **Structured tool output** - Define output schemas for type-safe responses
- **Resource links** - Tools can return resources alongside content
- **Completion context** - Pass previous values to completion handlers
- **_meta field support** - Optional metadata for various message types
- **Breaking change:** JSON-RPC batching removed (array requests no longer supported)

📚 **[See the 2025-11-25 Migration Guide](doc/MIGRATION-2025-11-25.md)** for upgrading to the latest protocol version.

📚 **[See the 2025-06-18 Migration Guide](MIGRATION-2025-06-18.md)** for upgrading from older versions.

## Implemented features

- [x] API for both clients & servers
- [x] CLJC
  - [x] Clojure
  - [x] Clojurescript
  - [ ] Babashka
- I/O agnostic library
- Uses Promesa to support async tasks in prompts, resources and tools
- Compatible with protocol versions
  - [x] `2024-11-05`
  - [x] `2025-03-26`
  - [x] `2025-06-18`
  - [x] `2025-11-25`
- MCP features implemented
  - [x] Cancellation
  - [x] Ping
  - [x] Progress
  - [x] Roots
  - [x] Sampling
  - [x] Sampling with tools (2025-11-25)
  - [x] Prompts
  - [x] Resources
  - [x] Tools
  - [x] Completion
  - [x] Logging
  - [x] Elicitation (2025-11-25)
  - [x] Tasks - Experimental (2025-11-25)
  - [x] Icons (2025-11-25)
  - [ ] Pagination
- [Example projects](example)
  - [x] [CLJC server using STDIO](example/cljc-server-stdio)
  - [x] [CLJC client using STDIO](example/cljc-client-stdio)
  - [x] [CLJ server using HTTP/SSE](example/clj-server-sse)
  - [ ] CLJ server using Streamable HTTP (PR welcome)


## Dynamic Resources

Resources can provide content in two ways:

### 1. Static Content

Resources with static content include `:text` or `:blob` directly in their definition:

```clojure
{:uri "config://settings"
 :name "Settings"
 :description "Application settings"
 :mime-type "application/json"
 :text "{\"theme\": \"dark\"}"}
```

### 2. Dynamic Content (NEW)

Resources can use a `:read-fn` to generate content on-demand when `resources/read` is called:

```clojure
{:uri "config://status"
 :name "Server Status"
 :description "Current server status (dynamic)"
 :mime-type "application/json"
 :read-fn (fn [context uri]
            ;; Return a map with :text or :blob, or full :contents
            {:text (json/write-str {:status "running"
                                    :uptime (get-uptime)})})}
```

The `:read-fn` receives:
- `context` - Full handler context including `:session` and `:message`
- `uri` - The URI being read

It should return one of:
- `{:text "..."}` - Text content (will be merged with resource metadata)
- `{:blob "..."}` - Binary content as base64
- `{:contents [...]}` - Full MCP contents array
- `{:error {:code "..." :message "..."}}` - Error response

The function can be async (return a Promise).

## Usage

See the `README.md` in the `example/cljc-server-stdio/` project to learn:
- how to use this library to make your own MCP server in Clojure, and
- how to develop its components (prompts, resources and tools) via the REPL
while the server is running.

Additionally, see the documentation on CLJDocs or in the `doc/` directory.

## Testing

```shell
npm install
./bin/kaocha --watch
```

## Its place in the AI ecosystem

MCP toolkit aims to be more convenient for the Clojure community than
the official MCP SDKs for Java or Typescript.

It provides utilities to build an MCP server in Clojure(script), but
doesn't provide any prompts, resources or tools to help working on a Clojure codebase.
It is typically used for building general purpose MCP stuffs.

## Other MCP libs

- [MCP Clojure SDK](https://github.com/unravel-team/mcp-clojure-sdk): similar library, discovered after being mostly done implementing this one 😅
- Calva's [Backseat Driver](https://github.com/BetterThanTomorrow/calva-backseat-driver)
- [Clojure MCP](https://github.com/bhauman/clojure-mcp)
- [Modex](https://github.com/theronic/modex)

## License

This project is distributed under the [Eclipse Public License v2.0](LICENSE.txt).

Copyright (c) [Metosin](https://metosin.fi) and contributors.
