# Kebab-case key transformation

This is the most distinctive ergonomic choice in this fork. Your handler code uses kebab-case keys (`:max-tokens`, `:input-schema`, `:list-changed`); the wire format uses camelCase (`maxTokens`, `inputSchema`, `listChanged`). Conversion happens at the transport layer, not inside handlers.

## Why kebab-case

The MCP spec and JSON-RPC use camelCase keys on the wire. Idiomatic Clojure uses kebab-case. The fork's stance is that handlers should look like Clojure, not like a JS port:

```clojure
;; Handler code, kebab-case throughout
(def my-tool
  {:name "summarize"
   :description "Summarize text"
   :input-schema {:type "object"
                  :properties {:text {:type "string"}}}
   :output-schema {:type "object"
                   :properties {:summary {:type "string"}}}
   :tool-fn (fn [_context {:keys [text]}]
              {:content [{:type "text" :text "..."}]
               :is-error false
               :structured-content {:summary "..."}})})

;; Same data on the wire (what the client sees)
{
  "name": "summarize",
  "description": "Summarize text",
  "inputSchema": {"type": "object", "properties": {"text": {"type": "string"}}},
  "outputSchema": {"type": "object", "properties": {"summary": {"type": "string"}}},
  ...
}
```

You never write `:inputSchema` in handler code. The toolkit's internal handlers (`prompt-list-handler`, `resource-list-handler`, `tool-list-handler`, etc.) all expect kebab-case keys when reading your registered prompts / resources / tools, and emit kebab-case keys in their results. The conversion to / from camelCase happens at the JSON encode / decode boundary you control.

## Where the conversion happens

In the canonical wiring ([`example/cljc-server-stdio/src/example/my_server.cljc`](../../example/cljc-server-stdio/src/example/my_server.cljc)):

```clojure
;; Outbound: kebab-case → camelCase string keys
(def context
  {:session session
   :send-message (let [^OutputStreamWriter writer *out*
                       json-mapper (j/object-mapper {:encode-key-fn protocol/encode-key})]
                   (fn [message]
                     (.write writer (j/write-value-as-string message json-mapper))
                     (.write writer "\n")
                     (.flush writer)))})

;; Inbound: camelCase → kebab-case keyword keys
(defn listen-messages [context reader]
  (let [json-mapper (j/object-mapper {:decode-key-fn protocol/decode-key})]
    (loop []
      (when-some [line (.readLine reader)]
        (let [message (try (j/read-value line json-mapper)
                           (catch Exception _
                             (send-message json-rpc/parse-error-response)
                             nil))]
          (when message (json-rpc/handle-message context message))
          (recur))))))
```

The two `j/object-mapper` calls are doing all the work:

- **Outbound**: `:encode-key-fn protocol/encode-key` runs on every keyword as Jackson serializes the result map. `:input-schema` becomes `"inputSchema"`. Strings already in camelCase pass through unchanged.

- **Inbound**: `:decode-key-fn protocol/decode-key` runs on every JSON object key as Jackson deserializes. `"inputSchema"` becomes `:input-schema`. Already-kebab strings pass through unchanged.

**Use `protocol/encode-key` and `protocol/decode-key`, not `camel-snake-kebab`
directly.** They wrap `csk` and add the two exceptions the protocol requires,
and both exceptions fail silently if you skip them:

- **A leading underscore survives.** `"_meta"` decodes to `:_meta`. Raw
  `csk/->kebab-case-keyword` returns `:meta`, so every `_meta` field on the
  message quietly stops matching.
- **A namespaced key stays a string, verbatim.** `"io.modelcontextprotocol/protocolVersion"`
  decodes to itself. Raw `csk` mangles it to
  `:io.modelcontextprotocol/protocol-version`, and re-encoding drops the
  namespace, so the field never round-trips. Every `2026-07-28` `_meta` key is
  namespaced, and so is every multi round-trip correlation key.

Ordinary fields behave identically either way, which is exactly why the
difference is easy to miss until a `_meta` field goes missing in production.

