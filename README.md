# mcp-tkx

Build [Model Context Protocol](https://modelcontextprotocol.io/) clients
and servers in Clojure and ClojureScript.

`mcp-tkx` is an independently maintained fork of
[metosin/mcp-toolkit](https://github.com/metosin/mcp-toolkit). It speaks
all four MCP protocol revisions, negotiates the version at the
handshake, and keeps your handler code in kebab-case while the wire
stays camelCase. It does no I/O of its own, so you can put it behind
STDIO, HTTP+SSE, Streamable HTTP, or anything else that can move JSON.

We have used this internally for a good while now, across a number of
our own projects, and exercised it against Claude Desktop and Claude
Code. 98 tests run across Clojure and ClojureScript. The API has held
stable in practice, though it carries no formal compatibility guarantee
yet.

## Install

There is no Clojars release. Depend on it by git SHA:

```clojure
{:deps {io.github.burinc/mcp-tkx
        {:git/url "git@github.com:burinc/mcp-tkx.git"
         :git/sha "88313b1760046d757943f37d842eb131d3d8edd1"}}}
```

The SSH URL is deliberate. This repo is private, so the shorter
`io.github.burinc/mcp-tkx` shorthand resolves to an unauthenticated
`https://` URL and fails even for someone who has access over SSH.

Examples inside this repo use `:local/root "../.."` instead.

## Quickstart

A server with one tool, over STDIO:

```clojure
(require '[mcp-toolkit.server :as server]
         '[mcp-toolkit.json-rpc :as json-rpc])

(def greet-tool
  {:name         "greet"
   :title        "Greet someone"
   :description  "Returns a greeting."
   :input-schema {:type       "object"
                  :properties {"name" {:type "string"}}
                  :required   ["name"]}
   :tool-fn      (fn [context {:keys [name]}]
                   {:content [{:type "text"
                               :text (str "Hello, " name)}]})})

(def session
  (atom
    (server/create-session
      {:server-info {:name "my-server" :version "0.1.0"}
       :tools       [greet-tool]})))
```

You supply the transport by giving the context a `:send-message` fn and
feeding decoded messages into `json-rpc/handle-message`. The four
projects under [`example/`](example) show that wiring for STDIO,
HTTP+SSE and Streamable HTTP.

Full walkthrough: [Getting started](docs/guide/getting-started.md).

## What this fork adds

| | What it gives you |
|---|---|
| **Kebab-case end to end** | Handlers see `:max-tokens` and `:input-schema`. The camelCase conversion lives in the transport layer, so no handler does field renaming. |
| **Malli protocol registry** | `mcp-toolkit.schema` types the protocol itself: icons, sampling requests, elicitation, tasks, content blocks. `valid?` / `validate` / `explain`, plus `!`-suffixed throwing constructors. |
| **Dynamic resources** | A resource can compute its content at `resources/read` time through `:read-fn`, returning `:text`, `:blob`, `:contents` or `:error`, or a Promesa promise of any of them. |
| **Four-version negotiation** | One build serves `2024-11-05` through `2025-11-25`, chosen at the handshake rather than pinned at compile time. |
| **Cancellation that reaches your handler** | A per-request `is-cancelled` atom plus a `notifications/cancelled` handler, so long-running work can actually stop. |
| **Streamable HTTP reference server** | A complete implementation with sessions, the JSON-or-SSE response flip, `Last-Event-Id` resumability and Host/Origin validation. |

## Protocol and feature support

Versions are negotiated automatically at the initial handshake:
`2024-11-05`, `2025-03-26`, `2025-06-18` and `2025-11-25`.

JSON-RPC batching was removed in `2025-06-18`. Note that this library
rejects array requests on **every** version, not only that one, so a
`2024-11-05` or `2025-03-26` client that sends a batch gets
`-32600 Invalid Request` back. See `json_rpc.cljc`, which does not
consult the negotiated version before rejecting.

| Capability | Since | Status |
|---|---|---|
| Prompts, resources, tools | `2024-11-05` | Full |
| Cancellation, ping, progress | `2024-11-05` | Full |
| Roots | `2024-11-05` | Full |
| Sampling | `2024-11-05` | Partial, see below |
| Completion, logging | `2024-11-05` | Full |
| Title fields | `2025-06-18` | Full |
| `_meta` passthrough | `2025-06-18` | Partial, see below |
| Output schema on `tools/list` | `2025-06-18` | Advertised, results not validated |
| Resource links | `2025-06-18` | Not implemented |
| Elicitation, form and URL | `2025-11-25` | Partial, see below |
| Sampling with tools | `2025-11-25` | Partial, see below |
| Icons | `2025-11-25` | Partial, see below |
| Server description | `2025-11-25` | Full |
| JSON Schema 2020-12 dialect | `2025-11-25` | Full |
| Tasks | `2025-11-25` | Experimental, as in the spec; also Partial, see below |
| Dynamic resources via `:read-fn` | fork | Full |
| Pagination | any | Not implemented |

<details>
<summary><b>What the partial rows mean</b></summary>

**Sampling, and sampling with tools.** `request-sampling` carries a
`FIXME: implementation is not complete` marker in the source, inherited
from upstream. Capability detection around it is complete, so
`client-supports-sampling-tools?` and friends work; the request path is
the part that is not finished.

**Icons.** The spec puts `icons?: Icon[]` on tools, prompts, resources
and server info, where each `Icon` is an object with `src`, and
optionally `mimeType`, `sizes` and `theme`. This library ships a
singular `:icon` holding a bare URI string, and does not attach icons to
server info at all. A spec-conforming client will therefore not see an
icon. Treat it as a working convention inside this library rather than
as spec support.

**`_meta` passthrough.** Inbound `_meta` reaches your handler, and a
`_meta` you put on a result travels back out. But the `prompts/list`,
`resources/list` and `tools/list` handlers select a fixed set of keys
and drop `:_meta` from each entry, so metadata attached to a
registration does not appear in listings.

**Elicitation.** This library builds both clients and servers, and the
server side can request elicitation (`request-elicitation`). But a
client built with this library has no `elicitation/create` handler —
`impl/client/handler.cljc` registers only `ping`, `roots/list`,
`sampling/createMessage`, and the notification callbacks. A client
built with this library answers `-32601 Method not found` to an
elicitation request, so elicitation only works when your server talks
to a third-party client that implements it.

**Tasks.** Same gap as elicitation, on both sides. The server side can
send `tasks/get` / `tasks/result` / `tasks/cancel` / `tasks/list` to
the client (`request-task-get` and friends), but there is no inbound
handler for any `tasks/*` method on either `impl/server/handler.cljc`
or `impl/client/handler.cljc`. Tasks only work end to end against a
third-party implementation that has its own `tasks/*` handler, which
lines up with the spec's own experimental status for this capability.

</details>

<details>
<summary><b>Runtimes, content blocks, and what is missing</b></summary>

### Runtimes

| Runtime | Status |
|---|---|
| Clojure (JVM) | Supported |
| ClojureScript (Node, shadow-cljs) | Supported |
| Babashka | Not supported. The load fails inside `promesa`, which `json-rpc` depends on. |

### Content block types

Supported: `text`, `image`, `audio`, `tool_use`, `tool_result`.

Not implemented: `resource_link` and embedded resource blocks. A tool
returns content, so it cannot yet hand back a resource reference
alongside it.

### Pagination

`prompts/list`, `resources/list` and `tools/list` return a single full
page. The cursor field is stubbed out in the handlers rather than
implemented, so a client expecting to page through results gets
everything at once instead.

</details>

## Examples

All four live under [`example/`](example) and share their MCP content
through [`common-mcp-content`](example/common-mcp-content), so the
transports are directly comparable.

| Example | Transport | Run it |
|---|---|---|
| [`cljc-server-stdio`](example/cljc-server-stdio) | STDIO server | `bb example:server:stdio` |
| [`cljc-client-stdio`](example/cljc-client-stdio) | STDIO client | `bb example:client:stdio` |
| [`clj-server-sse`](example/clj-server-sse) | HTTP+SSE, `2024-11-05`, deprecated | `bb example:server:sse` |
| [`clj-server-streamable-http`](example/clj-server-streamable-http) | Streamable HTTP, `2025-03-26`+ | `bb example:server:streamable-http` |

## Documentation

The [user guide](docs/guide/index.md) is the place to start. Twelve
pages covering the architecture, all four protocol revisions, schema
validation, both HTTP transports, the REPL workflow, and recipes for
lifting pieces of this into other projects.

<details>
<summary><b>Guide contents</b></summary>

| Page | What it covers |
|---|---|
| [Getting started](docs/guide/getting-started.md) | Install, first STDIO server, smoke test against MCP Inspector |
| [Architecture](docs/guide/architecture.md) | Session atom, context hashmap, message lifecycle, namespace map |
| [Kebab-case transformation](docs/guide/kebab-case-transformation.md) | Where the casing boundary sits, and how to wire it per transport |
| [Protocol versions](docs/guide/protocol-versions.md) | The negotiation algorithm and what each revision adds |
| [Schema validation](docs/guide/schema-validation.md) | The Malli registry and the throwing constructors |
| [Dynamic resources](docs/guide/dynamic-resources.md) | `:read-fn`, its return contract, and when to prefer static content |
| [2025-11-25 features](docs/guide/2025-11-25-features.md) | Elicitation, tasks, sampling with tools, icons |
| [Claude Desktop setup](docs/guide/claude-desktop-setup.md) | `claude_desktop_config.json` and `claude mcp add` |
| [Streamable HTTP](docs/guide/streamable-http.md) | The current remote transport, end to end |
| [REPL workflow](docs/guide/repl-workflow.md) | Editing tools while a client stays connected |
| [Extraction recipes](docs/guide/extraction-recipes.md) | Lifting the transport or the schema registry elsewhere |

</details>

Reference material lives in [`docs/reference/`](docs/reference). Most of
it is preserved from upstream, but only
[`MIGRATION-2025-06-18.md`](docs/reference/MIGRATION-2025-06-18.md) carries
a preserved-from-upstream banner, since it is the only file that still
shows upstream coordinates. The other migration guide,
[2025-11-25](docs/reference/MIGRATION-2025-11-25.md), is this fork's own
work.

## Build and test

```sh
bb test    # full suite, Clojure and ClojureScript, via kaocha
bb check   # compile and lint, run this before committing
bb info    # categorised cheat-sheet of every task
```

## Acknowledgements

This library began as [Metosin](https://metosin.fi)'s
[mcp-toolkit](https://github.com/metosin/mcp-toolkit), and the
architecture that makes it pleasant to work with is theirs: the session
atom, the I/O-agnostic core, the Promesa-based handler contract. That
design carried every feature added here without needing to be
rethought, which is the best thing you can say about a foundation.

Source namespaces are still `mcp-toolkit.*`, so this remains a drop-in
replacement. Only the project name and the build coordinates differ.

<details>
<summary><b>Why this is a fork rather than pull requests</b></summary>

Upstream states its
[contributing policy](https://github.com/metosin/mcp-toolkit#contributing)
plainly:

> Only code **typed and reviewed by a human** will be accepted for
> review, discussion, and maybe merged. We have a policy of keeping the
> source code clean, organized, and easy to read for a human.

The work in this fork is LLM-assisted, so it falls outside that policy.
Opening pull requests that upstream has already said it cannot accept
would only cost a maintainer their time, so the changes are maintained
here instead. Setting that bar is upstream's call and a reasonable one
to make.

There is a practical driver as well. Some of our internal libraries and
tools are built on this one, and we plan to open source a few of them.
Anything we publish needs its dependencies to exist as real projects
that other people can resolve, and the changes those tools rely on are
exactly the ones upstream cannot take. Holding those changes as a
private patch set would leave everything built on top of them
unpublishable too. Giving the fork its own name, coordinate and history
is what makes opening up that work possible at all.

EPL-2.0 exists so an independent continuation like this one can happen
without either side needing to agree.

</details>

## Other MCP libraries for Clojure

- [MCP Clojure SDK](https://github.com/unravel-team/mcp-clojure-sdk)
- [Clojure MCP](https://github.com/bhauman/clojure-mcp)
- Calva's [Backseat Driver](https://github.com/BetterThanTomorrow/calva-backseat-driver)
- [Modex](https://github.com/theronic/modex)

## License

Distributed under the [Eclipse Public License v2.0](LICENSE.txt).

Copyright (c) [Metosin](https://metosin.fi) and contributors.
