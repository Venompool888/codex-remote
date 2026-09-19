# Remote protocol

Protocol v2 adds explicit capability negotiation while preserving the v1 routes for older clients. See [`docs/protocol-v2.md`](docs/protocol-v2.md) and the machine-readable [`protocol/remote-v2.schema.json`](protocol/remote-v2.schema.json).

## Protocol v1 compatibility

The public Remote protocol is deliberately smaller than the Codex app-server protocol. The Host owns a private `codex app-server --stdio` child process and only forwards allow-listed methods.

## Pairing

`POST /v1/pair`

```json
{
  "code": "one-time-code",
  "deviceName": "Android device"
}
```

The response contains a device id and a bearer token. The code expires after five minutes and can only be used once. The Host persists only the SHA-256 token digest. Android encrypts the token with an Android Keystore AES-GCM key.

## WebSocket

Connect to `/v1/ws` with `Authorization: Bearer <device-token>`. HTTPS becomes WSS. Every frame is one UTF-8 JSON object.

Client RPC:

```json
{
  "type": "rpc",
  "id": "client-generated-id",
  "method": "thread/list",
  "params": { "limit": 50 }
}
```

RPC result:

```json
{
  "type": "rpc_result",
  "id": "client-generated-id",
  "result": {}
}
```

Codex notification:

```json
{
  "type": "codex_event",
  "sequence": 42,
  "method": "item/agentMessage/delta",
  "params": {}
}
```

Codex approval request:

```json
{
  "type": "codex_request",
  "requestId": "host-generated-id",
  "method": "item/commandExecution/requestApproval",
  "params": {}
}
```

The first valid authenticated device response wins. The current Android prototype supports command and file-change approvals. Unsupported interactive requests receive a JSON-RPC `Method not found` response rather than leaving Codex blocked forever.

## Allow-listed methods

