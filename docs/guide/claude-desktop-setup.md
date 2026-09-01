# Claude Desktop / Claude Code setup

This page covers the actual config files for plugging an `mcp-tkx` server into the two main Anthropic clients. Both clients support STDIO; Claude Code also supports SSE (Claude Desktop does not, as of November 2025).

## Claude Desktop (STDIO)

### Config file location

| OS | Path |
|---|---|
| macOS | `~/Library/Application Support/Claude/claude_desktop_config.json` |
| Windows | `%APPDATA%\Claude\claude_desktop_config.json` |
| Linux | `~/.config/Claude/claude_desktop_config.json` |

### Direct invocation via `clojure -X`

For the example STDIO server in this repo:

```json
{
  "mcpServers": {
    "toolkit": {
      "command": "/bin/sh",
      "args": [
        "-c",
        "cd /path/to/mcp-tkx/example/cljc-server-stdio && clojure -X:mcp-server"
      ]
    }
  }
}
```

For your own server, replace the path and the alias:

```json
{
  "mcpServers": {
    "my-server": {
      "command": "/bin/sh",
      "args": [
        "-c",
        "cd /path/to/your/project && clojure -X:mcp-server"
      ]
    }
  }
}
```

The `/bin/sh -c "cd ... && ..."` wrapper is the most reliable way to set the working directory before launch. On Windows, use `cmd.exe /c` instead, or use `cwd` (some Claude Desktop builds support it; older builds don't).

After saving the config, **restart Claude Desktop** (quit fully, File → Quit on macOS, not just close the window). On launch, the hammer icon (or "Connect apps" button) shows your server.

### Direct invocation via `bb`

If you have Babashka installed and a `bb mcp` (or similar) task in your `bb.edn`:

```json
{
  "mcpServers": {
    "my-server": {
      "command": "/bin/sh",
      "args": [
        "-c",
        "cd /path/to/your/project && bb mcp"
      ]
    }
  }
}
```

bb startup is ~50ms, JVM Clojure cold start is ~3–5s. If your server doesn't need to run on the JVM (e.g. a thin proxy), bb is the faster option. Most Clojure servers, including this fork's example, use the JVM and accept the cold-start cost; the server stays alive for the duration of the Claude Desktop session.

### Docker-based server config

The repo ships a `docker-compose.yml` and `Dockerfile` for running the example server in a container. This isolates JVM dependencies but adds startup latency:

```json
{
  "mcpServers": {
    "toolkit": {
      "command": "/bin/sh",
      "args": [
        "-c",
        "cd /path/to/mcp-tkx && docker-compose run --service-ports --rm mcp-server clojure -X:mcp-server '{:bind \"0.0.0.0\"}'"
      ]
    }
  }
}
```

**Important:** build the image first (`docker-compose build`) before pointing Claude Desktop at it. Otherwise the build output (Maven downloads, Docker layer pulls) gets sent to Claude Desktop as JSON-RPC and breaks the connection.

### Tailing logs

Claude Desktop writes one log file per MCP server, named after the key in `mcpServers`:

```sh
# macOS, replace "toolkit" with your server name
tail -n 200 -F ~/Library/Logs/Claude/mcp-server-toolkit.log

# Windows
type %APPDATA%\Claude\Logs\mcp-server-toolkit.log

# Linux
tail -n 200 -F ~/.config/Claude/Logs/mcp-server-toolkit.log
```

The log captures the server's **stderr** plus a record of the JSON-RPC traffic. The toolkit's example server logs to stderr (via the embedded nREPL banner and any user `println` to `*err*`); stdout is reserved for JSON-RPC.

If the log shows JSON-RPC parse errors at startup, your server is probably writing non-JSON to stdout (a startup banner, a `println`, a build artefact). Move it to stderr, `(.println System/err "...")`, or remove it.

## Claude Code (CLI)

[Claude Code](https://docs.anthropic.com/en/docs/claude-code) reads two config files: project-level `.mcp.json` (commit this so teammates inherit) and global `~/.claude.json`.

### Project-level `.mcp.json`

```json
{
  "mcpServers": {
    "my-server": {
      "command": "clj",
      "args": ["-X:mcp-server"],
      "cwd": "${workspaceFolder}"
    }
  }
}
```

`${workspaceFolder}` expands to the repo root. `cwd` is reliable in Claude Code (unlike older Claude Desktop builds).

### Global via `claude mcp add`

```sh
# Add for the current project
claude mcp add my-server -- clj -X:mcp-server

# List
claude mcp list

# Show config for one server
claude mcp get my-server

# Remove
claude mcp remove my-server
```

### Claude Code via SSE

This fork's example SSE server ([`example/clj-server-sse/`](https://github.com/b12n-oss/mcp-tkx/tree/main/example/clj-server-sse)) listens on `:7925`. Start it first:

```sh
cd example/clj-server-sse
clojure -X:mcp-server
# Server logs:
# Listening on http://127.0.0.1:7925
```

Then register with Claude Code:

```sh
claude mcp add toolkit-sse --transport sse http://127.0.0.1:7925/sse
```

Make sure the server is running before starting Claude Code; the SSE transport requires the server to be live for the connection to establish.

**Claude Desktop does not currently support SSE** (as of the 2025-11-25 spec release). Use STDIO for Claude Desktop and SSE for Claude Code if you need both.

The SSE example uses the [older 2024-11-05 SSE transport](https://modelcontextprotocol.io/specification/2024-11-05/basic/transports). For the current `2025-03-26`+ transport, use the [`clj-server-streamable-http`](https://github.com/b12n-oss/mcp-tkx/tree/main/example/clj-server-streamable-http) example (single `/mcp` endpoint, `Mcp-Session-Id` sessions, `Last-Event-Id` resumability), see the [Streamable HTTP transport](streamable-http.md) guide. Register it with Claude Code via:

```sh
claude mcp add toolkit-http --transport http http://127.0.0.1:7926/mcp
```

## MCP Inspector, for development

[`@modelcontextprotocol/inspector`](https://github.com/modelcontextprotocol/inspector) is the browser-based dev tool for driving any MCP server:

```sh
# STDIO
npx @modelcontextprotocol/inspector clojure -X:mcp-server

# Or for the cljs / Node example
npx @modelcontextprotocol/inspector node out/node-server.js

# For SSE, open the inspector and configure manually
npx @modelcontextprotocol/inspector
# In the UI: Transport Type → SSE; URL → http://127.0.0.1:7925/sse
```

The Inspector shows the prompt list, resource list, tool list, and lets you call tools / read resources interactively. It's the fastest way to validate a server before wiring it into Claude.

## Multi-server setup

Claude Desktop / Claude Code support multiple servers concurrently. Each entry under `mcpServers` is a separate process:

```json
{
  "mcpServers": {
    "spock": {
      "command": "clj",
      "args": ["-M:spock-mcp"],
      "cwd": "/path/to/spock"
    },
    "toolkit": {
      "command": "clj",
      "args": ["-X:mcp-server"],
      "cwd": "/path/to/mcp-tkx/example/cljc-server-stdio"
    },
    "ts-mcp": {
      "command": "node",
      "args": ["/path/to/ts-mcp/dist/index.js"]
    }
  }
}
```

The client merges tool / prompt / resource lists from all connected servers. Tool names should be globally unique; if two servers register the same tool name, behaviour is client-dependent (Claude Desktop disambiguates with a server prefix in the UI; Claude Code may pick one).

## Troubleshooting checklist

- **Tool list not showing up**: Click "Connect apps" in Claude Desktop's UI to force a refresh. The first-load delay can be 5–15 seconds depending on JVM cold-start.
- **Server starts then immediately exits**: Check the log file. The most common cause is the server writing JSON to a non-stdout stream, or writing non-JSON to stdout.
- **Claude Code SSE connection refused**: Start the server before starting Claude Code. SSE requires the server to be live at registration time AND at connection time.
- **Different behaviour between MCP Inspector and Claude Desktop**: Inspector negotiates the latest version (2025-11-25); Claude Desktop may negotiate down. Check `(:protocol-version @session)` in the running server to see what was actually negotiated.
- **`clojure: command not found` in Claude Desktop logs**: Claude Desktop launches the server with a minimal PATH on macOS. Wrap the command with the absolute path: `"command": "/opt/homebrew/bin/clojure"` or use the `/bin/sh -c "cd ... && clojure ..."` form which inherits the user shell's PATH.

## See also

- [Getting started](getting-started.md): the example server that's referenced in the configs above.
- [Architecture](architecture.md) §initialization: what the handshake actually does.
- [REPL workflow](repl-workflow.md): once the server is running, how to iterate on tools without restarting.
- The example server READMEs: [`example/cljc-server-stdio/README.md`](https://github.com/b12n-oss/mcp-tkx/blob/main/example/cljc-server-stdio/README.md), [`example/clj-server-sse/README.md`](https://github.com/b12n-oss/mcp-tkx/blob/main/example/clj-server-sse/README.md).
