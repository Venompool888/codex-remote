# Connection deletion

Implemented on `codex/delete-connections`, based on the current `main` checkout.
The separate Compose migration worktree has existing uncommitted changes and
is intentionally not part of this change.

AGY CLI owns the connection manager frontend; Codex owns device storage,
credential cleanup, transport lifecycle integration and independent tests.

Deletion is device-local and works offline. It removes all saved projects for
each selected host plus that host's pairing credential and navigation entry.
It does not delete host files or chats or revoke credentials on the remote host.
Connecting again requires pairing again. Clearing matching legacy settings
prevents startup migration from resurrecting the last deleted connection.

The existing monitor service observes project and credential preferences and
reconciles its transports after deletion. Foreground transports are removed
before closing; queued events from an obsolete transport are ignored.

Run JVM coverage with `./gradlew :app:testDebugUnitTest` inside `android`.
The `ConnectionDeletionDeviceTest` instrumentation fixture clears local test
preferences and must run only on the disposable `Codex_Deletion_QA` emulator,
never on an existing paired client. It uses `.invalid` fixture hosts and sends
no requests to real hosts.

## Validation

- 155 JVM tests passed, with zero failures, errors or skipped tests.
- Debug APK, instrumentation APK, Release APK and `lintDebug` passed.
- Four independent emulator tests cover encrypted credential isolation and
  legacy cleanup; deleting the active host and ignoring its stale transport
  callbacks; cancelling the popup and batch deletion followed by relaunch;
  deselecting, Back, and single deletion preserving the other host.
- Visual review caught fragmented selection-count text at 2x font. AGY moved
  count and actions into separate rows, and the UI test now asserts that the
  count is not fragmented at that font size.
- All four device tests passed again on the final layout in dark mode at 2x
  font. Screenshots and execution logs are in `qa/connection-deletion-20260911`.

The APK is built locally. This change does not update any physical device or
the separate Compose migration checkout.