The transformation is **shallow per JSON object** but **deep across the document**, Jackson recurses into nested objects automatically. You don't need to walk anything yourself.

## Wire-to-Clojure key reference

This is the table of every conversion that fires in the MCP protocol, pulled from the MIGRATION-2025-11-25 doc and verified against the schema:

| Wire (camelCase) | Clojure (kebab-case) |
|---|---|
| `inputSchema` | `:input-schema` |
| `outputSchema` | `:output-schema` |
| `structuredContent` | `:structured-content` |
| `isError` | `:is-error` |
| `mimeType` | `:mime-type` |
| `listChanged` | `:list-changed` |
| `maxTokens` | `:max-tokens` |
| `toolChoice` | `:tool-choice` |
| `toolUseId` | `:tool-use-id` |
| `modelPreferences` | `:model-preferences` |
| `intelligencePriority` | `:intelligence-priority` |
| `speedPriority` | `:speed-priority` |
| `costPriority` | `:cost-priority` |
| `systemPrompt` | `:system-prompt` |
| `stopReason` | `:stop-reason` |
| `stopSequences` | `:stop-sequences` |
| `includeContext` | `:include-context` |
| `requestedSchema` | `:requested-schema` |
| `elicitationId` | `:elicitation-id` |
| `statusMessage` | `:status-message` |
| `oneOf` | `:one-of` |
| `anyOf` | `:any-of` |
| `minItems` | `:min-items` |
| `maxItems` | `:max-items` |
| `taskId` | `:task-id` |
| `pollInterval` | `:poll-interval` |
| `nextCursor` | `:next-cursor` |
| `hasMore` | `:has-more` |
| `progressToken` | `:progress-token` |
| `requestId` | `:request-id` |
| `clientInfo` | `:client-info` |
| `serverInfo` | `:server-info` |
| `protocolVersion` | `:protocol-version` |
| `resourceTemplates` | `:resource-templates` |
| `uriTemplate` | `:uri-template` |
| `serverInstructions` | `:server-instructions` |

When in doubt: write kebab-case. If the spec says camelCase, the conversion table covers it.

## Edge case: keys that are already lowercase or that contain non-ASCII

For an ordinary field, `protocol/decode-key` delegates to
`csk/->kebab-case-keyword`, which is robust to:

- Already-lowercase strings: `"name"` → `:name`, `"text"` → `:text`. No-op.
- Snake_case (rare in MCP): `"server_info"` → `:server-info`.
- Acronyms-as-prefixes: `"URLMessage"` → `:url-message` (but `"urlMessage"` also → `:url-message`).
- Single-letter acronyms: `"isOK"` → `:is-ok`.

`protocol/encode-key` is the inverse and delegates to `csk/->camelCaseString`: `:url-message` → `"urlMessage"`. The first segment is always lowercase; subsequent segments capitalise the first letter.

Neither function delegates for the two protocol exceptions. A key starting with `_`, and a key containing `/`, are returned verbatim in both directions:

```clojure
(protocol/decode-key "_meta")                                   ;=> :_meta
(protocol/decode-key "io.modelcontextprotocol/protocolVersion") ;=> "io.modelcontextprotocol/protocolVersion"
(protocol/decode-key "inputSchema")                             ;=> :input-schema
```

The one trap: **string keys that are already camelCase** survive intact through `csk/->camelCaseString`, `"inputSchema"` stays `"inputSchema"`. So if you happen to mix `:input-schema` and `"inputSchema"` in the same map, both serialize to the same wire key. Don't rely on that, pick one.

## Setup for STDIO (JVM)

Library deps:

```clojure
{:deps {metosin/jsonista                   {:mvn/version "0.3.13"}
        camel-snake-kebab/camel-snake-kebab {:mvn/version "0.4.3"}}}
```

Wiring (full version in [`example/cljc-server-stdio/src/example/my_server.cljc`](../../example/cljc-server-stdio/src/example/my_server.cljc)):

