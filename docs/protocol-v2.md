# Remote protocol v2 contract

Status: implemented baseline. The capability values describe the current Host, not planned features.

## Compatibility

- Existing clients continue to use `POST /v1/pair` and `/v1/ws` unchanged.
- New clients try `POST /v2/pair` and `/v2/ws` first. They fall back to v1 only when the Host returns HTTP 404.
- A v2 WebSocket is not ready for RPC traffic until the three-message handshake completes.
- The canonical message schema is [`protocol/remote-v2.schema.json`](../protocol/remote-v2.schema.json).

## Handshake

1. Host sends `server_hello` with its supported protocol versions, process-scoped `sessionId`, current event sequence, and capabilities.
2. Android sends `client_hello` with its supported versions and optional `lastSequence`.
3. Host chooses the highest common version and sends `hello_ack`. If no v2 version is common, it closes with WebSocket code 1002.
4. RPC and approval traffic may begin only after `hello_ack`.

The Host retains the latest 500 sequenced App Server events in memory. Android supplies both `lastSessionId` and `lastSequence` when reconnecting. If the process-scoped session matches and the missing range is still retained, the Host acknowledges and replays only newer events. A new Host process or an older gap sets `resyncRequired`, causing Android to reload authoritative thread state instead of applying an unsafe partial stream.

## Capability rules

Clients must gate UI and requests from `hello_ack.capabilities`; they must not infer a feature from app version numbers. Unknown capability fields must be ignored for forward compatibility.

The current Host truthfully reports:

- sequenced events with a bounded, same-session replay journal;
- legacy base64 image upload for v1 compatibility plus authenticated, resumable chunked HTTP uploads for v2;
- stable skill listing and forwarded skill change events;
- stable, projected `app/installed` runtime snapshots when supported by the current App Server, with experimental `plugin/list` retained for composer mention metadata and older-server fallback;
- expiring, scoped bearer device credentials with rotation, self-revocation, and active socket closure.

Each false value is an explicit implementation target, not an unavailable product promise.

## Attachment transport

When `attachments.chunkedHttp` is true, Android uploads images, PDFs, UTF-8 text, JSON, and common source-code files through the authenticated `/v2/attachments` HTTP resource. Upload initialization declares the exact byte count, MIME type, safe display name, and whole-file SHA-256. Each ordered chunk carries `Content-Range` and `X-Chunk-SHA256`; completion rechecks the whole digest and file signature/encoding.

The returned attachment id is device-bound. A v2 `turn/start` or `turn/steer` uses `{ "type": "remoteAttachment", "attachmentId": "…" }`. The Host resolves that opaque id to a verified private file and translates it to the App Server's supported input types. Raw client-provided `localImage` paths are rejected in v2. See [`attachments.md`](attachments.md) for the lifecycle and limits.

## App Server boundary

The Remote protocol is independent from the Codex App Server protocol. The Host performs the required App Server `initialize` / `initialized` sequence, owns the allow-list, and translates Host-only methods. Android never talks directly to App Server and never stores OpenAI or plugin credentials.

`plugin/list` remains experimental because composer mentions still require its local plugin path metadata. The Host probes stable App Server `app/installed` at startup and sets `plugins.installedApps` only after receiving a valid snapshot. Android then reads the credential-free `host/apps/installed` projection for callable/enabled status while retaining `plugin/list` for usable mention entries. Older App Servers automatically remain on the experimental-list path.

## Next compatible changes

- M1: completed baseline for credential expiry, scoped tokens, rotation, revocation-driven socket closure, and safe account status.
- M2: completed resumable chunked HTTP attachment transport, validation, device isolation, and App Server input translation.
- M4: skill cache invalidation and refresh state.
- M5: completed stable installed-app discovery, startup feature detection, safe projection, and experimental plugin fallback.
- M6: completed bounded event replay, per-device write-RPC idempotency, pending-operation replay within the same Host session, and jittered exponential reconnect backoff.

## Write idempotency

Protocol v2 requires `idempotencyKey` on RPC methods guarded by `rpc:write`. Android uses the stable client request id as that key and retains unconfirmed write messages while reconnecting to the same Host session. The Host caches the in-flight promise and final outcome for 24 hours per device, method, and key, so a lost response cannot duplicate a turn or archive action. Pending writes are deliberately not replayed after a Host process restart, because the in-memory outcome ledger is no longer authoritative; Android surfaces an explicit unconfirmed-operation error instead.

Capability flags must change in the same release as their implementation and tests.
