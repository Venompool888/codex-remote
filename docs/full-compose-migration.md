# Full Compose UI migration — 0.3.0

The 0.2.0 unified main (`0b964c7`) was the baseline. Version 0.3.0 replaces all
app-owned native UI with Compose and continues the unified main development line.
The backend, protocol and persistence layers remain in use; UI migration does not
require rewriting these layers.

## Feature ownership

Use [the Android feature map](../android/ARCHITECTURE.md) to locate changes. UI and
presentation controllers are grouped into connections, projects, sidebar,
conversation, composer, catalog, interactions and artifacts. Theme and generic
runtime prompts have their own small modules. `ui/CodexApp.kt` mounts the features.
MainActivity remains the Android/runtime coordinator, reduced from approximately
6,140 lines to 2,112; it no longer constructs native layouts or dialogs.

The production source gate reports **zero native UI or interoperability references**.
Fourteen old rendering files were removed after replacement. Platform permission
prompts, photo/document pickers, share sheets, notifications and browser handoff are
Android services, not app-owned View screens. `scripts/release-check.sh` runs the
Compose gate and its regression tests, including in CI.

## Preserved behavior and migration fixes

- Connections retain package-specific stores, existing credentials and original
  client-identity checks. Pairing/settings, credential rotation/revocation, long
  press selection, select all and confirmed single/batch local deletion use the
  original runtime cleanup. Updates do not seed or replace connection preferences.
- Projects/sidebar retain host/workspace scope, validation before saving, folder
  browsing, task search and paging. Back clears project scope before leaving;
  accumulated drag distance supports slow swipes.
- Conversation UI includes Markdown links, ordered/bullet lists, quotes, tables,
  syntax highlighting, exact source copying, long-message disclosure, attachments,
  tool output/progress, file diffs, image previews and fullscreen zoom. History
  scrolling remains stable during streaming; jump to latest restores following.
- Composer uses `TextFieldValue` to preserve cursor/selection/IME composition.
  Drafts, upload ownership, send acknowledgements, model/effort/speed and permission
  metadata retain their existing runtime paths. An old send acknowledgement cannot
  clear a newer draft. Pending permission choices close when task identity changes.
- Interactive forms share `InteractionFormModel` with the existing protocol reply
  format. Busy forms disable edits; rejected/cancelled replies remain recoverable;
  ordinary drafts restore with device/request identity while secrets stay unsaved.
- Catalog and artifact controllers reject obsolete request generations and task
  scopes. The downloader emits presentation events while retaining verification,
  cancellation and scoped Open/Share actions. Image loading uses the captured
  server/task/credential source through `RemoteImageRepository`.

Compose dependencies use one BOM for compile/runtime alignment. Device execution
caught and resolved a FlowRow ABI mismatch that compilation alone did not expose.

## Validation

Local build and emulator evidence is under
`/private/tmp/codex-full-compose-20260911`.

- 217 Android unit tests passed; 122 backend tests passed.
- All 67 offline instrumentation cases across 43 classes passed, including the
  cancellation-fixture correction and its rerun. The baseline run is `full-device`;
  `large-font-final` and `form-large-font-final` contain follow-up evidence.
- Twelve feature classes were additionally exercised at 2× font size, including
  keyboard reachability, form validation/rejection, RTL attachment reflow, scroll
  retention, approvals, code copying, image zoom and sidebar drag. Long forms scroll
  to reveal controls; project submission errors bring their message into view.
- Debug/release builds and lint passed (zero errors; pre-existing/resource/style
  warnings remain). Release manifest checks passed.
- The zero-native-UI gate, its seven regression tests and all seven update-script
  preservation tests passed.

Device evidence comes from the disposable API 36 emulator. It does not establish
real-host authorization/integration performance or physical-device performance.

The device suite excludes three environment-dependent helpers:
`ClipboardInputDeviceTest`, `RemoteSkillSearchDeviceTest` and
`PublicAuthenticationDeviceTest`. Their manual/live-host/no-op paths are not counted
as validation. No real host credential was used for this UI acceptance, and no
physical phone was installed, cleared or reconfigured during the migration.

agy supplied UI modules in an isolated worktree; the main agent reviewed and
integrated them, connected the existing runtime, fixed parity issues and ran all
verification. agy did not claim a verified build. Task-only agy permissions were
removed after delivery.
