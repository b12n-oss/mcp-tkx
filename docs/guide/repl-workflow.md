# REPL workflow

The toolkit is built around a session atom plus a context hashmap. Both are values you own; both are mutable from a REPL. This means: **you can iterate on prompts, resources, and tools while the MCP client is connected and watching.** No restart needed; the toolkit emits the right notifications so the client refreshes its view.

## Setup: an embedded nREPL inside your STDIO server

The example server starts an nREPL alongside the STDIO loop:

```clojure
;; example/cljc-server-stdio/src/example/my_server.cljc
(defn main [{:keys [bind port]}]
  (let [server (nrepl/start-server {:bind bind
                                    :port port})]
    (try
      (listen-messages context *in*)        ; STDIO loop, returns when *in* closes
      (finally
        (nrepl/stop-server server)))))      ; nREPL stops when STDIO does
```

The defaults in `deps.edn` for `:mcp-server`:

```clojure
:mcp-server {:exec-fn   example.my-server/main
             :exec-args {:bind "127.0.0.1"
                         :port 7925}}
```

So when Claude Desktop launches the server, an nREPL is listening on `127.0.0.1:7925`. Connect from your editor / `clj-nrepl-eval` and you're talking to the same JVM that Claude is talking to.

The same pattern works for the cljs / shadow-cljs path — `npx shadow-cljs node-repl` boots a connected REPL into the running Node process.

## Connecting

```sh
# Discover ports — picks up any nREPL .nrepl-port file or env-var
clj-nrepl-eval --discover-ports

# Direct connect
clj-nrepl-eval -p 7925 "(+ 1 2)"
;; => 3
```

Or from your editor (Cursive, CIDER, Calva, Cider for Emacs, etc.) — connect to `127.0.0.1:7925` as a plain nREPL.

## Inspecting session state

The session is `(deref session)` — a regular Clojure map you can `pprint`:

```clojure
(require '[example.my-server :refer [session context]])

@session
;; → giant map; pick fields:

(:protocol-version @session)
;; → "2025-11-25"

(keys (:tool-by-name @session))
;; → ("parentify")

(:client-info @session)
;; → {:name "Claude" :version "..."}

(:client-capabilities @session)
;; → {:roots {} :sampling {} :elicitation {} ...}

(:client-subscribed-resource-uris @session)
;; → #{"file:///doc/hello.md"}
```

## Adding / removing tools live

```clojure
(require '[mcp-toolkit.server :as server])

(def my-new-tool
  {:name "uppercase"
   :description "Uppercase a string"
   :input-schema {:type "object"
                  :properties {:text {:type "string"}}
                  :required [:text]}
   :tool-fn (fn [_ctx {:keys [text]}]
              {:content [{:type "text" :text (.toUpperCase text)}]
               :is-error false})})

(server/add-tool context my-new-tool)
;; Mutates :tool-by-name and emits notifications/tools/list_changed
;; Claude Desktop refreshes its tool list within ~1s
```

`remove-tool` is the inverse:

```clojure
(server/remove-tool context my-new-tool)
;; Removes from :tool-by-name and emits notifications/tools/list_changed
```

The same pattern works for prompts (`add-prompt` / `remove-prompt`) and resources (`add-resource` / `remove-resource`).

If you re-`add` a tool that already exists, the new definition replaces the old one (same `:name` key indexes by name). The notification fires, and the client's next `tools/call` hits the new tool-fn.

## Updating a tool's implementation

The tool's `:tool-fn` is a Clojure value; if you re-evaluate it (e.g. in your editor), then re-`add` the tool, the new fn replaces the old one immediately. No notification storm — `add-tool` only fires `tools/list_changed`, not "tool updated":

```clojure
;; In your editor — edit + re-eval
(def parentify-tool
  {:name "parentify"
   :description "Now wraps in DOUBLE parens"
   :input-schema {:type "object"
                  :properties {:text {:type "string"}}
                  :required [:text]}
   :tool-fn (fn [_ctx {:keys [text]}]
              {:content [{:type "text" :text (str "((" text "))")}]
               :is-error false})})

;; In the REPL
(server/add-tool context parentify-tool)
```

The next call from Claude lands on the new fn. The tool-list metadata (description) is also updated since the client re-fetches on `tools/list_changed`.

## Resource updates

Static resource content can be mutated in the session and announced:

```clojure
;; Update the text in place
(swap! session update-in [:resource-by-uri "file:///doc/hello.md" :text]
       str " (live update at " (java.util.Date.) ")")

;; Tell subscribers
(server/notify-resource-updated context {:uri "file:///doc/hello.md"})
```

`notify-resource-updated` only fires if the client is actually subscribed to that URI (`:client-subscribed-resource-uris` set in the session). MCP clients subscribe via `resources/subscribe`; Claude Desktop subscribes to resources the user has open in its UI.

