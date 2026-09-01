# Schema validation

`mcp-toolkit.schema` is a Malli-based schema namespace covering the MCP protocol types you're likely to validate before shipping a server. It ships ~25+ schemas plus three categories of helpers: validation predicates, schema constructors (plain), and `!`-suffixed throwing constructors.

## Why Malli (not spec, not Schema)

The fork picks Malli for three reasons that are visible in the codebase:

1. **Inline `:fn` predicates with `:error/message`**: the `Icon` schema is `[:and :string [:fn {:error/message "..."} (fn [s] ...)]]`. Custom validation lives next to the schema, with a human-readable error.
2. **`malli.error/humanize`**: `(explain schema value)` returns a tree of strings that maps to the input shape. No extra wiring.
3. **No instrumentation overhead at runtime**: schemas are values you call `validate` / `valid?` / `explain` on at the boundary; no global registry; no macro-time gen.

`mcp-toolkit.schema` is independent of `mcp-toolkit.server` / `mcp-toolkit.client`: you can require it standalone to validate request shapes outside the toolkit.

## The three validation entry points

```clojure
(require '[mcp-toolkit.schema :as schema])

;; Predicate, true / false
(schema/valid? schema/Icon "https://example.com/icon.png")
;; => true

(schema/valid? schema/Icon "ftp://bad.com/icon.png")
;; => false

;; Humanized errors, nil if valid, otherwise a tree of strings
(schema/explain schema/Icon "ftp://bad.com/icon.png")
;; => ["Icon must be a data:image/ URI or https:// URL"]

;; Result map, {:valid? true} or {:valid? false :errors [...]}
(schema/validate schema/Icon "https://example.com/icon.png")
;; => {:valid? true}

(schema/validate schema/Icon "ftp://bad.com/icon.png")
;; => {:valid? false :errors ["Icon must be a data:image/ URI or https:// URL"]}
```

`validate!` (no map; throws on invalid) is provided indirectly via the `!`-suffixed constructors below.

## Schema catalog

