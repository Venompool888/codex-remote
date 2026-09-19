# Follow-up Android lifecycle audit

Scope: the conversation Stop/send state, lifecycle event ordering, and local diagnostic
collection following the stale Stop report. This is a bounded audit, not a claim that
all application defects have been eliminated.

Confirmed failure sequences:

1. Cold-open an already active task. The snapshot can make the composer show Stop,
   but no live start event has populated the cache. Interrupt looked only in that
   cache and kept refreshing instead of sending the snapshot's active turn ID.
2. Start turn B before a delayed completion for A arrives. The old completion
   unconditionally cleared the current composer running state.
3. Request interruption, receive an active status notification, then receive an
   interrupt error. A global revision check prevented restoring the same live turn,
   leaving a Stop button whose cached turn was marked cancelled.
4. Receive an item or turn completion followed by a late delta/start/patch. Item
   activity could be re-enabled; an ignored canonical delta could also enter the
   legacy assistant-text fallback and reactivate the composer.
5. Corrupt the recent diagnostic task metadata JSON. Every future capture attempted
   to parse it before replacing it, permanently blocking new state collection until
   the user manually cleared logs. The new failure/recovery test confirms collection
   resumes, and recovery is explicitly recorded without logging malformed contents.

Each lifecycle fix must preserve newer running turns, keep terminal evidence
monotonic, and distinguish an interrupt request from a confirmed terminal event.
Tests use synthetic local sockets/data; no private recordings or task content are
sent to external services. Destructive Android fixtures run on a disposable emulator.

Validation of the frozen changes:

- Full Android unit suite: 306 tests, zero failures or errors.
- Disposable emulator: all five TurnRunningDeviceTest and four DiagnosticLogDeviceTest
  cases passed. Cold-open coverage checks the actual synthetic WebSocket interrupt
  request, then checks error recovery; event ordering is exercised through MainActivity.
- The diagnostic corruption regression failed before its fix and passes after recovery.
- Compose source gate and all seven gate tests passed.
- Debug, Release and instrumentation APKs built successfully; release manifest checks passed.
- The connected Pixel 10's existing pinned Debug package was updated and launched using
  the preserving-connections script. All four connection preference files were unchanged.
- Pixel 11 Release remains unmodified; no private logs or project content were uploaded.
- Debug and Release Lint both passed with zero errors on the frozen sources.
