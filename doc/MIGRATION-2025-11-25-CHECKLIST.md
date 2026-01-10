# MCP 2025-11-25 Migration Checklist

**Quick Reference Checklist for Migration**

## Status Legend
- ⬜ Not started
- 🟡 In progress
- ✅ Complete
- ⏭️ Skipped (not applicable)

---

## Phase 0: Key Transformation (Est: 3 hours) ✅
- ✅ Use `csk/->kebab-case-keyword` at transport decode (camelCase → kebab-case)
- ✅ Use `csk/->camelCaseString` at transport encode (kebab-case → camelCase)
- ✅ Updated example transports: stdio (CLJ/CLJS), SSE
- ✅ Updated all handlers to use kebab-case keys
- ✅ Updated client.cljc, server.cljc, impl/server/handler.cljc
- ⏭️ No separate keys namespace needed - using csk directly at boundary

## Phase 1: Protocol Version (Est: 1 hour) ✅
- ✅ Add `"2025-11-25"` to `supported-protocol-versions` in `server.cljc`
- ✅ Update default `protocol-version` to `"2025-11-25"` in `client.cljc`
- ✅ Test version negotiation with 2025-11-25 client
- ✅ Test fallback to 2025-03-26 for older clients
- ✅ Update documentation links to 2025-11-25 spec

## Phase 2: Description Field (Est: 30 min) ✅
- ✅ Allow `:description` in `server-info` map (already worked, just documented)
- ✅ Verify description passes through in `initialize` response
- ✅ Update docstrings for `create-session`
- ✅ Add test for server-info description field

## Phase 3: Icons Support (Est: 2 hours) ✅
- ✅ `tool-list-handler` - include `:icon` if present
- ✅ `prompt-list-handler` - include `:icon` if present
- ✅ `resource-list-handler` - include `:icon` if present
- ✅ `resource-templates-list-handler` - include `:icon` if present
- ⏭️ Create `mcp-toolkit.util.icons` namespace (optional) - not needed, icons pass through
- ✅ Add unit tests for icon serialization
- ✅ Document icon format (data URI / HTTPS URL) in commit

## Phase 4: EnumSchema Updates (Est: 2 hours) ✅
- ✅ Create `mcp-toolkit.schema` namespace (with Malli)
- ✅ Implement `enum-schema` helper function
- ✅ Support `:enum-titles` field
- ✅ Support `:multi-select` field
- ✅ Support `:default` field for enums
- ✅ Implement schema validation with Malli
- ✅ Add Icon schema validation
- ✅ Add comprehensive unit tests

## Phase 5: Sampling with Tools (Est: 3 hours) ✅
- ✅ Add Malli schemas for sampling types (ToolChoice, SamplingTool, ToolUseContent, etc.)
- ✅ Add helper constructors (tool-choice, sampling-tool, tool-result, tool-result-message)
- ✅ Update `request-sampling` docstring with full parameter documentation
- ✅ Add `client-supports-sampling-tools?` capability check
- ✅ Add comprehensive unit tests for all schemas and helpers
- ✅ Document sampling with tools flow and examples

## Phase 6: URL Mode Elicitation (Est: 3 hours)
- ⬜ Add `request-url-elicitation` function to `server.cljc`
- ⬜ Update `request-elicitation` to support URL params
- ⬜ Update `elicitation-create-handler` for URL mode
- ⬜ Handle `"url-completed"` action in results
- ⬜ Add unit tests
- ⬜ Document URL elicitation flow

## Phase 7: Tasks Support - Experimental (Est: 8 hours)
- ⬜ Create `mcp-toolkit.impl.tasks` namespace
  - ⬜ `create-task`
  - ⬜ `update-task-state`
  - ⬜ `complete-task`
  - ⬜ `fail-task`
  - ⬜ `cancel-task`
  - ⬜ `get-task-status`
  - ⬜ `get-task-result`
  - ⬜ `cleanup-old-tasks`
- ⬜ Add task handlers in `server/handler.cljc`
  - ⬜ `tasks/status` handler
  - ⬜ `tasks/result` handler
  - ⬜ `tasks/cancel` handler
- ⬜ Add task notifications
  - ⬜ `notifications/tasks/progress`
- ⬜ Add task functions to `server.cljc`
  - ⬜ `create-task`
  - ⬜ `notify-task-progress`
  - ⬜ `complete-task`
  - ⬜ `fail-task`
- ⬜ Initialize `:tasks-by-id` in session
- ⬜ Add task capability in initialize response
- ⬜ Add comprehensive unit tests
- ⬜ Add integration tests with long-running tools
- ⬜ Mark as experimental in documentation

## Phase 8: OAuth Enhancements (Est: 4 hours) - Optional
- ⬜ Review OIDC Discovery requirements
- ⬜ Review Client ID Metadata Documents (CIMD)
- ⬜ Review incremental scope consent
- ⬜ Create `mcp-toolkit.auth.oidc` namespace (if needed)
- ⬜ Document OAuth changes

## Phase 9: Minor Clarifications (Est: 1 hour)
- ⬜ Return input validation errors as Tool Execution Errors
- ⬜ Add JSON Schema 2020-12 dialect constant
- ⬜ Add `with-schema-dialect` helper function
- ⬜ Review stderr logging guidance (stdio transport)

---

## Final Steps
- ⬜ Update `README.md` - add 2025-11-25 to supported versions
- ⬜ Update `CHANGELOG.md` - document all changes
- ⬜ Update version in `deps.edn` (e.g., `0.2.0-alpha`)
- ⬜ Run full test suite: `./bin/kaocha`
- ⬜ Test with MCP Inspector
- ⬜ Test with Claude Desktop
- ⬜ Test with Claude Code
- ⬜ Update cljdoc configuration if needed
- ⬜ Create git tag for release
- ⬜ Deploy to Clojars

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
| `multiSelect` | `:multi-select` |
| `enumTitles` | `:enum-titles` |
| `taskId` | `:task-id` |

---

## Files to Create/Modify

### New Files
| File | Phase | Description |
|------|-------|-------------|
| `src/mcp_toolkit/impl/tasks.cljc` | 7 | Task management |
| `src/mcp_toolkit/schema.cljc` | 4 | Malli schemas and validation ✅ |
| `src/mcp_toolkit/util/icons.cljc` | 3 | Icon utilities (optional) |
| `test/mcp_toolkit/schema_test.cljc` | 4 | Schema tests ✅ |
| `test/mcp_toolkit/protocol_2025_11_25_test.cljc` | All | New protocol tests |

### Files Modified (Phase 0) ✅
| File | Changes |
|------|---------|
| `src/mcp_toolkit/client.cljc` | kebab-case keys |
| `src/mcp_toolkit/server.cljc` | kebab-case keys |
| `src/mcp_toolkit/impl/server/handler.cljc` | kebab-case keys |
| `example/*/transport` | csk key transformation |
| `example/common-mcp-content` | kebab-case keys |

---

## Testing Commands

```bash
# Run all tests
./bin/kaocha

# Run tests in watch mode
./bin/kaocha --watch

# Run specific test namespace
./bin/kaocha --focus mcp-toolkit.protocol-2025-11-25-test

# Test with MCP Inspector
npx @modelcontextprotocol/inspector clojure -X:mcp-server
```

---

## Notes

- Tasks are **experimental** - mark clearly in docs
- All new features should be **backward compatible**
- Icon validation: must be `data:image/` or `https://`
- OAuth changes mainly affect HTTP transport users
- Use keyword arguments for functions where it improves clarity
