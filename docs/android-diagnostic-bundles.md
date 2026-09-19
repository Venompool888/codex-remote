# Export diagnostic bundles from Android

Open a conversation's top-right overflow menu and select **Export logs**.
Choose **Save ZIP** to use Android's document picker, or **Share ZIP** to choose
a recipient app. Export works offline and without USB. Choose device storage in
the picker for a local copy; cloud-backed providers and sharing destinations are
chosen by the user. The app never uploads the bundle automatically.

The ZIP contains:

- `diagnostic-log.txt`: the bounded client operation log, up to two 256 KiB files.
- `app-state.json`: app/build version, Android version/model, connection/composer
  state, and counts of pending requests/submissions. No device identifier.
- `recent-threads.json`: last recorded state of up to 20 recently viewed tasks,
  retained across restarts, with up to 12 snapshot and 12 live turns per task.
  Includes server origin, task/turn IDs, status, item counts and capture time.
- `README.txt`: collection scope and limitations.

The exporter uses an explicit metadata allowlist. It does not copy task titles,
conversation text, drafts, attachment files, preferences or credentials. Known
credentials are redacted before recording. Logs can still contain host names,
task IDs, error text and paths; review a saved bundle before sending it elsewhere.
This is client evidence, not Android logcat or server-side logs. Old rotated logs,
events before collection was installed and tasks never visited are unavailable.

The existing **Diagnostics → Diagnostic log** viewer remains available. Its Share
button opens the same export options. Clear removes the local log, recent task
metadata and cached exports. Subsequent activity can record new state. Copies
already saved or shared are outside the app's control.

Metadata uses the existing ordered diagnostic worker and an atomic file replacement.
At most four immutable ZIP snapshots are retained in app cache, exposed only via the
private FileProvider with temporary read grants. The save picker survives Activity
recreation by retaining the pending cache filename. Export never reads credential
or preference files into the archive.

Validation on 2026-09-19: full Android unit suite (303 tests), Debug/Release and
instrumentation APK builds, Compose source gate and seven gate tests, release
manifest checks, and all four DiagnosticLogDeviceTest cases on a disposable emulator.
The device tests cover the direct menu callback, local save/explicit share choices,
log viewer actions, readable ZIP content through FileProvider, and clear cleanup.
A first menu test used text-only lookup for an icon's accessibility description;
correcting the lookup made the rerun pass.

Final Debug and Release Lint both passed with zero errors after a serial rerun; the
first run hit Lint's internal Kotlin parser exception while test sources were changing.
The connected Pixel 10's existing pinned Debug package was updated with the preserving-
connections script and launched; all four connection preference files were unchanged.
The Pixel 11 Release installation was not changed. The local Release build is unsigned.

Malformed recent-task JSON is replaced on the next recorded state instead of blocking
all future records. Recovery emits a fixed diagnostic event and a missing-entry warning;
the malformed contents are never copied to logs. Filesystem read/write failures still
report failure rather than silently claiming successful recovery. A regression starts
with malformed metadata, records two new tasks, and reads the recovered file after
restart; the provider device test checks recovery and the exported warning together.
