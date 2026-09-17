# State and rollout migrations

## Current state versions

- `devices.json` version 2 adds expiry, scopes, rotation, and revocation timestamps. Loading a v1 file normalizes records and atomically writes v2 while retaining token hashes.
- Android preserves the existing per-Host encrypted token and adds device id, expiry, and scopes alongside it. A legacy token remains usable; metadata is populated after the next pairing or rotation.
- Attachments are versionless resources under the private Host state directory. Incomplete uploads expire after 24 hours; completed resources are now retained until explicit deletion. Preserve attachment metadata and bytes together during migration, including existing complete records with legacy seven-day expiry timestamps. Already deleted bytes cannot be restored by this change.

## Safe rollout

1. Back up `devices.json` with owner-only permissions.
2. Deploy and run the release gate.
3. Start the Host loopback-only and inspect `/healthz` before exposing it through TLS.
4. Upgrade one Android device. Confirm v2; older devices continue on v1.
5. Rotate the canary device credential and validate reconnect.
6. Roll out remaining clients, then narrow device scopes where appropriate.

## Rollback

Rolling the Host binary back keeps v1 connectivity, but older builds may ignore v2-only metadata. Preserve the version-2 device file and do not hand-edit token hashes. Android falls back only when the v2 route is absent; a broken v2 endpoint should be fixed or rolled back rather than silently masked.

If an attachment release must be rolled back, queued attachment ids may become unusable. Remove them from the Android composer and reattach after the Host is stable. Project files and Codex task history are unaffected.
