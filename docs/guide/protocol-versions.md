# Protocol versions

This fork supports five MCP protocol revisions in one library: `2024-11-05`, `2025-03-26`, `2025-06-18`, `2025-11-25` and `2026-07-28`.

The first four negotiate automatically at the initial handshake, and this page covers that algorithm, the feature matrix, and the breaking changes between them. `2026-07-28` is a different animal, since it removed the handshake altogether, so it is opted into rather than negotiated. See [2026-07-28: the stateless revision](2026-07-28-stateless.md) for that one.

## Version negotiation

`create-session` picks one of three lists of supported versions, by session kind:

```clojure
;; src/mcp_toolkit/server.cljc create-session, lines 1000-1010
(let [stateless (protocol/stateless? protocol-version)
      handshake-versions ["2024-11-05" "2025-03-26" "2025-06-18" "2025-11-25"]]
  {:server-supported-protocol-versions
   (cond
     dual-era? (conj handshake-versions protocol/latest-protocol-version)
     stateless [protocol/latest-protocol-version]
     :else handshake-versions)
   ...})
```

- A plain handshake session, the default, gets the four versions above.
- A stateless session (`:protocol-version "2026-07-28"`, no `:dual-era?`) gets
  a single-entry list, `["2026-07-28"]`, since it has no handshake to
  negotiate at all.
- A dual-era session (`:dual-era? true`) gets the four handshake versions
  with `2026-07-28` appended, so the list ends with the newest revision
  instead of `2025-11-25`.

When the client sends `initialize`, `initialize-handler` (in `src/mcp_toolkit/impl/server/handler.cljc`) picks the negotiated version from whichever list the session was given:

```clojure
(let [protocol-version
      (if (contains? (set server-supported-protocol-versions) client-protocol-version)
        client-protocol-version                       ; pick what the client asked for
        (last server-supported-protocol-versions))])  ; else: highest the server supports
```

The rules in plain English:

1. If the client asks for a version the session's list contains, the server agrees.
2. If the client asks for an unknown version, the server picks the last entry in that list. For a plain session that is `"2025-11-25"`, the latest handshake revision. A dual-era session's list ends with `"2026-07-28"` instead, so an unrecognised version falls back there rather than to a handshake revision.
3. There is no minimum version enforcement. A client asking for `"2024-11-05"` against this server gets `"2024-11-05"`.

Those three rules hold for a plain session. A dual-era one complicates them:

> **Open question: what a dual-era session should do at `initialize`.**
> It is not yet settled whether the following is intended. First, it
> accepts `2026-07-28` at `initialize` and returns it, so a client can
> reach that revision through a handshake the revision itself removed.
> Second, a version it does not recognise falls back to `2026-07-28` too.
> The fallback is `(last server-supported-protocol-versions)`
> (`handler.cljc:212-214`), and `create-session` appends `2026-07-28` to
> the dual list, so the offer made to the client least likely to speak it
> is the newest revision; a plain session correctly offers `2025-11-25`
> here. Third, both paths advertise `resources.subscribe`, which
> `server/discover` on the same session omits on purpose, because
> `2026-07-28` replaced `resources/subscribe` with `subscriptions/listen`.
> Whether to narrow the dual list, change the fallback, or accept all
> three as the cost of serving both eras is an open decision. This page
> describes the behaviour as it stands.

