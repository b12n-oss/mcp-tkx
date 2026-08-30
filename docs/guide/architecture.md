# Architecture

This page is the namespace map, the message lifecycle, and the session/context split. For day-to-day API reference, see [`docs/reference/api-design.md`](../reference/api-design.md), [`docs/reference/session.md`](../reference/session.md), [`docs/reference/context.md`](../reference/context.md), [`docs/reference/using-the-library.md`](../reference/using-the-library.md). This page is the architecture-level orientation.

## Namespace map

Everything lives under `src/mcp_toolkit/`. Arrows point from a namespace to
what it requires.

```mermaid
flowchart TD
  subgraph pub["Public API, the stable surface you depend on"]
    server["server.cljc<br/>create-session, add-tool,<br/>request-sampling, notify-progress"]
    client["client.cljc<br/>create-session, request-prompt,<br/>request-tool-call, request-subscribe"]
    jsonrpc["json_rpc.cljc<br/>handle-message, route-message,<br/>call-remote-method, hold-open"]
    protocol["protocol.cljc<br/>revision predicates, _meta field names,<br/>latest-protocol-version"]
    schema["schema.cljc<br/>Malli schemas for MCP protocol types<br/>+ helper constructors"]
  end

  subgraph impl["impl/, marked ^:no-doc and evolved with the spec"]
    shandler["server/handler.cljc<br/>the handshake era: initialize,<br/>prompts/list, resources/read, tools/call"]
    shandler26["server/handler_2026.cljc<br/>the stateless era: server/discover,<br/>subscriptions/listen"]
    shandlerd["server/handler_dual.cljc<br/>picks an era per request"]
    chandler["client/handler.cljc<br/>client-side handshake handlers"]
    chandler26["client/handler_2026.cljc<br/>client-side 2026-07-28 notifications"]
    mrtr["mrtr.cljc<br/>multi-round-trip requests"]
    subs["subscriptions.cljc<br/>subscriptions/listen stream state"]
    common["common.cljc<br/>user-callback dispatch helper"]
    meta["meta_support.cljc<br/>_meta field merging (2025-06-18 spec)"]
  end

  server --> shandler
  server --> shandler26
  server --> shandlerd
  server --> mrtr
  server --> subs
  server --> jsonrpc
  server --> protocol
  server --> common
  client --> chandler
  client --> chandler26
  client --> jsonrpc
  client --> protocol
  client --> common
  shandler --> jsonrpc
  shandler --> mrtr
  shandler --> common
  shandler26 --> shandler
  shandler26 --> subs
  shandler26 --> jsonrpc
  shandler26 --> protocol
  shandlerd --> shandler
  shandlerd --> shandler26
  shandlerd --> jsonrpc
  shandlerd --> protocol
  shandlerd --> common
  chandler --> common
  chandler26 --> common
  mrtr --> protocol
  subs --> protocol
```

`schema.cljc` and `meta_support.cljc` sit on their own: neither is required by
another namespace here, so you reach for them directly rather than through the
message path. `server.cljc` mentions `mcp-toolkit.schema` in its docstrings, but
that is a pointer for readers, not a dependency.

`protocol.cljc` is the one public namespace you may not have expected to need.
Transport authors read it, because a 2026-07-28 transport has to recognise the
`_meta` field names it defines.

| Namespace | Public? | Lines | Role |
|---|---|---|---|
| `mcp-toolkit.server` | yes | 1050 | Server-side public API. `create-session` is the entry point; everything else operates on the `context` (which holds the `session`). |
| `mcp-toolkit.client` | yes | 621 | Client-side public API. `create-session` (a different shape, for clients), then `request-*` / `notify-*` fns to drive the server. |
| `mcp-toolkit.schema` | yes | 791 | Malli schemas + `valid?` / `validate` / `explain` + `!`-suffixed throwing constructors (`enum-schema!`, `url-elicitation!`, `form-elicitation!`, `tool-result-message!`). |
| `mcp-toolkit.json-rpc` | yes | 234 | JSON-RPC plumbing. `handle-message` is the single entry point you call when an inbound JSON-RPC message arrives. `call-remote-method` is the outbound side, and `hold-open` is the sentinel a handler returns to keep a stream open. |
| `mcp-toolkit.protocol` | yes | 160 | Revision predicates, the `_meta` field names 2026-07-28 uses, and `latest-protocol-version`. Transport authors need this one. |
| `mcp-toolkit.impl.*` | **no** (`^:no-doc`) | 1138 total | Per-method handlers, across nine namespaces. You don't call these directly. They're wired into the session at create-time. |

That is 14 namespaces and 3,994 lines in total.

