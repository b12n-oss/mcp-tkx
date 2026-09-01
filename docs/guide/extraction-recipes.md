# Extraction recipes

Lift-and-shift recipes for reusable patterns from `mcp-tkx`. Each recipe answers "what do I copy, what do I skip, how long does it take, what trips you up."

---

## Recipe 1, Lift the kebab-case JSON-RPC transport into another service

**You want:** Idiomatic Clojure handlers (kebab-case keys) over a wire format that uses camelCase. Works for any JSON-RPC service, not just MCP: JSON Schema validation services, LSP servers, custom JSON-RPC APIs.

**Lift from:** [`example/cljc-server-stdio/src/example/my_server.cljc`](https://github.com/b12n-oss/mcp-tkx/blob/main/example/cljc-server-stdio/src/example/my_server.cljc): specifically the `:send-message` fn in `context` and the `listen-messages` reader.

**What you copy:**

- The two `j/object-mapper` calls, one with `{:encode-key-fn protocol/encode-key}` for outbound, one with `{:decode-key-fn protocol/decode-key}` for inbound. Stable across STDIO, HTTP, WebSocket, only the IO bytes change.
- The deps, `metosin/jsonista` (Jackson wrapper) + `camel-snake-kebab/camel-snake-kebab`.
- The shadow-cljs / Node analogue: `(cske/transform-keys protocol/encode-key message)` for outbound and `protocol/decode-key` for inbound. Slower (walks the whole tree) but simpler, JS doesn't have an analogue to Jackson's stream-based per-key conversion. Note the argument order: `transform-keys` takes the function first, so threading with `->` silently yields the function object rather than the map.
- The discipline: NEVER hand-write camelCase keys in handler code. Always kebab.

**What to skip:**

- The MCP-specific `json-rpc/handle-message`, since your service has a different dispatch table.
- The `mcp-toolkit.json-rpc` namespace's `parse-error-response` / `method-not-found-response` / `resource-not-found`, they're idiomatic but you can write your own in 5 lines.

**Why kebab internally:**

Idiomatic Clojure code uses kebab-case. Mixing camelCase keys for protocol fields and kebab for domain fields is visual noise; the conversion at the encode boundary is one line per direction, and Jackson is fast enough that the cost is invisible.

**Effort:** ~1 hour. Most of the time is verifying that all your handlers consistently emit kebab-case (no leftover camelCase string keys from a `clj->js` round-trip).

**Hidden traps:**

- **Already-camelCase string keys survive `csk/->camelCaseString` unchanged.** `"inputSchema"` (string) and `:input-schema` (kebab keyword) both serialize to `"inputSchema"`. Don't mix them in the same map: pick one form per project.
- **JSON Schema documents are camelCase by spec convention** (`additionalProperties`, `minLength`, `oneOf`). If you write `:additional-properties` in handler code and let the encoder convert, downstream consumers that read your tool-list response see `additionalProperties` (correct on the wire). But if a non-MCP consumer reads your tool registry directly and bypasses the encoder, they'll see kebab where camelCase is expected. The conservative choice is to write JSON Schema keys as camelCase strings (or camelCase keywords) from the start.
- **Key conversion keywordizes nearly everything.** `protocol/decode-key` returns keywords for ordinary fields, so user-supplied dictionary keys in a generic content map become keywords on inbound too. It makes two exceptions, both required by MCP: a key starting with `_` keeps its underscore, and a key containing `/` stays a string so its namespace survives the trip back out. If keywordising user content is a problem, scope the conversion narrower: maybe only the JSON-RPC envelope fields.
- **Do not reach for `camel-snake-kebab` directly here.** It handles ordinary fields identically, which is what makes the mistake easy, but it strips the underscore from `_meta` and mangles namespaced keys. `protocol/encode-key` and `protocol/decode-key` are that library plus those two exceptions.

**See:** [Kebab-case key transformation](kebab-case-transformation.md), [`example/cljc-server-stdio/src/example/my_server.cljc`](https://github.com/b12n-oss/mcp-tkx/blob/main/example/cljc-server-stdio/src/example/my_server.cljc).

---

## Recipe 2, Lift the Malli protocol-schema-registry pattern

**You want:** A namespace of `def`-bound Malli schemas for the types in some external protocol (MCP, LSP, JSON-RPC, REST API), plus `valid?` / `validate` / `explain` predicates and `!`-suffix throwing constructors that combine "build the value" with "validate the result."

**Lift from:** `src/mcp_toolkit/schema.cljc`: the structure not the contents.

**What you copy:**

- The three validation entry points: `valid?` (returns bool), `explain` (returns humanized errors or nil), `validate` (returns `{:valid? true}` or `{:valid? false :errors [...]}`).
- The `!`-suffix throwing constructor pattern: `(defn url-elicitation! [opts] (let [request (url-elicitation opts) result (validate Schema request)] (if (:valid? result) request (throw (ex-info "..." {:errors ... :request ...})))))`. Pairs cheap construction with a one-line validation gate.
- Inline `:fn` predicates with `:error/message` for protocol-specific rules (e.g. "must be https://" for the `Icon` schema).
- Deep dependencies between schemas, `Task` references `TaskStatus`, `ToolResultMessage` references `ToolResultContent`. Malli composes via plain `def`s; no registry needed for protocol types you control.

**What to skip:**

- The MCP-specific schemas themselves (`Icon`, `EnumSchema`, `SamplingRequest`, etc.), domain-specific.
- The 2025-11-25-only schemas if you're targeting an older spec.

**Why Malli (not spec, not Schema):**

- Inline `:fn` predicates with custom error messages, clean.
- `malli.error/humanize` returns a tree of strings that maps back to the input shape: debuggable error output for free.
- No instrumentation overhead at runtime: schemas are values, and you call `validate` / `valid?` on them at boundaries.

**Effort:** ~3 hours for 31 schemas, depending on how many inline `:fn` validators you need.

**Hidden traps:**

- **`:fn` validators ignore the schema's previous `:and` arms.** `[:and :string [:fn ...]]` runs `:fn` on every value, even non-strings. The `:and` short-circuits on schema validity, so `(m/validate ... 42)` returns false at `:string`, but if you write `[:fn ...]` standalone, your fn must defensively handle non-string input.
- **`:enum` is by-value, not by-keyword-or-string.** `[:enum "form" "url"]` accepts only strings; `[:enum :form :url]` accepts only keywords. Pick one and stick to it across your schema namespace.
- **`{:optional true}` only works inside `[:map [...]]` entries.** It's not a global modifier: `[:optional :string]` is a syntax error.

**See:** [Schema validation](schema-validation.md) for the full API this recipe lifts from.

---

## Recipe 3, Lift the dynamic-resource `:read-fn` pattern

**You want:** A resource / asset / record API where some entries serve static content and others compute content on demand. Same registration shape; per-entry behaviour switch.

**Lift from:** `src/mcp_toolkit/impl/server/handler.cljc`: the `resource-read-handler` function (~30 lines).

**What you copy:**

- The branch on `(:read-fn resource)`: present → call `(read-fn context uri)`; absent → return static `:text` / `:blob` content.
- The Promesa-aware return chain: `(p/then ...)` to await a promise, `(p/catch ...)` to convert any thrown exception into an error envelope.
- The auto-merge of registration metadata (`[:uri :description :mime-type]`) into the `:read-fn` return, convenient when the read-fn returns just `{:text "..."}` and you don't want to repeat the URI / mime-type.
- The four return-shape conventions: `{:text}` / `{:blob}` / `{:contents [...]}` / `{:error {:code :message}}`.

**What to skip:**

- The MCP-specific `resources/read` JSON-RPC envelope, since your protocol differs.
- The `select-keys` mask (`[:uri :description :mime-type]`), pick keys appropriate to your domain.

**Why the auto-merge:**

Most `:read-fn`s want to return just the dynamic part (the `:text` or `:blob`) and inherit the static metadata (the URI, the mime-type). Without auto-merge, every `:read-fn` repeats `(merge {:uri "..." :mime-type "..."} {:text ...})`, which is rote.

**Effort:** ~1 hour. The fn is ~30 lines; most of the time is choosing the merge mask and the error envelope shape for your domain.

**Hidden traps:**

- **Synchronous vs. async returns are interchangeable here.** The `(p/then ...)` chain accepts a plain map, a delivered promise, an in-flight promise: all transparent to the caller. But: if your `:read-fn` performs a synchronous I/O call (e.g. `(slurp big-file)`), every read blocks the JSON-RPC dispatch thread. Wrap in `(p/future ...)` if reads can be slow.
- **`:contents` bypasses the auto-merge.** If your `:read-fn` returns `{:contents [...]}`, the toolkit returns it verbatim: no `:uri` injection. Use this when you need precise control over each part's URI / mime-type.

**See:** [Dynamic resources](dynamic-resources.md), `src/mcp_toolkit/impl/server/handler.cljc` `resource-read-handler`.

---

## Recipe 4, Lift the multi-version handshake negotiation

**You want:** A protocol that supports multiple wire versions; the server accepts whatever version the client requests if known, else falls back to the latest the server supports.

**Lift from:** `src/mcp_toolkit/impl/server/handler.cljc`: the `initialize-handler` function (~25 lines), specifically the version-pick clause.

**What you copy:**

```clojure
(let [protocol-version
      (if (contains? (set server-supported-protocol-versions) client-protocol-version)
        client-protocol-version
        (last server-supported-protocol-versions))]
  ;; store, return in handshake response, etc.
  )
```

That's the whole negotiation. Plus:

- The `:server-supported-protocol-versions` list in session state, ordered oldest-to-newest. The "fallback" picks `(last ...)`, so the order matters.
- The two-phase dispatch: a `pre-initialization` handler-by-method table (just `ping` + `initialize` + `notifications/initialized`), swapped to `post-initialization` (the full method set) only after the client confirms initialization. Prevents the client from calling business methods before the version is settled.

**What to skip:**

- The MCP-specific capability negotiation (`:client-capabilities`, `:server-capabilities`), that's protocol-specific.
- The `initialized-notification-handler`, your protocol may not have an explicit "ready" notification; a one-shot `initialize` request might be enough.

**Why the "highest known fallback" rule:**

A client asking for an unknown version is either ahead of the server (newer client) or has a typo. The "highest known" pick is conservative, the newer client probably can negotiate down (most protocols are forward-compatible by design), and a typo'd client gets a sensible default rather than an error.

**Effort:** ~30 minutes. The pattern is small; most of the integration cost is wiring the dispatch-table swap into your existing handler routing.

**Hidden traps:**

- **`(last [...])` returns nil on an empty vector.** If your `:server-supported-protocol-versions` is somehow empty (config bug, dynamic computation gone wrong), the negotiation silently picks `nil` and downstream handlers see `(:protocol-version @session) → nil`. Add an assertion at session-creation time: `(assert (seq supported-versions))`.
- **The toolkit doesn't strip per-version fields based on negotiated version.** Your handlers' return shapes go on the wire as-is. If you need strict back-compat (e.g. truly hide 2025-11-25 fields from 2024-11-05 clients), branch on the negotiated version inside the handler.

**See:** [Protocol versions](protocol-versions.md), `src/mcp_toolkit/impl/server/handler.cljc` `initialize-handler`.

---

## Recipe 5, Lift the cancellation-via-atom pattern

**You want:** Long-running JSON-RPC handlers that the client can cancel mid-flight. No global cancellation registry; per-request atom that the handler polls.

**Lift from:** `src/mcp_toolkit/json_rpc.cljc` (`route-message`) + `src/mcp_toolkit/impl/server/handler.cljc` (`cancelled-notification-handler`).

**What you copy:**

- In `route-message`: when dispatching a method call with an `:id`, create `(atom false)`, register under `:is-cancelled-by-request-id` keyed by request-id, also add to handler context as `:is-cancelled`.
- In the handler context, your business handlers `@(:is-cancelled context)` periodically and bail out if true.
- After the handler resolves, check `@is-cancelled` once more before sending the result; if cancelled, drop the result.
- In a separate `cancelled-notification-handler`: when `notifications/cancelled` arrives with a `request-id`, look up the atom and `(reset! ... true)`.
- Cleanup: `(swap! session update :is-cancelled-by-request-id dissoc id)` after the handler settles, regardless of success / cancellation.

**What to skip:**

- The MCP-specific message names (`notifications/cancelled`), your protocol uses different names.

**Why per-request atom (vs. a thread interrupt):**

- Promesa promises don't have a native cancel signal, the `(p/cancel)` fn delivers a rejection but doesn't interrupt the work in progress. The atom is the in-band signal.
- An atom check inside a long-running loop is cheap (one volatile read per iteration). The cancellation is cooperative, the handler gets to clean up, log, etc.
- No thread management. Works for synchronous handlers, async handlers, and Promesa chains uniformly.

**Effort:** ~1 hour. The pattern is ~20 lines total; most of the time is auditing existing handlers to add `@(:is-cancelled context)` checks at logical bail-out points.

**Hidden traps:**

- **The atom is per-request, not per-session.** Adding the same atom to multiple in-flight requests would let one cancellation cancel all of them. The toolkit's `:is-cancelled-by-request-id` map ensures one atom per in-flight request.
- **A handler that doesn't check `@is-cancelled` runs to completion regardless of cancellation.** Cancellation is cooperative: the toolkit's only enforcement is to drop the **result** if cancelled, not to interrupt the work.
- **Memory leak if you forget the cleanup.** The session map grows unboundedly if `:is-cancelled-by-request-id` entries aren't dissoc'd after settle. The toolkit does this in `(p/handle ...)` after the handler resolves; in your own port, mirror that.

**See:** [Architecture](architecture.md) §cancellation, `src/mcp_toolkit/json_rpc.cljc` `route-message`.

---

## Recipe 6, Lift the REPL-aware notification helpers

**You want:** A library where mutations to in-memory state (registries, settings, caches) automatically emit notifications to a connected client. So adding a row to a registry from the REPL "just works": the client sees the change live.

**Lift from:** `src/mcp_toolkit/server.cljc`: `add-tool`, `remove-tool`, `add-prompt`, `remove-prompt`, `add-resource`, `remove-resource`, `set-resource-templates`, `notify-resource-updated`.

**What you copy:**

The pattern is uniformly:

```clojure
(defn add-tool [context tool]
  (let [{:keys [session]} context]
    (swap! session update :tool-by-name assoc (:name tool) tool)
    (notify-tool-list-changed context))
  nil)
```

Three lines: mutate the session, fire the notification, return `nil`. The notification helper itself is a thin wrapper around `(json-rpc/send-message context (json-rpc/notification "tools/list_changed"))`.

**What to skip:**

- The MCP-specific notification topic names (`prompts/list_changed`, `resources/list_changed`, `tools/list_changed`).
- The selective `notify-resource-updated`, only fires when the client is subscribed to the URI. If your protocol doesn't have subscriptions, drop the check.

**Why the explicit return `nil`:**

Returning the swap result (an updated map) leaks internal session state to the REPL caller. `nil` is unambiguous: "did the side-effect, no return value to interpret."

**Effort:** ~1 hour for ~10 helper fns + their notification topics.

**Hidden traps:**

- **REPL mutations need the actual context, not a copy.** If you `def context` and later mutate the underlying session via `(swap! session ...)`, both the running server and the REPL see the change (atom semantics). But if you `def session-snapshot @session` and pass that, you're working on a stale value. Always pass the live atom.
- **Multiple REPL-driven mutations can race the dispatch loop.** If the dispatcher is mid-handler when you `(add-tool context ...)`, the in-flight handler sees the new tool registry only if it dereferences the session after the swap lands. For most use cases this is fine; if not, scope mutations to known-quiet windows.

**See:** [REPL workflow](repl-workflow.md), [Architecture](architecture.md) §"REPL-time mutations".

---

## Where each recipe is used downstream

| Recipe | Used by |
|---|---|
| 1, Kebab-case JSON-RPC transport | Any Clojure JSON-RPC server that wants kebab-case handlers over a camelCase wire |
| 2, Malli protocol-schema registry | Any resource-type registry that wants a single Malli source of truth for validation and construction |
| 3, Dynamic resources via `:read-fn` | Registry-style APIs whose entries are computed on demand |
| 4, Multi-version handshake negotiation | Any evolving-spec library that needs to support several protocol versions at once |
| 5, Cancellation via atom | Any long-running tool-fn / prompt-fn in any MCP server built on this fork; lifts to non-MCP async handlers |
| 6, REPL-aware notification helpers | Any registry-style API where REPL-time inspection is a user feature |

## See also

- [Index](index.md): the guide overview.
- [Architecture](architecture.md): orientation for where each pattern sits in the namespace map.
