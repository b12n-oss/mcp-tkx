# Getting started

## What you'll have at the end of this page

- `mcp-tkx` on your classpath via `deps.edn` (or `:local/root`).
- A minimal STDIO MCP server in CLJC running locally.
- A successful smoke test against the [MCP Inspector](https://github.com/modelcontextprotocol/inspector).
- (Optional) the same server connected to Claude Desktop.

## Prerequisites

| Tool | Why | Install |
|---|---|---|
| **JDK 17+** | Required for Clojure CLI and the JVM execution path | [Adoptium](https://adoptium.net) or `brew install temurin@21` |
| **Clojure CLI 1.11+** | `clj` / `clojure` commands | `brew install clojure/tools/clojure` |
| **Babashka 1.3.0+** *(recommended)* | The `bb example:*` shortcuts | `brew install borkdude/brew/babashka` |
| **Node.js 20+** *(only if you want the cljs path)* | `shadow-cljs compile` + `node out/...js` | `brew install node` |
| **`@modelcontextprotocol/inspector`** *(for smoke testing)* | Browser-based MCP client to drive your server in dev | `npx @modelcontextprotocol/inspector ...` (no install needed) |

## Install

### Via `deps.edn` (git SHA)

```clojure
{:deps {io.github.burinc/mcp-tkx
        {:git/url "git@github.com:burinc/mcp-tkx.git"
         :git/sha "88313b1760046d757943f37d842eb131d3d8edd1"}}}
```

The SSH URL is deliberate. This repo is private, so the shorter
`io.github.burinc/mcp-tkx {:git/tag ...}` form would resolve to an
unauthenticated `https://` URL and fail even for someone who has
access over SSH.

There is no Clojars release, so there is no `:mvn/version` form.

### Via `:local/root` (this fork)

If you are working on this fork itself, or want it on your classpath without pinning a SHA, clone the repo and depend on it locally:

```clojure
{:deps {io.github.burinc/mcp-tkx {:local/root "/path/to/mcp-tkx"}
        funcool/promesa        {:mvn/version "11.0.678"}
        metosin/jsonista       {:mvn/version "0.3.13"}
        camel-snake-kebab/camel-snake-kebab {:mvn/version "0.4.3"}}}
```

## Your first MCP server (STDIO)

The toolkit ships a complete worked example at `example/cljc-server-stdio/`. Run it:

```sh
bb example:server:stdio
# or directly
clojure -X:mcp-server   # with cwd = example/cljc-server-stdio
```

This boots a server with one prompt (`pirate_mode_prompt`), two resources (`hello.md`, `world.md`), one tool (`parentify`), and one resource template (`file:///doc/{path}`).

The server's `main` is in [`example/cljc-server-stdio/src/example/my_server.cljc`](../../example/cljc-server-stdio/src/example/my_server.cljc) — the four moving parts are:

```clojure
;; 1. State — a session atom
(def session
  (atom
   (server/create-session {:prompts            [content/talk-like-pirate-prompt]
                           :resources          [content/hello-doc-resource
                                                content/world-doc-resource]
                           :tools              [content/parentify-tool]
                           :resource-templates content/my-resource-templates
                           :resource-uri-complete-fn content/my-resource-uri-complete-fn})))

;; 2. Context — wires the send-message fn (transport-specific)
(def context
  {:session session
   :send-message (let [^OutputStreamWriter writer *out*
                       json-mapper (j/object-mapper {:encode-key-fn csk/->camelCaseString})]
                   (fn [message]
                     (.write writer (j/write-value-as-string message json-mapper))
                     (.write writer "\n")
                     (.flush writer)))})

;; 3. Inbound — read JSON-RPC lines, decode, hand to the toolkit
(defn listen-messages [context reader]
  (let [{:keys [send-message]} context
        json-mapper (j/object-mapper {:decode-key-fn csk/->kebab-case-keyword})]
    (loop []
      (when-some [line (.readLine reader)]
        (let [message (try (j/read-value line json-mapper)
                           (catch Exception _
                             (send-message json-rpc/parse-error-response)
                             nil))]
          (when message
            (json-rpc/handle-message context message))
          (recur))))))

;; 4. Wire it together — STDIO + an embedded nREPL for live development
(defn main [{:keys [bind port]}]
  (let [server (nrepl/start-server {:bind bind :port port})]
    (try (listen-messages context *in*)
         (finally (nrepl/stop-server server)))))
```

The same file has a `#?(:cljs ...)` branch with the equivalent shadow-cljs / Node.js wiring — same `session`, same `context` shape, different transport plumbing.

The shared content (prompts / resources / tools) lives in [`example/common-mcp-content/src/example/server_content.cljc`](../../example/common-mcp-content/src/example/server_content.cljc) and works on both JVM and Node without changes.

## Your first tool

A minimal tool definition:

```clojure
(def parentify-tool
  {:name "parentify"
   :description "Parentify a text: wraps a text within parenthesis."
   :input-schema {:type "object"
                  :properties {:text {:type "string"
                                      :description "the text to be parentified"}}
                  :required [:text]}
   :tool-fn (fn [context arguments]
              {:content [{:type "text"
                          :text (str "(" (:text arguments) ")")}]
               :is-error false})})
```

Notice:

- Keys use **kebab-case** internally (`:input-schema`, `:is-error`). The toolkit converts to `inputSchema` / `isError` at the wire layer — see [Kebab-case key transformation](kebab-case-transformation.md).
- `:tool-fn` takes `(context, arguments)` and returns either a value (sent as the JSON-RPC `result`) or a Promesa promise that resolves to one.
- The return shape `{:content [...] :is-error false}` is the MCP `tools/call` result. A simple string is also accepted; the handler wraps it.

For a richer example with cancellation and progress notifications, see `parentify-tool` in [`server_content.cljc`](../../example/common-mcp-content/src/example/server_content.cljc):

```clojure
:tool-fn
(fn [context arguments]
  (-> (p/let [text (str "(" (:text arguments) ")")
              _ (p/delay 1000)
              _ (server/notify-progress context {:progress 1 :total 3 :message "thinking ..."})
              _ (p/delay 1000)
              _ (server/notify-progress context {:progress 2 :total 3 :message "thinking harder ..."})
              _ (when @(:is-cancelled context)
                  (throw (ex-info "tool was cancelled" {:note "too bad, was almost done"})))
              _ (p/delay 1000)]
        {:content [{:type "text" :text text}]
         :is-error false})
      (p/catch (fn [ex]
                 {:content [{:type "text" :text (str "Something went wrong: " (ex-message ex))}]
                  :is-error true}))))
```

The `(:is-cancelled context)` atom is wired by the toolkit; deref it inside long-running work to honour client `notifications/cancelled` messages — see [Architecture](architecture.md) §cancellation.

## Smoke test 1 — MCP Inspector

The fastest way to drive your server in dev:

```sh
cd example/cljc-server-stdio
npx @modelcontextprotocol/inspector clojure -X:mcp-server
```

Open the printed URL in your browser. You should see:

- One prompt (`pirate_mode_prompt`)
- Two resources (`hello.md`, `world.md`)
- One tool (`parentify`)
- One resource template (`file:///doc/{path}`)

Click **Connect**, then **Tools → parentify**, type `hello world` into the `text` input, and press **Run Tool**. You should see `(hello world)` come back, with two progress events along the way.

## Smoke test 2 — Claude Desktop

Add to `~/Library/Application Support/Claude/claude_desktop_config.json` (macOS) or `%APPDATA%\Claude\claude_desktop_config.json` (Windows):

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

Restart Claude Desktop. The hammer icon (or the **Connect apps** button) should show your server. Tail the logs for diagnostics:

```sh
tail -n 200 -F ~/Library/Logs/Claude/mcp-server-toolkit.log
```

For Claude Code (CLI) and the SSE transport, see [Claude Desktop / Claude Code setup](claude-desktop-setup.md).

## What to read next

- [Architecture](architecture.md) — the session-atom + context-hashmap split, the message lifecycle, the namespace map.
- [Kebab-case key transformation](kebab-case-transformation.md) — what's actually happening at the JSON boundary, and why your code uses kebab-case.
- [REPL workflow](repl-workflow.md) — `add-tool` / `remove-tool` / `notify-resource-updated` while a client is connected, log-tailing, the rich-comment patterns at the bottom of `my_server.cljc`.
- [2025-11-25 features](2025-11-25-features.md) — Elicitation, Tasks, Sampling with Tools, Icons, Server Description, JSON Schema 2020-12.
