# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),

Versions prior to v0.1.0 are considered experimental, their API may change.

## [v2025-11-25] - Unreleased

### Added
- **MCP Protocol 2025-11-25 Support** - Full implementation of the latest specification
  - **Elicitation** - Request additional information from users
    - Form mode with JSON Schema-based forms
    - URL mode for OAuth flows and external authentication
    - `request-elicitation` function supporting both modes
    - `notify-elicitation-complete` for completion notifications
    - Capability detection: `client-supports-elicitation?`, `client-supports-url-elicitation?`, `client-supports-form-elicitation?`
  - **Tasks (Experimental)** - Durable state machines for long-running operations
    - Full schema support for task lifecycle states
    - `request-task-get`, `request-task-result`, `request-task-cancel`, `request-tasks-list`
    - `notify-task-status` for status change notifications
    - Capability detection for task-augmented sampling and elicitation
  - **Sampling with tools** - LLMs can use tools during sampling requests
    - `client-supports-sampling-tools?` capability detection
    - Extended `SamplingRequest` schema with tool-related fields
  - **Icons** - Visual icons for prompts, resources, tools, and templates
    - Icon schema validation (data:image/ URIs or https:// URLs)
    - Support in prompts, resources, tools, and resource templates
  - **Server description** - Human-readable server descriptions
    - `:description` field in `server-info` during initialization
  - **JSON Schema 2020-12 dialect**
    - `JSON_SCHEMA_DIALECT` constant
    - `with-schema-dialect` helper function

- **Malli Schema Validation** (`mcp-toolkit.schema` namespace)
  - Comprehensive schemas for all MCP protocol types
  - Helper constructors for common patterns
  - `valid?`, `validate!`, `explain` functions for validation

### Changed
- **Internal naming convention** - All internal keys now use kebab-case
  - Wire format (camelCase) automatically converted at transport layer
  - Example: `:maxTokens` → `:max-tokens`, `:inputSchema` → `:input-schema`
- Protocol version updated to `2025-11-25` as default

### Fixed
- Variable shadowing bug in `completion-complete-handler`
- Missing else branches in `json_rpc.cljc`
- Unused binding warnings throughout codebase

### Documentation
- Added comprehensive migration guide (docs/reference/MIGRATION-2025-11-25.md)
- Added migration checklist (docs/reference/archive/MIGRATION-2025-11-25-CHECKLIST.md)
- Updated README with 2025-11-25 protocol features

---

## [v0.1.1-alpha] - 2025-07-03

### Fixed

- Server API: completion functions for prompts are now optional.
- Server API: completion functions for resource URIs are now optional.
- Server API: `:resource-templates` is now optional in the session.
- Server API: A promise bug when a response from an MCP client contained an error.
- Server API: The default log level, if unspecified, is now "debug".

### Changed

- `json-rpc/handle-message`'s method signature was changed from `[context]` to `[context message]`.

## [v0.1.0-alpha] - 2025-07-01

### Added

- First release 🎉
