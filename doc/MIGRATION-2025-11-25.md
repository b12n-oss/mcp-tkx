# MCP Toolkit Migration Plan: 2025-06-18 → 2025-11-25

**Document Version:** 1.0  
**Created:** 2025-01-11  
**Target Spec:** [MCP 2025-11-25](https://modelcontextprotocol.io/specification/2025-11-25)

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Changelog Overview](#changelog-overview)
3. [Implementation Phases](#implementation-phases)
4. [Phase 1: Protocol Version Negotiation](#phase-1-protocol-version-negotiation)
5. [Phase 2: Implementation Description Field](#phase-2-implementation-description-field)
6. [Phase 3: Icons Support](#phase-3-icons-support)
7. [Phase 4: EnumSchema Updates](#phase-4-enumschema-updates)
8. [Phase 5: Sampling with Tools](#phase-5-sampling-with-tools)
9. [Phase 6: URL Mode Elicitation](#phase-6-url-mode-elicitation)
10. [Phase 7: Tasks Support (Experimental)](#phase-7-tasks-support-experimental)
11. [Phase 8: OAuth Enhancements](#phase-8-oauth-enhancements)
12. [Phase 9: Minor Clarifications](#phase-9-minor-clarifications)
13. [Testing Strategy](#testing-strategy)
14. [Migration Checklist](#migration-checklist)

---

## Executive Summary

The MCP 2025-11-25 specification introduces several significant enhancements over 2025-06-18, most notably:

- **Tasks**: An experimental primitive for tracking long-running operations
- **Icons**: Visual metadata for tools, resources, and prompts
- **URL Mode Elicitation**: Browser-based authentication flows
- **Sampling with Tools**: Tool calling support in sampling requests
- **OAuth Enhancements**: OIDC Discovery and Client ID Metadata Documents

This migration maintains full backward compatibility with existing protocol versions while adding support for all new 2025-11-25 features.

**Estimated Effort:** 3-5 days for core implementation, 2-3 days for testing

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
Phase 1 ─► Phase 2 ─► Phase 3 ─► Phase 4
   │          │          │          │
   ▼          ▼          ▼          ▼
Protocol   Description  Icons    EnumSchema
Version    Field

Phase 5 ─► Phase 6 ─► Phase 7 ─► Phase 8 ─► Phase 9
   │          │          │          │          │
   ▼          ▼          ▼          ▼          ▼
Sampling   URL Mode    Tasks     OAuth     Minor
+ Tools    Elicit    (Exp.)    Enhance   Clarify
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
      (is (= "2025-11-25" (:protocolVersion result)))))
  
  (testing "Server falls back for older clients"
    (let [session (server/create-session {})
          result (handle-initialize session "2025-06-18")]
      (is (= "2025-06-18" (:protocolVersion result))))))
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
                      :version "0.1.1-alpha"
                      ;; description is optional, not included by default
                      }}}]
  ;; ... rest of implementation
  )
```

#### File: `src/mcp_toolkit/impl/server/handler.cljc`

Update `initialize-handler` to include description if present:

```clojure
(defn initialize-handler
  [{:keys [session message]}]
  (let [{:keys [server-info ...]} @session]
    ;; server-info now may contain :description
    (-> {:protocolVersion protocol-version
         :capabilities {...}
         :serverInfo server-info}  ;; description passes through naturally
        (cond-> ...))))
```

### Example Usage

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

```typescript
interface Tool {
  name: string;
  title?: string;
  description?: string;
  icon?: string;  // NEW: data URI or HTTPS URL
  inputSchema: object;
  outputSchema?: object;
}

// Same pattern for Resource, ResourceTemplate, Prompt
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
                           (cond-> (select-keys tool [:name :title :description :inputSchema])
                             ;; Add outputSchema if present (2025-06-18)
                             (:outputSchema tool) (assoc :outputSchema (:outputSchema tool))
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
                               (cond-> (select-keys resource [:uri :name :title :description :mimeType])
                                 ;; NEW: Add icon if present (2025-11-25)
                                 (:icon resource) (assoc :icon (:icon resource)))))))})