```clojure
(:require
 [jsonista.core :as j]
 [mcp-toolkit.protocol :as protocol])

;; Two mappers, one per direction. protocol/* rather than csk/* directly,
;; so _meta and namespaced keys survive the round trip.
(def out-mapper (j/object-mapper {:encode-key-fn protocol/encode-key}))
(def in-mapper  (j/object-mapper {:decode-key-fn protocol/decode-key}))

;; Outbound
(defn send-line [^OutputStreamWriter writer message]
  (.write writer (j/write-value-as-string message out-mapper))
  (.write writer "\n")
  (.flush writer))

;; Inbound
(defn read-line! [reader]
  (when-some [line (.readLine reader)]
    (j/read-value line in-mapper)))
```

`jsonista` is a Jackson wrapper, so the encoding is fast (~5x faster than `cheshire` in microbenchmarks) and predictable.

## Setup for shadow-cljs / Node.js

The Node side uses `camel-snake-kebab.extras` to walk nested maps, since `JSON.parse` has no analogue to Jackson's `:decode-key-fn`. The key functions are the same `protocol/*` pair:

```clojure
(:require
 [camel-snake-kebab.extras :as cske]
 [mcp-toolkit.protocol :as protocol])

;; Outbound
(defn send-message [message]
  (js/process.stdout.write
   (-> (cske/transform-keys protocol/encode-key message)
       clj->js
       js/JSON.stringify
       (str "\n"))))

;; Inbound
(defn parse-line [line]
  (->> (js->clj (js/JSON.parse line))
       (cske/transform-keys protocol/decode-key)))
```

Two things to get right here, both of which fail quietly:

`cske/transform-keys` takes the function first and the collection second. Threading with `->` puts the collection first and returns the *function object*, which `clj->js` and `JSON.stringify` happily turn into `undefined` with no error. Use `->>`, or pass both arguments explicitly as above.

Do not pass `:keywordize-keys true` to `js->clj`. It keywordises every key before `transform-keys` runs, which destroys `_meta` and namespaced keys before `protocol/decode-key` ever sees them. Let `transform-keys` do the conversion on string keys.

`cske/transform-keys` walks the entire tree applying the conversion. It's slower than Jackson's stream-based per-key conversion but simpler.

## Setup for HTTP / SSE

The same pattern applies in [`example/clj-server-sse/`](../../example/clj-server-sse/), http-kit + reitit middleware reads the request body, applies the inbound mapper, hands the Clojure map to `json-rpc/handle-message`, and writes the response back via the outbound mapper. The transport changes; the kebab-case story doesn't.

## When NOT to convert

The `:input-schema` and `:output-schema` values are **JSON Schema documents**, not MCP protocol fields. JSON Schema spec keys are camelCase (`additionalProperties`, `minLength`, `oneOf`). When you write a tool's input schema:

```clojure
{:type "object"
 :properties {:text {:type "string"
                     :minLength 1}}     ; ← camelCase
 :additionalProperties false             ; ← camelCase
 :required [:text]}
```

Whether to write these as kebab (`:min-length`, `:additional-properties`) and let the encoder convert them is a judgment call, if a downstream consumer of your tool registry reads the schema directly without going through the encoder, they'll see kebab-case where JSON Schema convention expects camelCase. The conservative choice is to write JSON Schema keys as camelCase strings or camelCase keywords from the start, accepting that one map in your code base mixes both casing styles.

The 2025-11-25 spec settles this by adopting JSON Schema 2020-12 explicitly, see [2025-11-25 features](2025-11-25-features.md) §JSON Schema dialect.

## See also

- [Architecture](architecture.md): the message lifecycle that this conversion sits at the boundary of.
- [Extraction recipes](extraction-recipes.md): Recipe 1 lifts this kebab-case transport pattern out of the toolkit for use in other JSON-RPC services.
- The canonical wiring is in [`example/cljc-server-stdio/src/example/my_server.cljc`](../../example/cljc-server-stdio/src/example/my_server.cljc).
