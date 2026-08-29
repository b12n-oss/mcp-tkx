# Dynamic resources via `:read-fn`

MCP resources are normally **static** — you register a resource with `:text` (or `:blob`) at session-creation time, and the toolkit serves that content verbatim on every `resources/read`. This fork adds a second mode: provide a `:read-fn` instead, and the toolkit calls it on each read to compute content on demand.

## Why dynamic content

Three real-world drivers:

1. **System status / metrics** — uptime, queue depth, last-error, "what's the server doing right now?" data that is meaningless to snapshot at session-creation time.
2. **External-system reflection** — current row count of a database table, list of active workspaces, current git branch / commit. The data lives in another system; you want to expose a view, not a copy.
3. **Authenticated / per-user data** — content that depends on `:context` (which carries the request-time state), rather than the static data baked into the registration.

For everything else, static `:text` / `:blob` is simpler — pre-compute the content at session-creation time and let the toolkit serve it.

## The contract

A dynamic resource looks like a static one with `:read-fn` instead of (or alongside) `:text` / `:blob`:

```clojure
(def status-resource
  {:uri "config://status"
   :name "Server Status"
   :description "Current server status (dynamic)"
   :mime-type "application/json"
   :read-fn (fn [context uri]
              {:text (json/write-str {:status "running"
                                      :uptime (get-uptime)})})})
```

The `:read-fn` receives:

- `context` — the **full handler context**, including `:session`, `:message`, plus the standard `:send-message` / `:close-connection`. You can read or mutate the session if needed.
- `uri` — the URI being read (the same URI the resource was registered with — useful when one `:read-fn` handles multiple URIs by closing over a registry).

It returns one of:

| Return shape | What the toolkit does |
|---|---|
| `{:text "..."}` | Wraps as `{:contents [<resource-meta-merged-with-text>]}` and returns to client |
| `{:blob "..."}` | Same, but with `:blob` (base64-encoded binary) |
| `{:contents [{:uri ... :mime-type ... :text ...} ...]}` | Returned as-is — useful when one read produces multiple content parts |
| `{:error {:code "..." :message "..."}}` | Returned as the result without wrapping. Client sees the error envelope. |
| **A Promesa promise of any of the above** | Awaited; result handled per the cases above |

The handler implementation in [`src/mcp_toolkit/impl/server/handler.cljc`](../../src/mcp_toolkit/impl/server/handler.cljc) (`resource-read-handler`) does exactly this:

```clojure
(if-some [read-fn (:read-fn resource)]
  ;; Dynamic content via :read-fn
  (-> (read-fn context uri)
      (p/then (fn [result]
                (if (:error result)
                  result
                  (if (:contents result)
                    result
                    {:contents [(merge (select-keys resource [:uri :description :mime-type])
                                       result)]}))))
      (p/catch (fn [exception]
                 {:error {:code "read-error"
                          :message (ex-message exception)}})))
  ;; Static content from :text or :blob
  {:contents [(select-keys resource [:uri :description :mime-type :text :blob])]})
```

The auto-merge of `[:uri :description :mime-type]` from the registration onto the `:read-fn` return is convenient — you don't repeat metadata that's already on the resource.

## Worked example: system status as JSON

```clojure
(require '[clojure.data.json :as json])

(def *start-time (System/currentTimeMillis))

(def status-resource
  {:uri "status://server"
   :name "Server status"
   :description "Live server health and metrics"
   :mime-type "application/json"
   :read-fn (fn [_context _uri]
              (let [uptime-ms (- (System/currentTimeMillis) *start-time)]
                {:text (json/write-str {:status "running"
                                        :uptime-ms uptime-ms
                                        :uptime-seconds (quot uptime-ms 1000)
                                        :memory-mb (-> (Runtime/getRuntime)
                                                       (.totalMemory)
                                                       (quot (* 1024 1024)))})}))})

;; register at session creation
(server/create-session
 {:resources [status-resource ...]
  ...})
```

Each `resources/read` call computes fresh values. The MIME type stays at `application/json` so clients render it correctly.

## Worked example: file content with error handling

```clojure
(def file-resource
  {:uri "file:///etc/hostname"
   :name "hostname"
   :mime-type "text/plain"
   :read-fn (fn [_context _uri]
              (try
                {:text (slurp "/etc/hostname")}
                (catch Exception e
                  {:error {:code "read-failed"
                           :message (.getMessage e)}})))})
```

