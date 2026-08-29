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

Protocol support for all four revisions (`2024-11-05`, `2025-03-26`,
`2025-06-18`, `2025-11-25`) with automatic negotiation. Elicitation and
server description from the `2025-11-25` revision, plus tasks, which the
spec itself marks experimental. A Malli schema registry for protocol
types.
Dynamic resources via `:read-fn`. Four runnable examples, including a
complete Streamable HTTP reference implementation with session
management, the JSON-or-SSE response flip, `Last-Event-Id` resumability
and Host/Origin validation.

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