The full list is in [`src/mcp_toolkit/schema.cljc`](https://github.com/b12n-oss/mcp-tkx/blob/main/src/mcp_toolkit/schema.cljc). The categories:

### Top-level constants

```clojure
schema/JSON_SCHEMA_DIALECT  ; "https://json-schema.org/draft/2020-12/schema"
(schema/with-schema-dialect {:type "object" ...})
;; => {:$schema "https://json-schema.org/draft/2020-12/schema" :type "object" ...}
```

Use this on `:input-schema` / `:output-schema` to mark them explicitly as JSON Schema 2020-12 (added in the 2025-11-25 spec).

### Icons

```clojure
schema/Icon
;; [:and :string
;;  [:fn {:error/message "Icon must be a data:image/ URI or https:// URL"}
;;   (fn [s] (or (str/starts-with? s "data:image/") (str/starts-with? s "https://")))]]

(schema/valid? schema/Icon "https://example.com/file.svg")            ; true
(schema/valid? schema/Icon "data:image/svg+xml;base64,PHN2Zy4uPg==")  ; true
(schema/valid? schema/Icon "http://insecure.com/file.svg")            ; false
(schema/valid? schema/Icon "/local/path/file.svg")                    ; false
```

http:// (insecure) and bare paths are rejected. data: URIs other than image/ (e.g. application/json) are rejected.

### Enum schemas (2025-11-25)

The specification defines **four** enum shapes rather than one, and they differ by more than a flag. Single selection is a string, multiple selection is an array, and titles ride as objects rather than as a parallel vector of names:

| Selection | Titles | Shape |
|---|---|---|
| single | no | `{:type "string" :enum [...]}` |
| single | yes | `{:type "string" :one-of [{:const v :title t} ...]}` |
| multiple | no | `{:type "array" :items {:type "string" :enum [...]}}` |
| multiple | yes | `{:type "array" :items {:any-of [{:const v :title t} ...]}}` |

Note the asymmetry between the two titled forms. It is the specification's, not ours: the single form puts `one-of` at the top level beside `:type "string"`, while the multiple form nests `any-of` inside `:items` with no `:type` there at all.

`enum-schema` picks the shape from `:titles` and `:multi-select`, so you describe what you want rather than assembling it:

```clojure
(schema/enum-schema {:values ["low" "medium" "high"]
                     :titles ["Low priority" "Medium priority" "High priority"]
                     :default "medium"})
;; => {:type "string"
;;     :one-of [{:const "low" :title "Low priority"}
;;              {:const "medium" :title "Medium priority"}
;;              {:const "high" :title "High priority"}]
;;     :default "medium"}

(schema/enum-schema {:values ["email" "sms"] :multi-select true :min-items 1})
;; => {:type "array"
;;     :items {:type "string" :enum ["email" "sms"]}
;;     :min-items 1}

;; Throwing version, validates and throws on shape mismatch
(schema/enum-schema! {:values ["a" "b"] :titles ["A"]})
;; => throws ex-info: "Invalid enum schema" with :errors
```

Mismatched `:titles` are checked before the shape is built, not after. Titles are zipped onto values, and a zip over mismatched lengths truncates to the shorter one, so the result would be structurally valid and quietly missing options.

Earlier versions of this library emitted `:enum-titles` and `:multi-select` fields. Neither exists in any MCP revision, so a conforming client ignored both and showed raw values with single selection.

Use `enum-schema` for the plain constructor; use `enum-schema!` when you want a panic if the construction is malformed (e.g. titles vector wrong length).

### Sampling with tools (2025-11-25)

```clojure
schema/ToolChoice                ; {:mode #{"auto" "required" "none"}}
schema/ToolChoiceMode            ; [:enum "auto" "required" "none"]
schema/SamplingTool              ; {:name :string :description? :string :input-schema :map}
schema/StopReason                ; [:enum "endTurn" "stopSequence" "maxTokens" "toolUse"]

;; Constructors
(schema/tool-choice :auto)        ; {:mode "auto"}
(schema/tool-choice :required)    ; {:mode "required"}
(schema/sampling-tool {:name "get_weather"
                       :description "Look up weather"
                       :input-schema {:type "object" :properties {...}}})
```

### Content blocks

```clojure
schema/TextContent       ; {:type "text" :text :string}
schema/ImageContent      ; {:type "image" :data :string :mime-type :string}
schema/AudioContent      ; {:type "audio" :data :string :mime-type :string}
schema/ToolUseContent    ; {:type "tool_use" :id :name :input}
schema/ToolResultContent ; {:type "tool_result" :tool-use-id :content :is-error?}
```

### Tool result messages

The MCP spec is strict: a user message containing tool results MUST contain ONLY tool results, no mixed text+tool-result content. The toolkit ships a schema and a constructor that enforces it:

```clojure
schema/ToolResultMessage
;; [:map
;;  [:role [:= "user"]]
;;  [:content [:or ToolResultContent [:vector {:min 1} ToolResultContent]]]]

(schema/tool-result {:tool-use-id "call_abc" :content {:type "text" :text "Weather: 18°C"}})
;; => {:type "tool_result" :tool-use-id "call_abc" :content {...}}

(schema/tool-result-message
  [(schema/tool-result {:tool-use-id "call_abc" :content {:type "text" :text "Result 1"}})
   (schema/tool-result {:tool-use-id "call_def" :content {:type "text" :text "Result 2"}})])
;; => {:role "user" :content [{...} {...}]}

;; ! variant validates and throws if you accidentally mix content types
(schema/tool-result-message! [...])

;; Standalone predicate
(schema/valid-tool-result-message? msg)
```

### Elicitation (2025-11-25)

```clojure
schema/ElicitationMode      ; [:enum "form" "url"]
schema/ElicitationAction    ; [:enum "accept" "decline" "cancel"]
schema/UrlElicitationRequest
schema/FormElicitationRequest
schema/ElicitationResponse
schema/ElicitationCompleteNotification
schema/UrlElicitationRequiredErrorData

;; Constructors, both have ! variants
(schema/url-elicitation
  {:elicitation-id "550e8400-..."
   :url "https://api.example.com/oauth/authorize"
   :message "Please authorize access"})
;; => {:mode "url" :elicitation-id "..." :url "..." :message "..."}

(schema/form-elicitation
  {:message "Please provide details"
   :requested-schema {:type "object" :properties {:name {:type "string"}} :required ["name"]}})
;; => {:mode "form" :message "..." :requested-schema {...}}
```

The URL schema validates that `:url` starts with `https://` (or `http://localhost` for development). The form schema doesn't validate the JSON Schema in `:requested-schema`, that's the client's job.

### Tasks (2025-11-25 experimental)

```clojure
schema/Task                 ; full Task object schema
schema/TaskStatus           ; [:enum "pending" "running" "input_required" "completed" "failed" "cancelled"]
schema/terminal-status?     ; (schema/terminal-status? "completed") => true; "running" => false
```

Use `terminal-status?` to check whether a task has reached a status from which it won't progress.

## Pattern: validate at the boundary

The schema namespace is designed for use at boundaries, when receiving an LLM-generated request, when registering a user-provided tool, when constructing a request to a remote MCP client. Internal handler code typically doesn't validate (the toolkit's own dispatch already trusts the inbound shape after JSON parsing).

