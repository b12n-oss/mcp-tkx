# MCP Toolkit Migration Plan: 2025-06-18 → 2025-11-25

**Document Version:** 2.0  
**Created:** 2025-01-11  
**Updated:** 2025-01-11  
**Target Spec:** [MCP 2025-11-25](https://modelcontextprotocol.io/specification/2025-11-25)

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Key Design Decision: Idiomatic Clojure Keys](#key-design-decision-idiomatic-clojure-keys)
3. [Changelog Overview](#changelog-overview)
4. [Implementation Phases](#implementation-phases)
5. [Phase 0: Key Transformation Layer](#phase-0-key-transformation-layer)
6. [Phase 1: Protocol Version Negotiation](#phase-1-protocol-version-negotiation)
7. [Phase 2: Implementation Description Field](#phase-2-implementation-description-field)
8. [Phase 3: Icons Support](#phase-3-icons-support)
9. [Phase 4: EnumSchema Updates](#phase-4-enumschema-updates)
10. [Phase 5: Sampling with Tools](#phase-5-sampling-with-tools)
11. [Phase 6: URL Mode Elicitation](#phase-6-url-mode-elicitation)
12. [Phase 7: Tasks Support (Experimental)](#phase-7-tasks-support-experimental)
13. [Phase 8: OAuth Enhancements](#phase-8-oauth-enhancements)
14. [Phase 9: Minor Clarifications](#phase-9-minor-clarifications)
15. [Testing Strategy](#testing-strategy)
16. [Migration Checklist](#migration-checklist)

---

## Executive Summary

The MCP 2025-11-25 specification introduces several significant enhancements over 2025-06-18, most notably:

- **Tasks**: An experimental primitive for tracking long-running operations
- **Icons**: Visual metadata for tools, resources, and prompts
- **URL Mode Elicitation**: Browser-based authentication flows
- **Sampling with Tools**: Tool calling support in sampling requests
- **OAuth Enhancements**: OIDC Discovery and Client ID Metadata Documents

This migration maintains full backward compatibility with existing protocol versions while adding support for all new 2025-11-25 features.

**Key Design Decision:** All internal Clojure code will use **kebab-case** keys (`:max-tokens`, `:input-schema`) with automatic conversion to/from **camelCase** at the JSON-RPC boundary using `camel-snake-kebab`.

**Estimated Effort:** 4-6 days for core implementation, 2-3 days for testing

---

## Key Design Decision: Idiomatic Clojure Keys

### Problem

The MCP protocol uses camelCase for JSON keys (e.g., `maxTokens`, `inputSchema`), but idiomatic Clojure uses kebab-case (e.g., `:max-tokens`, `:input-schema`).

### Solution

Use `camel-snake-kebab` (already a dependency) to transform keys at the JSON-RPC boundary:

```clojure
;; Incoming JSON (camelCase) → Internal Clojure (kebab-case)
{"maxTokens" 1000, "inputSchema" {...}}
→ {:max-tokens 1000, :input-schema {...}}

;; Outgoing Clojure (kebab-case) → JSON (camelCase)  
{:max-tokens 1000, :input-schema {...}}
→ {"maxTokens": 1000, "inputSchema": {...}}
```

### Benefits

1. **Idiomatic Clojure** - Code reads naturally
2. **Consistent API** - Users always work with kebab-case
3. **Less cognitive load** - No mixing of conventions
4. **Editor support** - Better autocomplete for kebab-case keywords

### Key Mapping Reference

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
| `protocolVersion` | `:protocol-version` |
| `serverInfo` | `:server-info` |
| `clientInfo` | `:client-info` |
| `resourceTemplates` | `:resource-templates` |
| `taskId` | `:task-id` |
| `enumTitles` | `:enum-titles` |
| `multiSelect` | `:multi-select` |

---

## Changelog Overview

### Major Changes (SEPs)

| SEP | Feature | Priority | Complexity |
|-----|---------|----------|------------|
| SEP-1686 | Tasks (experimental) | High | Complex |
| SEP-973 | Icons for entities | Medium | Simple |
| SEP-1036 | URL mode elicitation | Medium | Moderate |
| SEP-1577 | Sampling with tools | Medium | Moderate |
| SEP-1330 | EnumSchema updates | Medium | Moderate |
| SEP-991 | OAuth CIMD | Low | Complex |
| SEP-835 | Incremental scope consent | Low | Moderate |
| SEP-986 | Tool name guidance | Low | Documentation |

### Minor Changes

- Optional `description` field on `Implementation` interface
- HTTP 403 for invalid Origin headers (Streamable HTTP)
- Input validation errors as Tool Execution Errors (not Protocol Errors)
- SSE polling support
- JSON Schema 2020-12 as default dialect

---

## Implementation Phases

```
Phase 0 ─► Phase 1 ─► Phase 2 ─► Phase 3 ─► Phase 4
   │          │          │          │          │
   ▼          ▼          ▼          ▼          ▼
  Key      Protocol   Description  Icons    EnumSchema
Transform  Version    Field

Phase 5 ─► Phase 6 ─► Phase 7 ─► Phase 8 ─► Phase 9
   │          │          │          │          │
   ▼          ▼          ▼          ▼          ▼
Sampling   URL Mode    Tasks     OAuth     Minor
+ Tools    Elicit    (Exp.)    Enhance   Clarify
```

---

## Phase 0: Key Transformation Layer

**Priority:** Critical (Foundation for all other phases)  
**Complexity:** Moderate  
**Estimated Time:** 3 hours

### New File: `src/mcp_toolkit/impl/keys.cljc`

```clojure
(ns ^:no-doc mcp-toolkit.impl.keys
  "Key transformation utilities for converting between MCP wire format (camelCase)
   and idiomatic Clojure (kebab-case).
   
   Uses camel-snake-kebab for transformations."
  (:require [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as csk-extras]))

(defn wire->clj
  "Transforms all keys in a data structure from camelCase to kebab-case.
   Used when receiving JSON-RPC messages.
   
   Example:
     (wire->clj {:maxTokens 1000 :inputSchema {:type \"object\"}})
     => {:max-tokens 1000 :input-schema {:type \"object\"}}"
  [data]
  (csk-extras/transform-keys csk/->kebab-case-keyword data))

(defn clj->wire
  "Transforms all keys in a data structure from kebab-case to camelCase.
   Used when sending JSON-RPC messages.
   
   Example:
     (clj->wire {:max-tokens 1000 :input-schema {:type \"object\"}})
     => {:maxTokens 1000 :inputSchema {:type \"object\"}}"
  [data]
  (csk-extras/transform-keys csk/->camelCaseKeyword data))

;; Special handling for keys that should NOT be transformed
;; (e.g., user-defined keys in tool arguments, JSON Schema properties)

(def ^:private preserve-keys
  "Keys whose nested content should not be transformed.
   These typically contain user-defined schemas or data."
  #{:properties :arguments :data :result :content :_meta})

(defn wire->clj-shallow
  "Transforms top-level keys only, preserving nested structures.
   Useful when nested data contains user-defined keys."
  [data]
  (if (map? data)
    (into {}
          (map (fn [[k v]]
                 [(csk/->kebab-case-keyword k)
                  (if (contains? preserve-keys (csk/->kebab-case-keyword k))
                    v  ; Don't transform nested user data
                    (wire->clj-shallow v))]))
          data)
    data))

(defn clj->wire-shallow
  "Transforms top-level keys only for outgoing messages."
  [data]
  (if (map? data)
    (into {}
          (map (fn [[k v]]
                 [(csk/->camelCaseKeyword k)
                  (if (contains? preserve-keys k)
                    v  ; Don't transform nested user data
                    (clj->wire-shallow v))]))
          data)
    data))
```

### Update: `src/mcp_toolkit/json_rpc.cljc`

Add key transformation at the boundary:

```clojure
(ns mcp-toolkit.json-rpc
  (:require [mcp-toolkit.impl.keys :as keys]
            [promesa.core :as p]))

;; Update handle-message to transform incoming keys
(defn handle-message
  "Handles incoming JSON-RPC messages with automatic key transformation.
   
   - Incoming camelCase keys are converted to kebab-case
   - Outgoing kebab-case keys are converted to camelCase"
  [context message]
  (let [{:keys [send-message]} context
        ;; Transform incoming message keys to kebab-case
        message (keys/wire->clj message)
        ;; Wrap send-message to transform outgoing keys
        send-message* (fn [response]
                        (send-message (keys/clj->wire response)))]
    (if (vector? message)
      (send-message* invalid-request-response)
      (-> (route-message (assoc context 
                                :message message
                                :send-message send-message*))
          (p/then (fn [response]
                    (when (some? response)
                      (send-message* response))))))))

;; Update call-remote-method to transform keys
(defn call-remote-method
  "Calls a remote method via JSON-RPC with automatic key transformation."
  [context {:keys [method params] :as message}]
  (let [{:keys [session send-message]} context
        called-method-id (-> (swap! session update :last-called-method-id inc)
                             :last-called-method-id)]
    (p/create
     (fn [resolve reject]
       (let [response-handler (fn [{:keys [session message]}]
                                (swap! session update :handler-by-called-method-id dissoc called-method-id)
                                (if (contains? message :error)
                                  (reject (ex-info "error" (:error message)))
                                  ;; Result is already transformed by handle-message
                                  (resolve (:result message))))]
         (swap! session update :handler-by-called-method-id assoc called-method-id response-handler)
         ;; Transform outgoing message keys to camelCase
         (send-message (keys/clj->wire
                        (-> message
                            (assoc :jsonrpc "2.0"
                                   :id called-method-id)))))))))
```

### Testing the Key Transformation

```clojure
(ns mcp-toolkit.impl.keys-test
  (:require [clojure.test :refer [deftest testing is]]
            [mcp-toolkit.impl.keys :as keys]))

(deftest wire->clj-test
  (testing "transforms camelCase to kebab-case"
    (is (= {:max-tokens 1000
            :input-schema {:type "object"
                           :properties {:file-path {:type "string"}}}}
           (keys/wire->clj {:maxTokens 1000
                            :inputSchema {:type "object"
                                          :properties {:filePath {:type "string"}}}})))))

(deftest clj->wire-test
  (testing "transforms kebab-case to camelCase"
    (is (= {:maxTokens 1000
            :inputSchema {:type "object"}}
           (keys/clj->wire {:max-tokens 1000
                            :input-schema {:type "object"}})))))

(deftest round-trip-test
  (testing "round-trip transformation preserves data"
    (let [original {:max-tokens 1000 :tool-choice {:type "auto"}}]
      (is (= original
             (keys/wire->clj (keys/clj->wire original)))))))
```

---

## Phase 1: Protocol Version Negotiation

**Priority:** Critical  
**Complexity:** Simple  
**Estimated Time:** 1 hour

### Changes Required

#### File: `src/mcp_toolkit/server.cljc`

Update the supported protocol versions list:

```clojure
;; BEFORE
(def ^:private supported-protocol-versions
  ["2024-11-05" "2025-03-26" "2025-06-18"])

;; AFTER
(def ^:private supported-protocol-versions
  ["2024-11-05" "2025-03-26" "2025-06-18" "2025-11-25"])
```

#### File: `src/mcp_toolkit/client.cljc`

Update default protocol version:

```clojure
;; BEFORE (in create-session)
:or {protocol-version "2025-06-18" ...}

;; AFTER
:or {protocol-version "2025-11-25" ...}
```

### Testing

```clojure
(deftest protocol-version-negotiation-test
  (testing "Server negotiates 2025-11-25 with compatible client"
    (let [session (server/create-session {})
          result (handle-initialize session "2025-11-25")]
      (is (= "2025-11-25" (:protocol-version result)))))
  
  (testing "Server falls back for older clients"
    (let [session (server/create-session {})
          result (handle-initialize session "2025-06-18")]
      (is (= "2025-06-18" (:protocol-version result))))))
```

---

## Phase 2: Implementation Description Field

**Priority:** High  
**Complexity:** Simple  
**Estimated Time:** 30 minutes

### Specification

The `Implementation` interface now includes an optional `description` field:

```typescript
interface Implementation {
  name: string;
  version: string;
  description?: string;  // NEW in 2025-11-25
}
```

### Changes Required

#### File: `src/mcp_toolkit/server.cljc`

Update `create-session` to accept description:

```clojure
(defn create-session 
  [{:keys [server-info
           server-instructions
           ;; ... existing keys ...
           ]
    :or {server-info {:name "mcp-toolkit"
                      :version "0.2.0"
                      ;; description is optional
                      }}}]
  ;; ... rest of implementation
  )
```

### Example Usage (Kebab-case)

```clojure
(server/create-session
  {:server-info {:name "my-server"
                 :version "1.0.0"
                 :description "A comprehensive MCP server for data analysis"}})
```

---

## Phase 3: Icons Support

**Priority:** Medium  
**Complexity:** Simple  
**Estimated Time:** 2 hours

### Specification (SEP-973)

Tools, resources, resource templates, and prompts can now include an `icon` field:

```clojure
;; Internal Clojure representation (kebab-case)
{:name "read_file"
 :title "File Reader"
 :icon "data:image/svg+xml;base64,..."
 :description "Reads a file from disk"
 :input-schema {:type "object"
                :properties {:path {:type "string"}}
                :required [:path]}}
```

**Icon Format:**
- Data URI: `data:image/svg+xml;base64,...` or `data:image/png;base64,...`
- HTTPS URL: `https://example.com/icons/tool.svg`

### Changes Required

#### File: `src/mcp_toolkit/impl/server/handler.cljc`

Update list handlers to include icon:

```clojure
(defn tool-list-handler
  [{:keys [session]}]
  {:tools (-> @session :tool-by-name vals
              (->> (mapv (fn [tool]
                           (cond-> (select-keys tool [:name :title :description :input-schema])
                             ;; Add output-schema if present (2025-06-18)
                             (:output-schema tool) (assoc :output-schema (:output-schema tool))
                             ;; NEW: Add icon if present (2025-11-25)
                             (:icon tool) (assoc :icon (:icon tool)))))))})

(defn prompt-list-handler
  [{:keys [session]}]
  {:prompts (-> @session :prompt-by-name vals
                (->> (mapv (fn [prompt]
                             (cond-> (select-keys prompt [:name :title :description :arguments])
                               ;; NEW: Add icon if present (2025-11-25)
                               (:icon prompt) (assoc :icon (:icon prompt)))))))})

(defn resource-list-handler
  [{:keys [session]}]
  {:resources (-> @session :resource-by-uri vals
                  (->> (mapv (fn [resource]
                               (cond-> (select-keys resource [:uri :name :title :description :mime-type])
                                 ;; NEW: Add icon if present (2025-11-25)
                                 (:icon resource) (assoc :icon (:icon resource)))))))})
```

### Example Usage

```clojure
(def file-reader-tool
  {:name "read_file"
   :title "File Reader"
   :icon "data:image/svg+xml;base64,PHN2ZyB4bWxucz0i..."
   :description "Reads a file from disk"
   :input-schema {:type "object"
                  :properties {:path {:type "string"}}
                  :required [:path]}
   :tool-fn (fn [ctx args] (slurp (:path args)))})
```

### Icon Utility Namespace

Create `src/mcp_toolkit/util/icons.cljc`:

```clojure
(ns mcp-toolkit.util.icons
  "Utilities for working with MCP icons (2025-11-25+)."
  #?(:clj (:import [java.util Base64])))

(defn svg->data-uri
  "Converts an SVG string to a data URI."
  [svg-string]
  #?(:clj (str "data:image/svg+xml;base64,"
               (.encodeToString (Base64/getEncoder) (.getBytes svg-string "UTF-8")))
     :cljs (str "data:image/svg+xml;base64,"
                (js/btoa svg-string))))

(defn valid-icon?
  "Validates that an icon is either a data URI or HTTPS URL."
  [icon]
  (when (string? icon)
    (or (clojure.string/starts-with? icon "data:image/")
        (clojure.string/starts-with? icon "https://"))))
```

---

## Phase 4: EnumSchema Updates

**Priority:** Medium  
**Complexity:** Moderate  
**Estimated Time:** 2 hours

### Specification (SEP-1330)

EnumSchema now supports:
- Titled enums (display names for each option)
- Untitled enums (simple string arrays)
- Single-select (default)
- Multi-select

### Internal Representation (Kebab-case)

```clojure
{:type "string"
 :enum ["low" "medium" "high"]
 :enum-titles ["Low Priority" "Medium Priority" "High Priority"]  ;; kebab-case
 :multi-select true                                               ;; kebab-case
 :default ["medium"]}
```

### New File: `src/mcp_toolkit/impl/elicitation.cljc`

```clojure
(ns mcp-toolkit.impl.elicitation
  "Support for elicitation schemas (2025-11-25).")

(defn enum-schema
  "Creates a validated enum schema.
   
   Options:
   - :values       - Vector of string values (required)
   - :titles       - Vector of display titles (optional, must match values length)
   - :multi-select - Allow multiple selections (default: false)
   - :default      - Default value(s)"
  [{:keys [values titles multi-select default]}]
  (cond-> {:type "string"
           :enum values}
    titles (assoc :enum-titles titles)
    multi-select (assoc :multi-select true)
    default (assoc :default default)))

(defn validate-enum-schema
  "Validates an enum schema structure."
  [{:keys [enum enum-titles multi-select default] :as schema}]
  (cond
    (not (sequential? enum))
    {:valid? false :error "enum must be an array"}
    
    (and enum-titles (not= (count enum) (count enum-titles)))
    {:valid? false :error "enum-titles must match enum length"}
    
    (and multi-select default (not (sequential? default)))
    {:valid? false :error "default must be array when multi-select is true"}
    
    :else
    {:valid? true}))
```

### Example Usage

```clojure
;; Simple enum (backward compatible)
{:type "string"
 :enum ["low" "medium" "high"]}

;; Titled enum
{:type "string"
 :enum ["low" "medium" "high"]
 :enum-titles ["Low Priority" "Medium Priority" "High Priority"]}

;; Multi-select enum
{:type "string"
 :enum ["email" "sms" "push"]
 :enum-titles ["Email" "SMS" "Push Notification"]
 :multi-select true
 :default ["email"]}
```

---

## Phase 5: Sampling with Tools

**Priority:** Medium  
**Complexity:** Moderate  
**Estimated Time:** 3 hours

### Specification (SEP-1577)

Sampling requests can now include tool definitions, enabling server-side agent loops.

### Internal API (Kebab-case)

```clojure
;; Server requesting sampling with tools
(server/request-sampling context
  {:messages [{:role "user" 
               :content {:type "text" 
                         :text "Search for recent news"}}]
   :max-tokens 1000                                    ;; kebab-case
   :tools [{:name "web_search"
            :description "Search the web"
            :input-schema {:type "object"              ;; kebab-case
                           :properties {:query {:type "string"}}
                           :required [:query]}}]
   :tool-choice {:type "auto"}})                       ;; kebab-case
```

### Changes Required

#### File: `src/mcp_toolkit/server.cljc`

Update `request-sampling`:

```clojure
(defn request-sampling
  "Requests an LLM completion from the client with optional tool support.
   
   Args:
     context - The server session context
     params  - Map containing:
       :messages       - Vector of message maps with :role and :content
       :max-tokens     - Maximum tokens to generate (optional)
       :tools          - Vector of tool definitions (optional, 2025-11-25+)
       :tool-choice    - Tool selection strategy (optional, 2025-11-25+)
       :system-prompt  - System prompt (optional)
       :include-context - Context inclusion mode (optional)
       :temperature    - Sampling temperature (optional)
       :stop-sequences - Stop sequences (optional)
       :metadata       - Request metadata (optional)
   
   Returns:
     A promise resolving to the sampling result."
  [context params]
  (let [{:keys [session]} context
        {:keys [protocol-version]} @session
        ;; Only include tools if protocol supports it
        supports-tools? (contains? #{"2025-11-25"} protocol-version)]
    (json-rpc/call-remote-method 
      context 
      {:method "sampling/createMessage"
       :params (cond-> params
                 ;; Remove tools/tool-choice if client doesn't support
                 (not supports-tools?) (dissoc :tools :tool-choice))})))
```

#### File: `src/mcp_toolkit/impl/client/handler.cljc`

Update sampling handler:

```clojure
(defn sampling-create-message-handler
  [{:keys [session message] :as context}]
  ;; Keys are already kebab-case due to transformation layer
  (let [{:keys [messages max-tokens tools tool-choice 
                system-prompt include-context temperature 
                stop-sequences metadata]} (:params message)]
    (if-some [on-sampling-requested (:on-sampling-requested @session)]
      (on-sampling-requested context 
                             {:messages messages
                              :max-tokens max-tokens
                              :tools tools           ;; NEW
                              :tool-choice tool-choice ;; NEW
                              :system-prompt system-prompt
                              :include-context include-context
                              :temperature temperature
                              :stop-sequences stop-sequences
                              :metadata metadata})
      (json-rpc/method-not-found-response (:id message)))))
```

---

## Phase 6: URL Mode Elicitation

**Priority:** Medium  
**Complexity:** Moderate  
**Estimated Time:** 3 hours

### Specification (SEP-1036)

Servers can now request URL-based user interactions.

### Internal API (Kebab-case)

```clojure
;; URL elicitation request
{:url "https://auth.example.com/oauth/authorize?..."
 :url-message "Complete authentication in your browser"}  ;; kebab-case

;; Result
{:action "url-completed"}  ;; or "accept" / "decline"
```

### Changes Required

#### File: `src/mcp_toolkit/server.cljc`

```clojure
(defn request-elicitation
  "Requests user input via form or URL redirect.
   
   For form-based elicitation:
     {:message \"Please provide details\"
      :requested-schema {:type \"object\" :properties {...}}}
   
   For URL-based elicitation (2025-11-25+):
     {:url \"https://auth.example.com/oauth/authorize?...\"
      :url-message \"Complete authentication in your browser\"}
   
   Returns a promise resolving to ElicitResult."
  [context params]
  (let [{:keys [session]} context
        {:keys [client-capabilities]} @session]
    (when (-> client-capabilities :elicitation)
      (json-rpc/call-remote-method context
        {:method "elicitation/create"
         :params params}))))

(defn request-url-elicitation
  "Convenience function for URL-based elicitation.
   
   Args:
     context - The server session context
     url     - The URL to open in user's browser
     message - Message explaining what user should do (optional)"
  [context url & {:keys [message]}]
  (request-elicitation context
    {:url url
     :url-message (or message "Please complete the action in your browser")}))
```

### Example Usage

```clojure
;; OAuth flow via URL elicitation
(defn oauth-tool-fn [context args]
  (let [auth-url (build-oauth-url args)]
    (-> (server/request-url-elicitation context auth-url
          :message "Please sign in with your Google account")
        (p/then (fn [result]
                  (case (:action result)
                    "url-completed" {:content [{:type "text" 
                                                :text "Authentication successful!"}]}
                    "decline" {:content [{:type "text" 
                                          :text "Authentication cancelled"}]
                               :is-error true}))))))
```

---

## Phase 7: Tasks Support (Experimental)

**Priority:** High  
**Complexity:** Complex  
**Estimated Time:** 8 hours

### Specification (SEP-1686)

Tasks provide tracking for long-running operations.

### Task States

```clojure
(def task-states #{:working :input-required :completed :failed :cancelled})
```

### New File: `src/mcp_toolkit/impl/tasks.cljc`

```clojure
(ns ^:no-doc mcp-toolkit.impl.tasks
  "Experimental task support for MCP 2025-11-25."
  (:require [promesa.core :as p]))

(def task-states
  "Valid task states."
  #{:working :input-required :completed :failed :cancelled})

(defn create-task
  "Creates a new task entry.
   
   Args:
     id      - Unique task identifier
     options - Map with optional :message, :progress"
  [id & {:keys [message progress]}]
  {:id id
   :state :working
   :message message
   :progress progress
   :created-at (System/currentTimeMillis)
   :result-promise (p/deferred)})

(defn update-task-state
  "Updates task state in the session."
  [session task-id new-state & {:keys [message progress result error]}]
  (swap! session update-in [:tasks-by-id task-id]
         (fn [task]
           (cond-> (assoc task :state new-state
                              :updated-at (System/currentTimeMillis))
             message (assoc :message message)
             progress (assoc :progress progress)
             result (assoc :result result)
             error (assoc :error error)))))

(defn complete-task
  "Marks a task as completed with result."
  [session task-id result]
  (let [task (get-in @session [:tasks-by-id task-id])]
    (update-task-state session task-id :completed :result result)
    (when-let [deferred (:result-promise task)]
      (p/resolve! deferred result))))

(defn fail-task
  "Marks a task as failed with error."
  [session task-id error]
  (let [task (get-in @session [:tasks-by-id task-id])]
    (update-task-state session task-id :failed :error error)
    (when-let [deferred (:result-promise task)]
      (p/reject! deferred (ex-info "Task failed" {:error error})))))

(defn cancel-task
  "Cancels a task."
  [session task-id]
  (update-task-state session task-id :cancelled))

(defn get-task-status
  "Returns current task status."
  [session task-id]
  (when-let [task (get-in @session [:tasks-by-id task-id])]
    (select-keys task [:id :state :message :progress])))

(defn get-task-result
  "Returns task result if completed, or waits for completion."
  [session task-id]
  (if-let [task (get-in @session [:tasks-by-id task-id])]
    (case (:state task)
      :completed (p/resolved (:result task))
      :failed (p/rejected (ex-info "Task failed" (:error task)))
      :cancelled (p/rejected (ex-info "Task cancelled" {}))
      ;; Still working - return the promise
      (:result-promise task))
    (p/rejected (ex-info "Task not found" {:task-id task-id}))))

(defn cleanup-old-tasks
  "Removes tasks older than max-age-ms."
  [session max-age-ms]
  (let [cutoff (- (System/currentTimeMillis) max-age-ms)]
    (swap! session update :tasks-by-id
           (fn [tasks]
             (into {} (filter (fn [[_ task]]
                                (> (:updated-at task) cutoff))
                              tasks))))))
```

### File: `src/mcp_toolkit/server.cljc` - Task Functions

```clojure
(defn create-task
  "Creates a task for tracking a long-running operation.
   Returns the task handle that can be included in tool responses.
   
   Args:
     context - The server session context
     opts    - Map with :message (optional), :progress (optional)"
  [context & {:keys [message progress]}]
  (let [{:keys [session]} context
        task-id (str (random-uuid))]
    (swap! session assoc-in [:tasks-by-id task-id]
           (tasks/create-task task-id :message message :progress progress))
    {:id task-id
     :state :working
     :message message
     :progress progress}))

(defn notify-task-progress
  "Sends a task progress notification to the client.
   
   Args:
     context - The server session context
     task-id - The task ID
     current - Current progress value
     total   - Total progress value (optional)
     message - Progress message (optional)"
  [context task-id current & {:keys [total message]}]
  (json-rpc/send-message context
    (json-rpc/notification "tasks/progress"
      {:task-id task-id
       :progress {:current current
                  :total total}
       :message message})))

(defn complete-task
  "Marks a task as completed with the given result."
  [context task-id result]
  (let [{:keys [session]} context]
    (tasks/complete-task session task-id result)))

(defn fail-task
  "Marks a task as failed with the given error."
  [context task-id error]
  (let [{:keys [session]} context]
    (tasks/fail-task session task-id error)))
```

### Example: Long-Running Tool with Tasks

```clojure
(def data-analysis-tool
  {:name "analyze_large_dataset"
   :title "Large Dataset Analyzer"
   :description "Analyzes large datasets (may take several minutes)"
   :input-schema {:type "object"
                  :properties {:dataset-url {:type "string"}}
                  :required [:dataset-url]}
   :tool-fn (fn [context args]
              ;; Create task for tracking
              (let [task (server/create-task context
                           :message "Starting analysis...")]
                ;; Start async work
                (p/do
                  ;; Simulate long-running work with progress
                  (dotimes [i 10]
                    (Thread/sleep 1000)
                    (server/notify-task-progress context (:id task) (inc i)
                      :total 10
                      :message (str "Processing chunk " (inc i) "/10")))
                  
                  ;; Complete the task
                  (let [result {:summary "Analysis complete"
                                :records-processed 10000}]
                    (server/complete-task context (:id task) result)
                    
                    ;; Return response with task reference
                    {:content [{:type "text"
                                :text "Analysis started"}]
                     :task task}))))})
```

---

## Phase 8: OAuth Enhancements

**Priority:** Low  
**Complexity:** Complex  
**Estimated Time:** 4 hours

### Changes (Using Kebab-case)

OAuth changes primarily affect HTTP transport. Internal representations use kebab-case:

```clojure
;; Client ID Metadata Document (internal representation)
{:client-id "my-client"
 :client-name "My MCP Client"
 :redirect-uris ["http://localhost:8080/callback"]
 :grant-types ["authorization_code"]
 :response-types ["code"]}
```

---

## Phase 9: Minor Clarifications

**Priority:** Low  
**Complexity:** Simple  
**Estimated Time:** 1 hour

### Tool Input Validation Errors

Return validation errors as tool execution errors using kebab-case:

```clojure
;; Error response with kebab-case internally
{:content [{:type "text"
            :text "Validation error: path is required"}]
 :is-error true}  ;; kebab-case
```

### JSON Schema 2020-12

```clojure
(def default-schema-dialect 
  "https://json-schema.org/draft/2020-12/schema")

(defn with-schema-dialect
  "Adds $schema declaration to a schema."
  [schema]
  (assoc schema :$schema default-schema-dialect))
```

---

## Testing Strategy

### Unit Tests

Create `test/mcp_toolkit/protocol_2025_11_25_test.cljc`:

```clojure
(ns mcp-toolkit.protocol-2025-11-25-test
  (:require [clojure.test :refer [deftest testing is]]
            [mcp-toolkit.server :as server]
            [mcp-toolkit.impl.keys :as keys]
            [mcp-toolkit.impl.tasks :as tasks]))

(deftest key-transformation-test
  (testing "wire->clj transforms camelCase to kebab-case"
    (is (= {:max-tokens 1000}
           (keys/wire->clj {:maxTokens 1000}))))
  
  (testing "clj->wire transforms kebab-case to camelCase"
    (is (= {:maxTokens 1000}
           (keys/clj->wire {:max-tokens 1000})))))

(deftest icons-test
  (testing "Tools include icon in list response"
    (let [session (atom (server/create-session
                          {:tools [{:name "test"
                                    :icon "data:image/svg+xml;base64,..."
                                    :input-schema {}
                                    :tool-fn identity}]}))]
      ;; Test icon is preserved
      )))

(deftest tasks-test
  (testing "Task lifecycle uses kebab-case"
    (let [session (atom {})]
      (let [task (tasks/create-task "task-1" :message "Starting")]
        (is (= :working (:state task)))
        
        (tasks/update-task-state session "task-1" :completed 
          :result {:data "done"})
        (is (= :completed (:state (tasks/get-task-status session "task-1"))))))))

(deftest sampling-with-tools-test
  (testing "Sampling uses kebab-case keys internally"
    ;; Verify :max-tokens, :tool-choice work
    ))
```

---

## Migration Checklist

### Phase 0: Key Transformation
- [ ] Create `mcp-toolkit.impl.keys` namespace
- [ ] Implement `wire->clj` function
- [ ] Implement `clj->wire` function
- [ ] Handle special keys (properties, arguments, etc.)
- [ ] Update `json-rpc/handle-message` with transformation
- [ ] Update `json-rpc/call-remote-method` with transformation
- [ ] Add comprehensive tests
- [ ] Update all handlers to use kebab-case keys

### Phase 1: Protocol Version
- [ ] Add `"2025-11-25"` to `supported-protocol-versions`
- [ ] Update default `protocol-version` to `"2025-11-25"`
- [ ] Test version negotiation

### Phase 2-9: See individual phase sections

### Final Steps
- [ ] Update `README.md` with kebab-case examples
- [ ] Update `CHANGELOG.md`
- [ ] Run full test suite
- [ ] Test with Claude Desktop
- [ ] Test with Claude Code

---

## References

- [MCP Specification 2025-11-25](https://modelcontextprotocol.io/specification/2025-11-25)
- [Key Changes](https://modelcontextprotocol.io/specification/2025-11-25/changelog)
- [camel-snake-kebab](https://github.com/clj-commons/camel-snake-kebab)
