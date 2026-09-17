# Operations and observability

## Probes

- `GET /healthz` is an unauthenticated liveness/capability response suitable for a loopback reverse-proxy health check. It contains no account or device data.
- `GET /v2/status` requires a valid device Bearer token with `rpc:read`. It reports process uptime, session id, connected socket count, event/journal depth, idempotency ledger size, feature availability, and aggregate RPC/attachment counters. It never reports tokens, device names, prompts, filenames, or account identity.

## Local administration

The running Host creates `control.sock` inside `CODEX_REMOTE_STATE_DIR` with mode
`0600`. `node dist/cli.js pair` uses this local-only socket to request a new
in-memory pairing ticket; it does not expose an unauthenticated network admin
endpoint or persist the one-time code. Set `CODEX_REMOTE_CONTROL_SOCKET` on both
the Host and CLI only when the default state-directory path is unsuitable.

Use reverse-proxy access logs for request latency and status distributions. Keep logs private: even though Host responses are sanitized, URLs and timing still reveal usage patterns. Do not enable body logging for pairing, attachment, or WebSocket traffic.

## Alerts

- `/healthz` unavailable for two consecutive checks: restart the service and inspect the App Server child-process log.
- Repeated 401/403: inspect device expiry, revocation, and scopes; do not print or request the bearer token.
- Growing `idempotencyEntries` without successful RPCs: inspect App Server responsiveness. Entries expire after 24 hours.
- Attachment quota errors: allow completed resources to expire, delete unused ids, or investigate a stuck client. Do not raise limits before checking disk capacity.
- Frequent `resyncRequired`: look for Host restarts or gaps larger than the 500-event journal.

## Backup boundary

Back up project data and Codex's own state according to their policies. The Remote Host state contains device hashes and retained historical attachments; preserve attachment metadata and files together, protect them with filesystem permissions, and exclude them from general artifact publishing. Restoring `devices.json` restores device access until each record expires or is revoked, so treat backups as credentials.