The split is deliberate: any code under `impl/` is meant to be evolved with the spec, while `server.cljc` / `client.cljc` / `json_rpc.cljc` / `protocol.cljc` / `schema.cljc` are the stable surface you depend on.

## The session atom

A session is the mutable state that represents one logical client ↔ server connection. It lives in an atom you own:

```clojure
(def session
  (atom
   (server/create-session
    {:server-info {:name "my-server" :version "1.0.0"}
     :prompts     [...]
     :resources   [...]
     :tools       [...]})))
```

After `(server/create-session opts)` returns, the atom value contains:

| Key | What it holds |
|---|---|
| `:initialized` | `(and stateless (not dual-era?))` at creation: `true` only for a pure stateless session, `false` for plain and dual-era. On the handshake path, `notifications/initialized` sets it `true`. |
| `:protocol-version` | `nil` at creation for plain and dual-era sessions, `"2026-07-28"` for a stateless one. `initialize` sets it on the handshake path, so five string values are possible: the four handshake revisions or `"2026-07-28"`. |
| `:server-supported-protocol-versions` | one of three lists, chosen by a `cond` on session kind: `["2024-11-05" "2025-03-26" "2025-06-18" "2025-11-25"]` for a plain session, those four plus `"2026-07-28"` for a dual-era one, or `["2026-07-28"]` alone for a stateless one |
| `:dual-era?` | `true` when `create-session` was called with `:dual-era? true`, `false` otherwise. Overrides `:protocol-version`. |
| `:modern-protocol-versions` | `nil` for a plain session; `["2026-07-28"]` for a stateless or dual-era one. The subset of `:server-supported-protocol-versions` a request may declare in `_meta`. |
| `:cache-policy` | `nil` unless passed to `create-session`. Overrides the per-method `{:ttl-ms :cache-scope}` freshness hints on 2026-07-28 cacheable results. |
| `:server-info` / `:server-instructions` | static metadata you passed in |
| `:client-info` / `:client-capabilities` | populated from the `initialize` request |
| `:prompt-by-name` / `:resource-by-uri` / `:tool-by-name` | indexed registries |
| `:resource-templates` / `:resource-uri-complete-fn` | resource template metadata |
| `:client-subscribed-resource-uris` | `#{}` of URIs the client has subscribed to (`resources/subscribe`) |
| `:subscription-by-id` | `{}` at creation, keyed by the id of the request that opened each stream. The 2026-07-28 counterpart to `:client-subscribed-resource-uris`: `subscriptions/listen` replaces the older per-resource `resources/subscribe` on that path, and this key stays empty on the handshake revisions. |
| `:client-root-by-uri` | populated after `roots/list` to the client |
| `:logging-level` | `"debug"` by default |
| `:on-initialized` / `:on-client-root-list-changed` / `:on-client-root-list-updated` | user callbacks |
| `:handler-by-method` | the method dispatch table. On a plain session, swapped from `pre-initialization` to `post-initialization` after `notifications/initialized`. On a dual-era session it holds the dual dispatch and is never swapped: see `:legacy-handler-by-method` below. |
| `:legacy-handler-by-method` | `nil` except on a dual-era session, where it holds the handshake era's own pre-initialization table. `initialized-notification-handler` swaps this key instead of `:handler-by-method`, so one client finishing the handshake does not strand the stateless clients sharing the session. |
| `:is-cancelled-by-request-id` | `{request-id → atom of bool}` for in-flight cancellable requests |
| `:last-called-method-id` / `:handler-by-called-method-id` | tracking outbound calls awaiting a response |

The session is mutated by the toolkit's handlers (e.g. `prompt-list-handler` reads from it; `add-tool` writes to it and notifies the client). You can also `(swap! session ...)` from your own code — that's the basis of the [REPL workflow](repl-workflow.md).

The client-side session has the symmetric shape: `:server-prompt-by-name` / `:server-resource-by-uri` / `:server-tool-by-name` etc.

## The context hashmap

The context is an immutable Clojure value that the toolkit threads through every handler. It contains:

| Key | What it holds |
|---|---|
| `:session` | the session atom (always present) |
| `:send-message` | `(fn [message] ...)` — sends an outbound JSON-RPC message; **you provide this** |
| `:close-connection` | optional `(fn [])` — called from `json-rpc/close-connection`; useful for graceful shutdown |
| `:message` | the current JSON-RPC message being processed (added by `route-message`) |
| `:is-cancelled` | per-request atom (added when a cancellable method is in flight) |
| `:completion-context` | when a `completion/complete` request includes 2025-06-18-spec context |
| `:protocol-version` | 2026-07-28 only: the revision this request declared in `_meta`, put there by `with-request-context`. Never written to the session. |
| `:client-capabilities` | 2026-07-28 only: the capabilities this request declared in `_meta`. Read it via `request-client-capabilities`, which falls back to the session for a handshake-based session. |
| `:client-info` | 2026-07-28 only: the client identity this request declared in `_meta` |
| `:log-level` | 2026-07-28 only: the log level this request opted into in `_meta`. Read it via `request-log-level`; a server must not send log notifications for a request that omitted it. |