The handler swallows the exception locally and returns an `{:error}` map. If you let the exception escape, the toolkit catches it and returns `{:error {:code "read-error" :message "<the exception's message>"}}` — same envelope shape, different code string.

## Worked example: async fetch via Promesa

```clojure
(require '[promesa.core :as p])

(def remote-config-resource
  {:uri "config://remote"
   :name "Remote config"
   :mime-type "application/json"
   :read-fn (fn [_context _uri]
              ;; HTTP fetch returns a promise — toolkit awaits it
              (-> (http/get "https://config.example.com/api/current"
                            {:as :json})
                  (p/then (fn [response]
                            {:text (json/write-str (:body response))}))))})
```

The toolkit `(p/then)`-chains your return, so any Promesa promise (or anything that satisfies `p/promise?`) is awaited. If the chain rejects, the catch in `resource-read-handler` converts it to `{:error ...}` — your fn doesn't have to handle async errors itself.

## Worked example: multiple URIs per `:read-fn`

A single `:read-fn` can serve multiple URIs by closing over a router or a registry. Useful when you have N resources whose content all comes from one source (DB rows, files in a directory):

```clojure
(def *items-store (atom {"item-1" "Hello"
                         "item-2" "World"
                         "item-3" "Foo"}))

(defn item-read-fn [_context uri]
  (let [id (last (str/split uri #"/"))]
    (if-let [content (get @*items-store id)]
      {:text content}
      {:error {:code "not-found"
               :message (str "No item with id " id)}})))

;; Register one resource per URI, all sharing the same :read-fn
(def item-resources
  (for [id (keys @*items-store)]
    {:uri (str "items://" id)
     :name (str "Item " id)
     :mime-type "text/plain"
     :read-fn item-read-fn}))
```

If you anticipate adding / removing items at runtime, combine this with [the REPL workflow](repl-workflow.md): `(server/add-resource context new-item-resource)` after a write to the store and the client gets a `resources/list_changed` notification.

## Worked example: returning `:contents` directly

When one read produces multiple content parts (rare but possible — e.g. a "manifest" resource that bundles a JSON header and a binary body), bypass the auto-merge and provide `:contents` yourself:

```clojure
(def bundle-resource
  {:uri "bundle://config-with-icon"
   :name "Config bundle"
   :read-fn (fn [_context _uri]
              {:contents [{:uri "bundle://config-with-icon#config"
                           :mime-type "application/json"
                           :text "{...}"}
                          {:uri "bundle://config-with-icon#icon"
                           :mime-type "image/png"
                           :blob "<base64>"}]})})
```

Each `:contents` entry is returned verbatim — no merge, no metadata inheritance. Use this when you need precise control over `:uri` / `:mime-type` per part.

## When NOT to use `:read-fn`

- **Content that doesn't change after session start** — register `:text` / `:blob` directly. The toolkit's static path skips the promise machinery.
- **Content that changes rarely and on a known schedule** — register static `:text`, then use `(server/notify-resource-updated context resource)` from a REPL or a scheduled task to nudge subscribed clients to re-read. The next `resources/read` returns the (still-static) content; **but** before that you'd need to update the resource registration via `(swap! session ...)` or `(server/add-resource context updated-resource)` (which replaces by URI).

  This pattern works for "the README in this directory was just edited" — the change is event-driven, not on every read.

- **Content where ANY read is expensive** — `:read-fn` runs synchronously (or async-via-promise) on every request. If your read is a 5-second remote call, every client that opens the resource pays the cost. Cache inside the `:read-fn` (e.g. with a memoize-with-ttl) to amortize.

## See also

- The handler that drives this: [`src/mcp_toolkit/impl/server/handler.cljc`](../../src/mcp_toolkit/impl/server/handler.cljc) `resource-read-handler`.
- The README's "What this fork adds" table, "Dynamic resources" row, in the project root for the spec-level summary.
- [REPL workflow](repl-workflow.md) — for the related pattern of mutating resource registrations from a REPL while the server is live.
- [Extraction recipes](extraction-recipes.md) Recipe 3 — lifting this dynamic-resource pattern into another resource-based service.
