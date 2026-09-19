# Recovering a stale Stop button

A Release user reported a completed response with the composer still showing Stop;
pressing it returned `no active turn to interrupt (-32600)`. The screenshot proves
UI/server disagreement, but without that device's logs it does not establish which
event was lost or the exact ordering that triggered it.

Code inspection found that a refreshed `thread/read` replaced the timeline without
recomputing the composer running state. A cached live `inProgress` turn could also
outlive a missed completion event and override later idle history. Previously covered
start-event/stale-idle behavior did not cover terminal snapshot recovery.

The client now reconciles terminal turn statuses and idle thread snapshots into its
live cache, recomputes the composer, and refreshes after a confirmed no-active-turn
interrupt error. A lifecycle revision prevents old resume/read results from overriding
newer turn events. Interrupt callbacks are scoped to navigation, host, and credential;
a later error must not resurrect a completed turn or stop a newer one. Live tool items
are preserved when server history omits them. Assistant message text alone is never
used to conclude that a turn finished.

Regression coverage includes missed completion recovery, stale snapshots after a new
turn, delayed start after completion, no-active-turn recovery without settling another
turn, and real Activity snapshot/composer state on a disposable emulator.

The direct **Export logs** menu now captures the state needed to investigate future
reports without USB; see [diagnostic bundle documentation](android-diagnostic-bundles.md).