You construct the bare context once (with `:session` + `:send-message`); the toolkit decorates it per-message. The last four keys above come from `with-request-context`, and only for a 2026-07-28 request: capabilities and identity arrive per request now, and writing either one onto the shared session would let two concurrent requests race.

The **session-vs-context** rule: anything that mutates per message goes in the session atom; anything you want to pass down to handlers as a one-shot value goes in the context. You can add your own keys to either — handlers receive the context as their one and only argument.

## Message lifecycle

Inbound JSON-RPC messages flow through the toolkit like this:

```mermaid
flowchart TD
  wire["Wire format<br/>JSON, camelCase keys"]
  decode["YOU: read line, decode JSON,<br/>convert keys to kebab-case"]
  cljmap["Clojure map with kebab-case keys"]
  hm["YOU: json-rpc/handle-message"]
  batch{"vector?"}
  invalid["invalid-request-response<br/>batching removed in 2025-06-18"]
  route["json-rpc/route-message"]
  kind{"has :method?"}
  lookup["look up :handler-by-method<br/>in @session"]
  found{"handler found?"}
  nf["method-not-found-response, -32601<br/>sent only when the message has an :id"]
  hasid{"has :id?"}
  notif["notification:<br/>handler runs, no response"]
  method["method call:<br/>register the :is-cancelled atom, run handler,<br/>clean up the atom on settle"]
  heldopen{"result =<br/>json-rpc/hold-open?"}
  held["stream stays open:<br/>no response sent now,<br/>subscriptions/listen answers later, or never"]
  resp["response to OUR earlier outbound call:<br/>look up :handler-by-called-method-id,<br/>resolve or reject the original promise,<br/>remove the entry"]
  out["Outbound response, Clojure map"]
  send["TOOLKIT: passes to :send-message"]
  encode["YOU in :send-message: convert keys to camelCase,<br/>encode JSON, write to wire"]
  wire2["Wire format"]

  wire --> decode --> cljmap --> hm --> batch
  batch -->|yes| invalid --> send
  batch -->|no| route --> kind
  kind -->|yes| lookup --> found
  found -->|no| nf --> out
  found -->|yes| hasid
  hasid -->|no| notif
  hasid -->|yes| method --> heldopen
  heldopen -->|yes| held
  heldopen -->|"no: wrap into {:result ...}"| out
  kind -->|"no, has :id plus :result or :error"| resp
  out --> send --> encode --> wire2
```

The toolkit does not know about transports. STDIO, HTTP/SSE, WebSocket — all the same: you decode bytes → Clojure map, hand to `handle-message`, and your `:send-message` fn writes the response back out. See [Kebab-case key transformation](kebab-case-transformation.md) for the JSON ↔ Clojure boundary.

## Initialization handshake

The first two messages are special — they negotiate the protocol version and bootstrap the session:

1. **Client → server: `initialize`** — includes `:protocol-version` (the one the client wants), `:client-info`, `:capabilities`. Server's `initialize-handler` (in `impl/server/handler.cljc`):
   - Picks `protocol-version` = the client's request if supported, else the **last** entry in `:server-supported-protocol-versions` (which is the highest the server knows).
   - Stores `:protocol-version`, `:client-info`, `:client-capabilities` in the session.
   - Returns `{:protocol-version ... :capabilities {...} :server-info {...} :instructions ...?}`.

