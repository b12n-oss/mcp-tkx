# Driving a containerised server from the REPL

A smoke test you can run in about a minute: stand the `2026-07-28` server up in
a container, then talk to it from a REPL using this library's own client. Not
curl. The client, over a real socket, into a process that has none of your
machine's state.

That combination is worth more than either half. A container catches what your
working tree is hiding from you, and using the client rather than curl means
the encode and decode rules are exercised in both directions rather than
assumed.

Every command and every result on this page was run. The outputs are copied,
not written from memory.

## Stand the server up

```sh
docker compose up -d mcp-2026
docker compose logs mcp-2026
```

```
mcp-2026-1  | 2026-07-28 MCP server on http://0.0.0.0:7927/mcp
```

`0.0.0.0`, not `127.0.0.1`. Inside a container, binding to loopback means the
published port forwards to an interface nothing is listening on, and you get a
connection refused that looks like the server failed to start. The compose
service passes `:bind` for exactly this reason.

## Start a REPL

```sh
bb nrepl
```

Then connect however you like. The examples below are what you evaluate.

## Wire up a transport

The library does no I/O. You provide a `send-message` fn and feed replies back
in, which is the whole of what a transport is here. This one is a POST and a
parse, using the JDK's own HTTP client so there is nothing to install:

```clojure
(require '[mcp-toolkit.client :as client]
         '[mcp-toolkit.json-rpc :as json-rpc]
         '[mcp-toolkit.protocol :as protocol]
         '[camel-snake-kebab.extras :as cske]
         '[cheshire.core :as json])

(import '(java.net URI)
        '(java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                        HttpResponse$BodyHandlers))

(def http (HttpClient/newHttpClient))

(defn post-json [body]
  (let [req (-> (HttpRequest/newBuilder (URI/create "http://127.0.0.1:7927/mcp"))
                (.header "content-type" "application/json")
                (.POST (HttpRequest$BodyPublishers/ofString body))
                (.build))]
    (.body (.send http req (HttpResponse$BodyHandlers/ofString)))))
```

Now the session and the context. Note `protocol/encode-key` and
`protocol/decode-key` rather than `camel-snake-kebab` directly: that is the
difference between `_meta` surviving the trip and quietly losing its
underscore. [Kebab-case key transformation](kebab-case-transformation.md) is
the long version.

```clojure
(def session (atom (client/create-session {:protocol-version "2026-07-28"
                                           :on-initialized nil})))

;; ctx refers to itself, because a reply has to go back into the toolkit
;; through the same context it came out of.
(declare ctx)

(def ctx
  {:session session
   :send-message
   (fn [message]
     (let [wire  (cske/transform-keys protocol/encode-key message)
           reply (post-json (json/generate-string wire))]
       (when (seq reply)
         (->> (json/parse-string reply)
              (cske/transform-keys protocol/decode-key)
              (json-rpc/handle-message ctx)))
       nil))})
```

`cske/transform-keys` takes the function first. Threading it with `->` passes
the arguments the other way round and hands you the function itself, which
serialises to `undefined` with no error at all. Use `->>`, or pass both
arguments as above.

## Ask the server what it is

```clojure
@(client/request-discover ctx)

(select-keys @session [:server-supported-protocol-versions
                       :server-capabilities
                       :server-instructions])
```

```clojure
{:server-supported-protocol-versions ["2026-07-28"]
 :server-capabilities {:completions {}
                       :logging {}
                       :prompts {:list-changed true}
                       :resources {:list-changed true}
                       :tools {:list-changed true}}
 :server-instructions "An example 2026-07-28 server."}
```

`server/discover` replaces the handshake on this revision. It is optional: a
stateless client can go straight to `tools/call`. Running it first means the
client knows what the server actually has, and the list functions below respect
that answer.

## List and call

```clojure
@(client/request-tool-list ctx)

{:tools (keys (:server-tool-by-name @session))
 :supported? (client/server-supports-protocol-version? ctx)}
```

```clojure
{:tools ("parentify" "touch")
 :supported? true}
```

```clojure
@(client/request-tool-invocation ctx "parentify" {:text "from the repl"})
```

```clojure
{:content [{:type "text" :text "(from the repl)"}]
 :is-error false
 :result-type "complete"
 :_meta {"io.modelcontextprotocol/serverInfo" {:name "mcp-tkx-example"
                                              :version "1.0.0"}}}
```

## What that last result proves

Look at the `:_meta` map rather than the content. Two things had to survive a
round trip through JSON, a socket and a container:

```clojure
(let [r @(client/request-tool-invocation ctx "parentify" {:text "x"})
      k (first (keys (:_meta r)))]
  {:meta-key        k
   :key-type        (type k)
   :underscore-kept (contains? r :_meta)
   :namespace-kept  (clojure.string/includes? (str k) "/")})
```

```clojure
{:meta-key        "io.modelcontextprotocol/serverInfo"
 :key-type        java.lang.String
 :underscore-kept true
 :namespace-kept  true}
```

`:_meta` kept its underscore instead of decoding to `:meta`, and the namespaced
key stayed a string instead of becoming a keyword that would lose its namespace
on the way back out. Both are silent failures when they go wrong: nothing
throws, a field simply stops matching. Wiring the transport with raw
`camel-snake-kebab` produces exactly that, and this is the check that catches
it.

## Tear down

```sh
docker compose down
```

## Why bother, when curl works

Curl tells you the server answered. It does not tell you the client can read
the answer, and the client is half the library.

A container adds the other half of the story. Your working tree accumulates
caches, generated config and untracked files, and any of them can make a broken
build look healthy. One did: `.clj-kondo` was ignored wholesale, so a fresh
clone had none of the linter configs libraries export and `bb check` failed on
code that was fine. Every existing checkout had a warm cache and could not see
it. `bb docker:verify` runs the whole gate the same way, from a copy that obeys
`.dockerignore`.

## See also

- [The 2026-07-28 stateless revision](2026-07-28-stateless.md), for what
  `server/discover` replaced and how multi round-trip requests work.
- [Kebab-case key transformation](kebab-case-transformation.md), for why the
  transport uses `protocol/encode-key` rather than `camel-snake-kebab`.
- [Streamable HTTP](streamable-http.md), for the server side of this transport.
- [REPL workflow](repl-workflow.md), for editing a server's tools while a
  client stays connected.