For dynamic content, subscribe + emit on every change is the wrong pattern — use a `:read-fn` ([Dynamic resources](dynamic-resources.md)) so each `resources/read` recomputes.

## REPL-only tools (development helpers)

A common pattern: ship a `dev-tools` registry that's only added when running locally:

```clojure
(comment
  ;; Register dev tools — only run from REPL, not on production launch

  (def *call-log (atom []))

  (def log-inspector-tool
    {:name "_log_inspector"   ; underscore-prefix convention for dev tools
     :description "Show recent tool call log entries (development tool)"
     :input-schema {:type "object" :properties {} :required []}
     :tool-fn (fn [_ctx _args]
                {:content [{:type "text" :text (pr-str @*call-log)}]
                 :is-error false})})

  (server/add-tool context log-inspector-tool))
```

Or guard with an env var so production launches skip them:

```clojure
(def session
  (atom
   (server/create-session
    (cond-> {:tools [parentify-tool]}
      (System/getenv "MCP_DEV") (update :tools conj log-inspector-tool)))))
```

## Inspecting in-flight messages

The session can carry an arbitrary key — including a message log if you want one:

```clojure
;; Add to session at create-time, or via swap!
(swap! session assoc :message-log [])

;; Wrap your context's :send-message to log
(def context
  {:session session
   :send-message (fn [m]
                   (swap! session update :message-log conj {:dir :out :msg m})
                   (real-send-message m))})

;; Inspect later
(take 5 (:message-log @session))

;; Clear
(swap! session update :message-log empty)
```

## Manual log emission

While developing, send log notifications to the connected client:

```clojure
(server/notify-log context "info" "my-server" "Tool registry updated")
(server/notify-log context "emergency" "datacenter" {:error "HCF"})
```

The level threshold is `(:logging-level @session)` — set at session-creation time (default `"debug"`). To change at runtime:

```clojure
(swap! session assoc :logging-level "info")
;; The client can also change it via logging/setLevel
```

## Tailing Claude Desktop logs

When developing against Claude Desktop:

```sh
# macOS
tail -n 200 -F ~/Library/Logs/Claude/mcp-server-toolkit.log

# Replace "toolkit" with the key you used in claude_desktop_config.json
```

Useful patterns:

- `grep '\[ERROR\]' file.log` — only errors.
- `grep -i 'tool' file.log` — tool-related events.
- The log captures stderr — anything you `println` to `*err*` shows up here.

## The `(comment ...)` block at the bottom of `my_server.cljc`

The example server has a rich-comment block with copy-pasteable REPL forms. Worth quoting in full as the canonical REPL cheat-sheet:

```clojure
(comment
  ;; tail -n 20 -F ~/Library/Logs/Claude/mcp-server-toolkit.log

  @session
  (:message-log @session)
  (swap! session update :message-log empty)

  (server/add-prompt context talk-like-pirate-prompt)
  (server/remove-prompt context talk-like-pirate-prompt)

  (server/add-resource context hello-world-resource)
  (server/remove-resource context hello-world-resource)

  ;; Simulates changing a resource.
  (swap! session update-in [:resource-by-uri "file:///doc/hello.md" :text]
         str " xxx")
  (server/notify-resource-updated context {:uri "file:///doc/hello.md"})

  (server/set-resource-templates context my-resource-templates)
  (server/set-resource-uri-complete-fn context my-resource-uri-complete-fn)

  (server/add-tool context parentify-tool)
  (server/remove-tool context parentify-tool)

  (server/notify-log context "info" "mcp-toolkit" {:message "Made in Finland"})
  (server/notify-log context "emergency" "datacenter" {:error "HCF"})

  (some-> (server/request-sampling
           context
           {:messages [{:role "user"
                        :content {:type "text"
                                  :text "What is the capital of France?"}}]
            :model-preferences {:hints [{:name "claude-3-sonnet"}]
                                :intelligence-priority 0.8
                                :speed-priority 0.5}
            :system-prompt "You are a helpful assistant"
            :max-tokens 100})
          deref)

  *e)
```

Most of these are useful in your own server too — copy the pattern. The `*e` at the end is the convention for "show me the most recent exception" if anything threw.

## See also

- [Architecture](architecture.md) §"REPL-time mutations" — the toolkit fns that are designed for REPL use.
- [Getting started](getting-started.md) — the canonical wiring.
- [Dynamic resources](dynamic-resources.md) — when REPL-mutating a resource, you might prefer a `:read-fn` instead.
- [`docs/reference/repl-story.md`](../reference/repl-story.md) — the upstream Metosin take on the REPL workflow.
- [`example/cljc-server-stdio/src/example/my_server.cljc`](../../example/cljc-server-stdio/src/example/my_server.cljc) — the rich-comment block at the bottom.
