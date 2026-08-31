# Shared example content

This project is not a server, and there is nothing here to run. It holds the
prompt, resource and tool definitions that the four runnable examples share, so
each of them can demonstrate its transport without redefining the same content
four times.

All four depend on it:

- [`cljc-server-stdio`](../cljc-server-stdio) — STDIO, on the JVM and Node
- [`clj-server-sse`](../clj-server-sse) — HTTP with Server-Sent Events
- [`clj-server-streamable-http`](../clj-server-streamable-http) — Streamable HTTP, with both a handshake-era and a `2026-07-28` server
- [`cljc-client-stdio`](../cljc-client-stdio) — a client, over STDIO

## What is in here

`src/example/server_content.cljc` is the server side:

| | |
|---|---|
| `talk-like-pirate-prompt` | a prompt taking one argument |
| `hello-doc-resource`, `world-doc-resource` | two static text resources |
| `my-resource-templates` | a `file:///doc/{path}` template |
| `my-resource-uri-complete-fn` | completion for that template's `path` |
| `parentify-tool` | a tool that wraps text in parentheses, with progress notifications and a cancellation check |

`src/example/client_content.cljc` is the client side, holding the callbacks a
client passes to `create-session`.

Everything is `.cljc` and runs unchanged on the JVM and on Node.

## Using it

Point a `deps.edn` at it the way the examples do:

```clojure
{:deps {io.github.b12n-oss/mcp-tkx {:local/root "../.."}
        example/common-mcp-content {:local/root "../common-mcp-content"}}}
```

Then pass the definitions straight to `create-session`:

```clojure
(require '[example.server-content :as content]
         '[mcp-toolkit.server :as server])

(server/create-session
  {:prompts                  [content/talk-like-pirate-prompt]
   :resources                [content/hello-doc-resource
                              content/world-doc-resource]
   :tools                    [content/parentify-tool]
   :resource-templates       content/my-resource-templates
   :resource-uri-complete-fn content/my-resource-uri-complete-fn})
```

To actually start a server, go to one of the four projects above. If you want a
walkthrough instead, [Getting started](../../docs/guide/getting-started.md)
builds one from scratch.
