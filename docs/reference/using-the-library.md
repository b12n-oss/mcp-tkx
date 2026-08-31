## How to use the library

The process is best described in the example projects which are well commented:

- `example/cljc-server-stdio` - CLJC server using STDIO transport
- `example/cljc-client-stdio` - CLJC client using STDIO transport  
- `example/clj-server-sse` - CLJ server using HTTP/SSE transport
- `example/common-mcp-content` - Shared prompts, resources, and tools

### Basic Steps

1. Create a session with all your resources and customization in it, and put it into an atom.
2. Create a context hashmap with all your I/O related hooks in it.
3. When you receive a message, transform it into a Clojure data structure and call `(json-rpc/handle-message context message)`.
4. If you are implementing a client, send the first message of the initial handshake by calling `(client/send-first-handshake-message context)`.
5. Watch it work and enjoy.

### Example Server Setup

```clojure
(require '[mcp-toolkit.server :as server])

(def session
  (atom
   (server/create-session 
     {:server-info {:name "my-server"
                    :version "1.0.0"
                    :description "My MCP server"}
      :prompts [my-prompt]
      :resources [my-resource]
      :tools [my-tool]})))

(def context
  {:session session
   :send-message (fn [message] 
                   ;; Send message over your transport
                   )})
```

### Key Transformation

> **This section is preserved from upstream and is not the wiring this fork
> uses.** Raw `camel-snake-kebab` strips the underscore from `_meta` and mangles
> namespaced keys, both of which this fork's `2025-11-25` and `2026-07-28`
> support depends on. Use `mcp-toolkit.protocol/encode-key` and
> `mcp-toolkit.protocol/decode-key` instead, which are that library plus those
> two exceptions. See
> [Kebab-case key transformation](../guide/kebab-case-transformation.md).

The library uses kebab-case internally. At the transport layer, use `camel-snake-kebab` to convert:

```clojure
(require '[camel-snake-kebab.core :as csk])
(require '[jsonista.core :as j])

;; For encoding outgoing messages (kebab-case → camelCase)
(def json-mapper 
  (j/object-mapper {:encode-key-fn csk/->camelCaseString}))

;; For decoding incoming messages (camelCase → kebab-case)
(def json-mapper 
  (j/object-mapper {:decode-key-fn csk/->kebab-case-keyword}))
```
