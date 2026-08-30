# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),

Versions prior to v0.1.0 are considered experimental, their API may change.

## [v2026-07-28] - Unreleased

### Added
- **MCP Protocol 2026-07-28 Support** - The stateless revision, alongside the four handshake ones. A session speaks one era, chosen when it is created, and each reports only what it can actually serve. See `docs/guide/2026-07-28-stateless.md`.
  - **Stateless core** - No initialize handshake and no protocol-level session. Every request carries its own protocol version, capabilities and client identity in `_meta`
    - `:protocol-version "2026-07-28"` on `create-session`, server and client
    - `server/discover` replacing the handshake, with capabilities derived from what the session actually holds
    - `resultType` on every result, and `ttlMs` / `cacheScope` on the six cacheable ones, overridable per session with `:cache-policy`
    - Deterministic ordering of list results, by name for tools and prompts and by URI for resources and templates
  - **Multi Round-Trip Requests** - Replacing server-initiated sampling, roots and elicitation
    - `input-required`, `input-response`, `input-responses`, `request-state`, `retry?`
    - Request builders: `sampling-request`, `roots-request`, `elicit-form-request`, `elicit-url-request`
    - The client fulfils and retries automatically, so calling code is unchanged between revisions, with `:max-round-trips` as a guard
  - **subscriptions/listen** - The only way a stateless server tells a client anything unprompted
    - `request-subscribe`, `notify-unsubscribe`, `subscription-id`, `:on-subscription-acknowledged`
    - `close-subscription!`, `close-all-subscriptions!`, `active-subscriptions`
    - Filter honouring, per-subscription tagging, and URI subtree matching
  - **Dual-era serving** - `:dual-era? true` answers a handshake client and a stateless one on the same session, choosing per request
  - **Renumbered error codes** - `-32020` header mismatch, `-32021` missing client capability, `-32022` unsupported protocol version, and `-32602` for a missing resource
  - `mcp-toolkit.protocol` - version constants, the `_meta` vocabulary, and the JSON key rules every transport needs

- **A 2026-07-28 Streamable HTTP transport** (`example/clj-server-streamable-http`), beside the handshake one rather than replacing it. POST only, no session id, no GET endpoint, and one held-open SSE response per subscription. Run it with `bb example:server:streamable-http:2026`

### Changed
- `json-rpc/hold-open` lets a handler take ownership of its request instead of answering it, which `subscriptions/listen` needs since the pending request is the stream
- `tool-call-handler` passes through multi round-trip results and full JSON-RPC responses instead of printing them into a text block
- A stateless session reports only the revision it implements, rather than every revision the library implements

### Fixed
- **`_meta` was corrupted by every transport.** camel-snake-kebab drops the namespace when re-encoding, so `io.modelcontextprotocol/protocolVersion` went out as `protocolVersion`. The two stdio examples also lost `_meta` entirely, so `progressToken` never reached `notify-progress` there. The rules now live in the library as `protocol/decode-key` and `protocol/encode-key`
- **`request-tool-list` guarded on the `:prompts` capability rather than `:tools`**, so a client talking to a tools-only server never listed its tools. Hidden for as long as `initialize` advertised a fixed map that always included prompts
- **`notify-prompt-list-changed` sent `notifications/prompt/list_changed`**, singular, which neither the specification nor this library's own client tables listen for. Inherited from upstream
- A handshake client reaching a stateless server now gets an error naming the versions it could use, rather than a bare Method not found

### Documentation
- Added `docs/guide/2026-07-28-stateless.md`, covering the redesign, multi round-trip handlers, subscriptions, dual-era serving, and the two wire-key rules a transport must get right
- ROADMAP records what is left: the tasks extension, the Streamable HTTP header requirements, and dual-era support in the example HTTP transport

---

## [v2025-11-25] - Unreleased

### Added
- **MCP Protocol 2025-11-25 Support** - Implementation of the latest specification, not all of it Full; see the README's capability table for what is Full vs. Partial
  - **Elicitation** - Request additional information from users
    - Form mode with JSON Schema-based forms
    - URL mode for OAuth flows and external authentication
    - `request-elicitation` function supporting both modes
    - `notify-elicitation-complete` for completion notifications
    - Capability detection: `client-supports-elicitation?`, `client-supports-url-elicitation?`, `client-supports-form-elicitation?`
  - **Tasks (Experimental)** - Durable state machines for long-running operations
    - Full schema support for task lifecycle states
    - `request-task-get`, `request-task-result`, `request-task-cancel`, `request-tasks-list`
    - `notify-task-status` for status change notifications
    - Capability detection for task-augmented sampling and elicitation
  - **Sampling with tools** - LLMs can use tools during sampling requests
    - `client-supports-sampling-tools?` capability detection
    - Extended `SamplingRequest` schema with tool-related fields
  - **Icons** - Visual icons for prompts, resources, tools, and templates
    - Icon schema validation (data:image/ URIs or https:// URLs)
    - Support in prompts, resources, tools, and resource templates
  - **Server description** - Human-readable server descriptions
    - `:description` field in `server-info` during initialization
  - **JSON Schema 2020-12 dialect**
    - `JSON_SCHEMA_DIALECT` constant
    - `with-schema-dialect` helper function

- **Malli Schema Validation** (`mcp-toolkit.schema` namespace)
  - Comprehensive schemas for all MCP protocol types
  - Helper constructors for common patterns
  - `valid?`, `validate`, `explain` functions for validation

### Changed
- **Internal naming convention** - All internal keys now use kebab-case
  - Wire format (camelCase) automatically converted at transport layer
  - Example: `:maxTokens` → `:max-tokens`, `:inputSchema` → `:input-schema`
- Protocol version updated to `2025-11-25` as default

### Fixed
- Variable shadowing bug in `completion-complete-handler`
- Missing else branches in `json_rpc.cljc`
- Unused binding warnings throughout codebase

### Documentation
- Added comprehensive migration guide (docs/reference/MIGRATION-2025-11-25.md)
- Added migration checklist (docs/reference/archive/MIGRATION-2025-11-25-CHECKLIST.md)
- Updated README with 2025-11-25 protocol features

---

## [v0.1.1-alpha] - 2025-07-03

### Fixed

- Server API: completion functions for prompts are now optional.
- Server API: completion functions for resource URIs are now optional.
- Server API: `:resource-templates` is now optional in the session.
- Server API: A promise bug when a response from an MCP client contained an error.
- Server API: The default log level, if unspecified, is now "debug".

### Changed

- `json-rpc/handle-message`'s method signature was changed from `[context]` to `[context message]`.

## [v0.1.0-alpha] - 2025-07-01

### Added

- First release 🎉
