# Codex Remote for Android

Release baseline: **0.3.1** uses Compose for all app-owned UI, organized by feature,
with the existing connection, draft and protocol runtime. Debug and Release are build
variants of this same source, with separate on-device data. See the
[Android feature map](android/ARCHITECTURE.md),
[full Compose migration](docs/full-compose-migration.md), and
[unification and validation](docs/compose-unification.md).


Remote is an Android-native client for Codex running on a Linux or macOS server. The server-side Remote Host manages `codex app-server --stdio`; it does not scrape terminal output and it never sends the server's Codex/OpenAI credentials to Android.

This is an independent community client, not an official OpenAI product. Protocol v2, scoped device credentials, resumable attachments, remote skills/plugins/apps, and reconnect safety are implemented. Internet-facing Hosts require HTTPS/WSS; see the compatibility gates below and the [0.3.2 release notes](docs/releases/0.3.2.md).

## License and commercial use

**Source-available for noncommercial use.** Project-authored code is licensed
under [PolyForm Noncommercial 1.0.0](LICENSE), not AGPL. Commercial use outside
that license's permitted purposes—including commercial resale, paid hosting,
and use in commercial products—requires separate written permission.
See [LICENSING.md](LICENSING.md) for scope and how to request commercial terms.
Third-party components retain their own licenses.

Required Notice: Copyright (c) 2026 Codex Remote contributors.

## What works

- One-time, five-minute device pairing.
- Random 256-bit device bearer tokens; only SHA-256 digests are stored by the Host.
- Android Keystore AES-GCM storage for the device token.
- Authenticated WebSocket RPC and Codex event forwarding.
- Multiple saved remote projects, each combining a display name, Host connection, encrypted per-Host device token, and working directory. Every paired Host keeps its own background WebSocket connection with automatic retry. The Android drawer uses horizontal Connection chips to filter recently used projects without reconnecting; opening a project switches RPC context to its already-connected Host.
- Authenticated remote-folder browsing and availability checks when adding a project.
- Codex task list, task resume/history, new task creation, message send, stop, and streaming assistant text.
- Native Markdown rendering for assistant headings, lists, quotes, links, inline code, fenced code, and images. While a turn runs, reasoning and execution stay visible, and only the newest live step gets a muted-gray left-to-right shimmer. After completion, the animation stops and the whole work log collapses under `Worked for ...`. Expanding it reveals compact reasoning and separately expandable commands, file changes, and tool calls, while the final answer stays visible.
- Expanded projects show their five newest sessions first, followed by `Show more`/`Show less` when additional sessions exist.
- Authenticated local-image transfer from the Host with path, file-type, and size restrictions. Android keeps a private cache so an already-viewed temporary image survives a later Host restart.
- Native allow/deny dialogs for command, file-change, and exact turn-scoped additional-permission approval requests.
- Host-side allow-list that excludes raw shell execution, account login, configuration writes, and plugin installation.
- Remote protocol v2 capability negotiation with v1 fallback, a 500-event same-session replay journal, write-RPC idempotency, and jittered exponential reconnect backoff.
- Device credentials with 180-day expiry, least-privilege scopes, rotation, self-revocation, and immediate socket closure.
- Resumable, SHA-256-verified uploads for images, PDFs, UTF-8 text/JSON, and common source files. Attachment ids are device-bound; Android never chooses a Host path.
- Real remote Skill/Plugin catalogs, searchable scoped caches, refresh, availability/dependency/auth states, removable tags and host-side revalidation before structured invocation. Connected apps are displayed separately as runtime status.
- Durable per-host/device/task drafts and attachment queues, bounded streaming imports, photo/document multi-select and Android sharing, chunk integrity, process-death recovery, cancel/retry/remove and deferred cleanup.
- Multi-question Agent input, bounded native MCP forms and explicit browser URL requests, response acknowledgement, expiration, replay and duplicate protection.
- Opt-in completion/failure/input/approval notifications that open the correct host and task. No per-token notifications.
- Device-scoped artifact IDs, workspace output snapshots, verified downloads with progress/retry, and system open/share.
- Persistent write-RPC ledger across host restarts; ambiguous in-flight outcomes preserve the draft and are never blindly executed again.

Protocol behavior and operational limits are documented in
[`PROTOCOL.md`](PROTOCOL.md) and [`docs/protocol-v2.md`](docs/protocol-v2.md).

## Layout

```text
server/   TypeScript Remote Host and tests
android/  Kotlin native Android client
PROTOCOL.md  Public protocol v1
```

## Server quick start

Prerequisites:

- Node.js 20 or newer.
- A current Codex CLI with `codex app-server` support.
- A completed server-local `codex login`.

```bash
cd server
npm install
npm test
npm run build
npm start -- --host 127.0.0.1 --port 8787 --url http://127.0.0.1:8787 --cwd /srv/project
```

The Host prints a QR code and one-time pairing code. In Android, choose **Add
Connection**, then **Scan QR Code**, or enter protocol, domain/IP, port, base path
and Pair Code manually. Scanned `codexremote://pair` links (including links opened
from the system camera) show the server for confirmation before pairing. Connections
are saved independently; adding a Project is a later step. The in-app scanner uses
Google Play services and processes images on the device. For a trusted USB test:

```bash
adb reverse tcp:8787 tcp:8787
```

Enter `http://127.0.0.1:8787` and the printed pairing code in Android.

For a real server, keep the Host on `127.0.0.1` and place Caddy or another TLS reverse proxy in front of it. See `server/deploy/Caddyfile.example`. Do not publish plaintext HTTP or the raw app-server port.

## Device administration

Install the local administration command once, as the same OS user that runs
the Host (Node.js 20+ and the dependencies above are required):

```bash
cd server
npm run install:cli
export PATH="$HOME/.local/bin:$PATH"
codexremote
```

The interactive menu offers **1 New connection** (a five-minute pairing QR),
**2 Paired devices**, **3 Configure public pairing URL**, and **0 Exit**. Select a device under option 2 to revoke
its pairing; deletion requires confirmation and does not delete host files.
The QR and device list stay in the local terminal. Opening the menu does not
generate a ticket. Without a terminal, the command prints help instead.

For a service with a custom state directory, install with
`node scripts/install-cli.mjs --state-dir /path/to/private/state`; optionally
pass `--control-socket` and `--bin-dir`. These paths must match the running
service. This installs an administration command, not a second Host service.
Keep the command's installation directory on PATH in your shell configuration.

For Cloudflare Tunnel or another reverse proxy, choose menu option **3** and
enter the full public HTTPS address the phone should use, including any service
path prefix. The CLI saves it privately in `$CODEX_REMOTE_STATE_DIR/cli-config.json`
(default `~/.codex-remote/cli-config.json`, mode `0600`):

```json
{"publicUrl":"https://host.example/remote"}
```

You can edit this file directly, run `codexremote config show`, or set it with
`codexremote config set-url https://host.example/remote`. The next generated QR
uses the new address without restarting the Host. Configuration takes precedence
over `CODEX_REMOTE_PUBLIC_URL`, which takes precedence over the running Host's
startup URL. `codexremote config reset` removes the override. Invalid configuration
fails visibly rather than silently generating a QR for a different server.
This changes only the address embedded in pairing output; it does not create a
tunnel or change the Host's listen address. Old QR images must be regenerated.

```bash
cd server
npm run cli -- pair
npm run cli -- devices list
npm run cli -- devices scopes DEVICE_ID rpc:read,rpc:write,attachments:read,attachments:write,approvals:respond,device:rotate,device:revoke
npm run cli -- devices revoke DEVICE_ID
```

Device records default to `~/.codex-remote/devices.json` with mode `0600`. Set `CODEX_REMOTE_STATE_DIR` to use another private location. Revocation or rotation closes that device's active sockets immediately; the Host also rechecks externally administered records periodically.

`pair` talks to the running Host through an owner-only Unix socket, replaces any
older unused ticket, and prints a fresh five-minute code plus terminal QR. Use
`--no-qr` for text only or `--json` for automation. For an installed production
build, the equivalent command is `node dist/cli.js pair`.

`SIGUSR1` remains available as a log-oriented fallback:

```bash
systemctl kill --kill-whom=main -s USR1 codex-remote-host
journalctl -u codex-remote-host -n 50
```

`--kill-whom=main` is required: sending `SIGUSR1` to the whole service cgroup also
signals the managed `codex app-server` child process and disconnects active clients.

## Android build

The project targets Android API 36 and supports Android 8.0/API 26 or newer.

```bash
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The debug APK is generated at:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

HTTPS is the default. Both build variants support explicitly selected HTTP for trusted private networks and `adb reverse` development. HTTP does not encrypt pairing codes or credentials. Public deployments must terminate HTTPS/WSS at the reverse proxy and keep the Host loopback-bound.

## Verification

Run the repository release gate before a test build:

```bash
./scripts/release-check.sh
```

It validates protocol JSON and the release cleartext policy, runs all Host tests and TypeScript compilation, then runs Android unit tests, debug/release Lint, and debug/release APK assembly. See [`docs/compatibility.md`](docs/compatibility.md), [`docs/security-model.md`](docs/security-model.md), [`docs/operations.md`](docs/operations.md), [`docs/migrations.md`](docs/migrations.md), and [`docs/release-plan.md`](docs/release-plan.md).

## Service template

`server/deploy/codex-remote-host.service` is an example, not a drop-in universal unit. Change its user, Node path, installation directory, public hostname, project write paths, and home directory before installing it. Run `codex login` as the same OS user configured in the unit.

## Remaining release limitations

- In-app QR scanning requires Google Play services and its scanner module (downloaded on first use if needed); manual connection entry remains available.
- Offline drafts are supported; starting a remote turn still requires a connection.
- Background monitoring uses an opt-in Android foreground service. Force-stop prevents jobs and notifications until the app is opened again.
- URL authorization opens the system browser; unsupported forms and plugin setup require the host. Full third-party login management and phone-side plugin installation are outside this client.
- The public gateway currently relies on a reverse proxy for TLS.
- Codex app-server evolves; the Host is the compatibility boundary and must be tested against each deployed CLI version.
