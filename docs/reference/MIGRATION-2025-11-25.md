# MCP Toolkit Migration Guide: 2025-06-18 → 2025-11-25

**Version:** 2025-11-25  
**Updated:** 2025-01-11  
**Spec:** [MCP 2025-11-25](https://modelcontextprotocol.io/specification/2025-11-25)

## Overview

MCP Toolkit v2025-11-25 adds support for the MCP 2025-11-25 specification while maintaining backward compatibility with older protocol versions. Not every new capability is Full; see the [README's capability table](../../README.md#protocol-and-feature-support) for exactly which ones are Partial.

### Key Features

- **Elicitation** - Request user input via forms or URL redirects
- **Tasks (Experimental)** - Track long-running operations
- **Sampling with Tools** - LLMs can use tools during sampling
- **Icons** - Visual icons for prompts, resources, tools, templates
- **Server Description** - Human-readable server descriptions
- **JSON Schema 2020-12** - Standard dialect for schemas

### Key Design Decision: Kebab-case Keys

All internal Clojure code uses **kebab-case** keys (`:max-tokens`, `:input-schema`). Conversion to/from camelCase happens automatically at the transport layer.

```clojure fragment
;; Your code uses kebab-case
{:max-tokens 1000
 :input-schema {:type "object"}
 :tool-choice {:type "auto"}}

;; Wire format uses camelCase (automatic)
{"maxTokens": 1000, "inputSchema": {...}, "toolChoice": {...}}
```

---

## Quick Start

### Update Dependencies

```clojure
;; deps.edn
{:deps {io.github.b12n-oss/mcp-tkx
        {:git/url "git@github.com:b12n-oss/mcp-tkx.git"
         :git/sha "666ed1f5af0f27bb75017ec97d429b445ada814f"}}}
```

### Protocol Version

The default protocol version is now `2025-11-25`. For backward compatibility:

```clojure
;; Server automatically negotiates with client
;; No changes needed - older clients will negotiate down
```

---

## New Features

### Icons

Add icons to prompts, resources, tools, and templates:

```clojure
(def my-tool
  {:name "read_file"
   :title "File Reader"
   :icon "https://example.com/icons/file.svg"  ;; or data:image/svg+xml;base64,...
   :description "Reads a file from disk"
   :input-schema {:type "object"
                  :properties {:path {:type "string"}}
                  :required [:path]}
   :tool-fn read-file-fn})
```

Icon must be either:
- HTTPS URL: `https://...`
- Data URI: `data:image/svg+xml;base64,...` or `data:image/png;base64,...`

### Server Description

Add a description to your server:

```clojure
(server/create-session
  {:server-info {:name "my-server"
                 :version "1.0.0"
                 :description "A helpful MCP server for data analysis"}})
```

### Elicitation

Request user input via forms or URLs:

```clojure
(require '[mcp-toolkit.server :as server]
         '[mcp-toolkit.schema :as schema])

;; Check if client supports elicitation
(when (server/client-supports-elicitation? context)

  ;; Form-based elicitation. :mode defaults to "form".
  (server/request-elicitation context
    {:message "Please provide details"
     :requested-schema {:type "object"
                        :properties {:name {:type "string"}
                                     :email {:type "string"}}
                        :required [:name :email]}})

  ;; URL-based elicitation (for OAuth flows)
  (when (server/client-supports-url-elicitation? context)
    (server/request-elicitation context
      (schema/url-elicitation
        {:elicitation-id "550e8400-e29b-41d4-a716-446655440000"
         :url            "https://auth.example.com/oauth/authorize"
         :message        "Please sign in with your account"}))))
```

URL mode requires `:mode "url"`, `:elicitation-id` and `:url`, and
`request-elicitation` defaults `:mode` to `"form"` when it is absent. So
build the request with `schema/url-elicitation`, which fills all three in
for you, rather than assembling the map by hand and having it silently
take the form-mode path. Use `schema/url-elicitation!` if you want it to
throw on an invalid request instead of returning one.

### Sampling with Tools

LLMs can now use tools during sampling:

```clojure
;; Check capability
(when (server/client-supports-sampling-tools? context)
  (server/request-sampling context
    {:messages [{:role "user"
                 :content {:type "text"
                           :text "Search for recent AI news"}}]
     :max-tokens 1000
     :tools [{:name "web_search"
              :description "Search the web"
              :input-schema {:type "object"
                             :properties {:query {:type "string"}}
                             :required [:query]}}]
     :tool-choice {:type "auto"}}))
```

### Tasks (Experimental)

Track long-running operations:

```clojure
(require '[mcp-toolkit.server :as server])
(require '[mcp-toolkit.schema :as schema])

;; Check capability
(when (server/client-supports-tasks? context)
  ;; Request task status
  (server/request-task-get context {:task-id "task-123"})
  
  ;; Get task result (blocks until complete)
  (server/request-task-result context {:task-id "task-123"})
  
  ;; List tasks
  (server/request-tasks-list context {})
  
  ;; Cancel a task
  (server/request-task-cancel context {:task-id "task-123"}))

;; Check terminal status
(schema/terminal-status? "completed")  ;; => true
(schema/terminal-status? "working")    ;; => false
```

### JSON Schema Dialect

Use the standard JSON Schema 2020-12 dialect:

```clojure
(require '[mcp-toolkit.schema :as schema])

;; Constant
schema/JSON_SCHEMA_DIALECT
;; => "https://json-schema.org/draft/2020-12/schema"

;; Helper to add $schema
(schema/with-schema-dialect
  {:type "object"
   :properties {:name {:type "string"}}})
;; => {:$schema "https://json-schema.org/draft/2020-12/schema"
;;     :type "object"
;;     :properties {:name {:type "string"}}}
```

---

## Schema Validation

New Malli-based schema validation in `mcp-toolkit.schema`:

```clojure
(require '[mcp-toolkit.schema :as schema])

;; Validate data
(schema/valid? schema/Icon "https://example.com/icon.png")  ;; => true
(schema/valid? schema/Icon "http://insecure.com/icon.png")  ;; => false

;; Get validation errors
(schema/explain schema/Icon "http://bad.com/icon.png")
;; => ["Icon must be a data:image/ URI or https:// URL"]

;; Result map, {:valid? true} or {:valid? false :errors [...]}
(schema/validate schema/Icon "https://example.com/icon.png")
;; => {:valid? true}
(schema/validate schema/Icon "bad")
;; => {:valid? false, :errors ["Icon must be a data:image/ URI or https:// URL"]}
```

There is no throwing `validate!` function. If you want validate-then-throw
behaviour, use one of the `!`-suffixed constructors instead (`enum-schema!`,
`tool-result-message!`, `url-elicitation!`, `form-elicitation!`), which build
a value and throw `ExceptionInfo` if it doesn't pass its schema. See
[Schema validation](../guide/schema-validation.md) for the full picture.

### Available Schemas

- `Icon` - Icon field validation
- `EnumSchema` - The four enum shapes: single or multiple selection, titled or not
- `SamplingRequest` - Sampling request with tools support
- `UrlElicitationRequest` - URL-based elicitation
- `FormElicitationRequest` - Form-based elicitation
- `Task` - Task object
- `TaskStatus` - Task status enum
- And many more...

---

## Capability Detection

Check client capabilities before using features:

```clojure
(require '[mcp-toolkit.server :as server])

;; Elicitation
(server/client-supports-elicitation? context)
(server/client-supports-url-elicitation? context)
(server/client-supports-form-elicitation? context)

;; Sampling with tools
(server/client-supports-sampling-tools? context)

;; Tasks
(server/client-supports-tasks? context)
(server/client-supports-task-augmented-sampling? context)
(server/client-supports-task-augmented-elicitation? context)
(server/client-supports-tasks-list? context)
(server/client-supports-tasks-cancel? context)
```

---

## Key Naming Convention

| Wire Format (camelCase) | Internal (kebab-case) |
|-------------------------|----------------------|
| `maxTokens` | `:max-tokens` |
| `inputSchema` | `:input-schema` |
| `outputSchema` | `:output-schema` |
| `toolChoice` | `:tool-choice` |
| `hasMore` | `:has-more` |
| `isError` | `:is-error` |
| `mimeType` | `:mime-type` |
| `listChanged` | `:list-changed` |
| `oneOf` | `:one-of` |
| `anyOf` | `:any-of` |
| `taskId` | `:task-id` |
| `pollInterval` | `:poll-interval` |
| `statusMessage` | `:status-message` |

---

## Backward Compatibility

The library automatically negotiates protocol versions with clients:

- Clients requesting `2025-11-25` get full feature support
- Clients requesting `2025-06-18` work with all 2025-06-18 features
- Clients requesting older versions continue to work

---

## References

- [MCP Specification 2025-11-25](https://modelcontextprotocol.io/specification/2025-11-25)
- [MCP Changelog](https://modelcontextprotocol.io/specification/2025-11-25/changelog)
- [Implementation Checklist](archive/MIGRATION-2025-11-25-CHECKLIST.md)