- `thread/list`, `thread/search`, `thread/searchOccurrences`, `thread/read`, `thread/turns/list`, `thread/items/list`
- `thread/start`, `thread/resume`, `thread/fork`, `thread/archive`, `thread/unarchive`, `thread/delete`, `thread/name/set`
- `thread/compact/start`, `thread/goal/set`, `thread/goal/get`, `thread/goal/clear`, `review/start`
- `turn/start`, `turn/steer`, `turn/interrupt`
- `model/list`, `collaborationMode/list`, `permissionProfile/list`
- `skills/list`, `plugin/list` (read-only discovery for composer mentions)
- `host/workspace/validate` (authenticated, read-only directory availability check used before creating tasks)
- `host/workspace/list` (authenticated, read-only child-directory listing used by the remote project picker)
- `host/image/read` (authenticated image-only read, limited to the configured workspace and OS temporary directories, with a 12 MB cap and magic-byte validation)
- `host/account/status` (v2 safe projection of Host-side Codex authentication state; never includes tokens or API keys)
- `host/account/usage` (v2 quota/token-usage projection; each upstream section reports `supported` or a version-aware error independently)
- `host/administration/status` (read-only declaration of the narrow account, MCP, plugin, named-setting, and device-owned terminal surfaces)
- `host/workspace/files/search`, `host/workspace/file/read` (one validated workspace root, relative results, at most 200 search results and 512 KiB UTF-8 reads; symlink escapes and binary files are rejected)
- `host/file/readReference` (resolves a task-scoped `remote-file://` reference against the task's current authoritative workspace and returns relative path plus preserved start/end lines)
- `host/mcp/resource/read` (bounded text/HTML/image/audio projection of the App Server's `mcpServer/resource/read`; credential-bearing and local-file URIs are rejected)
- `host/workspace/text/save`, `host/workspace/text/create` (bounded UTF-8 writes under one validated workspace; save requires the exact prior SHA-256 and both require an exact path preview confirmation)
- `host/memory/reset`, `host/thread/backgroundTerminals/list`, `host/thread/backgroundTerminals/terminate`, `host/thread/backgroundTerminals/clean` (typed App Server wrappers; mutations require exact action previews)
- `host/git/diff` (one validated workspace root and at most 1 MiB of diff text)
- `host/apps/installed` (v2 credential-free projection of the stable installed-app runtime snapshot; capability-gated)
- Typed administration wrappers: `host/account/login/*`, `host/account/logout`, `host/mcp/*`, `host/plugin/*`, `host/settings/set`, `host/skill/setEnabled`, `host/thread/memoryMode/set`, and `host/terminal/*`
- Experimental realtime methods: `thread/realtime/listVoices`, `thread/realtime/start`, `thread/realtime/appendAudio`, `thread/realtime/appendText`, `thread/realtime/appendSpeech`, `thread/realtime/stop`

## Protocol v2 attachments

When negotiated capabilities report `attachments.chunkedHttp: true`, use the authenticated `/v2/attachments` resource described in [`docs/attachments.md`](docs/attachments.md). Android sends only an opaque, device-bound attachment id in `turn/start`; it never supplies a Host filesystem path. Protocol v1 keeps `host/image/upload` solely as an image compatibility path.

Methods such as `thread/shellCommand`, raw `command/exec`, configuration writes, plugin installation, and account login are not exposed in protocol v1.

Task mutation, context compaction, goal mutation and review start require `rpc:write` and a v2 idempotency key. Search, pagination, goal reads, account usage, bounded workspace reads and Git diff require `rpc:read`. Direct feature inputs are bounded before dispatch. Capability negotiation advertises only callable Remote methods; an older App Server can still reject a newly advertised upstream feature, and the Host returns that as an explicit unavailable/version error rather than fabricating support.

Administrative wrappers use closed parameter schemas and require `rpc:write`, v2 idempotency, and exact action-preview confirmations for potentially destructive operations. Account login accepts only browser/device authorization flows; API keys and client-supplied tokens are rejected. Plugin installation accepts remote names, never local marketplace paths. Configuration changes are limited to three named presentation/search settings, skill enablement by name, and per-thread memory mode. Terminal commands use argv vectors in one validated workspace, bounded output/time/input, generated upstream process IDs, and device-bound control/output routing; environment and sandbox overrides are not accepted. Hosts return explicit unsupported/version errors when an upstream feature is absent.

Workspace text save/create never expose a generic filesystem RPC. They are advertised only on Linux Hosts where descriptor-relative `/proc/self/fd` operations are available; other platforms reject them explicitly. Each parent is opened relative to an anchored workspace directory descriptor with no-follow checks. Content is limited to 512 KiB UTF-8, and create publishes a fully written file without replacing an existing entry. Save rejects symlinks and multiply-linked files, checks the expected lowercase SHA-256, then updates that same open descriptor while preserving its mode. The hash is optimistic conflict detection, not exclusion of unrelated external editors. Save is not an atomic replacement and a Host crash during the write can leave partial content. Background-terminal responses project only bounded identifiers, command text, workspace basename, and numeric process statistics; raw cwd and other upstream metadata are removed.

Remote realtime accepts websocket transport only. Start may forward bounded `includeStartupContext`, `version` (`v1`/`v2`/`v3`), `model`, `prompt`, and V3-only `initialItems` (128 items and 8,192 conservatively estimated text tokens). Audio input is base64 PCM16LE, mono 24 kHz, at most 256 KiB per append with verified sample counts; remote text input is user-role only and bounded to 16,000 characters.

Assistant-rendered workspace links, file citations, and code-comment file attributes can become task-scoped opaque `remote-file://` references after realpath containment checks. User messages and fenced code are never converted into actionable references. Outside-workspace and unavailable targets keep the generic artifact/unavailable fallback, and absolute Host paths remain subject to outbound routing/redaction. Guardian-denied notifications expose only a sanitized summary and `approvable:false`: the public completed notification does not contain the exact serialized `GuardianAssessmentEvent` required by `thread/approveGuardianDeniedAction`, so Remote deliberately does not advertise or reconstruct that mutation.

### Additive attachment presentation and failure details (2026-09-06)

Attachment-backed user content may include `remoteAttachment: { id, name, kind }` metadata (`kind: image|file`), alongside legacy-readable text. Clients render cards separately from prompt text. When `attachments.restrictedImagePreview` is advertised, `GET /v2/attachments/:id/preview` requires `attachments:read` and a complete image owned by the authenticated device. It streams Content-Length and X-Content-SHA256 with no-store/nosniff. Missing/expired/other-device resources fail; raw filesystem paths are not accepted by this endpoint.

ZIP MIME `application/zip` is now advertised; `.zip` with the common ZIP/octet-stream MIME variants is accepted as archive transport with signature/end-directory and complete SHA checks. No extraction occurs in the upload service. Attachment HTTP errors can include `code: unsupported_type|quota_exceeded|too_large|invalid_content|upload_failed`; older clients may ignore it. New clients map known codes to local text instead of exposing arbitrary response bodies.

The endpoint also accepts validated DOCX/XLSX/PPTX, MP3/WAV/OGG/M4A and MP4/MOV/WebM attachments up to 20 MiB. Office files must be recognizable ZIP packages, and media must match a bounded signature check for its declared MIME type. These files remain private, device-owned transport inputs; the upload service never extracts or executes them, and model/tool support for interpreting a format can vary by Host version.

Capability negotiation version `0` is transient/unknown in Android. Preserve a draft's previously confirmed durable-upload capability until negotiation returns an actual supported/unsupported result.


Markdown image sources may contain the existing Host-redacted form `remote-attachment://<uuid>-<display-name>`. A client with `attachments.restrictedImagePreview` support extracts only the UUID and uses the authenticated attachment preview endpoint; the suffix is display metadata, never a filesystem path or URL to fetch. Malformed references do not fall back to `host/image/read`. This applies to complete image blocks reconstructed from events and historical text. Old clients may show the reference as unavailable. The same ownership, expiry, completion and image-type checks apply as for attachment-backed user-content previews.

### Attachment provider MIME aliases

The advertised `attachments.acceptedMimeTypes` includes Android document-provider aliases: `application/javascript`, `application/xml`, `application/yaml`, `application/x-yaml`, `application/x-zip-compressed`, and `application/octet-stream`. This list describes potentially accepted MIME types, not unconditional acceptance. The attachment endpoint additionally validates supported filename extensions, type-specific byte limits, content format/UTF-8, hashes, per-device quotas and ownership. In particular, `application/octet-stream` only covers supported text/source/ZIP extensions; arbitrary binary/executable uploads remain unsupported. Older hosts can advertise a smaller list, and upload rejections remain authoritative. Android currently uses the system all-files chooser to accommodate providers that report generic MIME types.

### File-change approval review

Hosts advertising `interactions.fileChangeReview=true` enrich `item/fileChange/requestApproval` params with `fileChangeReview`. An available review contains `status:"available"` and `changes:[{path,kind,diff,movePath?}]`. Paths are relative to the configured host project, and changes come only from the canonical `item/started` fileChange event matching threadId/turnId/itemId. The enriched pending request is retained for device reconnect replay. No filesystem content is read to reconstruct a missing patch.

Missing event data, invalid/out-of-project paths or a serialized patch exceeding128KiB produce `status:"unavailable"` plus a user-facing message. Current Android displays filename/kind/patch for available reviews. Without a complete review (including older hosts without this extension), or with a session grantRoot request, it offers Deny and a host-review explanation rather than one-time Allow. Existing approval reply types remain unchanged; this extension supplies review context, not a new permission grant.

Approval responses are validated against `availableDecisions` when the upstream request explicitly provides it. An absent/null list retains legacy decisions; an empty or malformed list does not permit a generic default. A decision outside the offered set returns an invalid acknowledgement and leaves the request pending for a valid response or expiry. Session-wide/policy-changing decision variants remain unsupported by this Remote protocol.

Identical outstanding `item/tool/requestUserInput` or `mcpServer/elicitation/request` reissued by App Server for the same RPC or thread/item retains its remote `requestId` and original expiry. The host updates the upstream response target. Clients can retain unfinished answers across resume; changed request parameters continue to expire the old request and require a new response. This does not automatically approve or submit anything.

### Restricted generated-image previews

Hosts advertising `attachments.restrictedArtifactImages` rewrite local Agent image Markdown sources to `remote-artifact-image://<64-hex-selector>`. The selector is a hash of the original Markdown source and is **not an authorization credential**. It contains no host path. User messages quoting image Markdown remain user prose. Streaming image references are withheld until they can be rewritten, with a 4096-character bound and explicit unavailable marker for incomplete/overlong references.

The client queries `host/artifacts/list` for the current task and matches the descriptor's `imageReference` selector. It then downloads using the descriptor's device-owned `id` through the existing authenticated `/v2/artifacts/:id` endpoint. Preview accepts PNG/JPEG/GIF/WebP, at most12MiB, and verifies exact byte count and SHA-256 before decoding. Missing/expired/unavailable outputs produce an unavailable presentation. Host/device/task navigation guards and cache namespaces still apply. Listing only resolves assistant-linked files under the authoritative task workspace; generated files outside that workspace are not authorized by this selector.

When ArtifactStore is configured, `host/image/read` is omitted from advertised methods and explicitly rejects requests, including authenticated callers. Hosts without ArtifactStore retain the old method and do not emit the new reference scheme. Old clients encountering a new reference need an updated client for previews; the selector is never a fallback host path.

Artifact-enabled hosts also filter known private home/service/temp roots in human-facing text before publication and journal replay. The incremental transform runs after image-source rewriting, retains only potential-root lookbehind, and discards path bodies, including quoted paths with spaces. Completed messages and standalone diagnostic logs use the same rule. Routing `cwd` and legacy skill-path fields remain protocol identifiers; this is a bounded known-root privacy rule, not a guarantee of recognizing every possible secret or filesystem root.


### Negotiated opaque workspace routing (implementation in progress)

A Host configured with a persistent `RoutingReferences` store advertises
`capabilities.routing.opaqueWorkspaceReferences: true`. An updated v2 client opts
in with `client_hello.features.opaqueWorkspaceRouting: true`; `hello_ack.features`
confirms the selected behavior. Requesting it from an unconfigured Host fails
negotiation rather than silently returning private cwd values.

For opted-in connections, outgoing `cwd` fields become
`remote-workspace://<64 lowercase hex>` and receive a sibling `cwdName` display
label. References are bound to the authenticated device and workspace type.
Incoming nonempty `cwd` and `cwds` values must resolve for that device; raw paths
and another device's IDs are rejected before upstream dispatch. The Host keeps
canonical cwd internally in its journal and RPC ledger and projects each socket
separately, preserving stable replay IDs. Outgoing projection is serialized per
socket so persistence does not reorder acknowledgements/events. Failures close
the connection without falling back to raw paths.

Legacy/non-opted-in connections retain established routing. This extension does
**not yet cover arbitrary `path` fields** or client
migration. It is not enabled in the production bootstrap or Android yet. Complete
those integrations and privacy tests before advertising final path-free support.


Negotiated `host/workspace/validate` accepts only device-owned workspace references
in its `paths` array. Its `workspaces[].path` results reuse the same references,
with `pathName` for display, including unavailable targets. This method-specific
projection must not turn ordinary file paths into workspace references. In opted-in
mode `host/workspace/list` explicitly rejects directory browsing; projects come
from task history. Legacy connections retain existing behavior.


### Workspace migration (not yet production enabled)

`host/workspace/migrate` requires `rpc:write` and an opaque-routing-negotiated socket. It accepts `paths`, up to100 absolute strings with a4096-character limit and no control line/NUL separators. This explicit migration boundary accepts the client's existing local configuration; ordinary cwd RPC fields still reject raw paths in opaque mode. It does not list directories or read arbitrary files. Existing workspace validation rejects unavailable/non-directory/reserved/inaccessible entries.

Response `workspaces` retains request order and duplicate indices. Each entry has `index` and `available`; success includes device-bound `cwd` and basename `cwdName`, failure includes `reason` without the raw input path. The client correlates by index, persists draft aliases before updating navigation/project references, and retains failed/conflicting originals. This endpoint does not itself update Android storage or claim migration completion.


### Replaced interaction acknowledgements

A server_response_ack with status expired may include reason superseded when a newly issued App Server request replaces the old approval. Clients must retire the old request as before, but should not display an expiry warning for this explicit replacement reason. Real timeout and legacy expiry acknowledgements still warrant expiry feedback. The optional additive field requires no new RPC/capability; older clients retain their existing expired behavior. It grants no permission and never makes the retired request executable.


### Outbound pressure limits

Each negotiated opaque connection retains at most1024 projection jobs including its active job, with64 MiB serialized payload accounting. WebSocket outbound buffers are also limited to64 MiB for both protocol paths. Backlog overflow closes with1013; clients reconnect using existing session/replay/snapshot rules. Pending jobs are discarded after close; this does not authorize automatic replay of non-idempotent writes outside the existing ledger rules.


### Attachment size metadata

attachments.maxBytesByKind optionally gives per-kind byte limits; maxBytesPerFile remains the global ceiling. A too_large HTTP response may include numeric maxBytes. Clients must treat it as display metadata only, validate its type/range, and use a generic safe error when absent or malformed. Older clients may ignore these additive fields. Server limits and permission checks remain authoritative.

## Permission menu compatibility

The Remote Host augments `permissionProfile/list` with two optional local entries:
`local:auto-review` and `local:config`. Clients must check `allowed`; absence means
that the host does not advertise the feature. Auto-review is capability-probed
without starting a model turn and maps to `permissions: ":workspace"` with
`approvalsReviewer: "auto_review"`. Built-in modes explicitly reset the reviewer
to `user` when selected after Auto-review.

`local:config` is a Remote Host selection marker, never a Codex profile ID. For
`thread/start`, the host removes permission overrides so Codex loads its effective
configuration. For `turn/start`, the host resolves the task directory and opens an
ephemeral thread without starting a turn, copies Codex's effective profile or
sandbox policy and approval settings, then unsubscribes the probe. This prevents
an existing task's previous Full access or Auto-review settings from persisting
when Custom is selected. Raw configuration is not sent to the client. Existing
named profiles remain available under their original IDs.

New clients keep unsupported entries visible and disabled on older hosts. A
resumed configuration-based task on an older host sends no synthetic override,
retaining the task's current host settings.
