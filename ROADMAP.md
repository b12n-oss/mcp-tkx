# Roadmap

Status of `mcp-tkx`, and what is deliberately not done yet.

## Not implemented

**Pagination.** `prompts/list`, `resources/list` and `tools/list` do not
paginate. The handlers leave a `#_#_:next-cursor "next-page-cursor"`
placeholder where the cursor would go. Clients that expect a cursor get
a single full page instead.

**Babashka support.** The CLJC core runs on the JVM and on Node via
shadow-cljs. The JVM path pulls in `jsonista`, a Jackson wrapper, which
does not load on Babashka's smaller classpath. Supporting bb means
swapping the JSON layer for one bb ships with.

## Deliberately deferred

These are known and chosen, not oversights.

**No CI.** `.github/workflows/` is empty. `bb ci` runs the full pipeline
locally and that is currently the gate.

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
`2025-06-18`, `2025-11-25`) with automatic negotiation. Elicitation,
tasks, sampling with tools, icons and server description from the
`2025-11-25` revision. A Malli schema registry for protocol types.
Dynamic resources via `:read-fn`. Four runnable examples, including a
complete Streamable HTTP reference implementation with session
management, the JSON-or-SSE response flip, `Last-Event-Id` resumability
and Host/Origin validation.
