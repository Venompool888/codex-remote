# Remote attachment lifecycle

Protocol v2 treats an attachment as a device-owned Host resource. Completed files are retained for historical conversation access until explicitly deleted. It does not expose arbitrary phone folders or arbitrary Host paths.

## Supported content and limits

- PNG, JPEG, GIF, WebP, and PDF: up to 20 MiB each.
- UTF-8 text, JSON, Markdown, CSV, logs, and common source files: up to 5 MiB each.
- Up to four attachment references per turn.
- 40 MiB reserved attachment quota per paired device.
- Incomplete uploads expire after 24 hours; completed files have no automatic expiry and remain subject to the device quota and explicit deletion.
- Completed records from older hosts are retained too, provided their bytes were not already deleted. The v2 `expiresAt` string uses `9999-12-31T23:59:59.999Z` for completed files so existing clients keep parsing the same schema.

The Host sanitizes display names, verifies MIME-sensitive magic bytes, rejects binary or malformed UTF-8 text, validates JSON syntax when declared as JSON, and stores files and metadata with private permissions.

## HTTP flow

Every request uses `Authorization: Bearer <device-token>`. Initialization and writes require `attachments:write`; status reads require `attachments:read`.

1. `POST /v2/attachments` with `{ name, mimeType, size, sha256 }` returns an attachment descriptor and `chunkBytes`.
2. `GET /v2/attachments/:id` returns the authoritative offset and status so a client can resume.
3. `PUT /v2/attachments/:id` sends the next chunk with `Content-Range: bytes start-end/total` and `X-Chunk-SHA256`.
4. `POST /v2/attachments/:id/complete` verifies the full file and atomically promotes it.
5. `DELETE /v2/attachments/:id` removes the caller's resource early.
6. `GET /v2/attachments/:id/preview` returns verified image bytes; `GET /v2/attachments/:id/download` returns any completed attachment. Both require `attachments:read` and original device ownership, and send Content-Length and X-Content-SHA256. Android checks these before opening a downloaded file.

Changing to a separately paired app/device does not transfer attachment ownership. An access-denied response must not be bypassed. Existing files deleted by a previous host's cleanup cannot be reconstructed from a chat card. Update the Remote Host as well as Android to enable the download route and durable retention; an APK update alone cannot change server cleanup behavior.

Offsets are strictly ordered. A mismatched offset returns a conflict instead of appending ambiguous data. After a lost response, HTTP conflict, or transient server failure, Android reads the authoritative offset and resumes the same attachment id, with three bounded recovery attempts per chunk. Attachment ownership is checked on every operation and again when a turn is submitted.

## App Server translation

The official Codex App Server turn input currently supports text, URL images, and Host-local images rather than a generic PDF/document input. The Remote Host therefore translates verified image attachments to `localImage`. It translates PDF/text/code attachments to a tagged model instruction containing the exact private Host path so Codex can read it when needed. Before App Server notifications or history cross back to Android, the Host replaces that tagged instruction with a user-facing attachment label and redacts any private attachment-root path. Android never receives or chooses the Host path.

The v2 boundary rejects client-supplied `localImage` paths. This prevents a paired phone from using the attachment input as an arbitrary Host file-read primitive.