The negotiated version is stored in `(:protocol-version @session)` and returned in the `initialize` response. Your handlers can branch on it if needed (rarely required, the toolkit's own handlers are already version-aware).

The client side has the symmetric rule in `src/mcp_toolkit/impl/client/handler.cljc`.

## Default version

For the handshake revisions, `create-session` takes no default version. The client decides at handshake time and the server accepts what it asks for, subject to the rules above. For a server that never acts as a client, that default is the right one: support all four and let the client pick.

`2026-07-28` is the exception, because there is no handshake in which to negotiate. A session speaks it only when you say so:

```clojure
(server/create-session {:protocol-version "2026-07-28"
                        :tools [...]})
```

That session's dispatch table still has an `initialize` entry, but it can't
perform a handshake. Calling it returns the `UnsupportedProtocolVersion`
diagnostic described in [2026-07-28: the stateless revision](2026-07-28-stateless.md),
naming the versions the server does support, and a client is expected to call
`server/discover` instead.

## Feature matrix

This matrix covers the four handshake revisions only. `2026-07-28` negotiates
differently and has its own feature set. See
[2026-07-28: the stateless revision](2026-07-28-stateless.md) and the
[capability table in the README](../../README.md#protocol-and-feature-support).

| Feature | 2024-11-05 | 2025-03-26 | 2025-06-18 | 2025-11-25 |
|---|---|---|---|---|
| Cancellation | ✅ | ✅ | ✅ | ✅ |
| Ping | ✅ | ✅ | ✅ | ✅ |
| Progress notifications | ✅ | ✅ | ✅ | ✅ |
| Roots | ✅ | ✅ | ✅ | ✅ |
| Sampling | ✅ | ✅ | ✅ | ✅ |
| Prompts | ✅ | ✅ | ✅ | ✅ |
| Resources (static `:text` / `:blob`) | ✅ | ✅ | ✅ | ✅ |
| Resources (dynamic `:read-fn`) | ✅ | ✅ | ✅ | ✅ |
| Tools | ✅ | ✅ | ✅ | ✅ |
| Completion | ✅ | ✅ | ✅ | ✅ |
| Logging | ✅ | ✅ | ✅ | ✅ |
| HTTP/SSE transport (the older one) | ✅ | ✅ | ✅ | ✅ |
| Title fields (prompts / resources / tools) | n/a | n/a | ✅ | ✅ |
| Structured tool output (`:output-schema`) | n/a | n/a | ✅ | ✅ |
| Resource links in tool results | n/a | n/a | ❌ Not implemented | ❌ Not implemented |
| Completion context (`:completion-context`) | n/a | n/a | ✅ | ✅ |
| `_meta` field support | n/a | n/a | ⚠️ Partial | ⚠️ Partial |
| **JSON-RPC batching** | ❌ rejected | ❌ rejected | ❌ removed | ❌ removed |
| Server description (`:server-info :description`) | n/a | n/a | n/a | ✅ |
| Icons (`:icon`) | n/a | n/a | n/a | ⚠️ Partial |
| Sampling with tools (`:tools` + `:tool-choice`) | n/a | n/a | n/a | ✅ |
| Elicitation, form mode | n/a | n/a | n/a | ⚠️ Partial |
| Elicitation, URL mode (OAuth) | n/a | n/a | n/a | ⚠️ Partial |
| Tasks (experimental) | n/a | n/a | n/a | ⚠️ Partial |
| JSON Schema 2020-12 dialect | n/a | n/a | n/a | ✅ |

Rows marked `⚠️ Partial` are implemented far enough to be useful but do
not fully match the spec. `README.md`'s capability table carries the
detail for each. In short: sampling carries an unfinished-implementation
marker in the source; elicitation and tasks work outbound from a server
but have no inbound handler, so a client built with this library cannot
answer either; and icons ship as a singular string field rather than the
spec's array of objects.

## Breaking change: JSON-RPC batching removed in 2025-06-18

Up to 2025-03-26, MCP allowed JSON-RPC batch requests, an array of method calls in a single message:

```json
[{"jsonrpc": "2.0", "id": 1, "method": "tools/list"},
 {"jsonrpc": "2.0", "id": 2, "method": "resources/list"}]
```

From 2025-06-18 onwards, batching is no longer supported. The toolkit enforces this in `json-rpc/handle-message`:

```clojure
(if (vector? message)
  ;; Batch requests are not supported as of 2025-06-18
  (send-message invalid-request-response)
  ;; Single message
  (route-message (assoc context :message message)))
```

Servers built with this fork return `{:error {:code -32600 :message "Invalid Request"}}` on any array request, regardless of negotiated version. If you need to talk to a 2024-11-05 / 2025-03-26 client that sends batches, you'll need to either fork the dispatch or have the client send unbatched messages.

In practice, no major MCP client we've tested sends batches, so this is largely a spec-cleanliness change.

## What 2025-06-18 added

The 2025-06-18 spec is the previous-generation feature set. Most MCP clients in the wild today negotiate down to this version.

### Title fields

Prompts, resources, and tools gained an optional `:title` for human-readable display:

```clojure
(def my-tool
  {:name "calculate_sum"
   :title "Calculator, Addition"            ; ← NEW
   :description "Calculates the sum of two numbers"
   :input-schema {...}
   :tool-fn (fn [_context {:keys [a b]}] (str (+ a b)))})
```

Same shape applies to prompts (`:title`) and resources (`:title`). The toolkit's list handlers (`prompt-list-handler`, `resource-list-handler`, `tool-list-handler`) include `:title` in the response.

### Structured tool output (`:output-schema`)

Tools can declare an output schema and return structured content:

```clojure
(def weather-tool
  {:name "get_weather"
   :title "Weather Lookup"
   :description "Get current weather for a city"
   :input-schema {:type "object" :properties {:city {:type "string"}} :required [:city]}
   :output-schema {:type "object"                    ; ← NEW
                   :properties {:temp {:type "number"}
                                :conditions {:type "string"}}
                   :required [:temp :conditions]}
   :tool-fn (fn [_context {:keys [city]}]
              {:content [{:type "text" :text "Sunny, 22°C"}]
               :structured-content {:temp 22 :conditions "Sunny"}    ; ← NEW
               :is-error false})})
```

Clients that support `:output-schema` can validate `:structured-content` and present it as typed data instead of (or in addition to) the human-readable `:content`. The handler in `tool-call-handler` (`src/mcp_toolkit/impl/server/handler.cljc`) preserves both keys when present.

### Resource links in tool results

Not implemented. The spec's `resource_link` and embedded-resource
content-block types don't exist in `schema.cljc`, a tool can return
`text`, `image`, `audio`, `tool_use` and `tool_result` content, but it
has no spec-conforming way to hand back a resource reference alongside
it.

`tool-call-handler` will pass a `:resources` key through unmodified if
your tool result map happens to include one:

```clojure
:tool-fn (fn [_context {:keys [path]}]
           {:content [{:type "text" :text "Found 3 matches"}]
            :resources [{:uri (str "file://" path)
                         :mime-type "text/plain"
                         :text (slurp path)}]})
```

but this library gives `:resources` no wire meaning. It isn't a
recognized content-block type, so a spec-conforming client won't
render it or let a user navigate to it. Treat the passthrough as a
place to carry your own data alongside a result, not as resource-link
support.

### Completion context

When a client requests completion, it can pass previously-resolved values as context:

```json
{
  "ref": {"type": "ref/prompt", "name": "my_prompt"},
  "argument": {"name": "param2", "value": "partial"},
  "context": {"arguments": {"param1": "already-resolved"}}
}
```

Your `:complete-fn` (and `:resource-uri-complete-fn`) receive this as `:completion-context` on the handler context, so completions can be context-aware:

```clojure
:complete-fn (fn [{:keys [completion-context] :as context} param-name partial-value]
               (let [param1 (get-in completion-context [:arguments :param1])]
                 ;; use param1 to filter completions for param2
                 ...))
```

The wiring is in `completion-complete-handler`, it conditionally adds `:completion-context` to the handler context only when the request includes it.

### `_meta` field support

Partial. Various message types can carry an optional `:_meta` field for
client / server-specific metadata, and inbound `:_meta` does reach your
handler, with any `:_meta` you attach to a result travelling back out.
But `prompt-list-handler`, `resource-list-handler` and `tool-list-handler`
each `select-keys` their entries down to a fixed set of fields, and
`:_meta` isn't one of them: so metadata you attach to a registration
never appears in a `prompts/list` / `resources/list` / `tools/list`
response. `impl/meta_support.cljc` exists but is required by no
namespace under `src/`, only by a test; it does not handle merging for
you.

## What 2025-11-25 added

This is the latest handshake revision; most production clients haven't negotiated up to it yet but Claude Desktop and Claude Code do. See [2025-11-25 features](2025-11-25-features.md) for the full walkthrough, the headline list:

- **Server description**: `:server-info :description` field for human-readable server intent.
- **Icons**: Visual icons on prompts / resources / tools / templates (data:image/ URIs or https:// URLs).
- **Sampling with tools**: LLMs can use tools during sampling requests (`:tools` + `:tool-choice` on `request-sampling`).
- **Elicitation, form mode**: Server requests structured user input via JSON Schema-described forms.
- **Elicitation, URL mode**: Server redirects user to an external URL for OAuth flows / sensitive data collection.
- **Tasks (experimental)**: Long-running operation state machines with `tasks/get` / `tasks/result` / `tasks/cancel` / `tasks/list`.
- **JSON Schema 2020-12 dialect**: `JSON_SCHEMA_DIALECT` constant + `with-schema-dialect` helper.

All of these are gated by client capability, your server can declare support, but the toolkit's `request-*` fns return `nil` (don't send anything) when the client doesn't declare the matching capability.

## Backward compatibility

The library negotiates down silently. There's no warning, no opt-in. If your tool registers `:output-schema` (a 2025-06-18 feature) and a 2024-11-05 client connects, the field is sent in `tool-list` response anyway, the client either ignores it or fails depending on its strictness. The toolkit doesn't strip per-version fields based on negotiated version.

Two practical consequences:

1. **You can write handlers against the 2025-11-25 surface and ship.** Older clients will negotiate down on the version field but still receive the new feature shapes; they'll ignore unknown keys per JSON-RPC convention.

2. **If you need strict back-compat behaviour** (e.g. truly hide 2025-11-25 fields from 2024-11-05 clients), branch on `(:protocol-version @session)` inside your handler. There's no automatic stripping.

## See also

- [Architecture](architecture.md): the initialization handshake that negotiates the version.
- [2025-11-25 features](2025-11-25-features.md): every new feature in the latest handshake revision.
- [`MIGRATION-2025-06-18.md`](../reference/MIGRATION-2025-06-18.md): the 2025-03-26 → 2025-06-18 migration writeup.
- [`docs/reference/MIGRATION-2025-11-25.md`](../reference/MIGRATION-2025-11-25.md): the 2025-06-18 → 2025-11-25 migration writeup.
- [Spec, 2025-11-25](https://modelcontextprotocol.io/specification/2025-11-25)
- [Spec, 2025-06-18](https://modelcontextprotocol.io/specification/2025-06-18)
