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
{:supported-versions ["2026-07-28"]
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

## The client side

A client opts in the same way, and then stops thinking about any of this.

```clojure
(require '[mcp-toolkit.client :as client])

(def session
  (atom (client/create-session
         {:protocol-version "2026-07-28"
          :client-info {:name "my-client" :version "1.0.0"}
          :client-capabilities {:elicitation {:form {}}}
          :on-elicitation-requested
          (fn [_context params]
            ;; ask your user, however your app does that
            {:action "accept" :content {:name (prompt-user (:message params))}})})))
```

Every request it sends carries the version, capabilities and identity in
`_meta` automatically. You do not assemble that yourself.

### Round trips are handled for you

This is the part worth understanding. When a server answers with
`input_required`, the client fulfils the requests and re-issues the call,
echoing the server's keys and `requestState` back untouched. Your code sees
only the finished result:

```clojure
(client/request-tool-invocation context "greet" {})
;; => a promise of {:result-type "complete" :content [{:type "text" :text "hello octocat"}]}
```

Two round trips happened there, and an elicitation was answered in the middle,
and the calling code is identical to what you would write against
`2025-11-25`. That is deliberate. The protocol changed underneath; the shape
of a tool call did not.

Requests are routed by kind:

| Server asks for | Answered by |
| --- | --- |
| `roots/list` | The session's own `:roots`, no callback needed |
| `sampling/createMessage` | `:on-sampling-requested`, the same callback the handshake revisions use |
| `elicitation/create` | `:on-elicitation-requested`, new, since this was never an inbound request before |

Two things stop it going wrong. A server that keeps asking is cut off after
`:max-round-trips`, which defaults to 8. A request you have no handler for
fails immediately, naming both the method and the callback that was missing,
rather than hanging.

### Discovery

`request-discover` is optional. A stateless client may go straight to
`tools/call`. What it buys you is the server's capabilities, which the
default `:on-initialized` then uses to fetch the prompt, resource and tool
lists.

```clojure
(client/request-discover context)
(client/server-supports-protocol-version? context)  ;; true, false, or nil before discovery
```

It does not switch versions for you. Moving between `2026-07-28` and the
handshake revisions is a change of mode rather than of version, so if the
server does not list yours, that is reported and the decision is yours.

### Logging

There is no `logging/setLevel` any more. Set `:log-level` on the session and
it rides along on every request. A server must not send log notifications for
a request that did not ask for them.

## Subscriptions

`subscriptions/listen` is the only way a server tells a client anything on
its own initiative. It replaced both `resources/subscribe` and the HTTP GET
endpoint.

It matters more than it looks, because list results now carry `ttlMs`. A
client is invited to cache your tool list, so without a change notification
it can legitimately serve a stale one for the whole TTL. Caching without
invalidation is worse than neither.

### Subscribing

```clojure
(client/request-subscribe
 context
 {:tools-list-changed true
  :resource-subscriptions ["file:///project/"]})
```

The promise this returns resolves at the **end** of the subscription, not the
start. The request itself is the stream: it stays open for the subscription's
life, and it is answered only if the server closes it gracefully. Do not
await it before carrying on.

The server replies first with an acknowledgement naming the subset of the
filter it will actually honour, which reaches `:on-subscription-acknowledged`.
Check it. A type the server cannot support is omitted rather than refused, so
a stream that is merely quiet and one that was never going to carry anything
look identical otherwise.

A URI ending in a slash covers everything beneath it, so
`file:///project/` also catches `file:///project/config.json`. One without a
slash matches exactly, which stops `file:///proj` from quietly capturing
`file:///project`.

### Receiving

Every message on a stream carries the subscription's id, which is the
JSON-RPC id of the `subscriptions/listen` request that opened it:

```clojure
(client/create-session
 {:protocol-version "2026-07-28"
  :on-server-resource-changed
  (fn [context]
    (println "changed on stream" (client/subscription-id context)
             (-> context :message :params :uri)))})
```

On stdio every stream shares one channel, so that id is the only way to tell
them apart. A client may hold several at once.

### On the server

Notifications fan out only to the subscriptions that asked for them. Nothing
extra to write:

```clojure
(server/add-tool context my-tool)          ;; reaches toolsListChanged subscribers
(server/notify-resource-updated context {:uri "file:///project/config.json"})
```

One thing to get right. A tool-fn's own context sends to the response of the
call it is serving, which is the wrong destination for a subscription
notification. Whatever context your application uses to raise notifications
has to be one whose `send-message` routes to the subscription streams, which
is what the example transport's `server-context` does.

To end a subscription deliberately, answer it:

```clojure
(server/close-subscription! context subscription-id)
(server/close-all-subscriptions! context)   ;; on shutdown
(server/active-subscriptions context)
```

A stream that just stops looks to a client like a dropped transport, which it
may reconnect on. Closing it properly says the ending was intentional.

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

For a worked HTTP transport, `example/clj-server-streamable-http` ships two.
`streamable-http` serves the handshake revisions with sessions and
resumability. `streamable-http-2026` is the stateless one: POST only, no
session id, no GET endpoint, and one held-open SSE response per
`subscriptions/listen`. Run it with
`bb example:server:streamable-http:2026`.

## Talking to the other era

`2026-07-28` supersedes the handshake revisions rather than extending them.
The specification's own compatibility matrix is blunt: a modern client and a
handshake server fail, and so do a handshake client and a modern server.
There is no negotiation to rescue either, because there is no handshake left
to negotiate in.

A session in this library speaks one era, chosen when you create it. Both are
fully supported, side by side, but not at the same time on one session:

```clojure
(server/create-session {})                             ;; handshake, four revisions
(server/create-session {:protocol-version "2026-07-28"});; stateless, this one
```

Each reports only what it can actually serve. Ask a stateless server what it
supports and it says `["2026-07-28"]`, not every revision the library
implements, and a request declaring an older version is refused rather than
answered with the wrong semantics.

A handshake client that reaches a stateless server gets a diagnostic instead
of a shrug:

```json
{"jsonrpc": "2.0", "id": 1,
 "error": {"code": -32022,
           "message": "This server does not implement the initialize handshake...",
           "data": {"supported": ["2026-07-28"], "requested": "2025-11-25"}}}
```

That matters because such a client has no way to move forward to a newer
revision. This error is probably the only thing it can put in front of a
person, so it names the versions that would have worked.

### Serving both at once

If one endpoint has to serve a mixed fleet, a session can answer both:

```clojure
(server/create-session {:dual-era? true
                        :tools [my-tool]})
```

It picks per request. A protocol version in `_meta` gets stateless
semantics; an `initialize` gets a real handshake. Neither disturbs the other,
so a stateless client keeps working after a handshake client connects.

Such a session reports all five revisions from `server/discover`, since it
serves them all, but the stateless path still only accepts the one revision
it implements. Declaring `2025-11-25` in `_meta` is refused rather than
answered with the wrong semantics.

Notifications reach both audiences from one call. Every subscription gets a
copy tagged with its id, and once a handshake has completed one more goes
down that connection untagged.

On stdio this needs nothing from the transport: one process is one
connection. On HTTP it does, because the untagged copy has to reach the
handshake client's own connection, and the stateless transport in
`example/` drops anything it cannot route to a subscription. See
[ROADMAP.md](../../ROADMAP.md).

## What is not implemented yet

- The `io.modelcontextprotocol/tasks` extension, which is where Tasks went.
- The required Streamable HTTP headers, `Mcp-Method` and `Mcp-Name`, and
  `x-mcp-header` support.

See [ROADMAP.md](../../ROADMAP.md) for where these sit.
