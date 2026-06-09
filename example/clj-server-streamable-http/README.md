# Streamable HTTP MCP server (example)

A minimal MCP server using the **Streamable HTTP** transport (MCP `2025-03-26`+),
built on `mcp-toolkit`. Single endpoint `/mcp`; session id via the
`Mcp-Session-Id` header; progress/sampling stream back over SSE; resumable via
`Last-Event-Id`.

Companion to the older [`clj-server-sse`](../clj-server-sse) example (2024-11-05
HTTP+SSE transport).

## Run

```sh
bb example:server:streamable-http     # from the repo root
# or:
clojure -X:mcp-server                 # from this directory (defaults to 127.0.0.1:7926)
```

## Try it with curl

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

4. **Call `parentify`** — emits progress, so the response is an SSE stream:

```sh
curl -N -X POST http://127.0.0.1:7926/mcp \
  -H 'content-type: application/json' -H "mcp-session-id: $SID" \
  -H 'mcp-protocol-version: 2025-11-25' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call",
       "params":{"name":"parentify","arguments":{"text":"hi"}}}'
# → text/event-stream: progress frames, then the result frame "(hi)", then close
```

5. **End the session**:

```sh
curl -i -X DELETE http://127.0.0.1:7926/mcp \
  -H "mcp-session-id: $SID" -H 'mcp-protocol-version: 2025-11-25'
# → 204
```

## MCP Inspector

```sh
npx @modelcontextprotocol/inspector
# Transport: "Streamable HTTP"   URL: http://127.0.0.1:7926/mcp
```

## What this demonstrates

- Single-endpoint Streamable HTTP (POST/GET/DELETE on `/mcp`).
- `Mcp-Session-Id` session management.
- JSON response for simple calls; SSE upgrade when a tool emits progress.
- `GET /mcp` server→client stream + `Last-Event-Id` resumability.
- DNS-rebinding protection (`Host`/`Origin`) and the `MCP-Protocol-Version` check.

The transport is in `src/example/transport/streamable_http.clj`; the MCP content
(prompts/resources/tools) is shared with the SSE example via
[`common-mcp-content`](../common-mcp-content).
