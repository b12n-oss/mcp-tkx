# mcp-tkx — User guide

`mcp-tkx` is a Clojure / ClojureScript library for building MCP ([Model Context Protocol](https://modelcontextprotocol.io/)) **clients and servers**. It is a fork of [Metosin's `mcp-toolkit`](https://github.com/metosin/mcp-toolkit) with extended protocol support and a kebab-case-first developer experience.

What's distinctive about this fork:

- **Five protocol revisions in one library** — `2024-11-05`, `2025-03-26`, `2025-06-18` and `2025-11-25` negotiate automatically at the handshake. `2026-07-28` removed the handshake, so a session opts into it explicitly.
- **Kebab-case keys end-to-end** — your Clojure code uses `:max-tokens` / `:input-schema` / `:list-changed`; the wire format (`maxTokens` / `inputSchema` / `listChanged`) is produced and consumed at the transport layer via `camel-snake-kebab`. No bespoke per-field renaming inside handlers.
- **Malli-based schema validation** — `mcp-toolkit.schema` ships 31 schemas for protocol types (icons, sampling tools, elicitation requests, tasks, content blocks). `valid?` / `validate` / `explain` for sanity-checking your tool definitions and request shapes before you ship them.
- **Dynamic resources via `:read-fn`** — resources can compute `:text` / `:blob` / `:contents` on demand at `resources/read` time, returning a plain map or a Promesa promise.
- **2025-11-25 spec features wired up** — Elicitation (form + URL mode for OAuth flows), Tasks (long-running operation state machines), Sampling with Tools (LLM tool use during sampling), Icons (data: URIs or https:// URLs), Server Description, JSON Schema 2020-12 dialect.
- **CLJC** — runs on the JVM via `jsonista` / nREPL or on Node.js via `shadow-cljs` with no transport-specific changes to your handler code.
- **I/O-agnostic core** — the library does no I/O itself; you wire STDIO, HTTP/SSE, or any other transport by providing a `:send-message` fn and feeding decoded messages into `json-rpc/handle-message`.
- **REPL-driven development** — `add-tool` / `remove-tool` / `add-resource` / `notify-resource-updated` mutate the running server's session and notify clients live, so you can iterate on tools while Claude Desktop / Claude Code is connected.

This guide focuses on the **reusable building blocks**, the patterns that lift to other MCP servers and to other Clojure projects. For day-to-day API reference, see the `docs/reference/` folder. Most of it (`api-design.md`, `session.md`, `context.md`, `using-the-library.md`, `repl-story.md`, and [`MIGRATION-2025-06-18.md`](../reference/MIGRATION-2025-06-18.md)) is preserved from the upstream Metosin docs. Two files are this fork's own work: [`introduction.md`](../reference/introduction.md) and [`MIGRATION-2025-11-25.md`](../reference/MIGRATION-2025-11-25.md), both covering the `2025-11-25` protocol revision.

## How to read this guide

**New to MCP or to this library?** Start with [Getting started](getting-started.md) for install, your first STDIO server, and a smoke test against the MCP Inspector. Then read [Architecture](architecture.md) for the session-atom + context-hashmap split, the message lifecycle, and the namespace map.

**Trying to understand the kebab-case story?** [Kebab-case key transformation](kebab-case-transformation.md) explains why the wire format and your code disagree on casing, where the conversion happens, and how to wire it up for STDIO and HTTP transports.

**Bumping a server to the latest spec?** [Protocol versions](protocol-versions.md) is the version-by-version feature matrix and the negotiation algorithm. [2025-11-25 features](2025-11-25-features.md) walks through what that revision added. [2026-07-28: the stateless revision](2026-07-28-stateless.md) covers the redesign that followed, which removed the handshake and replaced server-initiated requests.

**Validating tool / prompt / resource shapes before shipping?** [Schema validation](schema-validation.md) covers the Malli schema namespace and the `!`-suffixed throwing constructors.

**Resources whose content changes over time?** [Dynamic resources](dynamic-resources.md) covers the `:read-fn` pattern and when to reach for it vs. static `:text` / `:blob`.

**Plugging your server into Claude?** [Claude Desktop / Claude Code setup](claude-desktop-setup.md) covers `claude_desktop_config.json` and `claude mcp add`, plus the STDIO vs SSE transport choices.

**Serving your server over HTTP to modern / remote clients?** [Streamable HTTP transport](streamable-http.md) covers the current `2025-03-26`+ transport — the single `/mcp` endpoint, `Mcp-Session-Id` sessions, JSON-or-SSE responses, `Last-Event-Id` resumability — and how it differs from the older HTTP+SSE example.

**Iterating on tools while a client is connected?** [REPL workflow](repl-workflow.md) is the live-development story — `add-tool` / `remove-tool` / `notify-resource-updated` from a REPL.

**Lifting a piece of the toolkit into a different MCP server?** [Extraction recipes](extraction-recipes.md) is the worked-example index — how to copy the kebab-case transport, the Malli schema registry, the dynamic-resource handler, the session lifecycle, and the cancellation pattern.

## Guide map

| Page | What you'll learn |
|---|---|
| [Getting started](getting-started.md) | Install via `deps.edn`; first STDIO server in CLJC; smoke test against MCP Inspector + Claude Desktop |
| [Architecture](architecture.md) | `session` atom + `context` hashmap split, message lifecycle, namespace map (`server` / `client` / `json-rpc` / `schema` / `impl/*`), capability negotiation, cancellation |
| [Kebab-case key transformation](kebab-case-transformation.md) | Why kebab-case internally, where the camelCase ↔ kebab-case conversion sits (transport layer), `jsonista` + `camel-snake-kebab` setup for STDIO, equivalent for shadow-cljs / Node, the wire format reference table |
| [Protocol versions](protocol-versions.md) | Version negotiation algorithm, what each of the four handshake revisions adds, breaking changes (JSON-RPC batching removed in 2025-06-18), backward-compat notes |
| [Schema validation](schema-validation.md) | Malli schema namespace tour (Icon, EnumSchema, SamplingRequest, FormElicitationRequest, UrlElicitationRequest, Task, ToolResultMessage, content-block schemas), `valid?` / `validate` / `explain`, `!`-suffix throwing constructors (`enum-schema!`, `url-elicitation!`, `form-elicitation!`, `tool-result-message!`) |
| [Dynamic resources](dynamic-resources.md) | `:read-fn` pattern for on-demand resource content, return-shape contract (`:text` / `:blob` / `:contents` / `:error`), Promesa async support, when to use vs. static `:text` / `:blob` |
| [2025-11-25 features](2025-11-25-features.md) | Elicitation (form + URL mode for OAuth), Tasks (experimental long-running state machines), Sampling with Tools (LLM tool use during sampling), Icons (data: URIs / https:// URLs), Server Description, JSON Schema 2020-12 dialect, capability detection helpers |
| [2026-07-28: the stateless revision](2026-07-28-stateless.md) | The handshake-free redesign: what was removed, creating a stateless session, `server/discover`, per-request context, Multi Round-Trip Requests, `subscriptions/listen`, the new result and error-code shapes, writing a transport, serving both eras on one session |
| [Claude Desktop / Claude Code setup](claude-desktop-setup.md) | `claude_desktop_config.json` STDIO config, `claude mcp add` for Claude Code, SSE transport for Claude Code (not yet supported by Desktop), Docker-based server config, MCP Inspector for debugging |
| [Streamable HTTP transport](streamable-http.md) | The current remote transport (`2025-03-26`+): single `/mcp` endpoint, `Mcp-Session-Id` sessions, JSON-or-SSE responses (the "flip"), `Last-Event-Id` resumability, Host/Origin + `MCP-Protocol-Version` security; how it differs from the HTTP+SSE example |
| [REPL workflow](repl-workflow.md) | nREPL embedded in your STDIO server, `add-tool` / `remove-tool` / `notify-resource-updated` from the REPL while a client is connected, Claude Desktop log tailing for diagnostics |
| [Extraction recipes](extraction-recipes.md) | Worked recipes — kebab-case transport for any JSON-RPC service, Malli protocol-schema registry pattern, dynamic-resource `:read-fn`, multi-version handshake negotiation, cancellation via `is-cancelled` atom, REPL-aware notification helpers |

## Find your scenario

| Scenario | Pages to read |
|---|---|
| Build my first MCP server in Clojure | [Getting started](getting-started.md), [Architecture](architecture.md) |
| Build an MCP server in ClojureScript / Node.js | [Getting started](getting-started.md), [Kebab-case key transformation](kebab-case-transformation.md) (cljs section) |
| Plug my server into Claude Desktop | [Claude Desktop / Claude Code setup](claude-desktop-setup.md) |
| Plug my server into Claude Code via SSE | [Claude Desktop / Claude Code setup](claude-desktop-setup.md) (SSE section) |
| Serve my MCP server over Streamable HTTP (current remote transport) | [Streamable HTTP transport](streamable-http.md) |
| Add a tool that streams progress notifications | [Architecture](architecture.md) (`notify-progress`), [REPL workflow](repl-workflow.md) |
| Add a resource whose content is computed at request time | [Dynamic resources](dynamic-resources.md) |
| Add a tool with structured output | [Schema validation](schema-validation.md), [Protocol versions](protocol-versions.md) (2025-06-18 §`output-schema`) |
| Add an icon to a tool / prompt / resource | [2025-11-25 features](2025-11-25-features.md) (Icons) |
| Request user input via a form | [2025-11-25 features](2025-11-25-features.md) (Elicitation §form mode) |
| Drive an OAuth flow from inside an MCP tool | [2025-11-25 features](2025-11-25-features.md) (Elicitation §url mode) |
| Track a long-running operation | [2025-11-25 features](2025-11-25-features.md) (Tasks) |
| Let an LLM use tools during a sampling request | [2025-11-25 features](2025-11-25-features.md) (Sampling with Tools) |
| Validate my tool definition before registering it | [Schema validation](schema-validation.md) |
| Negotiate down to an older client | [Protocol versions](protocol-versions.md) |
| Iterate on tools while my MCP client is connected | [REPL workflow](repl-workflow.md) |
| Lift the kebab-case transport into another JSON-RPC service | [Extraction recipes](extraction-recipes.md) (Recipe 1) |
| Lift the Malli schema-registry pattern into another project | [Extraction recipes](extraction-recipes.md) (Recipe 2) |

## What's distinctive about this fork

| Pattern | Where it lives | Closest sibling |
|---|---|---|
| Kebab-case keys internally + camelCase on the wire (transport-layer conversion) | `example/cljc-server-stdio/src/example/my_server.cljc` (the canonical wiring); `csk/->camelCaseString` + `csk/->kebab-case-keyword` via `jsonista` | (none as a standalone library; Metosin upstream uses raw camelCase) |
| Malli registry of MCP protocol schemas with `!`-suffix throwing constructors | `src/mcp_toolkit/schema.cljc` | (none — most MCP SDKs validate ad hoc rather than through a shared schema registry) |
| Dynamic resources via `:read-fn` returning `{:text}` / `{:blob}` / `{:contents}` / `{:error}` (or a Promesa promise of any) | `src/mcp_toolkit/impl/server/handler.cljc` (`resource-read-handler`) | (none — most MCP SDKs only support static resource content) |
| Multi-version automatic negotiation against `:server-supported-protocol-versions` (`["2024-11-05" "2025-03-26" "2025-06-18" "2025-11-25"]`) | `src/mcp_toolkit/impl/server/handler.cljc` (`initialize-handler`) | (none — most MCP SDKs hardcode a single version) |
| MCP cancellation via per-request `is-cancelled` atom + `notifications/cancelled` handler | `src/mcp_toolkit/json_rpc.cljc` (`route-message`) + `src/mcp_toolkit/impl/server/handler.cljc` (`cancelled-notification-handler`) | (none — Promesa promises don't have a built-in cancel signal; this pattern lifts to any long-running async handler) |

If you're picking patterns:

- **Building a Clojure MCP server** — start at [Getting started](getting-started.md), then [Architecture](architecture.md). Most server work fits the prompt / resource / tool registration pattern; you don't need to touch `impl/`.
- **Wiring an MCP server into a different JSON-RPC framework** — the [Extraction recipes](extraction-recipes.md) Recipe 1 lifts the kebab-case transport pattern out of the toolkit. The schema validation in Recipe 2 is independent of MCP.

## Build & dev

See `bb tasks` for the full list. The 30-second version:

```sh
bb test                      # run all tests via kaocha
bb dev                       # nREPL on :7777 with CIDER middleware
bb example:server:stdio      # run example STDIO server
bb example:server:sse        # run example SSE server (clj-server-sse)
bb example:server:streamable-http  # run example Streamable HTTP server (2025-03-26+)
bb example:server:streamable-http:2026  # run example Streamable HTTP server (2026-07-28, stateless)
bb example:client:stdio      # run example STDIO client
bb info                      # grouped, categorised cheat-sheet of all tasks
bb tasks                     # list every task with its docstring
```

## Not covered yet

- **Pagination** — not wired through `prompts/list`, `resources/list` or `tools/list`. Implementations leave a `#_#_:next-cursor "next-page-cursor"` placeholder in the handler.
- **Babashka support** — the CLJC core runs on the JVM and on Node via shadow-cljs. It does not load on Babashka: the failure is inside `promesa`, which `mcp-toolkit.json-rpc` depends on for its promise-based handler contract. Verified with `bb --classpath $(clojure -Spath)`, which fails loading `promesa.util` at `promesa/util.cljc:9:3` (an unresolvable `java.util.concurrent.locks.ReentrantLock` import), reached via `server` to `impl.server.handler` to `json-rpc` to `promesa.core`. Supporting bb means finding a promise layer that bb can load.

## See also

- [Project README](../../README.md) — protocol and feature support table, the dynamic-resources capability row, other-MCP-libs comparison.
- [`CHANGELOG.md`](../../CHANGELOG.md) — version history per release. Two unreleased entries: `v2026-07-28` covers the stateless revision, and `v2025-11-25` covers the 9 phases of the upstream-spec port (Phase 0 = kebab-case transport, Phase 1 = protocol negotiation, Phase 2 = server description, Phase 3 = icons, Phase 4 = Malli schemas, Phase 5 = sampling with tools, Phase 6 = elicitation, Phase 7 = tasks, Phase 9 = JSON Schema 2020-12 dialect).
- [`docs/reference/`](../reference/) — preserved upstream Metosin docs (`api-design.md`, `session.md`, `context.md`, `using-the-library.md`, `repl-story.md`, `MIGRATION-2025-06-18.md`).
- [`MIGRATION-2025-06-18.md`](../reference/MIGRATION-2025-06-18.md) — the older migration writeup (added by upstream when bumping from 2025-03-26 → 2025-06-18).
