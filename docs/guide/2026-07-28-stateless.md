# 2026-07-28: the stateless revision

`2026-07-28` is not another set of features bolted onto the handshake
protocol. It removes the handshake. It removes the session. It removes
server-initiated requests. If you have a working `2025-11-25` server, most of
your tool and prompt code carries over untouched, but the shape of the
conversation around it changes completely.

This page covers what moved, what it means for code you have already written,
and how to run a server on the new revision.

## What the revision removed

| Gone | Replaced by |
| --- | --- |
| `initialize` and `notifications/initialized` | `server/discover`, or nothing at all |
| The protocol-level session and `Mcp-Session-Id` | Per-request `_meta`, plus server-minted handles passed as ordinary tool arguments |
| Server-initiated `sampling/createMessage`, `roots/list`, `elicitation/create` | Multi Round-Trip Requests |
| `resources/subscribe` and `resources/unsubscribe` | `subscriptions/listen` |
| `ping` | Nothing. It existed to keep a session alive |
| `logging/setLevel` | A per-request `_meta` field |
| `notifications/roots/list_changed` | Nothing, since Roots is deprecated |

The point of all this is operational. With no session to pin a client to, an
MCP server sits behind an ordinary round-robin load balancer, and no request
depends on which process answered the last one.

## Creating a stateless session

Pass `:protocol-version`. Without it you get the handshake behaviour, exactly
as before.

```clojure
(require '[mcp-toolkit.server :as server])

(def session
  (atom (server/create-session
         {:protocol-version "2026-07-28"
          :server-info {:name "my-server" :version "1.0.0"}
          :tools [my-tool]})))
```

A stateless session is live the moment you make it. There is no handshake to
wait for, so `:initialized` is already true and the dispatch table is already
serving. A `2025-11-25` session still starts with only `initialize`,
`notifications/initialized` and `ping` available, and opens up once the client
has introduced itself.

The two do not interact. A session speaks one revision, chosen when you create
it, and the transport decides which one it is serving.

## Discovery

`server/discover` is how a client learns what a server can do. It replaces
`initialize`, with one important difference: it is a plain request that any
client may call at any time, and its result is cacheable.

```clojure
;; request
{:jsonrpc "2.0" :id 1 :method "server/discover"}

;; result
{:supported-versions ["2025-03-26" "2025-06-18" "2025-11-25" "2026-07-28"]
 :capabilities {:completions {} :logging {} :tools {:list-changed true}}
 :instructions "..."
 :result-type "complete"
 :ttl-ms 3600000
 :cache-scope "public"
 :_meta {"io.modelcontextprotocol/serverInfo" {:name "my-server" :version "1.0.0"}}}
```

Capabilities here are derived from what your session actually holds. A server
with no prompts does not advertise prompts. That differs from `initialize`,
which answers with a fixed map, and the reason is that discovery is the only
place a `2026-07-28` client ever learns capabilities, and the answer is
cacheable for as long as you say. An overclaim there lasts.

## Every request carries its own context

There is no negotiated state, so each request brings what the server needs to
know:

```clojure
{:jsonrpc "2.0" :id 2 :method "tools/call"
 :params {:name "greet"
          :arguments {}
          :_meta {"io.modelcontextprotocol/protocolVersion" "2026-07-28"
                  "io.modelcontextprotocol/clientCapabilities" {:elicitation {:form {}}}
                  "io.modelcontextprotocol/clientInfo" {:name "some-client" :version "1"}}}}
```

Read those from the handler context, not from the session:

```clojure
(server/request-client-capabilities context)  ;; => {:elicitation {:form {}}}
(server/request-log-level context)            ;; => nil unless the client opted in
```

They live on the context deliberately. Two requests arriving at once can
declare different capabilities, and writing either onto the shared session
would let them race.

If a request asks for a version this library does not implement, it gets back
`-32022` naming what is supported.

## Multi Round-Trip Requests

This is the part that changes how you write handlers.

Under `2025-11-25` a tool could ask the client something in the middle of a
call and wait for the answer:

```clojure
;; 2025-11-25
(defn greet [context _arguments]
  (p/let [answer (server/request-elicitation context {...})]
    {:content [{:type "text" :text (str "hello " (:name answer))}]}))
```

That is gone. A handler that needs something now returns a request for it and
stops. The client answers, then re-issues the **same** call with the answers
attached, and your handler runs a second time.

```clojure
;; 2026-07-28
(defn greet [context _arguments]
  (if-some [answer (server/input-response context :who)]
    ;; second turn: the client has answered
    {:content [{:type "text" :text (str "hello " (-> answer :content :name))}]}

    ;; first turn: ask, and stop
    (server/input-required
     {:input-requests {:who (server/elicit-form-request
                             {:message "Who are you?"
                              :requested-schema {:type "object"
                                                 :properties {:name {:type "string"}}
                                                 :required ["name"]}})}
      :request-state "asked-for-name"})))
```

