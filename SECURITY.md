# Security

## Reporting a vulnerability

Please report security issues privately, through GitHub's
[private vulnerability reporting](https://github.com/b12n-oss/mcp-tkx/security/advisories/new).
That opens a draft advisory only you and the maintainers can see.

**Please do not open a public issue for a vulnerability.** A public
report is visible to everyone the moment it lands, including before
there is a fix.

This is a small project maintained by one person, so treat any timeline
below as an intention rather than a guarantee. You should get an
acknowledgement within a week. If a report goes unanswered for two,
assume it was missed rather than ignored, and nudge it.

## What is in scope

`mcp-tkx` is a library. It parses JSON-RPC messages that arrive from
whatever transport you wire up, dispatches them to handlers you write,
and encodes what comes back. Things worth reporting:

- A crafted message that crashes the router, escapes the per-request
  error handling, or takes down a session other than its own.
- Anything that lets one session read or affect another session's state,
  including subscriptions and pending multi round-trip requests.
- A parsing or key-transformation bug that lets a client forge a field
  the server treats as trusted, such as anything in `_meta`.
- Schema validation that accepts a message the spec forbids, where doing
  so has a security consequence rather than only a correctness one.

## What is not

- **Transports.** The library does no I/O. If your HTTP transport lacks
  Origin checking or authentication, that belongs in your transport.
  The `example/` servers are illustrations, not hardened deployments,
  and they say so.
- **Handler code.** A `tool-fn` that shells out with unsanitised input is
  your bug. The library passes arguments through as given, deliberately.
- **`:output-schema` not being enforced.** A tool's declared output
  schema is advertised on `tools/list` and never validated against an
  actual result. That is documented in the README's capability table, and
  the spec makes it the tool author's obligation.

## Supported versions

Only the tip of `main` and the most recent tag. There is no long-term
support branch and no backporting.

| version | supported |
|---|---|
| `main` | yes |
| `v2026-07-28` | yes |
| anything earlier | no |
