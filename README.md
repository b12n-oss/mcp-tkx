# mcp-tkx

A Clojure and ClojureScript library for building MCP ([Model Context
Protocol](https://modelcontextprotocol.io/)) clients and servers.

Status: **alpha quality**. Tested against Claude Desktop and Claude Code,
with no problems found for the features implemented.

## About this fork

`mcp-tkx` is an independently maintained fork of
[metosin/mcp-toolkit](https://github.com/metosin/mcp-toolkit), which
remains the upstream project.

### Why a fork rather than pull requests

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
to make. EPL-2.0 exists so that an independent continuation like this
one can happen without either side needing to agree.

Source namespaces are still `mcp-toolkit.*`, so this stays a drop-in
replacement for the upstream library. Only the project name and the
build coordinates differ.

What this fork adds on top of upstream:

- Protocol `2025-11-25` support, covering elicitation, tasks, sampling
  with tools, icons, server description, and the JSON Schema 2020-12
  dialect.
- A Malli schema registry for MCP protocol types in
  `mcp-toolkit.schema`, with `!`-suffixed throwing constructors.
- Kebab-case keys end to end, with the camelCase conversion pushed out
  to the transport layer.
- Dynamic resources via `:read-fn`, so resource content can be computed
  at `resources/read` time.
- A complete Streamable HTTP reference implementation under
  `example/clj-server-streamable-http/`.

Copyright (c) [Metosin](https://metosin.fi) and contributors.
Distributed under the [Eclipse Public License v2.0](LICENSE.txt).

## Install

There is no Clojars release. Consume the library by git SHA:

```clojure
{:deps {io.github.burinc/mcp-tkx
        {:git/url "git@github.com:burinc/mcp-tkx.git"
         :git/sha "88313b1760046d757943f37d842eb131d3d8edd1"}}}
```

The SSH URL is deliberate. This repo is private, so the shorter
`io.github.burinc/mcp-tkx` shorthand would resolve to an
unauthenticated `https://` URL and fail even for someone who has
access over SSH.

The example projects in this repo use `:local/root "../.."` instead,
since they already sit inside the tree.

## Documentation

The [user guide](docs/guide/index.md) is the place to start. It covers
getting started, the architecture, all four protocol versions, schema
validation, both HTTP transports, and the REPL workflow.

For the fastest path to a running server, read
[Getting started](docs/guide/getting-started.md), then
[Architecture](docs/guide/architecture.md).

Migration guides:

- [2025-11-25](docs/reference/MIGRATION-2025-11-25.md), for upgrading to
  the latest protocol version.
- [2025-06-18](docs/reference/MIGRATION-2025-06-18.md), preserved from
  upstream, for upgrading from older versions.

## Protocol version support

Versions are negotiated automatically at the initial handshake.

| Version | Support |
|---|---|
| `2025-11-25` | full, including all features new in that revision |
| `2025-06-18` | full |
| `2025-03-26` | full, backward compatible |
| `2024-11-05` | legacy |

`2025-06-18` removed JSON-RPC batching, so array requests are no longer
accepted on that version or later.

## Implemented features

- [x] API for both clients and servers
- [x] CLJC
  - [x] Clojure
  - [x] ClojureScript
  - [ ] Babashka
- I/O agnostic library
- Uses Promesa to support async tasks in prompts, resources and tools
- MCP features
  - [x] Cancellation
  - [x] Ping
  - [x] Progress
  - [x] Roots
  - [x] Sampling
  - [x] Sampling with tools (`2025-11-25`)
  - [x] Prompts
  - [x] Resources, static and dynamic
  - [x] Tools
  - [x] Completion
  - [x] Logging
  - [x] Elicitation (`2025-11-25`)
  - [x] Tasks, experimental (`2025-11-25`)
  - [x] Icons (`2025-11-25`)
  - [ ] Pagination

## Example projects

All four live under [`example/`](example) and share their MCP content
via [`common-mcp-content`](example/common-mcp-content).

| Example | Transport |
|---|---|
| [`cljc-server-stdio`](example/cljc-server-stdio) | STDIO server |
| [`cljc-client-stdio`](example/cljc-client-stdio) | STDIO client |
| [`clj-server-sse`](example/clj-server-sse) | HTTP+SSE server (`2024-11-05`, deprecated) |
| [`clj-server-streamable-http`](example/clj-server-streamable-http) | Streamable HTTP server (`2025-03-26`+, current) |

Resources can serve static `:text` / `:blob` content or compute it on
demand through a `:read-fn`. See
[Dynamic resources](docs/guide/dynamic-resources.md) for the return-shape
contract and the async story.

## Build and test

```sh
bb test    # full suite via kaocha, Clojure and ClojureScript
bb check   # compile and lint, run this before committing
bb info    # categorised cheat-sheet of every task
bb tasks   # flat list with docstrings
```

## Its place in the AI ecosystem

This library aims to be more convenient for the Clojure community than
the official MCP SDKs for Java or TypeScript. It gives you the tools to
build an MCP server in Clojure or ClojureScript, but ships no prompts,
resources or tools of its own for working on a Clojure codebase. It is
for building general purpose MCP servers.

## Other MCP libraries

- [MCP Clojure SDK](https://github.com/unravel-team/mcp-clojure-sdk)
- Calva's [Backseat Driver](https://github.com/BetterThanTomorrow/calva-backseat-driver)
- [Clojure MCP](https://github.com/bhauman/clojure-mcp)
- [Modex](https://github.com/theronic/modex)

## License

Distributed under the [Eclipse Public License v2.0](LICENSE.txt).

Copyright (c) [Metosin](https://metosin.fi) and contributors.
