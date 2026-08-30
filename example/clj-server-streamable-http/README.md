# Streamable HTTP MCP server (example)

Two servers live here, one per era. `example.my-server` speaks the handshake
revisions (MCP `2025-03-26`+): single endpoint `/mcp`, session id via the
`Mcp-Session-Id` header, progress/sampling stream back over SSE, resumable via
`Last-Event-Id`. `example.my-server-2026` speaks the stateless `2026-07-28`
revision: same `/mcp` endpoint, POST only, no session id, no GET, and one
long-lived stream per `subscriptions/listen` instead. Both share the same MCP
content (the pirate prompt, two doc resources, `parentify`) and run on
different ports, so you can start both at once.

Companion to the older [`clj-server-sse`](../clj-server-sse) example (2024-11-05
HTTP+SSE transport).

## Run

```sh
bb example:server:streamable-http          # 2025-03-26+, from the repo root
# or, from this directory:
clojure -X:mcp-server                      # defaults to 127.0.0.1:7926

bb example:server:streamable-http:2026     # 2026-07-28, from the repo root
# or, from this directory:
clojure -M -m example.my-server-2026       # binds 127.0.0.1:7927
```

## Try it with curl (2025-03-26+, session-based)

1. **Initialize** (the response header carries your session id):

```sh
curl -i -X POST http://127.0.0.1:7926/mcp \
  -H 'content-type: application/json' \
  -d '{"jsonrpc":"2.0","id":0,"method":"initialize",
       "params":{"protocolVersion":"2025-11-25",
                 "clientInfo":{"name":"curl","version":"1"},
                 "capabilities":{}}}'
# → 200, header `Mcp-Session-Id: <uuid>`, JSON initialize result
```

2. **Send `notifications/initialized`** (replace `$SID`):

```sh
curl -i -X POST http://127.0.0.1:7926/mcp \
  -H 'content-type: application/json' -H "mcp-session-id: $SID" \
  -H 'mcp-protocol-version: 2025-11-25' \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'
# → 202 Accepted
```

3. **List tools** (plain JSON response):

```sh
curl -s -X POST http://127.0.0.1:7926/mcp \
  -H 'content-type: application/json' -H "mcp-session-id: $SID" \
  -H 'mcp-protocol-version: 2025-11-25' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

4. **Call `parentify`**, including `_meta.progressToken` to opt into progress
   streaming (without it the server returns a plain JSON response instead of SSE):

```sh
curl -N -X POST http://127.0.0.1:7926/mcp \
  -H 'content-type: application/json' -H "mcp-session-id: $SID" \
  -H 'mcp-protocol-version: 2025-11-25' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call",
       "params":{"name":"parentify","arguments":{"text":"hi"},"_meta":{"progressToken":"tok"}}}'
# → text/event-stream: progress frames, then the result frame "(hi)", then close
```

5. **End the session**:

```sh
curl -i -X DELETE http://127.0.0.1:7926/mcp \
  -H "mcp-session-id: $SID" -H 'mcp-protocol-version: 2025-11-25'
# → 204
```

## Try it with curl (2026-07-28, stateless)

No handshake and no session id to carry. Every request stands on its own.

1. **List tools** (plain JSON response, port `7927`):

```sh
curl -s -X POST http://127.0.0.1:7927/mcp \
  -H 'content-type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

2. **Call `parentify`** (still a plain JSON response, since this transport has
   no progress/sampling path):

```sh
curl -s -X POST http://127.0.0.1:7927/mcp \
  -H 'content-type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call",
       "params":{"name":"parentify","arguments":{"text":"hi"}}}'
```

3. **Open a subscription** in one terminal (the request itself is the stream,
   so `curl -N` and leave it running). Note the filter fields sit under
   `notifications`:

```sh
curl -N -X POST http://127.0.0.1:7927/mcp \
  -H 'content-type: application/json' \
  -d '{"jsonrpc":"2.0","id":9,"method":"subscriptions/listen",
       "params":{"notifications":{"resourceSubscriptions":["file:///doc/hello.md"]}}}'
# → an SSE frame acknowledging the filter, held open. Nothing closes it but
#   the server, so this curl call does not return on its own.
```

4. **Trigger it** from a second terminal, while the first is still open:

```sh
curl -s -X POST http://127.0.0.1:7927/mcp \
  -H 'content-type: application/json' \
  -d '{"jsonrpc":"2.0","id":10,"method":"tools/call",
       "params":{"name":"touch","arguments":{}}}'
# → the first terminal receives a `notifications/resources/updated` frame
#   for file:///doc/hello.md, tagged with subscription id 9 in _meta
```

There is no `DELETE` to end anything, since there is no session. Closing the
first terminal's `curl` ends that one subscription.

## MCP Inspector

```sh
npx @modelcontextprotocol/inspector
# Transport: "Streamable HTTP"   URL: http://127.0.0.1:7926/mcp
```

Inspector speaks the handshake revisions, so point it at the `2025-03-26`+
server (port `7926`). It has no `2026-07-28` mode to point at the other one.

## What this demonstrates

**2025-03-26+** (`example.my-server`, `transport/streamable_http.clj`):

- Single-endpoint Streamable HTTP (POST/GET/DELETE on `/mcp`).
- `Mcp-Session-Id` session management.
- JSON response for simple calls; SSE upgrade when a tool emits progress.
- `GET /mcp` server→client stream + `Last-Event-Id` resumability.
- DNS-rebinding protection (`Host`/`Origin`) and the `MCP-Protocol-Version` check.

**2026-07-28** (`example.my-server-2026`, `transport/streamable_http_2026.clj`):

- Stateless POST-only handling: no session id, no GET endpoint, no SSE
  resumability. A dropped stream loses its request and the client re-issues it.
- `subscriptions/listen` held open with `mcp-toolkit.json-rpc/hold-open`, one
  response stream per subscription, closed by the server rather than the
  client.
- Host/Origin/Content-Type validation and JSON encoding reused from the 2025
  transport rather than duplicated.
- A known limitation carried over from the source, not hidden: subscription
  channels are keyed by the JSON-RPC id the client chose, and one server
  session is shared by every connection, so two clients that both open a
  subscription with id `1` would collide. A real deployment wants a session
  per client, or a transport-minted key. See the namespace docstring in
  `transport/streamable_http_2026.clj` for the full reasoning.

Both transports share the same MCP content (prompts/resources/tools) with the
SSE example via [`common-mcp-content`](../common-mcp-content).