(defn resource-templates-list-handler
  [{:keys [session]}]
  {:resourceTemplates (-> @session :resource-templates
                          (or [])
                          (->> (mapv (fn [template]
                                       (cond-> template
                                         ;; NEW: Icon passes through if present
                                         true identity)))))})
```

### Example Usage

```clojure
(def file-reader-tool
  {:name "read_file"
   :title "File Reader"
   :icon "data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIyNCIgaGVpZ2h0PSIyNCI+PHBhdGggZD0iTTYgMmg5bDUgNXYxNUg2eiIvPjwvc3ZnPg=="
   :description "Reads a file from disk"
   :inputSchema {:type "object"
                 :properties {:path {:type "string"}}
                 :required [:path]}
   :tool-fn (fn [ctx args] (slurp (:path args)))})
```

### Icon Utility Namespace (Optional)

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

```typescript
interface EnumSchema {
  type: "string";
  enum: string[];
  enumTitles?: string[];   // NEW: Display names
  multiSelect?: boolean;   // NEW: Allow multiple selections
  default?: string | string[];  // NEW: Default value(s)
}
```

### Changes Required

#### File: `src/mcp_toolkit/impl/elicitation.cljc` (NEW FILE)

```clojure
(ns mcp-toolkit.impl.elicitation
  "Support for elicitation schemas (2025-11-25).")

(defn enum-schema
  "Creates a validated enum schema.
   
   Options:
   - :values - Vector of string values (required)
   - :titles - Vector of display titles (optional, must match values length)
   - :multi-select? - Allow multiple selections (default: false)
   - :default - Default value(s)"
  [{:keys [values titles multi-select? default]}]
  (cond-> {:type "string"
           :enum values}
    titles (assoc :enumTitles titles)
    multi-select? (assoc :multiSelect true)
    default (assoc :default default)))

(defn validate-enum-schema
  "Validates an enum schema structure."
  [{:keys [enum enumTitles multiSelect default] :as schema}]
  (cond
    (not (sequential? enum))
    {:valid? false :error "enum must be an array"}
    
    (and enumTitles (not= (count enum) (count enumTitles)))
    {:valid? false :error "enumTitles must match enum length"}
    
    (and multiSelect default (not (sequential? default)))
    {:valid? false :error "default must be array when multiSelect is true"}
    
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
 :enumTitles ["Low Priority" "Medium Priority" "High Priority"]}

;; Multi-select enum
{:type "string"
 :enum ["email" "sms" "push"]
 :enumTitles ["Email" "SMS" "Push Notification"]
 :multiSelect true
 :default ["email"]}
```

---

## Phase 5: Sampling with Tools

**Priority:** Medium  
**Complexity:** Moderate  
**Estimated Time:** 3 hours

### Specification (SEP-1577)

Sampling requests can now include tool definitions, enabling server-side agent loops:

```typescript
interface CreateMessageRequest {
  messages: SamplingMessage[];
  maxTokens: number;
  // NEW in 2025-11-25:
  tools?: Tool[];
  toolChoice?: ToolChoice;
  // ... existing fields
}

type ToolChoice = 
  | { type: "auto" }
  | { type: "none" }
  | { type: "tool"; name: string };
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
       :messages   - Vector of message maps with :role and :content
       :maxTokens  - Maximum tokens to generate (optional)
       :tools      - Vector of tool definitions (optional, 2025-11-25+)
       :toolChoice - Tool selection strategy (optional, 2025-11-25+)
       :systemPrompt - System prompt (optional)
       :includeContext - Context inclusion mode (optional)
       :temperature - Sampling temperature (optional)
       :stopSequences - Stop sequences (optional)
       :metadata - Request metadata (optional)
   
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
                 ;; Remove tools/toolChoice if client doesn't support
                 (not supports-tools?) (dissoc :tools :toolChoice))})))