You can ask for several things at once. Each gets a key you choose, and the
answers come back under the same keys.

### `:request-state` is not a continuation

The retry may reach a different process, so whatever your handler needs in
order to resume has to travel inside `:request-state`. It is a string, the
client treats it as opaque, and it comes back untouched.

Do not park a closure in an atom and hand out an id for it. That works on your
laptop and fails the moment a second instance exists, which is the exact
property this revision was designed to give you.

Serialize instead:

```clojure
{:request-state (pr-str {:step :awaiting-name :order-id 4711})}
```

### Helpers

| Function | Purpose |
| --- | --- |
| `server/input-required` | Build the interim result that asks for input |
| `server/input-response` | Read one answer by the key you asked under |
| `server/input-responses` | Read every answer |
| `server/request-state` | Read the state you sent last turn |
| `server/retry?` | True when this is a retry rather than a first call |
| `server/elicit-form-request` | Ask the user to fill in a form |
| `server/elicit-url-request` | Send the user to a URL, for an OAuth flow say |
| `server/sampling-request` | Ask the client's model for a completion |
| `server/roots-request` | Ask the client for its roots |

Sampling and Roots both still work, and both are deprecated as of this
revision, with a removal window of at least twelve months. For new code, talk
to a model provider directly rather than sampling, and take file locations as
tool arguments rather than roots.

## Results changed shape

Every result names its own type, and the six cacheable ones carry a freshness
hint:

```clojure
{:result-type "complete"          ;; or "input_required"
 :content [...]
 :ttl-ms 60000                    ;; list and read results only
 :cache-scope "public"            ;; "public" or "private"
 :_meta {"io.modelcontextprotocol/serverInfo" {...}}}
```

`:ttl-ms` and `:cache-scope` are policy rather than protocol, so they are
defaults you can change. Set `:cache-policy` on the session to move them, or
return your own values from a handler and they win:

```clojure
(server/create-session
 {:protocol-version "2026-07-28"
  :cache-policy {"tools/list" {:ttl-ms 300000 :cache-scope "public"}}})
```

List results also come back in a stable order, sorted by name or URI, so a
client's cache is not invalidated by a reshuffle that changed nothing.

## Error codes moved

The revision carved `-32020` to `-32099` out for the specification itself and
renumbered accordingly.

| Condition | Code |
| --- | --- |
| Header mismatch | `-32020` |
| Missing required client capability | `-32021` |
| Unsupported protocol version | `-32022` |
| Resource not found | `-32602`, previously `-32002` |

A `2025-11-25` server still answers `-32002` for a missing resource. Only the
new revision renumbered it.

## Writing a transport

Two rules, and both fail silently if you get them wrong.

A key beginning with an underscore keeps it. A key containing a slash passes
through verbatim as a string. Everything else converts between camelCase and
kebab-case as usual. The library ships this, so use it rather than reaching
for camel-snake-kebab directly:

```clojure
(require '[jsonista.core :as j]
         '[mcp-toolkit.protocol :as protocol])

(def object-mapper
  (j/object-mapper {:decode-key-fn protocol/decode-key
                    :encode-key-fn protocol/encode-key}))
```

The reason is worth knowing, because the failure is invisible. Every
`2026-07-28` `_meta` key is namespaced, as in
`io.modelcontextprotocol/protocolVersion`. Turn that into a keyword and
convert it back and you get `protocolVersion`, with the namespace gone and no
client able to recognise the field. Multi round-trip correlation keys have the
same problem from the other side: a bare `:step1` returns as `:step-1`, and
`:foo-bar`, `:foo_bar` and `:fooBar` all collapse onto one wire key. That is
why this library namespaces them before they go out.

If you are converting in ClojureScript, do not reach for
`(js->clj m :keywordize-keys true)` first. It turns those keys into namespaced
keywords before your converter sees them, and the namespace is already at risk
by then. Parse to string keys, then convert.

## What is not implemented yet

- `subscriptions/listen`, so no server-to-client change notifications on this
  revision. `listChanged` capabilities are still advertised because the
  underlying features work, and the delivery mechanism is what is missing.
- Client-side support. `mcp-toolkit.client` still speaks the handshake
  revisions only.
- The `io.modelcontextprotocol/tasks` extension, which is where Tasks went.
- The required Streamable HTTP headers, `Mcp-Method` and `Mcp-Name`, and
  `x-mcp-header` support.

See [ROADMAP.md](../../ROADMAP.md) for where these sit.
