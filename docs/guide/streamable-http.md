# Streamable HTTP transport

The **Streamable HTTP** transport (MCP `2025-03-26`, refined `2025-06-18`) is the current transport for remote MCP servers. It supersedes the older HTTP+SSE transport (`2024-11-05`) used by the [`clj-server-sse`](../../example/clj-server-sse/) example. This fork ships a complete reference implementation at [`example/clj-server-streamable-http/`](../../example/clj-server-streamable-http/).

Like everything else in `mcp-toolkit`, the transport is just an adapter over the I/O-agnostic core: it decodes wire JSON to kebab-case, hands each message to `json-rpc/handle-message`, and supplies a `:send-message` fn. See [Architecture](architecture.md) for that contract and [Kebab-case key transformation](kebab-case-transformation.md) for the JSON boundary.

## Why Streamable HTTP over HTTP+SSE

- **It's the current spec.** HTTP+SSE (`2024-11-05`) is deprecated; modern remote MCP clients expect Streamable HTTP.
- **Single endpoint.** One `/mcp` URL handles everything: friendlier to proxies, API gateways, and load balancers than a long-lived SSE `GET` connection.
- **Stateless-capable.** A simple request/response call returns plain JSON with no sticky connection, so servers can scale horizontally.
- **Resumable.** A dropped stream can resume via `Last-Event-Id` without losing messages.

## Run the example

```sh
bb example:server:streamable-http        # from the repo root
# or, from the example directory:
cd example/clj-server-streamable-http && clojure -X:mcp-server   # 127.0.0.1:7926
```

The example reuses the same MCP content as the SSE example (the pirate prompt, two doc resources, and the `parentify` tool) via [`common-mcp-content`](../../example/common-mcp-content/), so the two transports are directly comparable. The full `curl` walkthrough lives in the example's [README](../../example/clj-server-streamable-http/README.md).

## Endpoints

A single path, `/mcp`:

| Method | Request | Response |
|---|---|---|
| `POST /mcp` | a JSON-RPC **request** | `200 application/json` (a simple call) **or** `200 text/event-stream` (when the handler emits progress/sampling/elicitation), then close |
| `POST /mcp` | a JSON-RPC **notification / response** | `202 Accepted` |
| `GET /mcp` | n/a | `200 text/event-stream`: the server→client stream (`405` if one is already open) |
| `DELETE /mcp` | n/a | `204`: terminate the session (`404` if unknown) |

## Sessions

The server assigns a session id in the **`Mcp-Session-Id` response header** of the `initialize` call. The client echoes that header on every subsequent request. Unknown/expired ids get `404` (the client must re-`initialize`). Sessions are held in an in-memory pool, one `mcp-toolkit` session per id.

## JSON vs. SSE responses (the "flip")

A `POST` of a request does **not** commit to a response type up front. The per-request `:send-message`:

- buffers the message and returns **plain JSON** if it is simply *the response* to the request, but
- **flips to an SSE stream** the moment the handler emits anything else first: a `notifications/progress`, or a server→client request like `sampling/createMessage`. Once streaming, every subsequent message (including the final response) is an SSE frame, then the stream closes.

This is what lets `tools/call` stream progress and still return a normal JSON-RPC result on the same connection.