```

#### File: `src/mcp_toolkit/impl/client/handler.cljc`

Update sampling handler to pass tools:

```clojure
(defn sampling-create-message-handler
  [{:keys [session message]
    :as context}]
  (let [{:keys [messages maxTokens tools toolChoice 
                systemPrompt includeContext temperature 
                stopSequences metadata]} (:params message)]
    (if-some [on-sampling-requested (:on-sampling-requested @session)]
      ;; Pass all params including new tools/toolChoice
      (on-sampling-requested context 
                             {:messages messages
                              :max-tokens maxTokens
                              :tools tools           ;; NEW
                              :tool-choice toolChoice ;; NEW
                              :system-prompt systemPrompt
                              :include-context includeContext
                              :temperature temperature
                              :stop-sequences stopSequences
                              :metadata metadata})
      (json-rpc/method-not-found-response (:id message)))))
```

### Example Usage

```clojure
;; Server requesting sampling with tools
(server/request-sampling context
  {:messages [{:role "user" 
               :content {:type "text" 
                         :text "Search for recent news about AI"}}]
   :maxTokens 1000
   :tools [{:name "web_search"
            :description "Search the web"
            :inputSchema {:type "object"
                          :properties {:query {:type "string"}}
                          :required [:query]}}]
   :toolChoice {:type "auto"}})
```

---

## Phase 6: URL Mode Elicitation

**Priority:** Medium  
**Complexity:** Moderate  
**Estimated Time:** 3 hours

### Specification (SEP-1036)

Servers can now request URL-based user interactions:

```typescript
interface ElicitRequest {
  message: string;
  requestedSchema?: ObjectSchema;  // Form-based
  // OR
  url?: string;                     // NEW: URL-based
  urlMessage?: string;              // NEW: Message for URL mode
}

interface ElicitResult {
  action: "accept" | "decline" | "url_completed";  // url_completed is NEW
  content?: object;  // For form-based
  // URL mode has no content - completion is signaled by action
}
```

### Changes Required

#### File: `src/mcp_toolkit/server.cljc`

Add URL elicitation support:

```clojure
(defn request-elicitation
  "Requests user input via form or URL redirect.
   
   For form-based elicitation:
     {:message \"Please provide details\"
      :requestedSchema {:type \"object\" :properties {...}}}
   
   For URL-based elicitation (2025-11-25+):
     {:url \"https://auth.example.com/oauth/authorize?...\"
      :urlMessage \"Complete authentication in your browser\"}
   
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
     :urlMessage (or message "Please complete the action in your browser")}))
```

#### File: `src/mcp_toolkit/impl/client/handler.cljc`

Handle URL elicitation:

```clojure
(defn elicitation-create-handler
  [{:keys [session message]
    :as context}]
  (let [{:keys [message requestedSchema url urlMessage]} (:params message)]
    (if-some [on-elicitation-requested (:on-elicitation-requested @session)]
      (if url
        ;; URL mode elicitation (2025-11-25)
        (on-elicitation-requested context
          {:type :url
           :url url
           :message urlMessage})
        ;; Form mode elicitation
        (on-elicitation-requested context
          {:type :form
           :message message
           :schema requestedSchema}))
      (json-rpc/method-not-found-response (:id message)))))
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
                    "url_completed" {:content [{:type "text" 
                                                :text "Authentication successful!"}]}
                    "decline" {:content [{:type "text" 
                                          :text "Authentication cancelled"}]
                               :isError true}))))))
```

---

## Phase 7: Tasks Support (Experimental)

**Priority:** High  
**Complexity:** Complex  
**Estimated Time:** 8 hours

### Specification (SEP-1686)

Tasks provide tracking for long-running operations:

```typescript
// Task states
type TaskState = "working" | "input_required" | "completed" | "failed" | "cancelled";

interface Task {
  id: string;
  state: TaskState;
  message?: string;
  progress?: {
    current: number;
    total?: number;
  };
}

// New methods
"tasks/status"  - Get task status
"tasks/result"  - Get task result (when completed)
"tasks/cancel"  - Cancel a task

// New notification
"notifications/tasks/progress" - Task progress updates
```

### New File: `src/mcp_toolkit/impl/tasks.cljc`

```clojure
(ns ^:no-doc mcp-toolkit.impl.tasks
  "Experimental task support for MCP 2025-11-25.
   
   Tasks enable tracking of long-running operations with:
   - Status polling
   - Progress notifications  
   - Cancellation
   - Deferred result retrieval"
  (:require [promesa.core :as p]))

