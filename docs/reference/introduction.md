## Introduction

MCP-toolkit is a library for interfacing Clojure code with the MCP protocol.

### Supported Protocol Versions

- **2026-07-28** (latest) - Removed the handshake. A session opts into it explicitly instead of negotiating up to it; see [2026-07-28: the stateless revision](../guide/2026-07-28-stateless.md)
- **2025-11-25** - Supported, but not every new capability is Full; see the [README's capability table](../../README.md#protocol-and-feature-support) for which ones are Partial
- **2025-06-18** - Full support
- **2025-03-26** - Full backward compatibility
- **2024-11-05** - Legacy support

### Features

It provides a structure for:
- Registering and unregistering your prompts, resources, tools, roots, etc.
- Making them discoverable and invocable to the remote site
- Updating their presence or lack of
- Sending notifications to the remote site when they are updated
- Experimenting and developing MCP tools from a REPL session while they are being served

### New in 2025-11-25

- **Elicitation** - Request user input via forms or URL redirects (OAuth flows)
- **Tasks (Experimental)** - Track long-running operations
- **Sampling with Tools** - LLMs can use tools during sampling
- **Icons** - Visual icons for prompts, resources, tools, templates
- **Server Description** - Human-readable server descriptions
- **Malli Schema Validation** - Validate MCP protocol types

### Key Convention

All internal Clojure code uses **kebab-case** keys (`:max-tokens`, `:input-schema`). Conversion to/from camelCase happens automatically at the transport layer.

```clojure
;; Your code uses kebab-case
{:max-tokens 1000
 :input-schema {:type "object"}}
```