2. **Client → server: `notifications/initialized`** — the client is ready. Server's `initialized-notification-handler`:
   - Sets `:initialized true`.
   - Swaps `:handler-by-method` from the **pre-initialization** table (`ping` + `initialize` + `notifications/initialized` only) to the **post-initialization** table (the full method set: `prompts/list`, `resources/read`, `tools/call`, etc.).
   - Invokes the `:on-initialized` callback (default: `request-root-list` — fetches the client's filesystem roots).

Before step 2 completes, calling any method other than `initialize` / `ping` returns `Method not found` (-32601). This is enforced by the dispatch table swap, not by ad-hoc checks.

See [Protocol versions](protocol-versions.md) for the version-negotiation algorithm in detail.

## Cancellation

MCP supports `notifications/cancelled` — the client tells the server "stop request id `42`." The toolkit's pattern:

1. When `route-message` dispatches a method call with an `:id`, it creates a per-request atom `(atom false)` and registers it under `:is-cancelled-by-request-id` in the session, keyed by request id. The atom is also added to `context` as `:is-cancelled`.

2. The handler (your `tool-fn`, `prompt-fn`, etc.) can read `(:is-cancelled context)` at any time. If `@is-cancelled` becomes true, the handler should bail out — typically by throwing or returning an error result. The toolkit's `route-message` checks `@is-cancelled` after the handler resolves; if the request was cancelled, the result is **not sent**.

3. When the client sends `notifications/cancelled`, `cancelled-notification-handler` looks up the atom by `request-id` and `(reset! is-cancelled-atom true)`. The in-flight handler sees the change on its next deref.

4. After the handler settles (success or error), `route-message` removes the entry from `:is-cancelled-by-request-id` to avoid leaking memory.

The pattern is in [`example/common-mcp-content/src/example/server_content.cljc`](../../example/common-mcp-content/src/example/server_content.cljc) — the `parentify-tool` checks `@(:is-cancelled context)` between progress steps and throws if cancelled.

## Capability negotiation

The toolkit detects what the client supports via the `:client-capabilities` map populated during `initialize`. The server-side helpers in `mcp-toolkit.server`:

```clojure
(client-supports-elicitation? context)            ; :elicitation key present
(client-supports-form-elicitation? context)       ; :elicitation {} or :elicitation {:form ...}
(client-supports-url-elicitation? context)        ; :elicitation {:url ...}
(client-supports-sampling-tools? context)         ; :sampling {:tools ...}
(client-supports-tasks? context)                  ; :tasks key present
(client-supports-task-augmented-sampling? context); :tasks {:requests {:sampling {:create-message ...}}}
(client-supports-task-augmented-elicitation? context); :tasks {:requests {:elicitation {:create ...}}}
(client-supports-tasks-list? context)             ; :tasks {:list ...}
(client-supports-tasks-cancel? context)           ; :tasks {:cancel ...}
```

> **These helpers only work on a handshake session.** All nine
> `client-supports-*?` predicates read `:client-capabilities` from the session.
> A 2026-07-28 client sends its capabilities per request in `_meta` instead, and
> `with-request-context` deliberately never writes them to the session, because
> two concurrent requests can declare different capabilities and writing either
> one would let them race.
>
> So on a stateless session every one of these returns `false`, including for a
> client that did declare the capability. Guard a stateless code path with
> `request-client-capabilities`, which reads the context first and falls back to
> the session:
>
> ```clojure
> ;; Works on both eras.
> (when (contains? (server/request-client-capabilities context) :elicitation)
>   (server/request-elicitation context {...}))
> ```

The convention: **always** check the capability before calling a feature that requires it. The `request-*` fns in `mcp-toolkit.server` themselves check capabilities and return `nil` (don't send anything) when the client doesn't declare support. See [2025-11-25 features](2025-11-25-features.md) for the full feature-by-feature breakdown.

## REPL-time mutations

Several `mcp-toolkit.server` fns are designed to be called from a REPL while the server is running. They mutate the session and notify the client of the change:

| Fn | Effect |
|---|---|
| `add-prompt` / `remove-prompt` | mutate `:prompt-by-name`, send `prompts/list_changed` notification |
| `add-resource` / `remove-resource` | mutate `:resource-by-uri`, send `resources/list_changed` |
| `add-tool` / `remove-tool` | mutate `:tool-by-name`, send `tools/list_changed` |
| `set-resource-templates` | mutate `:resource-templates` |
| `set-resource-uri-complete-fn` | mutate `:resource-uri-complete-fn` |
| `notify-resource-updated` | send `resources/updated` for a specific URI (only fires if the client is subscribed) |
| `notify-progress` | send `progress` if the current message had a `:progress-token` |
| `notify-log` | send `message` log notification if the level meets the session's threshold |

This is the basis of [the REPL workflow](repl-workflow.md): you can add a tool, edit it, remove it, re-add it, all while Claude Desktop or the MCP Inspector is connected and seeing the changes live.

## See also

- [`docs/reference/api-design.md`](../reference/api-design.md), [`docs/reference/session.md`](../reference/session.md), [`docs/reference/context.md`](../reference/context.md) — the upstream Metosin reference docs.
- [Kebab-case key transformation](kebab-case-transformation.md) — the JSON ↔ Clojure boundary that this page treats as a black box.
- [Protocol versions](protocol-versions.md) — the version-negotiation algorithm.
- [REPL workflow](repl-workflow.md) — using the REPL-mutation fns above in practice.
