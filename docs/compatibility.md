# Compatibility matrix

| Layer | Supported baseline | Negotiation and fallback | Release evidence |
|---|---|---|---|
| Android | API 26–36 | Tries Remote v2 first; falls back to v1 only on HTTP 404 | Unit tests, Lint, debug and release APKs |
| Remote Host | Node.js 20+ on macOS or Linux | Advertises exact capability flags in `server_hello` and `hello_ack` | TypeScript build and Host integration suite |
| Remote protocol v2 | Handshake, scopes, attachments, replay, idempotency, apps | Unknown capability fields are ignored; absent capabilities disable UI | `protocol/remote-v2.schema.json` and protocol tests |
| Remote protocol v1 | Existing pairing/WebSocket and image upload | Image-only compatibility; no generic files, replay, or credential lifecycle UI guarantees | Dedicated v1 integration test |
| Codex App Server | Current CLI with `codex app-server --stdio` | Host probes stable `app/installed`; falls back to experimental `plugin/list`; unsupported calls do not reach Android | Startup probe, allow-list, projection tests |

Before deploying a new Codex CLI build, run `./scripts/release-check.sh`, start the Host, confirm `/healthz`, then verify on a physical Pixel: v2 negotiation, task history, one attachment, plugin/skill refresh, one command or file approval, one additional-permission approval when supported, disconnect/reconnect, and credential rotation.

The Host is the compatibility boundary. Android must never call App Server directly or infer support from a CLI/app version string.