> **Progress requires a `progressToken`.** Per the MCP spec, a tool call only emits progress if the client opts in with `_meta.progressToken` in the request `params`. Without it, even a tool that calls `notify-progress` returns plain JSON. (The example's `parentify` curl in the README includes the token.)

## Resumability (`Last-Event-Id`)

Every server→client SSE frame carries a monotonic `id:` and is buffered in a bounded per-session ring (capped by count and age; eviction is logged). If a stream drops, the client reconnects with a `Last-Event-Id: <n>` header and the server replays buffered frames with `id > n` before resuming live delivery. The ring is bounded, so a long-disconnected client whose events have been evicted gets a fresh stream (the resume is silently lossy, acceptable for a reference example; a production server would track the oldest-retained id and signal the gap).

## 2026-07-28 (stateless)

`2026-07-28` is not a mode of the transport above. It is a second transport,
[`transport/streamable_http_2026.clj`](../../example/clj-server-streamable-http/src/example/transport/streamable_http_2026.clj), served by a second example server, [`example.my-server-2026`](../../example/clj-server-streamable-http/src/example/my_server_2026.clj), on its own port. Endpoints, Sessions, the JSON/SSE flip, and Resumability above are all
specific to the handshake revisions and none of them apply here.

What changes, from the transport's point of view:

- **No session id.** There is no `Mcp-Session-Id` header to mint, carry, or expire, and no `DELETE /mcp` to end anything, because there is nothing session-shaped to end. One server session serves every connection.
- **Capabilities arrive per request.** A handshake client negotiates once, at `initialize`, and the server remembers. A `2026-07-28` request carries its own protocol version and capabilities in `_meta` instead, since there is no handshake to remember them from.
- **`subscriptions/listen` replaces the `GET` stream.** It is a `POST` whose request never gets an ordinary response. The handler returns `mcp-toolkit.json-rpc/hold-open`, the transport keeps that connection open, and writes the eventual response only when the subscription ends. Notifications for that subscription arrive as SSE frames on the same connection while it stays open.
- **No resumability.** There is no event log and no `Last-Event-Id`, so a dropped stream loses whatever request was in flight and the client re-issues it.

Host, Origin and Content-Type validation, and the JSON encoding, carry over unchanged: the 2026 transport reuses them from the transport above rather than duplicating them.

Run it with `bb example:server:streamable-http:2026`; the example's [README](../../example/clj-server-streamable-http/README.md) has a full curl walkthrough, including a working `subscriptions/listen` round trip. The protocol-level detail behind all of this, discovery, capabilities, Multi Round-Trip Requests, and the subscription filter shape, lives in [2026-07-28: the stateless revision](2026-07-28-stateless.md). This page covers the transport; that one covers the protocol.

## Security

The transport applies DNS-rebinding protection on every verb, lifted from the SSE example and hardened:

- **`Host`** must match `:allowed-hosts` (default `#{"127.0.0.1:*"}`): the port wildcard accepts only a numeric port, so `127.0.0.1:80@evil.com` is rejected. Invalid host → `421`.
- **`Origin`**, when present, must match `:allowed-origins`; a blank origin (non-browser client) is allowed. Invalid origin → `400`.
- **`MCP-Protocol-Version`** header is required on non-`initialize` requests once the negotiated session version is ≥ `2025-06-18`. Missing/unsupported → `400`.

Bind to localhost for local servers and configure `:allowed-origins` if a browser client will connect.

## Connecting clients

**MCP Inspector** (browser dev tool):

```sh
npx @modelcontextprotocol/inspector
# Transport: "Streamable HTTP"   URL: http://127.0.0.1:7926/mcp
```

**Claude Code** (Streamable HTTP transport):

```sh
claude mcp add toolkit-http --transport http http://127.0.0.1:7926/mcp
```

## Streamable HTTP vs. HTTP+SSE, quick comparison

| | HTTP+SSE (`clj-server-sse`) | Streamable HTTP (`clj-server-streamable-http`) |
|---|---|---|
| Spec | `2024-11-05` (deprecated) | `2025-03-26`+ (current) |
| Endpoints | `GET /sse` + `POST /messages/:id` | single `/mcp` (POST/GET/DELETE) |
| Session id | SSE `endpoint` event URL path | `Mcp-Session-Id` header |
| Simple call | always over the SSE stream | plain `application/json` (stateless-friendly) |
| Resumability | n/a | `Last-Event-Id` replay |
| Default port | `7925` | `7926` |

## See also

- [`example/clj-server-streamable-http/README.md`](../../example/clj-server-streamable-http/README.md): full `curl` walkthrough + Inspector setup.
- [2026-07-28: the stateless revision](2026-07-28-stateless.md). Covers the protocol this fork's stateless transport implements: discovery, capabilities, Multi Round-Trip Requests, and subscriptions.
- [Architecture](architecture.md): the transport-agnostic `handle-message` / `:send-message` contract.
- [Kebab-case key transformation](kebab-case-transformation.md): the JSON ↔ Clojure boundary (note: `_meta` keeps its leading underscore, `:_meta`).
- [Protocol versions](protocol-versions.md): what `2025-03-26` / `2025-06-18` add and the negotiation algorithm.
- [Claude Desktop / Claude Code setup](claude-desktop-setup.md): registering servers with Claude.