```clojure
;; At a boundary, incoming request from a flaky upstream
(defn handle-tool-registration [tool-shape]
  (if (schema/valid? schema/SamplingTool tool-shape)
    (server/add-tool context tool-shape)
    (throw (ex-info "Invalid tool"
                    {:errors (schema/explain schema/SamplingTool tool-shape)
                     :tool-shape tool-shape}))))

;; Or, use the !-suffixed constructor which is itself a boundary check
(let [request (schema/url-elicitation!  ; throws on invalid
                {:elicitation-id (str (random-uuid))
                 :url "https://auth.example.com/oauth"
                 :message "Authorize access"})]
  (server/request-elicitation context request))
```

The throw shape is `(ex-info <msg> {:errors [...] :request <input> | :schema <schema>})`, `:errors` is the humanized error tree, `:request` / `:schema` is the input that failed.

## When to use `!`-suffixed vs. plain constructors

| Constructor | When to use |
|---|---|
| `tool-choice` / `sampling-tool` / `tool-result` / `tool-result-message` / `enum-schema` / `url-elicitation` / `form-elicitation` | Constructing a known-good shape from internal data. No validation cost. |
| `tool-result-message!` / `enum-schema!` / `url-elicitation!` / `form-elicitation!` | Constructing from external data (user input, file, deserialized JSON). Throws on shape mismatch with a humanized error. |

There's no `tool-choice!` / `sampling-tool!` / `tool-result!`, those constructors are simple enough that a malformed input would be obvious at the call site.

## Pattern: extending with your own schemas

The MCP protocol is the bottom layer; your application probably has its own request / response shapes (custom tool inputs, business-domain types). The same Malli pattern applies:

```clojure
(def MyToolInput
  [:map
   [:query [:and :string [:fn {:error/message "Query must be 1-1024 chars"}
                          (fn [s] (<= 1 (count s) 1024))]]]
   [:max-results {:optional true} [:int {:min 1 :max 100}]]])

(defn my-tool-fn [_context arguments]
  (when-not (m/validate MyToolInput arguments)
    (throw (ex-info "Invalid input"
                    {:errors (me/humanize (m/explain MyToolInput arguments))})))
  ;; ... real work ...
  )
```

The convention in this fork is to put domain schemas in their own namespace (e.g. `my.app.schemas`) so the MCP-protocol schemas in `mcp-toolkit.schema` stay focused on protocol types.

## See also

- The full schema source: [`src/mcp_toolkit/schema.cljc`](https://github.com/b12n-oss/mcp-tkx/blob/main/src/mcp_toolkit/schema.cljc), ~791 lines, all referenced types are there.
- [Malli](https://github.com/metosin/malli): the underlying schema library.
- [2025-11-25 features](2025-11-25-features.md): uses these schemas in worked examples.
- [Extraction recipes](extraction-recipes.md) Recipe 2: lift the Malli protocol-schema-registry pattern into another project.