(def task-states
  "Valid task states."
  #{:working :input_required :completed :failed :cancelled})

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

### File: `src/mcp_toolkit/impl/server/handler.cljc`

Add task handlers:

```clojure
;; Add to handler-by-method-post-initialization
(def task-handlers
  {"tasks/status" task-status-handler
   "tasks/result" task-result-handler
   "tasks/cancel" task-cancel-handler})

(defn task-status-handler
  [{:keys [session message]}]
  (let [task-id (-> message :params :taskId)]
    (if-let [status (tasks/get-task-status session task-id)]
      status
      (json-rpc/error-response (:id message) -32002 "Task not found"))))

(defn task-result-handler
  [{:keys [session message]}]
  (let [task-id (-> message :params :taskId)]
    (tasks/get-task-result session task-id)))

(defn task-cancel-handler
  [{:keys [session message]}]
  (let [task-id (-> message :params :taskId)]
    (tasks/cancel-task session task-id)
    {}))
```

### File: `src/mcp_toolkit/server.cljc`

Add task utilities:

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
      {:taskId task-id
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
   :inputSchema {:type "object"
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

### Changes

1. **OIDC Discovery Support** (SEP-797)
   - Authorization server discovery via `/.well-known/openid-configuration`

2. **Client ID Metadata Documents** (SEP-991)
   - New client registration mechanism via CIMD

3. **Incremental Scope Consent** (SEP-835)
   - Enhanced `WWW-Authenticate` header handling

### Implementation Notes

OAuth changes primarily affect:
- HTTP transport layer
- Authorization middleware
- Client registration flows

These are more relevant for Streamable HTTP transport implementations. For STDIO-based servers, these changes have minimal impact.

### New File: `src/mcp_toolkit/auth/oidc.cljc` (Optional)

```clojure
(ns mcp-toolkit.auth.oidc
  "OpenID Connect Discovery support for MCP 2025-11-25."
  (:require [promesa.core :as p]))

(defn discover-oidc-config
  "Fetches OIDC configuration from issuer.
   
   Args:
     issuer - The OIDC issuer URL
   
   Returns:
     Promise resolving to OIDC configuration map."
  [issuer]
  ;; Implementation depends on HTTP client
  (p/resolved nil))

(defn discover-authorization-server
  "Discovers authorization server from protected resource metadata.
   
   Checks in order:
   1. WWW-Authenticate header
   2. /.well-known/oauth-protected-resource
   3. /.well-known/openid-configuration"
  [resource-url & {:keys [www-authenticate]}]
  ;; Implementation
  )
```

---

## Phase 9: Minor Clarifications

**Priority:** Low  
**Complexity:** Simple  
**Estimated Time:** 1 hour

### Tool Input Validation Errors

Return validation errors as tool execution errors, not protocol errors:

```clojure
;; In tool-call-handler
(defn tool-call-handler
  [{:keys [session message] :as context}]
  (let [{:keys [name arguments]} (:params message)]
    (if-some [tool (-> @session :tool-by-name (get name))]
      (let [;; Validate input against schema
            validation-error (validate-input arguments (:inputSchema tool))]
        (if validation-error
          ;; Return as tool execution error, NOT protocol error
          {:content [{:type "text"
                      :text (str "Validation error: " validation-error)}]
           :isError true}
          ;; Proceed with tool execution
          ((:tool-fn tool) context arguments)))
      (json-rpc/invalid-tool-name (:id message) name))))
```

### JSON Schema 2020-12

Add dialect declaration to schemas:

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
            [mcp-toolkit.impl.tasks :as tasks]))

(deftest icons-test
  (testing "Tools include icon in list response"
    (let [session (atom (server/create-session
                          {:tools [{:name "test"
                                    :icon "data:image/svg+xml;base64,..."
                                    :inputSchema {}
                                    :tool-fn identity}]}))]
      ;; Test icon is preserved in listing
      )))

(deftest tasks-test
  (testing "Task lifecycle"
    (let [session (atom {})]
      (let [task (tasks/create-task "task-1" :message "Starting")]
        (is (= :working (:state task)))
        
        (tasks/update-task-state session "task-1" :completed 
          :result {:data "done"})
        (is (= :completed (:state (tasks/get-task-status session "task-1"))))))))

(deftest url-elicitation-test
  (testing "URL elicitation request format"
    ;; Test URL elicitation params
    ))

(deftest sampling-with-tools-test
  (testing "Sampling request includes tools"
    ;; Test tools in sampling request
    ))

(deftest enum-schema-test
  (testing "Titled enum schema"
    ;; Test enum with titles
    )
  (testing "Multi-select enum"
    ;; Test multi-select enum
    ))
```

### Integration Tests

Test against MCP Inspector:

```bash
# Start server with 2025-11-25 support
clojure -X:mcp-server

# Test with MCP Inspector
npx @modelcontextprotocol/inspector clojure -X:mcp-server
```

---

## Migration Checklist

### Phase 1: Protocol Version
- [ ] Add "2025-11-25" to supported versions
- [ ] Update default client protocol version
- [ ] Test version negotiation

### Phase 2: Description Field
- [ ] Update `create-session` to accept description
- [ ] Verify description in initialize response
- [ ] Add documentation

### Phase 3: Icons
- [ ] Add icon to tool-list-handler
- [ ] Add icon to prompt-list-handler
- [ ] Add icon to resource-list-handler
- [ ] Add icon to resource-templates-list-handler
- [ ] Create icon utility functions (optional)
- [ ] Add tests
- [ ] Add documentation

### Phase 4: EnumSchema
- [ ] Create elicitation schema helpers
- [ ] Add enumTitles support
- [ ] Add multiSelect support
- [ ] Add default value support
- [ ] Add validation
- [ ] Add tests

### Phase 5: Sampling with Tools
- [ ] Update request-sampling to accept tools
- [ ] Update request-sampling to accept toolChoice
- [ ] Feature-detect client support
- [ ] Update client sampling handler
- [ ] Add tests
- [ ] Add documentation

### Phase 6: URL Elicitation
- [ ] Add request-url-elicitation function
- [ ] Update elicitation handler for URL mode
- [ ] Handle "url_completed" action
- [ ] Add tests
- [ ] Add documentation

### Phase 7: Tasks (Experimental)
- [ ] Create tasks.cljc namespace
- [ ] Implement task lifecycle (create, update, complete, fail, cancel)
- [ ] Add tasks/status handler
- [ ] Add tasks/result handler
- [ ] Add tasks/cancel handler
- [ ] Add notifications/tasks/progress
- [ ] Integrate with tool responses
- [ ] Add cleanup mechanism
- [ ] Add comprehensive tests
- [ ] Add documentation
- [ ] Mark as experimental in docs

### Phase 8: OAuth Enhancements
- [ ] Review OIDC discovery requirements
- [ ] Review CIMD requirements
- [ ] Document OAuth changes
- [ ] (Implementation as needed for HTTP transport)

### Phase 9: Minor Clarifications
- [ ] Update tool validation error handling
- [ ] Add JSON Schema 2020-12 dialect support
- [ ] Review and update documentation

### Final Steps
- [ ] Update README.md with 2025-11-25 support
- [ ] Update CHANGELOG.md
- [ ] Update version in deps.edn
- [ ] Run full test suite
- [ ] Test with Claude Desktop
- [ ] Test with Claude Code
- [ ] Create release

---

## References

- [MCP Specification 2025-11-25](https://modelcontextprotocol.io/specification/2025-11-25)
- [Key Changes](https://modelcontextprotocol.io/specification/2025-11-25/changelog)
- [TypeScript Schema](https://github.com/modelcontextprotocol/specification/blob/main/schema/2025-11-25/schema.ts)
- [MCP Blog: First Anniversary Release](https://blog.modelcontextprotocol.io/posts/2025-11-25-first-mcp-anniversary/)
