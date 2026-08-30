# Roadmap

Status of `mcp-tkx`, and what is deliberately not done yet.

## Not implemented

**Pagination.** `prompts/list`, `resources/list` and `tools/list` do not
paginate. The handlers leave a `#_#_:next-cursor "next-page-cursor"`
placeholder where the cursor would go. Clients that expect a cursor get
a single full page instead.

**Babashka support.** The CLJC core runs on the JVM and on Node via
shadow-cljs. It does not load on Babashka. The blocker is `promesa`,
which `mcp-toolkit.json-rpc` depends on for its promise-based handler
contract: loading it under `bb --classpath $(clojure -Spath)` fails at
`promesa/util.cljc:9:3` on an unresolvable
`java.util.concurrent.locks.ReentrantLock` import. Supporting bb means
finding a promise layer that bb can load.

## Deliberately deferred

These are known and chosen, not oversights.

**CI builds the docs, and nothing else.** `.github/workflows/site.yml`
builds the documentation site on pull requests. The library's own gates
still run locally through `bb ci`; there is no workflow for compile,
lint or test yet.

**The docs site is built but not published.** The deploy job is gated on
a `PUBLISH_SITE` repository variable that is not set, and GitHub Pages
is not enabled. Both are waiting on the decision to open source this
repo.

**No `upstream` remote.** Stale `refs/remotes/upstream/*` tracking refs
exist from an earlier clone, but no remote is configured. As a result no
document here states how far this fork has diverged from upstream, since
that number cannot be verified without adding the remote back.

**No Clojars release.** The library is consumed by git SHA. The `:jar`
and `:deploy` aliases carry the right coordinate,
`io.github.burinc/mcp-tkx`, so that a future release cannot land on
Metosin's coordinate by accident, but neither alias is used today.

## Done

Protocol support for four handshake revisions (`2024-11-05`, `2025-03-26`,
`2025-06-18`, `2025-11-25`) with automatic negotiation, plus the stateless
`2026-07-28` revision on the server side. Elicitation and
server description from the `2025-11-25` revision, plus tasks, which the
spec itself marks experimental. A Malli schema registry for protocol
types.
Dynamic resources via `:read-fn`. Four runnable examples, including a
complete Streamable HTTP reference implementation with session
management, the JSON-or-SSE response flip, `Last-Event-Id` resumability
and Host/Origin validation.

## 2026-07-28, and what is left of it

The stateless core and Multi Round-Trip Requests are done on both sides.
`server/discover`, per-request `_meta`, `resultType` on every result,
`ttlMs` and `cacheScope` on the six cacheable ones, deterministic list
ordering and the renumbered error codes all work. A client fulfils a
server's requests for input and retries automatically, so calling code does
not change between revisions. See
[the guide page](docs/guide/2026-07-28-stateless.md).

Three pieces of the revision are not implemented.

**`subscriptions/listen`.** The revision replaced the HTTP GET endpoint and
`resources/subscribe` with a single long-lived POST-response stream that a
client opts into. Nothing of it exists yet, so a `2026-07-28` session sends
no change notifications at all. The `listChanged` capabilities are still
advertised, since the underlying features work and it is only the delivery
mechanism that is missing.

**Tasks as an extension.** Tasks moved out of the core protocol into
`io.modelcontextprotocol/tasks`, and the redesign replaced the blocking
`tasks/result` with polling via `tasks/get`, added `tasks/update`, and
dropped `tasks/list`. The experimental in-core implementation is still
here, and it is still reachable from the handshake revisions only.

**Streamable HTTP header requirements.** The revision requires `Mcp-Method`
and `Mcp-Name` on POST requests and adds `x-mcp-header` for passing custom
headers from tool parameters. The example transport predates all of that.
It also still implements the session id and the resumability that
`2026-07-28` removed, which is correct for the revisions it serves and wrong
for this one.

## Pagination, and why it is still not done

`2026-07-28` touched the list-result path, since every list result now
carries `ttlMs` and `cacheScope`. That made it worth asking whether
pagination should ride along. It should not.

The two are unrelated. Caching is about how long a client may reuse a
result, and pagination is about splitting one that is too big. The revision
requires the first and says nothing about the second. The
`#_#_:next-cursor` placeholders in the three list handlers are untouched,
and a client that expects a cursor still gets a single full page.

What did land is the ordering the revision asks for. List results are now
sorted, by name for tools and prompts and by URI for resources and
templates, which is a precondition for stable pagination if it is ever
added.

## Partially done

Three things work well enough to use but do not fully match the spec.
`README.md`'s capability table has the detail.

- **Sampling, and sampling with tools.** `request-sampling` carries a
  `FIXME: implementation is not complete` marker inherited from
  upstream. Capability detection around it is complete.
- **Icons.** Shipped as a singular `:icon` holding a bare URI string,
  where the spec wants `icons?: Icon[]`, an array of objects. Icons are
  not attached to server info at all, so a spec-conforming client will
  not see one.
- **`_meta` passthrough.** Works inbound and on results, but the three
  list handlers drop `:_meta` from every entry they return.
