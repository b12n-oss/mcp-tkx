# MCP Toolkit Upgrade Checklist: 2025-03-26 → 2025-06-18

## Phase 1: Protocol Version Updates
- [ ] Add "2025-06-18" to server-supported-protocol-versions
- [ ] Update default client protocol-version to "2025-06-18"
- [ ] Update deps.edn version to indicate 2025-06-18 support

## Phase 2: Capability Changes
- [ ] Review and update server capabilities in initialize-handler
- [ ] Review and update client capabilities in create-session
- [ ] Check for new capability fields or structures

## Phase 3: Message Format Updates
- [ ] Review all message handlers for format changes
- [ ] Update resource-related messages (list, read, subscribe)
- [ ] Update tool-related messages (list, call)
- [ ] Update prompt-related messages (list, get)
- [ ] Update completion messages
- [ ] Update progress notification format
- [ ] Update logging message format

## Phase 4: New Features
- [ ] Check for new message types in 2025-06-18
- [ ] Implement any new required handlers
- [ ] Add support for new optional features

## Phase 5: Error Handling
- [ ] Review error codes for changes
- [ ] Update error response formats if needed
- [ ] Add new error types if introduced

## Phase 6: Testing
- [ ] Update handshake tests for new version
- [ ] Test backward compatibility with 2025-03-26
- [ ] Test forward compatibility with 2025-06-18
- [ ] Update example projects to demonstrate new features
- [ ] Test with MCP Inspector and Claude Desktop

## Phase 7: Documentation
- [ ] Update README.md compatibility notes
- [ ] Update CHANGELOG.md with 2025-06-18 support
- [ ] Update example configurations
- [ ] Update API documentation

## Phase 8: Breaking Changes
- [ ] Identify any breaking changes in 2025-06-18
- [ ] Update migration guide
- [ ] Consider deprecation warnings for removed features
