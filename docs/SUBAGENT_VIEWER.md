# Read-only subagent inspection

Codex Remote retains child references from app-server `collabAgentToolCall`
(`receiverThreadIds`, `agentsStates`, `model`) and `subAgentActivity`
(`agentThreadId`, `agentPath`). The conversation exposes a Subagents list; selecting
an entry opens a read-only inspector while retaining the parent conversation.
References are collected recursively through grouped timeline items, deduplicated,
and updated from later agent states. Tool completion is not child completion.

The inspector uses `thread/read` with `includeTurns: true`, never `thread/resume`,
`turn/start`, or a configuration write. It refreshes while visible. Missing or denied
threads show a retry state. Closing or changing the selected child invalidates pending
responses; parent task/connection changes close the inspector. Runtime callbacks
also check the captured navigation generation, connection and credential.

## Upstream reference

The installed Codex CLI 0.145.0 protocol was inspected using
`codex app-server generate-ts --experimental`. Upstream sources inspected:

- https://github.com/openai/codex/blob/main/codex-rs/tui/src/app/agent_picker.rs
  — the CLI `/agent` picker discovers descendant subagent threads and switches views.
- https://github.com/openai/codex/blob/main/codex-rs/app-server/src/request_processors/thread_processor.rs
  — `thread/read` reads live or persisted thread history without resuming it.
- https://developers.openai.com/zh-Hans/docs/app-server
  — app-server is a JSON-RPC interface; clients provide the visual surfaces.

This is a mobile inspector, not an implementation of the desktop application's
private UI. It does not change subagent execution, models, tools, or permissions.

## Security and limits

The existing host authorizes paired devices by RPC scopes (`rpc:read` includes
`thread/read`), not by independent per-thread/workspace ACLs. This feature retains
that boundary. It does not make child threads a new security sandbox.

The inspector shows textual messages and tool details, without automatically
loading child images or following external links. Status is the last reported
state; an idle thread is not necessarily a successfully completed subagent.

References require structured child IDs from the upstream protocol. Older/custom
unstructured tool transcripts may not supply these IDs. The desktop-owner bridge
only supplies its live snapshot for discovered desktop-owned threads; otherwise
`thread/read` uses the ordinary app-server live/persisted history path. Availability
therefore depends on the connected host having the child history.

## Validation (2026-09-19)

- All 312 Android unit tests passed, including child reference projection, grouped
  history/live preservation, request coalescing, late/wrong-thread responses,
  retry, and parent navigation/disconnection invalidation.
- Debug APK, Debug instrumentation APK and Release APK assembled successfully.
- Debug and Release lint passed; Compose-only gate and its seven script tests passed.
- Disposable emulator instrumentation passed: expand Subagents, open a child,
  recover via Retry, inspect a running child's message and command output, return
  to the parent, and verify polling has stopped. Synthetic screenshot reviewed.
- Pixel 10: updated the existing pinned Debug app through the preserving-connections
  script; all four checked preference files were unchanged, and the same package
  launched successfully. The current real task had no child references, so live
  subagent-history viewing on that device was not verified.

Frontend implementation and focused corrections were produced by AGY from an
explicitly approved minimal isolated handoff; Codex reviewed the diff, integrated,
compiled and validated it. Temporary handoff permissions were removed.
