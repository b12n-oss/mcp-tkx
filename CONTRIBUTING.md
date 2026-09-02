# Contributing to mcp-tkx

Issues and pull requests are welcome. This file is the short version of
what a change needs to pass, plus the handful of traps in this codebase
that have actually cost someone a debugging session. Read the traps
before the checklist; they are the part you cannot guess.

## The gate

There is no CI running the suite. `bb ci` on your own machine is the gate,
and it does the whole thing: clean, compile, lint, test, and a real
Babashka round trip.

```sh
bb ci
```

**The suite must stay at 236 tests and 0 failures.** If the test count
moves, either your change added tests or it broke something, and you
should know which. **Do not gate on the assertion count.** It is
nondeterministic and has been observed anywhere from 1379 to 1385 without
a change.

Faster loops while you work:

```sh
bb test        # the full suite, Clojure and ClojureScript
bb check       # compile and lint only
bb bb:smoke    # a real MCP round trip on Babashka
bb docs:links  # every markdown link, for a repo reader and a site reader
bb docs:blocks # every fenced Clojure block actually parses
```

## Traps

Each of these has bitten someone. None of them announces itself.

**Namespaced wire keys must survive untouched.**
`mcp-toolkit.protocol/decode-key` and `encode-key` deliberately leave two
kinds of key alone: anything starting with `_`, and anything containing
`/`. Both look like special cases begging to be simplified into a plain
camel-snake-kebab call. Round-tripping `io.modelcontextprotocol/protocolVersion`
through a keyword drops the namespace, and no client recognises the field
afterwards. Nothing throws. Tests in `protocol_2026_test.cljc` pin this.

**`p/do` is not symmetric across hosts.** It often runs synchronously on
the JVM and always defers on ClojureScript. A test that calls a
promise-returning function and then reads state on the next line passes on
the JVM and fails on Node. This has caught us four separate times. Await
the promise.

**If the ClojureScript test count wanders, the build is stale.** Run
`bb clean`. `.cljs_node_repl` holds kaocha-cljs's compiled output, and a
stale one silently runs old code. It has produced counts anywhere from 116
to 228 while nothing was wrong with the tree.

**Do not move the promesa override into the main `:deps`.** The
`:babashka` alias pins promesa 12 because Babashka needs it. The project
pin stays at 11 because promesa 12 has a ClojureScript regression: a
`p/handle` whose function returns a `Throwable` rejects with promesa's own
wrapper promise instead of the exception, so every error response silently
loses its reason. Reported as
[promesa#171](https://github.com/funcool/promesa/issues/171). The two
problems sit on opposite sides of the platform split, which is why the
override is scoped.

**`src/` has zero reader conditionals, and that is worth keeping.** It
means the JVM and the ClojureScript build genuinely run the same code, and
it is why JVM-only coverage says something about the whole library. Adding
a `#?(:cljs ...)` branch under `src/` is not forbidden, but it costs that
property, so say why in the pull request.

**The namespaces are `mcp-toolkit.*` on purpose.** The project is
`mcp-tkx`, the namespaces are not, and that mismatch is a decision rather
than an oversight. It keeps the library a drop-in for upstream. Renaming
them would break every consumer.

## Babashka

`bb bb:smoke` drives a real `tools/list` and `tools/call` through a server
session. Loading proves nothing here, because both of the problems it
guards against sit behind a require that succeeds.

It needs Babashka 1.13.220 or newer and refuses to run on anything older
with a message saying so. That refusal exits 2 rather than 1, so `bb ci`
treats it as "could not run here" and carries on. A genuine failure exits
1 and stops the gate.

## Docs

The guide lives in `docs/guide/` and renders through
[docs-engine](https://github.com/b12n-oss/docs-engine).

```sh
bb site:serve   # build and serve locally
```

Two checks run over the docs, and both are part of `bb ci`'s siblings
rather than optional:

- `bb docs:links` resolves every link twice, once for someone reading the
  files on GitHub and once for someone reading the built site, because a
  link can satisfy one and fail the other. It also checks that `#anchor`
  fragments name a heading that exists.
- `bb docs:blocks` reads every fenced Clojure block with a strict
  top-level `read` loop. A block a reader might copy has to parse. Mark a
  deliberate fragment with ` ```clojure fragment `.

**Prose style: no em-dashes anywhere**, headings and table cells included.
Simple connectives, varied sentence length. This applies to documentation,
commit messages and code comments alike.

## Pull requests

- Branch off `main`.
- Keep the change and its tests in the same commit where that is natural.
- Explain **why** in the commit message. The diff already shows what.
- Run `bb ci` before you open it, and say in the description that you did.
- Stage files by explicit path. Please do not `git add -A`.

A regression fix should come with a test that fails without the fix. Every
fix in the 2026-08-31 review round carries one, and each was checked to
fail with its fix reverted rather than assumed to.

## AI-assisted contributions

Upstream `mcp-toolkit` accepts
[only code typed and reviewed by a human](https://github.com/metosin/mcp-toolkit#contributing),
and that policy is part of why this is a fork rather than a stream of pull
requests.

This project takes a different line: **we care how the code behaves and
whether you can defend it, not which tools you used getting there.** Use
whatever helps. What we ask is that you have read what you are submitting,
that you can explain why each part is there, and that the tests are real
tests rather than assertions shaped to pass. A pull request whose author
cannot answer questions about it is the problem, and that is true whether
a model wrote it or a person did.

Do not paste generated documentation you have not verified. Several
claims in this repository's own docs were wrong on the first pass and were
caught by running the command rather than by reading it back.

## Security

Do not open a public issue for a vulnerability. Use
[private reporting](https://github.com/b12n-oss/mcp-tkx/security/advisories/new).
[SECURITY.md](SECURITY.md) sets out what is in scope, and what belongs in
your own transport or handler rather than here.

## Conduct

[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md), and it is short. Criticise the
code, not the person. Asking an obvious question and telling a maintainer
they got it wrong are both explicitly welcome.

## Licence

Eclipse Public License 2.0, the same as upstream. By contributing you
agree your contribution is licensed under it.
