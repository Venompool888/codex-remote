# Staged implementation and release plan

This plan is both the implementation record and the go/no-go checklist. A stage may ship only when its acceptance evidence passes; rollback never deletes Codex task history or project files.

| Stage | Delivered scope | Acceptance evidence | Rollback boundary |
|---|---|---|---|
| M0 — protocol boundary | Remote v2 handshake, capability negotiation, method allow-list, v1 fallback | Schema parse, protocol/unit tests, Pixel v2 negotiation | Roll Host back; v1 clients remain usable |
| M1 — identity | One-time pairing, hashed 180-day scoped credentials, Android Keystore, rotate/revoke, narrow Host account status | Auth tests plus real Pixel rotation and reconnect | Restore owner-only `devices.json` backup; never copy plaintext tokens |
| M2 — attachments | Device-bound resumable image/PDF/text/source upload, integrity/MIME/quota/expiry checks, no folder upload | Store and HTTP integration tests plus Pixel text attachment read by App Server | Disable attachment capability; reattach queued files after recovery |
| M3 — continuity | Sequenced event replay, write idempotency, reconnect backoff, explicit unknown-outcome handling after Host restart | Replay/dedup tests plus real Pixel Host-restart reconnect | Disable v2 endpoint or roll Host back; Android uses v1 only when v2 is absent |
| M4 — remote catalogs | Skills cache/refresh/change invalidation, plugin mentions, stable installed-app status with probe/fallback | Capability/projection tests plus Pixel catalog and refresh checks | Capability flags turn UI off without removing saved projects |
| M5 — approval boundary | Scoped delivery, command/file/exact-permission dialogs, first-response wins, timeout and fail-closed behavior | Host authorization/timeout tests; physical-device approval in release smoke | Remove `approvals:respond` scope to disable remote approval safely |
| M6 — operations | `/healthz`, authenticated `/v2/status`, counters, migration/runbook/security docs, CI and local release gate | `./scripts/release-check.sh`, `git diff --check`, service health | Roll service binary back while preserving state and logs |
| M7 — UI fidelity | ChatGPT Remote-inspired Pixel layout, loading shimmer, streaming state, collapsible work log, drawer/composer transitions | Recorded Pixel walkthroughs and screenshots under `qa/` | UI-only APK rollback; protocol and Host state are unchanged |

## Release gate

1. Run `./scripts/release-check.sh`; every Host test, TypeScript build, Android unit test, debug/release Lint, and debug/release assembly must pass.
2. Deploy Host loopback-only, confirm `/healthz`, and put a trusted HTTPS/WSS reverse proxy in front of any public endpoint.
3. Canary one Pixel: open history, send a turn, upload a document, refresh skills/plugins/apps, answer an approval, restart the Host during an idle connection, and rotate the device credential.
4. Inspect `/v2/status` for error growth without copying bearer credentials into a command history or log.
5. Keep the previous Host artifact, APK, and owner-only `devices.json` backup until the canary has remained healthy.

## Post-release backlog

The daily-driver upgrade adds capability-gated Android notifications, artifact downloads, persistent offline drafts, Agent questions and supported MCP elicitation. See `daily-driver-goal.md` for live Pixel acceptance and `daily-driver-protocol.md` for constraints. Connection setup includes on-device QR scanning through Google Play services, with explicit confirmation before pairing. Offline remote execution remains outside this release. Unknown interactive requests must remain explicit and fail closed.
