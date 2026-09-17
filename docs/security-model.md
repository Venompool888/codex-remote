# Codex Remote security model

## Credential boundaries

Codex Remote has three independent credential planes:

1. **Android to Remote Host.** Pairing issues a random 256-bit bearer credential. Android encrypts it with a non-exportable Android Keystore AES-GCM key. The Host stores only its SHA-256 digest.
2. **Remote Host to Codex/OpenAI.** Codex App Server owns these credentials. The Remote Host asks `account/read` for a narrow status projection; raw API keys, OAuth tokens, cookies, and unknown account fields never cross to Android.
3. **Plugin and MCP OAuth.** These credentials remain inside Codex/App Server. Android may eventually receive callable/auth-required state and a one-time login ceremony, but not stored provider tokens.

## Device credential lifecycle

- Pairing codes are random, single-use, and expire after five minutes.
- New and migrated device credentials expire after 180 days by default.
- Every credential carries explicit scopes: read RPC, write RPC, attachment read/write, approval response, rotation, and self-revocation.
- `POST /v2/device/rotate` returns a new credential, renews expiry, invalidates the old token, and closes every WebSocket for that device.
- `DELETE /v2/device` revokes the calling device and closes every WebSocket for it.
- `npm run devices -- revoke <device-id>` provides Host-side revocation. Connected devices are rechecked at most every five seconds.
- `npm run devices -- scopes <device-id> <comma-separated-scopes>` narrows a device. New scope values take effect on its next connection; administrators should revoke or rotate when an immediate reconnect is required.
- Revoked records remain in `devices.json` as audit tombstones. Plaintext device tokens are never written there.

## RPC authorization

- Read-only catalogs, task reads, workspace reads, Skills/Plugins discovery, and account status require `rpc:read` (image reads require `attachments:read`).
- Task creation/mutation and turn operations require `rpc:write`.
- Image uploads require `attachments:write`.
- Responses to App Server approval requests require `approvals:respond`.
- The method allow-list is enforced before scope authorization.

Approval requests are sent only to connected, negotiated devices holding `approvals:respond`; read-only devices never receive their command, path, or permission details. Command and file-change approvals return only the current App Server decision enums. Additional-permission approval can grant only the exact requested profile and is always limited to the current turn. The first valid device response wins. Duplicate responses are rejected, unanswered requests fail after five minutes, and a request fails immediately when no authorized device is connected. Unknown interactive request types fail closed.

Write RPCs carry a device/method-scoped idempotency key and are retained for 24 hours in the current Host process. A same-session reconnect replays retained sequenced events. After a Host restart, Android does not automatically retry an unconfirmed write because its outcome is unknowable; it reports that state to the user instead of risking a duplicate mutation.

## Attachment boundary

- Android submits metadata first, then fixed-offset chunks with per-chunk SHA-256, and finally asks the Host to verify the whole-file SHA-256 and content type.
- Attachment ids are bound to the owning device. The Host chooses all private paths and rejects raw v2 `localImage` paths from Android.
- Images and PDFs are limited to 20 MiB; text, JSON, and source files to 5 MiB; a device may hold at most 40 MiB. A turn accepts at most four files.
- Incomplete uploads expire after 24 hours. Completed uploads remain until explicit deletion for historical access, within the device quota. Files and metadata use owner-only permissions; historical downloads still require the original device and attachments:read scope.
- Verified document paths are supplied only inside the Host/App Server boundary and are redacted from events and results sent back to Android.

## Transport requirements

Production access must use HTTPS/WSS behind a trusted TLS endpoint. Android defaults to HTTPS and also supports an explicit HTTP selection for trusted private networks, loopback, USB reverse forwarding, or an authenticated SSH tunnel. Both manifests therefore permit cleartext; HTTP sends pairing codes and credentials without transport encryption. Tokens must never be placed in URLs or logs.

## Host account status

`host/account/status` calls App Server `account/read` with `refreshToken:false` and returns only:

- readiness and whether OpenAI authentication is required;
- authentication mode;
- email and plan type when App Server supplies them;
- credential source for supported non-OpenAI providers.

The Remote protocol does not expose account login, logout, API-key entry, or externally managed ChatGPT tokens. Host-local managed ChatGPT login remains the default.

## Android package identity

Use only `app.codexremote.android` for release builds and
`app.codexremote.android.debug` for debug builds. Do not restore a personal or
retired package identity for compatibility with an older installation. Local
device update policies must use these neutral identities too. Moving from an
older installation requires a separate installation and fresh pairing; never
copy its private preferences or silently uninstall it.
