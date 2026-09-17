# Unified development baseline — 2026-09-11

`main` is the shared feature baseline. The previous Compose and connection-deletion
branches diverged from `2e090fb`; neither was a superset. Preserve their history and
fast-forward both to the validated unified revision instead of maintaining parallel products.
The existing main project checkout remains the canonical development directory.
Remote history was previously sanitized and rewritten. The unified source is applied
on top of that history; the original local history is retained under
`codex/local-before-main-unification`. Personal device pins are kept in an ignored
local policy file, not reset or published.

Included from Compose: attachment cards/capability chips and composer action bar,
async thumbnail/preview and recycling fixes, menu/sidebar corrections, capability
search normalization, and extracted Markdown/message/tool/diff rendering components.
The previously uncommitted renderer extraction and project modal were checkpointed
before integration, including their source files and device tests.

Included from the deletion line: single/batch confirmed connection removal,
credential/legacy/navigation cleanup, obsolete foreground connection callback guards,
and canonical CLI authentication including native refresh on the server.

Project creation keeps a modal and directory selection while restoring new-host URL
and pairing inputs. All submissions share the existing connect/migrate/validate flow,
select the intended host, check actual availability before saving, and reject stale
validation responses. Known opaque workspace references are accepted. Browse results
are scoped by the frontend to their original host/path request. Connection preferences
keep their existing names and encoding; no reset, seed, restore or package migration
is part of this change.

Version 0.2.0 / code 2 distinguishes unified builds from the old 0.1.0 branches.
Debug and release remain independently installed packages. No physical-device update
is part of branch unification; use the guarded updater for a later in-place install.

## Compose tradeoffs

This is a mixed View/Compose app, not a complete rewrite. Extra UI dependencies increase
this project's unminified APK size. Recomposition, mixed focus/IME and lifecycle state
need tests; a View implementation also requires manual state and lifecycle management.
Release currently has minification disabled, and there is no app-specific performance
benchmark or baseline-profile module. Do not claim a speed, memory or battery win/loss
from instrumentation tests or from comparing a debug build with a release build.

Android recommends incremental migration with View interoperability:
https://developer.android.com/develop/ui/compose/migrate/strategy
Performance comparisons should use optimized release builds:
https://developer.android.com/develop/ui/compose/migrate/compare-metrics

Validation results are appended after running the merged tree's checks.

## Validation result

- 178 Android JVM tests passed, zero failures/errors/skips.
- 122 server tests passed; TypeScript build passed.
- Debug, instrumentation and Release APK builds, lintDebug and lintRelease passed.
- Release manifest checks passed (private providers/services, no debug/test component,
  backup disabled, cleartext disabled).
- 19 distinct emulator tests passed across connection deletion, Compose action bar,
  attachment semantics, message renderer boundaries, sidebar animation and project dialog.
- The 3 project-dialog and 4 deletion tests passed again at font scale 2.0. Keyboard
  test verifies the submit button is fully visible above the IME after scrolling.
- Initial sidebar test required an already-open live conversation and failed after
  deletion left the emulator unpaired. Added an in-memory offline conversation fixture;
  unchanged animation assertions then passed. No live host or user conversation was used.
- Update guard 7 tests and Pixel send guard 4 tests passed.
- Unminified Release APK: legacy 6,268,174 bytes (5.98 MiB), unified 11,345,565 bytes
  (10.82 MiB), increase 4.84 MiB. These are package sizes, not memory/latency measurements.
- Evidence: `/tmp/codex-compose-unify-20260911` (build/test logs, keyboard screenshot,
  agy raw output). agy modified only the new project dialog; temporary permissions removed.

No physical device was installed, cleared, re-paired or restored during this work.
New-host form submission and routing guards were tested locally; live host pairing
and hardware performance were not re-measured in this task.
