import { StreamingPrivatePaths } from './streaming-private-paths.js';
import { StreamingArtifactImages } from './artifact-image-reference.js';
import { fileChangeReview } from "./file-change-review.js";
import { StreamingSecrets } from "./streaming-secrets.js";
import { RequestBudget } from "./request-budget.js";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { createHash, randomUUID } from "node:crypto";
import { isDeepStrictEqual } from "node:util";
import { WebSocketServer, WebSocket } from "ws";
import type { AddressInfo } from "node:net";
import { CodexAppServer, type AppServerRequest } from "./codex-app-server.js";
import { DeviceAuth, type DeviceScope, type PairingTicket } from "./auth.js";
import {
  ALLOWED_CODEX_METHODS,
  currentRemoteCapabilities,
  MIN_REMOTE_PROTOCOL_VERSION,
  negotiateProtocolVersion,
  parseClientMessage,
  REMOTE_PROTOCOL_VERSION,
  requiredScopeForMethod,
  SUPPORTED_REMOTE_PROTOCOL_VERSIONS,
} from "./protocol.js";
import { listWorkspaceDirectories, validateWorkspacePaths } from "./workspaces.js";
import { readImageAsset, uploadImageAsset } from "./image-assets.js";
import { hydrateThreadHistory } from "./thread-history.js";
import { validateInteractionReply } from "./interaction-validation.js";
import { publicPayload } from "./public-payload.js";
import { RoutingReferences } from './routing-references.js';
import { WorkspaceRouting } from './workspace-routing.js';
import { RpcLedger } from "./rpc-ledger.js";
import { ArtifactStore } from "./artifact-store.js";
import { CapabilityCatalog } from "./capability-catalog.js";
import { AttachmentStore, AttachmentSizeError } from "./attachment-store.js";
import { OrderedDelivery } from './ordered-delivery.js';
import { CUSTOM_CONFIG_PERMISSION, resolvePermissionSelection } from './permission-selection.js';
import {
  projectFileSearch,
  projectGitDiff,
  readWorkspaceText,
  validateFeatureParams,
  validatedWorkspace,
} from "./workspace-features.js";
import { HostManagement, HOST_MANAGEMENT_METHODS } from "./host-management.js";
import { RichResourceBridge } from "./rich-resource.js";
import { WorkspaceMutations, WORKSPACE_MUTATION_METHODS } from "./workspace-mutations.js";

interface RemoteHostOptions {
  host: string;
  port: number;
  defaultCwd?: string;
  auth: DeviceAuth;
  codex: CodexAppServer;
  imageRoot?: string;
  attachments?: AttachmentStore;
  artifacts?: ArtifactStore;
  routingReferences?: RoutingReferences;
  ledger?: RpcLedger;
  authRecheckIntervalMs?: number;
  approvalTimeoutMs?: number;
}

interface PendingServerRequest {
  codexId: number | string;
  answered: boolean;
  timeout: NodeJS.Timeout;
  message: Record<string, any>;
  request: AppServerRequest;
}

interface SocketState {
  deviceId: string;
  scopes: DeviceScope[];
  protocolVersion: number;
  negotiated: boolean;
  handshakeTimer?: NodeJS.Timeout;
}

interface RpcOutcome {
  ok: boolean;
  result?: unknown;
  error?: string;
}

interface IdempotencyEntry {
  expiresAt: number;
  fingerprint: string;
  promise: Promise<RpcOutcome>;
}

export class RemoteHost {
  private readonly startedAt = Date.now();
  private rpcRequests = 0;
  private rpcErrors = 0;
  private attachmentUploads = 0;
  private attachmentBytes = 0;
  private readonly sockets = new Set<WebSocket>();
  private readonly socketStates = new Map<WebSocket, SocketState>();
  private readonly answeredServerRequests = new Set<string>();
  private readonly pendingServerRequests = new Map<string, PendingServerRequest>();
  private sequence = 0;
  private readonly sessionId = randomUUID();
  private cleanupTimer: NodeJS.Timeout | null = null;
  private authRecheckTimer: NodeJS.Timeout | null = null;
  private pairingTicket: PairingTicket | null = null;
  private installedAppsAvailable = false;
  private autoReviewAvailable: boolean | null = null;
  private installedAppsSnapshot: Record<string, unknown> = { apps: [] };
  private readonly eventJournal: Array<Record<string, unknown> & { sequence: number }> = [];
  private readonly idempotency = new Map<string, IdempotencyEntry>();
  private readonly requestBudget = new RequestBudget(600);
  private readonly pairingBudget = new RequestBudget(10);
  private readonly server = createServer({ requestTimeout: 30_000, headersTimeout: 10_000 }, (request, response) => {
    void this.handleHttp(request, response).catch(() => {
      if (!response.headersSent) json(response, 500, { error: "Host request failed" });
      else response.destroy();
    });
  });
  // Legacy image uploads are base64 JSON frames. 20 MiB safely contains the
  // advertised 12 MiB binary limit plus base64 and envelope overhead.
  private readonly websocket = new WebSocketServer({ noServer: true, maxPayload: 20 * 1024 * 1024 });

  private readonly attachmentTextStreams = new Map<string, {
    method: string; params: Record<string, unknown>; thread: string; item: string; turn: string;
    stream?: ReturnType<AttachmentStore["createOutboundStream"]>;
    secrets: StreamingSecrets;
    images?: StreamingArtifactImages;
    paths?: StreamingPrivatePaths;
  }>();

  private readonly catalog: CapabilityCatalog;
  private readonly management: HostManagement;
  private readonly richResources: RichResourceBridge;
  private readonly workspaceMutations: WorkspaceMutations;

  private capabilities() {
    const capabilities = currentRemoteCapabilities({
      chunkedHttp: Boolean(this.options.attachments),
      installedApps: this.installedAppsAvailable,
      artifacts: Boolean(this.options.artifacts),
    });
    return {...capabilities, rpcMethods: capabilities.rpcMethods.filter(method =>
      method !== 'host/workspace/migrate' || Boolean(this.options.routingReferences)),
      routing: {opaqueWorkspaceReferences: Boolean(this.options.routingReferences)}};
  }

  constructor(private readonly options: RemoteHostOptions) {
    this.catalog = new CapabilityCatalog(options.codex);
    this.management = new HostManagement(options.codex);
    this.richResources = new RichResourceBridge(options.codex);
    this.workspaceMutations = new WorkspaceMutations(options.codex);
    this.server.on("upgrade", (request, socket, head) => {
      void this.handleUpgrade(request, socket, head).catch(() => socket.destroy());
    });
    this.options.codex.on("notification", (message) => {
      if (/skills\/changed|plugin.*changed|app.*updated/.test(message.method)) this.catalog.invalidate();
      this.broadcastEvent(message.method, message.params ?? {});
    });
    this.options.codex.on("requestResolved", (codexId: string) => {
      for (const [id, pending] of this.pendingServerRequests) {
        if (pending.codexId !== codexId || pending.answered) continue;
        clearTimeout(pending.timeout);
        this.pendingServerRequests.delete(id);
        this.broadcast({ type: "server_response_ack", requestId: id, status: "expired", reason: "resolved_on_desktop" });
      }
    });
    this.options.codex.on("request", (request: AppServerRequest) => this.forwardServerRequest(request));
    this.options.codex.on("exit", (error: Error) => this.broadcast({
      type: "host_error",
      message: error.message,
    }));
  }

  async start(): Promise<{ host: string; port: number; pairing: PairingTicket }> {
    await this.options.auth.load();
    await this.options.codex.start();
    try {
      const installed = await this.options.codex.call("app/installed", { forceRefresh: false });
      if (isObject(installed) && Array.isArray(installed.apps)) {
        this.installedAppsSnapshot = projectInstalledApps(installed);
        this.installedAppsAvailable = true;
      }
    } catch {
      this.installedAppsAvailable = false;
    }
    this.pairingTicket = this.createPairingTicket();
    await new Promise<void>((resolve, reject) => {
      this.server.once("error", reject);
      this.server.listen(this.options.port, this.options.host, () => resolve());
    });
    const address = this.server.address() as AddressInfo;
    this.authRecheckTimer = setInterval(
      () => void this.recheckConnectedDevices(),
      this.options.authRecheckIntervalMs ?? 5_000,
    );
    this.authRecheckTimer.unref();
    this.cleanupTimer = setInterval(() => { void this.options.attachments?.cleanupExpired().catch(() => {}); }, 60_000);
    this.cleanupTimer.unref();
    return { host: this.options.host, port: address.port, pairing: this.pairingTicket };
  }

  createPairingTicket(): PairingTicket {
    this.pairingTicket = this.options.auth.createPairingTicket();
    return this.pairingTicket;
  }

  async stop(): Promise<void> {
    if (this.cleanupTimer) clearInterval(this.cleanupTimer);
    if (this.authRecheckTimer) clearInterval(this.authRecheckTimer);
    this.authRecheckTimer = null;
    for (const pending of this.pendingServerRequests.values()) clearTimeout(pending.timeout);
    this.pendingServerRequests.clear();
    this.attachmentTextStreams.clear();
    for (const socket of this.sockets) socket.close(1001, "Host stopping");
    await new Promise<void>((resolve) => this.server.close(() => resolve()));
    await this.options.codex.stop();
  }

  private async handleHttp(request: IncomingMessage, response: ServerResponse): Promise<void> {
    setSecurityHeaders(response);
    if (!this.requestBudget.take()) {
      response.setHeader("Retry-After", "60");
      return json(response, 429, { error: "Request rate exceeded; retry later" });
    }
    const url = new URL(request.url ?? "/", "http://remote.invalid");
    if (request.method === "GET" && url.pathname === "/healthz") {
      return json(response, 200, {
        ok: true,
        protocolVersion: REMOTE_PROTOCOL_VERSION,
        minProtocolVersion: MIN_REMOTE_PROTOCOL_VERSION,
        supportedProtocolVersions: SUPPORTED_REMOTE_PROTOCOL_VERSIONS,
        capabilities: this.capabilities(),
      });
    }
    if (request.method === "GET" && url.pathname === "/v2/status") {
      const device = await this.options.auth.authenticate(bearerToken(request.headers.authorization));
      if (!device) return json(response, 401, { error: "Device credential is invalid or expired" });
      if (!device.scopes.includes("rpc:read")) return json(response, 403, { error: "Device scope is required: rpc:read" });
      return json(response, 200, {
        ok: true,
        protocolVersion: REMOTE_PROTOCOL_VERSION,
        sessionId: this.sessionId,
        uptimeSeconds: Math.floor((Date.now() - this.startedAt) / 1000),
        connectedSockets: this.sockets.size,
        eventSequence: this.sequence,
        eventJournalDepth: this.eventJournal.length,
        idempotencyEntries: this.idempotency.size,
        installedAppsAvailable: this.installedAppsAvailable,
        attachmentsConfigured: Boolean(this.options.attachments),
        counters: {
          rpcRequests: this.rpcRequests,
          rpcErrors: this.rpcErrors,
          attachmentUploads: this.attachmentUploads,
          attachmentBytes: this.attachmentBytes,
        },
      });
    }
    if (request.method === "POST" && (url.pathname === "/v1/pair" || url.pathname === "/v2/pair")) {
      if (!this.pairingBudget.take()) {
        response.setHeader("Retry-After", "60");
        return json(response, 429, { error: "Pairing rate exceeded; retry later" });
      }
      try {
        const body = await readJson(request);
        const code = typeof body.code === "string" ? body.code : "";
        const deviceName = typeof body.deviceName === "string" ? body.deviceName : "Android device";
        const paired = await this.options.auth.pair(code, deviceName);
        return json(response, 201, {
          protocolVersion: url.pathname === "/v2/pair" ? REMOTE_PROTOCOL_VERSION : 1,
          minProtocolVersion: MIN_REMOTE_PROTOCOL_VERSION,
          supportedProtocolVersions: SUPPORTED_REMOTE_PROTOCOL_VERSIONS,
          capabilities: this.capabilities(),
          device: { id: paired.device.id, name: paired.device.name },
          credential: {
            expiresAt: paired.device.expiresAt,
            scopes: paired.device.scopes,
          },
          token: paired.token,
        });
      } catch (error) {
        return json(response, 401, { error: messageOf(error) });
      }
    }
    if (url.pathname === "/v2/device/rotate" && request.method === "POST") {
      const token = bearerToken(request.headers.authorization);
      const current = await this.options.auth.authenticate(token);
      if (!current) return json(response, 401, { error: "Device credential is invalid or expired" });
      if (!current.scopes.includes("device:rotate")) return json(response, 403, { error: "Device cannot rotate credentials" });
      const rotated = await this.options.auth.rotate(token);
      if (!rotated) return json(response, 401, { error: "Device credential could not be rotated" });
      json(response, 200, {
        device: { id: rotated.device.id, name: rotated.device.name },
        credential: { expiresAt: rotated.device.expiresAt, scopes: rotated.device.scopes },
        token: rotated.token,
      });
      queueMicrotask(() => void this.closeDeviceSockets(rotated.device.id, "Device credential rotated"));
      return;
    }
    if (url.pathname === "/v2/device" && request.method === "DELETE") {
      const token = bearerToken(request.headers.authorization);
      const current = await this.options.auth.authenticate(token);
      if (!current) return json(response, 401, { error: "Device credential is invalid or expired" });
      if (!current.scopes.includes("device:revoke")) return json(response, 403, { error: "Device cannot revoke credentials" });
      await this.options.auth.revoke(current.id);
      await this.closeDeviceSockets(current.id, "Device credential revoked");
      let referenceCleanup = true;
      try { await this.options.routingReferences?.revokeDevice(current.id); }
      catch { referenceCleanup = false; console.error('Revoked device routing cleanup requires host repair'); }
      json(response, 200, { revoked: true, deviceId: current.id, referenceCleanup });
      return;
    }
    const artifactMatch = url.pathname.match(/^\/v2\/artifacts\/([a-f0-9]{64})$/);
    if (artifactMatch && request.method === "GET" && this.options.artifacts) {
      const device = await this.options.auth.authenticate(bearerToken(request.headers.authorization));
      if (!device) return json(response, 401, { error: "Device credential is invalid or expired" });
      if (!device.scopes.includes("attachments:read")) return json(response, 403, { error: "Artifact download permission is required" });
      try {
        const artifact = await this.options.artifacts.download(device.id, artifactMatch[1]);
        response.writeHead(200, { "Content-Type": artifact.descriptor.mimeType, "Content-Length": artifact.descriptor.size,
          "X-Content-SHA256": artifact.descriptor.sha256, "Cache-Control": "no-store", "Content-Disposition": "attachment" });
        artifact.stream.on("error", () => response.destroy());
        response.on("close", () => artifact.stream.destroy());
        artifact.stream.pipe(response);
      } catch { return json(response, 404, { error: "Artifact expired, unavailable, or not owned by this device" }); }
      return;
    }
    const attachmentMatch = url.pathname.match(/^\/v2\/attachments(?:\/([a-f0-9-]{36})(?:\/(complete|preview|download))?)?$/);
    if (attachmentMatch) return this.handleAttachmentHttp(request, response, attachmentMatch[1], attachmentMatch[2]);
    return json(response, 404, { error: "Not found" });
  }

  private async handleAttachmentHttp(
    request: IncomingMessage,
    response: ServerResponse,
    id?: string,
    action?: string,
  ): Promise<void> {
    if (!this.options.attachments) return json(response, 404, { error: "Attachment uploads are not configured" });
    const device = await this.options.auth.authenticate(bearerToken(request.headers.authorization));
    if (!device) return json(response, 401, { error: "Device credential is invalid or expired" });
    const write = request.method === "POST" || request.method === "PUT" || request.method === "DELETE";
    const requiredScope: DeviceScope = write ? "attachments:write" : "attachments:read";
    if (!device.scopes.includes(requiredScope)) return json(response, 403, { error: `Device scope is required: ${requiredScope}` });
    try {
      if (request.method === "POST" && !id) {
        const body = await readJson(request);
        const descriptor = await this.options.attachments.init(device.id, {
          uploadKey: body.uploadKey,
          name: body.name,
          mimeType: body.mimeType,
          size: body.size,
          sha256: body.sha256,
        });
        this.attachmentUploads += 1;
        return json(response, 201, { attachment: descriptor, chunkBytes: this.options.attachments.chunkBytes });
      }
      if (request.method === "GET" && id && (action === "preview" || action === "download")) {
        const preview = action === "preview"
          ? await this.options.attachments.preview(device.id, id)
          : await this.options.attachments.download(device.id, id);
        response.writeHead(200, { "Content-Type": preview.descriptor.mimeType, "Content-Length": preview.descriptor.size,
          "X-Content-SHA256": preview.descriptor.sha256, "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" });
        preview.stream.on("error", () => response.destroy());
        response.on("close", () => preview.stream.destroy());
        preview.stream.pipe(response);
        return;
      }
      if (request.method === "GET" && id && !action) {
        return json(response, 200, { attachment: await this.options.attachments.status(device.id, id) });
      }
      if (request.method === "PUT" && id && !action) {
        const range = parseContentRange(request.headers["content-range"]);
        const bytes = await readBytes(request, this.options.attachments.chunkBytes);
        if (range.end - range.start + 1 !== bytes.length) throw new Error("Content-Range length does not match request body");
        const chunkSha256 = singleHeader(request.headers["x-chunk-sha256"]).toLowerCase();
        const descriptor = await this.options.attachments.writeChunk(device.id, id, range.start, range.total, bytes, chunkSha256);
        this.attachmentBytes += bytes.length;
        return json(response, 200, { attachment: descriptor });
      }
      if (request.method === "POST" && id && action === "complete") {
        return json(response, 200, { attachment: await this.options.attachments.complete(device.id, id) });
      }
      if (request.method === "DELETE" && id && !action) {
        return json(response, 200, { deleted: await this.options.attachments.delete(device.id, id) });
      }
      return json(response, 405, { error: "Method not allowed" });
    } catch (error) {
      return json(response, attachmentHttpStatus(error), { error: messageOf(error), code: attachmentErrorCode(error),
        ...(error instanceof AttachmentSizeError ? {maxBytes: error.maxBytes} : {}) });
    }
  }

  private async handleUpgrade(request: IncomingMessage, socket: import("node:stream").Duplex, head: Buffer): Promise<void> {
    if (!this.requestBudget.take()) {
      socket.end("HTTP/1.1 429 Too Many Requests\r\nConnection: close\r\nContent-Length: 0\r\nRetry-After: 60\r\n\r\n");
      return;
    }
    const url = new URL(request.url ?? "/", "http://remote.invalid");
    if (url.pathname !== "/v1/ws" && url.pathname !== "/v2/ws") {
      socket.end("HTTP/1.1 404 Not Found\r\nConnection: close\r\nContent-Length: 0\r\n\r\n");
      return;
    }
    const token = bearerToken(request.headers.authorization);
    const device = await this.options.auth.authenticate(token);
    if (!device) {
      socket.end("HTTP/1.1 401 Unauthorized\r\nConnection: close\r\nContent-Length: 0\r\n\r\n");
      return;
    }
    this.websocket.handleUpgrade(request, socket, head, (ws) => {
      this.sockets.add(ws);
      const isV2 = url.pathname === "/v2/ws";
      const state: SocketState = {
        deviceId: device.id,
        scopes: [...device.scopes],
        protocolVersion: isV2 ? 0 : 1,
        negotiated: !isV2,
      };
      this.socketStates.set(ws, state);
      ws.on("close", () => {
        if (state.handshakeTimer) clearTimeout(state.handshakeTimer);
        this.socketStates.delete(ws);
        this.sockets.delete(ws);
      });
      ws.on("message", (data, isBinary) => {
        if (isBinary) return ws.close(1003, "Text messages only");
        void this.handleSocketMessage(ws, data.toString());
      });
      if (isV2) {
        state.handshakeTimer = setTimeout(() => ws.close(1002, "Protocol negotiation timed out"), 5_000);
        send(ws, {
          type: "server_hello",
          minProtocolVersion: MIN_REMOTE_PROTOCOL_VERSION,
          maxProtocolVersion: REMOTE_PROTOCOL_VERSION,
          supportedProtocolVersions: SUPPORTED_REMOTE_PROTOCOL_VERSIONS,
          sessionId: this.sessionId,
          device: {
            id: device.id,
            name: device.name,
            credentialExpiresAt: device.expiresAt,
            scopes: device.scopes,
          },
          sequence: this.sequence,
          capabilities: this.capabilities(),
        });
      } else {
        send(ws, {
          type: "hello",
          protocolVersion: 1,
          device: { id: device.id, name: device.name },
          sequence: this.sequence,
        });
        if (state.scopes.includes("approvals:respond")) for (const pending of this.pendingServerRequests.values()) send(ws, pending.message);
      }
    });
  }

  private async handleSocketMessage(socket: WebSocket, text: string): Promise<void> {
    let message: ReturnType<typeof parseClientMessage>;
    try {
      message = parseClientMessage(JSON.parse(text));
    } catch (error) {
      send(socket, { type: "protocol_error", error: messageOf(error) });
      return;
    }
    const state = this.socketStates.get(socket);
    if (!state) {
      socket.close(1011, "Missing connection state");
      return;
    }
    if (message.type === "client_hello") {
      if (state.negotiated) {
        send(socket, { type: "protocol_error", error: "Protocol is already negotiated" });
        return;
      }
      const selectedProtocolVersion = negotiateProtocolVersion(message.supportedProtocolVersions);
      if (!selectedProtocolVersion || selectedProtocolVersion < 2) {
        send(socket, {
          type: "protocol_error",
          error: "No compatible v2 protocol version",
          supportedProtocolVersions: SUPPORTED_REMOTE_PROTOCOL_VERSIONS,
        });
        socket.close(1002, "No compatible protocol version");
        return;
      }
      if (state.handshakeTimer) clearTimeout(state.handshakeTimer);
      state.protocolVersion = selectedProtocolVersion;
      state.negotiated = true;
      if (message.features?.opaqueWorkspaceRouting) {
        if (!this.options.routingReferences) {
          send(socket, {type:'protocol_error',error:'Opaque workspace routing is unavailable on this host'});
          socket.close(1002, 'Workspace routing unavailable');
          return;
        }
        const delivery = new OrderedDelivery(overloaded => socket.close(overloaded ? 1013 : 1011,
          overloaded ? 'Connection backlog exceeded; reconnect' : 'Workspace references unavailable'));
        socket.once('close', () => delivery.stop());
        workspaceWires.set(socket, {routing:new WorkspaceRouting(this.options.routingReferences, state.deviceId), delivery});
      }
      const sameSession = message.lastSessionId === this.sessionId;
      const oldestSequence = this.eventJournal[0]?.sequence ?? this.sequence + 1;
      const canReplay = sameSession && message.lastSequence !== undefined
        && message.lastSequence < this.sequence && message.lastSequence >= oldestSequence - 1;
      const isCurrent = sameSession && message.lastSequence === this.sequence;
      send(socket, {
        type: "hello_ack",
        protocolVersion: selectedProtocolVersion,
        sessionId: this.sessionId,
        sequence: this.sequence,
        replayAvailable: true,
        resyncRequired: message.lastSequence !== undefined && !isCurrent && !canReplay,
        capabilities: this.capabilities(),
        features: {opaqueWorkspaceRouting: workspaceWires.has(socket)},
      });
      if (state.scopes.includes("approvals:respond")) {
        for (const pending of this.pendingServerRequests.values()) send(socket, pending.message);
      }
      if (canReplay) {
        for (const event of this.eventJournal) {
          if (event.sequence > (message.lastSequence ?? -1)) send(socket, event);
        }
      }
      return;
    }
    if (!state.negotiated) {
      send(socket, { type: "protocol_error", error: "client_hello is required before other messages" });
      return;
    }
    if (message.type === "server_response") {
      if (!state.scopes.includes("approvals:respond")) {
        send(socket, { type: "protocol_error", error: "Device is not allowed to answer approvals" });
        return;
      }
      const pending = this.pendingServerRequests.get(message.requestId);
      if (!pending || pending.answered) {
        send(socket, { type: "server_response_ack", requestId: message.requestId,
          status: this.answeredServerRequests.has(message.requestId) ? "answered" : "expired" });
        return;
      }
      let result = message.result;
      if (!message.error) {
        try {
          const wire = workspaceWires.get(socket);
          if (wire && pending.request.method === 'item/permissions/requestApproval') {
            result = await wire.routing.permissionReply(pending.request.params, result);
          } else validateInteractionReply(pending.request.method, pending.request.params, result);
        }
        catch (error) {
          send(socket, { type: "server_response_ack", requestId: message.requestId, status: "invalid", error: messageOf(error) });
          return;
        }
      }
      // Projection persists references asynchronously. Another device or the
      // expiry timer may have settled this request while that work was pending.
      if (!await this.options.auth.isActive(state.deviceId) || socket.readyState !== WebSocket.OPEN) {
        socket.close(4001, 'Device credential unavailable');
        return;
      }
      if (pending.answered || this.pendingServerRequests.get(message.requestId) !== pending) {
        send(socket, {type: 'server_response_ack', requestId: message.requestId,
          status: this.answeredServerRequests.has(message.requestId) ? 'answered' : 'expired'});
        return;
      }
      pending.answered = true;
      try { await this.options.codex.respond(pending.codexId, result, message.error); }
      catch (error) {
        // Do not retry an approval whose delivery outcome is uncertain.
        clearTimeout(pending.timeout);
        this.pendingServerRequests.delete(message.requestId);
        this.broadcast({ type: "server_response_ack", requestId: message.requestId, status: "expired", error: messageOf(error) });
        return;
      }
      clearTimeout(pending.timeout);
      this.pendingServerRequests.delete(message.requestId);
      this.answeredServerRequests.add(message.requestId);
      if (this.answeredServerRequests.size > 1000) this.answeredServerRequests.delete(this.answeredServerRequests.values().next().value!);
      this.broadcast({ type: "server_response_ack", requestId: message.requestId, status: "answered" });
      return;
    }
    if (!ALLOWED_CODEX_METHODS.has(message.method)) {
      send(socket, { type: "rpc_error", id: message.id, error: `Method is not allowed: ${message.method}` });
      return;
    }
    const requiredScope = requiredScopeForMethod(message.method);
    if (!state.scopes.includes(requiredScope)) {
      send(socket, { type: "rpc_error", id: message.id, error: `Device scope is required: ${requiredScope}` });
      return;
    }
    this.rpcRequests += 1;
    let params = { ...(message.params ?? {}) };
    const wire = workspaceWires.get(socket);
    if (message.method === 'host/workspace/migrate') {
      if (!wire) { send(socket, {type:'rpc_error',id:message.id,error:'Private workspace routing must be negotiated'}); return; }
      try { send(socket, {type:'rpc_result',id:message.id,result:{workspaces:await wire.routing.migrate(params.paths)}}); }
      catch { send(socket, {type:'rpc_error',id:message.id,error:'Workspace migration failed; existing drafts are retained'}); }
      return;
    }
    if (wire) {
      try { params = await wire.routing.inbound(params, message.method === 'host/workspace/validate') as Record<string, unknown>; }
      catch { send(socket, {type:'rpc_error',id:message.id,error:'Workspace reference unavailable; refresh the task or project'}); return; }
    }
    try { validateFeatureParams(message.method, params); }
    catch (error) { send(socket, { type: "rpc_error", id: message.id, error: messageOf(error) }); return; }
    try { params = validateRealtimeParams(message.method, params); }
    catch (error) { send(socket, { type: "rpc_error", id: message.id, error: messageOf(error) }); return; }
    if (message.method === "host/artifacts/list") {
      if (!this.options.artifacts) { send(socket, { type: "rpc_error", id: message.id, error: "Artifact download is not configured" }); return; }
      try {
        const result = await this.options.codex.call("thread/read", { threadId: params.threadId, includeTurns: true }) as any;
        const artifacts = await this.options.artifacts.list(state.deviceId, String(params.threadId), result.thread);
        send(socket, { type: "rpc_result", id: message.id, result: { artifacts } });
      } catch { send(socket, { type: "rpc_error", id: message.id, error: "Could not read this task's artifacts" }); }
      return;
    }
    if (message.method === "host/capabilities/list") {
      try {
        const result = await this.catalog.list(String(params.cwd || this.options.defaultCwd || ""), params.refresh === true);
        send(socket, { type: "rpc_result", id: message.id, result });
      } catch {
        send(socket, { type: "rpc_error", id: message.id, error: "Capability discovery failed; check the host working directory" });
      }
      return;
    }
    if (message.method === "host/account/status") {
      try {
        const account = await this.options.codex.call("account/read", { refreshToken: false });
        send(socket, { type: "rpc_result", id: message.id, result: projectAccountStatus(account) });
      } catch (error) {
        send(socket, { type: "rpc_error", id: message.id, error: messageOf(error) });
      }
      return;
    }
    if (message.method === "host/account/usage") {
      const [limits, usage] = await Promise.allSettled([
        this.options.codex.call("account/rateLimits/read", {}),
        this.options.codex.call("account/usage/read", {}),
      ]);
      send(socket, { type: "rpc_result", id: message.id, result: projectAccountUsage(limits, usage) });
      return;
    }
    if (message.method === "host/administration/status") {
      send(socket, { type: "rpc_result", id: message.id, result: {
        auth: { status: "browser_or_device_flow", apiKeysAccepted: false },
        mcp: { status: "oauth_and_reload" },
        plugins: { status: "catalog_install_uninstall", localPathsAccepted: false },
        config: { status: "named_settings_only", settings: ["web_search", "model_verbosity", "model_reasoning_summary"] },
        terminal: { status: "device_scoped", arbitraryEnvironmentAccepted: false },
      } });
      return;
    }
    if (message.method === "host/workspace/files/search") {
      try {
        const cwd = await validatedWorkspace(params.cwd);
        if (typeof params.query !== "string" || !params.query.trim() || params.query.length > 500) throw new Error("Search text must contain 1-500 characters");
        const result = await this.options.codex.call("fuzzyFileSearch", { query: params.query, roots: [cwd], cancellationToken: null });
        send(socket, { type: "rpc_result", id: message.id, result: projectFileSearch(result, cwd) });
      } catch (error) { send(socket, { type: "rpc_error", id: message.id, error: featureError("Workspace file search", error) }); }
      return;
    }
    if (message.method === "host/workspace/file/read") {
      try { send(socket, { type: "rpc_result", id: message.id, result: await readWorkspaceText(params.cwd, params.path) }); }
      catch (error) { send(socket, { type: "rpc_error", id: message.id, error: messageOf(error) }); }
      return;
    }
    if (message.method === "host/file/readReference") {
      try {
        assertExactKeys(params, ["threadId", "reference"], "File reference read");
        send(socket, { type: "rpc_result", id: message.id, result: await this.richResources.readFileReference(params.threadId, params.reference) });
      }
      catch (error) { send(socket, { type: "rpc_error", id: message.id, error: messageOf(error) }); }
      return;
    }
    if (message.method === "host/mcp/resource/read") {
      try {
        assertExactKeys(params, ["threadId", "server", "uri"], "MCP resource read");
        send(socket, { type: "rpc_result", id: message.id, result: this.sanitizePayload(await this.richResources.readMcpResource(params)) });
      }
      catch (error) { send(socket, { type: "rpc_error", id: message.id, error: featureError("MCP resource reading", error) }); }
      return;
    }
    if (message.method === "host/git/diff") {
      try {
        const cwd = await validatedWorkspace(params.cwd);
        const result = await this.options.codex.call("gitDiffToRemote", { cwd });
        send(socket, { type: "rpc_result", id: message.id, result: projectGitDiff(result) });
      } catch (error) { send(socket, { type: "rpc_error", id: message.id, error: featureError("Git diff", error) }); }
      return;
    }
    if (message.method === "host/apps/installed") {
      if (!this.installedAppsAvailable) {
        send(socket, { type: "rpc_error", id: message.id, error: "Stable installed-app discovery is unavailable" });
        return;
      }
      try {
        if (params.forceRefresh === true) {
          this.installedAppsSnapshot = projectInstalledApps(
            await this.options.codex.call("app/installed", { forceRefresh: true, threadId: params.threadId }),
          );
        }
        send(socket, { type: "rpc_result", id: message.id, result: this.installedAppsSnapshot });
      } catch (error) {
        send(socket, { type: "rpc_error", id: message.id, error: messageOf(error) });
      }
      return;
    }
    if (message.method === "host/workspace/validate") {
      const workspaces = await validateWorkspacePaths(params.paths);
      send(socket, { type: "rpc_result", id: message.id, result: { workspaces } }, true);
      return;
    }
    if (message.method === "host/workspace/list") {
      if (wire) {
        send(socket, {type:'rpc_error',id:message.id,error:'Host directory browsing is unavailable with private workspace routing; use projects from the task list'});
        return;
      }
      try {
        const listing = await listWorkspaceDirectories(params.path);
        send(socket, { type: "rpc_result", id: message.id, result: listing });
      } catch (error) {
        send(socket, { type: "rpc_error", id: message.id, error: messageOf(error) });
      }
      return;
    }
    if (message.method === "host/image/read") {
      if (this.options.artifacts) {
        send(socket, { type: "rpc_error", id: message.id, error: "Path-based image reads are disabled; use device-scoped task artifacts" });
        return;
      }
      try {
        const image = await readImageAsset(params.path, this.options.defaultCwd, this.options.imageRoot);
        send(socket, { type: "rpc_result", id: message.id, result: image });
      } catch (error) {
        send(socket, { type: "rpc_error", id: message.id, error: messageOf(error) });
      }
      return;
    }
    if (message.method === "host/image/upload") {
      if (!this.options.imageRoot) {
        send(socket, { type: "rpc_error", id: message.id, error: "Image uploads are not configured" });
        return;
      }
      try {
        const image = await uploadImageAsset(params.data, params.name, this.options.imageRoot);
        send(socket, { type: "rpc_result", id: message.id, result: image });
      } catch (error) {
        send(socket, { type: "rpc_error", id: message.id, error: messageOf(error) });
      }
      return;
    }
    if (message.method === "thread/start" && this.options.defaultCwd && !params.cwd) {
      params.cwd = this.options.defaultCwd;
    }
    const isWrite = requiredScope === "rpc:write";
    const requiresV2Write = HOST_MANAGEMENT_METHODS.has(message.method)
      || WORKSPACE_MUTATION_METHODS.has(message.method)
      || (message.method.startsWith("thread/realtime/") && message.method !== "thread/realtime/listVoices");
    if (requiresV2Write && isWrite && state.protocolVersion < 2) {
      send(socket, { type: "rpc_error", id: message.id, error: "This write operation requires Remote protocol v2" });
      return;
    }
    if (state.protocolVersion >= 2 && isWrite && !message.idempotencyKey) {
      send(socket, { type: "rpc_error", id: message.id, error: "A v2 write RPC requires idempotencyKey" });
      return;
    }
    const execute = async (): Promise<RpcOutcome> => {
      try {
        if (HOST_MANAGEMENT_METHODS.has(message.method)) {
          return { ok: true, result: this.sanitizePayload(await this.management.call(message.method, params, state.deviceId)) };
        }
        if (WORKSPACE_MUTATION_METHODS.has(message.method)) {
          return { ok: true, result: this.sanitizePayload(await this.workspaceMutations.call(message.method, params)) };
        }
        if (message.method === "thread/start" || message.method === "turn/start") {
          params = await resolvePermissionSelection(message.method, params, this.options.codex);
        }
        if ((message.method === "turn/start" || message.method === "turn/steer") && state.protocolVersion >= 2) {
          if (Array.isArray(params.input) && params.input.some((item: any) => ["remoteCapability", "skill", "mention"].includes(item?.type))) {
            const thread = await this.options.codex.call("thread/read", { threadId: params.threadId, includeTurns: false });
            const cwd = isObject(thread) && isObject(thread.thread) ? String(thread.thread.cwd || "") : "";
            const input = wire ? await wire.routing.skillInputs(params.input) : params.input;
            params.input = await this.catalog.resolve(cwd, input);
          }
          params.input = await resolveRemoteAttachmentInputs(params.input, state.deviceId, this.options.attachments);
        }
        let result: unknown;
        try { result = await this.options.codex.call(message.method, params, message.method === "turn/start" ? 60_000 : 30_000); }
        catch (error) {
          // A restarted App Server has persisted history but no loaded thread. This
          // explicit pre-dispatch rejection is safe to resume and retry once.
          if (message.method !== "turn/start" || !messageOf(error).startsWith("thread not found:")) throw error;
          await this.options.codex.call("thread/resume", { threadId: params.threadId });
          result = await this.options.codex.call(message.method, params, 60_000);
        }
        if (message.method === "permissionProfile/list" && isObject(result) && Array.isArray(result.data)) {
          const data = [...result.data];
          if (this.autoReviewAvailable === null) {
            try {
              const probe = await this.options.codex.call("thread/start", {
                cwd: this.options.defaultCwd || process.cwd(), ephemeral: true,
                permissions: ":workspace", approvalsReviewer: "auto_review",
              }) as any;
              this.autoReviewAvailable = probe?.approvalsReviewer === "auto_review";
              const probeId = probe?.thread?.id;
              if (typeof probeId === "string") await this.options.codex.call("thread/unsubscribe", { threadId: probeId }).catch(() => undefined);
            } catch { this.autoReviewAvailable = false; }
          }
          data.push({ id: "local:auto-review", description: "Reviews elevated requests automatically", allowed: this.autoReviewAvailable });
          data.push({ id: CUSTOM_CONFIG_PERMISSION, description: "Codex uses the permission defined in config.toml", allowed: true });
          result = { ...result, data };
        }
        if (message.method === "thread/read" && params.includeTurns !== false) result = await hydrateThreadHistory(result);
        if (message.method === "thread/turns/list") result = await hydrateTurnsPage(this.options.codex, params, result);
        return { ok: true, result: this.sanitizePayload(result) };
      } catch (error) {
        return { ok: false, error: String(publicPayload(messageOf(error), "error")) };
      }
    };
    let outcome: RpcOutcome;
    if (state.protocolVersion >= 2 && isWrite && message.idempotencyKey) {
      this.cleanupIdempotency();
      const key = `${state.deviceId}\u0000${message.method}\u0000${message.idempotencyKey}`;
      const fingerprint = requestFingerprint(params);
      let entry = this.idempotency.get(key);
      if (entry && entry.fingerprint !== fingerprint) {
        send(socket, { type: "rpc_error", id: message.id, error: "Idempotency key was already used with different parameters" });
        return;
      }
      if (!entry) {
        entry = { expiresAt: Date.now() + 24 * 60 * 60_000, fingerprint,
          promise: this.options.ledger ? this.options.ledger.run(key, params, execute) : execute() };
        this.idempotency.set(key, entry);
      }
      try { outcome = await entry.promise; } catch { outcome = { ok: false, error: "Could not confirm this operation safely; inspect the task before retrying" }; }
    } else {
      outcome = await execute();
    }
    if (outcome.ok) send(socket, { type: "rpc_result", id: message.id, result: outcome.result });
    else {
      this.rpcErrors += 1;
      send(socket, { type: "rpc_error", id: message.id, error: outcome.error ?? "Remote call failed" });
    }
  }

  private forwardServerRequest(request: AppServerRequest): void {
    const eligibleSockets = [...this.socketStates.entries()].filter(([socket, state]) =>
      socket.readyState === WebSocket.OPEN && state.negotiated && state.scopes.includes("approvals:respond"));
    // App Server can reissue an outstanding approval when a thread is resumed.
    // Retire the earlier client request so there is only one actionable dialog.
    const params = request.params as Record<string, unknown> | undefined;
    for (const [id, pending] of this.pendingServerRequests) {
      const prior = pending.request.params as Record<string, unknown> | undefined;
      if (pending.codexId === request.id || (request.method === pending.request.method &&
          params?.threadId && params.threadId === prior?.threadId && params?.itemId && params.itemId === prior?.itemId)) {
        if (["item/tool/requestUserInput", "mcpServer/elicitation/request"].includes(request.method) &&
            request.method === pending.request.method && isDeepStrictEqual(request.params, pending.request.params)) {
          // Resume may reissue the same question with a new App Server RPC id.
          // Preserve the phone's draft identity and deadline, but answer the newest RPC.
          pending.codexId = request.id;
          pending.request = request;
          for (const [socket] of eligibleSockets) send(socket, pending.message);
          return;
        }
        clearTimeout(pending.timeout);
        this.pendingServerRequests.delete(id);
        this.broadcast({ type: "server_response_ack", requestId: id, status: "expired", reason: "superseded" });
      }
    }
    const requestId = randomUUID();
    const timeout = setTimeout(() => {
      const pending = this.pendingServerRequests.get(requestId);
      if (!pending || pending.answered) return;
      this.pendingServerRequests.delete(requestId);
      this.broadcast({ type: "server_response_ack", requestId, status: "expired" });
      void Promise.resolve(this.options.codex.respond(pending.codexId, undefined, {
        code: -32002,
        message: "Remote approval request timed out",
      })).catch(() => { /* Desktop-owned requests remain available in the owner UI. */ });
    }, this.options.approvalTimeoutMs ?? 5 * 60_000);
    timeout.unref();
    const message = this.sanitizePayload({
      type: "codex_request",
      requestId,
      method: request.method,
      params: request.method === "item/fileChange/requestApproval"
        ? { ...params, fileChangeReview: fileChangeReview(params, this.eventJournal, this.options.defaultCwd) }
        : request.params,
      expiresAt: Date.now() + (this.options.approvalTimeoutMs ?? 5 * 60_000),
    }) as Record<string, any>;
    this.pendingServerRequests.set(requestId, { codexId: request.id, answered: false, timeout, message, request });
    for (const [socket] of eligibleSockets) send(socket, message);
  }

  private broadcast(message: object): void {
    const safeMessage = this.sanitizePayload(message) as object;
    for (const socket of this.sockets) send(socket, safeMessage);
  }

  private broadcastEvent(method: string, params: unknown): void {
    const routed = this.management.routeNotification(method, params);
    if (routed === null) return;
    if (routed) {
      const sequence = ++this.sequence;
      const event = this.sanitizePayload({ type: "codex_event", sequence, method, params: routed.params }) as object;
      const placeholder = { type: "codex_event", sequence, method: routed.placeholderMethod ?? "host/terminal/activity", params: { replayable: false } };
      this.eventJournal.push(placeholder);
      if (this.eventJournal.length > 500) this.eventJournal.shift();
      for (const [socket, state] of this.socketStates) {
        if (!state.negotiated) continue;
        send(socket, state.deviceId === routed.deviceId ? event : placeholder);
      }
      return;
    }
    if (isObject(params)) {
      const thread = typeof params.threadId === "string" ? params.threadId : "";
      const item = typeof params.itemId === "string" ? params.itemId :
        isObject(params.item) && typeof params.item.id === "string" ? params.item.id : "";
      const turn = typeof params.turnId === "string" ? params.turnId :
        isObject(params.turn) && typeof params.turn.id === "string" ? params.turn.id : "";
      const flush = (key: string) => {
        const pending = this.attachmentTextStreams.get(key);
        if (!pending) return;
        this.attachmentTextStreams.delete(key);
        const secretTail = pending.secrets.push(pending.stream?.finish() ?? "") + pending.secrets.finish();
        const imageTail = pending.images ? pending.images.push(secretTail) + pending.images.finish() : secretTail;
        const tail = pending.paths ? pending.paths.push(imageTail) + pending.paths.finish() : imageTail;
        if (tail) this.publishEvent(pending.method, { ...pending.params, delta: tail });
      };
      if (method === "item/completed" || method === "turn/completed") {
        for (const [key, pending] of this.attachmentTextStreams) {
          if (pending.thread === thread && (method === "item/completed" ? pending.item === item : !turn || !pending.turn || pending.turn === turn)) flush(key);
        }
      }
      if (thread && item && typeof params.delta === "string" && /(?:Delta|\/delta)$/.test(method)) {
        const key = JSON.stringify([method, thread, turn, item, params.contentIndex, params.summaryIndex]);
        if (!this.attachmentTextStreams.has(key)) {
          if (this.attachmentTextStreams.size >= 256) flush(this.attachmentTextStreams.keys().next().value!);
          this.attachmentTextStreams.set(key, { method, params, thread, turn, item, stream: this.options.attachments?.createOutboundStream(), secrets: new StreamingSecrets(), images: this.options.artifacts ? new StreamingArtifactImages() : undefined, paths: this.options.artifacts ? new StreamingPrivatePaths() : undefined });
        }
        const pending = this.attachmentTextStreams.get(key)!;
        pending.params = { ...params, delta: "" };
        const secretDelta = pending.secrets.push(pending.stream?.push(params.delta) ?? params.delta);
        const imageDelta = pending.images?.push(secretDelta) ?? secretDelta;
        const delta = pending.paths?.push(imageDelta) ?? imageDelta;
        if (delta) this.publishEvent(method, { ...params, delta });
        return;
      }
    }
    this.publishEvent(method, params);
  }

  private publishEvent(method: string, params: unknown): void {
    const event = this.sanitizePayload({
      type: "codex_event",
      sequence: ++this.sequence,
      method,
      params,
    }) as Record<string, unknown> & { sequence: number };
    this.eventJournal.push(event);
    if (this.eventJournal.length > 500) this.eventJournal.shift();
    this.broadcast(event);
  }

  private cleanupIdempotency(): void {
    const now = Date.now();
    for (const [key, entry] of this.idempotency) if (entry.expiresAt <= now) this.idempotency.delete(key);
  }

  private sanitizePayload(value: unknown): unknown {
    const projected = this.richResources.project(value);
    return publicPayload(this.options.attachments?.redactOutbound(projected) ?? projected, "", Boolean(this.options.artifacts));
  }

  private async closeDeviceSockets(deviceId: string, reason: string): Promise<void> {
    for (const [socket, state] of this.socketStates) {
      if (state.deviceId === deviceId) socket.close(4001, reason);
    }
    if (/expired|revoked|unavailable/i.test(reason)) await this.management.removeDevice(deviceId);
  }

  private async recheckConnectedDevices(): Promise<void> {
    const deviceIds = [...new Set([
      ...[...this.socketStates.values()].map((state) => state.deviceId),
      ...this.management.ownerDeviceIds(),
      ...(this.options.routingReferences ? this.options.auth.list().map((device) => device.id) : []),
    ])];
    await Promise.all(deviceIds.map(async (deviceId) => {
      try {
        if (!await this.options.auth.isActive(deviceId)) {
          await this.closeDeviceSockets(deviceId, "Device credential expired or revoked");
          await this.options.routingReferences?.revokeDevice(deviceId);
        }
      } catch (error) {
        console.error(`Could not recheck device ${deviceId}: ${messageOf(error)}`);
      }
    }));
  }
}

export async function hydrateTurnsPage(
  codex: Pick<CodexAppServer, "call">,
  params: Record<string, unknown>,
  value: unknown,
  sessionRoot?: string,
): Promise<unknown> {
  if (!isObject(value) || !Array.isArray(value.data) || value.data.length === 0 || typeof params.threadId !== "string") return value;
  try {
    const metadata = await codex.call("thread/read", { threadId: params.threadId, includeTurns: false });
    if (!isObject(metadata) || !isObject(metadata.thread)) return value;
    const envelope = { thread: { ...metadata.thread, turns: value.data } };
    await hydrateThreadHistory(envelope, sessionRoot);
    return { ...value, data: envelope.thread.turns };
  } catch {
    return value;
  }
}

export function projectAccountStatus(value: unknown): Record<string, unknown> {
  const result = isObject(value) ? value : {};
  const account = isObject(result.account) ? result.account : null;
  const requiresOpenaiAuth = result.requiresOpenaiAuth === true;
  return {
    ready: !requiresOpenaiAuth || account !== null,
    authenticated: account !== null,
    requiresOpenaiAuth,
    authMode: account && typeof account.type === "string" ? account.type : null,
    email: account && typeof account.email === "string" ? account.email : null,
    planType: account && typeof account.planType === "string" ? account.planType : null,
    credentialSource: account && typeof account.credentialSource === "string" ? account.credentialSource : null,
  };
}

export function projectAccountUsage(
  limits: PromiseSettledResult<unknown>,
  usage: PromiseSettledResult<unknown>,
): Record<string, unknown> {
  return {
    rateLimits: limits.status === "fulfilled"
      ? { supported: true, data: projectUsageValue(limits.value, new Set([
        "rateLimits", "rateLimitsByLimitId", "rateLimitResetCredits", "limitId", "limitName", "primary", "secondary",
        "usedPercent", "windowDurationMins", "resetsAt", "credits", "hasCredits", "unlimited", "balance",
        "individualLimit", "limit", "used", "remainingPercent", "spendControlReached", "planType", "rateLimitReachedType",
        "availableCount", "resetType", "status", "grantedAt", "expiresAt", "title", "description",
      ])) }
      : { supported: false, error: featureError("Account rate limits", limits.reason) },
    tokenUsage: usage.status === "fulfilled"
      ? { supported: true, data: projectUsageValue(usage.value, new Set([
        "summary", "dailyUsageBuckets", "lifetimeTokens", "peakDailyTokens", "longestRunningTurnSec",
        "currentStreakDays", "longestStreakDays", "startDate", "tokens",
      ])) }
      : { supported: false, error: featureError("Account token usage", usage.reason) },
  };
}

function projectUsageValue(value: unknown, allowed: ReadonlySet<string>, depth = 0, dynamicKeys = false): unknown {
  if (depth > 8) return null;
  if (value === null || typeof value === "boolean" || typeof value === "number") return value;
  if (typeof value === "bigint") return value.toString();
  if (typeof value === "string") return value.slice(0, 1000);
  if (Array.isArray(value)) return value.slice(0, 400).map((item) => projectUsageValue(item, allowed, depth + 1));
  if (!isObject(value)) return null;
  return Object.fromEntries(Object.entries(value).filter(([key]) => allowed.has(key) || dynamicKeys)
    .slice(0, 400).map(([key, item]) => [key.slice(0, 128), projectUsageValue(item, allowed, depth + 1, key === "rateLimitsByLimitId")]));
}

export function projectInstalledApps(value: unknown): Record<string, unknown> {
  const apps = isObject(value) && Array.isArray(value.apps) ? value.apps : [];
  return {
    source: "app/installed",
    apps: apps.filter(isObject).map((app) => ({
      id: typeof app.id === "string" ? app.id : "",
      name: typeof app.runtimeName === "string" && app.runtimeName ? app.runtimeName : null,
      enabled: app.enabled === true,
      callable: app.callable === true,
    })).filter((app) => app.id),
  };
}

export async function resolveRemoteAttachmentInputs(
  value: unknown,
  deviceId: string,
  store?: AttachmentStore,
): Promise<unknown[]> {
  if (!Array.isArray(value)) throw new Error("Turn input must be an array");
  const resolved: unknown[] = [];
  let attachmentCount = 0;
  for (const item of value) {
    if (!isObject(item)) throw new Error("Turn input item must be an object");
    if (item.type === "localImage") throw new Error("v2 clients must use a remote attachment id instead of a Host path");
    if (item.type !== "remoteAttachment") {
      resolved.push(item);
      continue;
    }
    if (!store) throw new Error("Attachment uploads are not configured");
    if (++attachmentCount > 4) throw new Error("A turn can include at most 4 attachments");
    const id = typeof item.attachmentId === "string" ? item.attachmentId : "";
    const attachment = await store.resolveForTurn(deviceId, id);
    if (attachment.kind === "image") {
      resolved.push({ type: "localImage", path: attachment.path });
    } else {
      const encodedName = Buffer.from(attachment.name, "utf8").toString("base64url");
      resolved.push({
        type: "text",
        text: `[[CODEX_REMOTE_ATTACHMENT:${encodedName}]]The user attached ${attachment.kind} file ${JSON.stringify(attachment.name)}. Its verified, read-only Host path is ${JSON.stringify(attachment.path)}. Read it only if needed for this request.[[/CODEX_REMOTE_ATTACHMENT]]`,
      });
    }
  }
  return resolved;
}

const workspaceWires = new WeakMap<WebSocket, {routing: WorkspaceRouting; delivery: OrderedDelivery}>();
function send(socket: WebSocket, message: object, workspacePaths = false): void {
  if (socket.readyState !== WebSocket.OPEN) return;
  // Host-local RPC handlers also use this boundary; they must not bypass
  // description/diagnostic sanitization performed for upstream RPC results.
  const safe = publicPayload(message);
  const wire = workspaceWires.get(socket);
  if (!wire) {
    const body = JSON.stringify(safe);
    if (socket.bufferedAmount + Buffer.byteLength(body) > 64 * 1024 * 1024) {
      socket.close(1013, 'Connection backlog exceeded; reconnect');
    } else socket.send(body);
    return;
  }
  wire.delivery.enqueue(Buffer.byteLength(JSON.stringify(safe)), async () => {
    if (socket.readyState !== WebSocket.OPEN) return;
    const projected = await wire.routing.outbound(safe, workspacePaths);
    if (socket.readyState === WebSocket.OPEN) {
      const body = JSON.stringify(projected);
      if (socket.bufferedAmount + Buffer.byteLength(body) > 64 * 1024 * 1024) {
        wire.delivery.stop();
        socket.close(1013, 'Connection backlog exceeded; reconnect');
        return;
      }
      socket.send(body);
    }
  });
}

function bearerToken(header: string | undefined): string {
  const match = header?.match(/^Bearer ([A-Za-z0-9_-]{43})$/i);
  return match?.[1] ?? "";
}

function setSecurityHeaders(response: ServerResponse): void {
  response.setHeader("Cache-Control", "no-store");
  response.setHeader("X-Content-Type-Options", "nosniff");
  response.setHeader("Content-Security-Policy", "default-src 'none'");
}

function json(response: ServerResponse, status: number, value: object): void {
  const body = JSON.stringify(value);
  response.writeHead(status, { "Content-Type": "application/json; charset=utf-8", "Content-Length": Buffer.byteLength(body) });
  response.end(body);
}

async function readJson(request: IncomingMessage): Promise<Record<string, unknown>> {
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of request) {
    const buffer = Buffer.from(chunk);
    size += buffer.length;
    if (size > 32 * 1024) throw new Error("Request is too large");
    chunks.push(buffer);
  }
  const value = JSON.parse(Buffer.concat(chunks).toString("utf8"));
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("JSON object required");
  return value as Record<string, unknown>;
}

async function readBytes(request: IncomingMessage, limit: number): Promise<Buffer> {
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of request) {
    const buffer = Buffer.from(chunk);
    size += buffer.length;
    if (size > limit) throw new Error("Chunk is too large");
    chunks.push(buffer);
  }
  return Buffer.concat(chunks);
}

function parseContentRange(value: string | string[] | undefined): { start: number; end: number; total: number } {
  const match = singleHeader(value).match(/^bytes (\d+)-(\d+)\/(\d+)$/);
  if (!match) throw new Error("A valid Content-Range header is required");
  const [start, end, total] = match.slice(1).map(Number);
  if (![start, end, total].every(Number.isSafeInteger) || start < 0 || end < start || total <= end) {
    throw new Error("Content-Range is invalid");
  }
  return { start, end, total };
}

function singleHeader(value: string | string[] | undefined): string {
  return Array.isArray(value) ? value[0] ?? "" : value ?? "";
}

function attachmentHttpStatus(error: unknown): number {
  const message = messageOf(error);
  if (message.includes("does not belong")) return 403;
  if (message.startsWith("Attachment exceeds the ")) return 413;
  if ((error as NodeJS.ErrnoException).code === "ENOENT") return 404;
  if (message.includes("offset") || message.includes("already complete") || message.includes("incomplete")) return 409;
  return 400;
}

function messageOf(error: unknown): string {
  // OS errors may reference arbitrary mount points, not just known private roots.
  // Do not serialize their message/path/destination into a client response.
  if (error instanceof Error && ('path' in error || 'dest' in error)) {
    const code = (error as NodeJS.ErrnoException).code;
    const known = ['ENOENT', 'EACCES', 'EPERM', 'ENOSPC', 'EROFS', 'EIO', 'EMFILE', 'ENFILE'];
    return `Host file operation failed${code && known.includes(code) ? ` (${code})` : ''}`;
  }
  return String(publicPayload(error instanceof Error ? error.message : String(error), "error"));
}

function featureError(feature: string, error: unknown): string {
  const message = messageOf(error);
  if (/method not found|not supported|unsupported|unknown method/i.test(message)) {
    return `${feature} is unavailable on this Codex version`;
  }
  return `${feature} is unavailable: ${message}`;
}

export function validateRealtimeParams(method: string, params: Record<string, unknown>): Record<string, unknown> {
  if (!method.startsWith("thread/realtime/")) return params;
  if (method === "thread/realtime/listVoices") {
    if (Object.keys(params).length > 0) throw new Error("Realtime voice listing takes no parameters");
    return {};
  }
  const threadId = typeof params.threadId === "string" && params.threadId.length <= 256 ? params.threadId : "";
  if (!threadId) throw new Error("A valid threadId is required");
  if (method === "thread/realtime/start") {
    const allowed = new Set(["threadId", "outputModality", "transport", "voice", "includeStartupContext", "version", "model", "prompt", "initialItems"]);
    const unknown = Object.keys(params).find((key) => !allowed.has(key));
    if (unknown) throw new Error(`Realtime start option is not allowed: ${unknown}`);
    if (!isObject(params.transport) || params.transport.type !== "websocket" || Object.keys(params.transport).length !== 1) {
      throw new Error("Remote realtime requires websocket transport");
    }
    if (!['text', 'audio'].includes(String(params.outputModality))) throw new Error("Realtime outputModality is invalid");
    const result: Record<string, unknown> = { threadId, outputModality: params.outputModality, transport: { type: "websocket" } };
    if (params.voice !== undefined && params.voice !== null) {
      if (typeof params.voice !== "string" || params.voice.length > 100 || !/^[A-Za-z0-9._-]+$/.test(params.voice)) throw new Error("Realtime voice is invalid");
      result.voice = params.voice;
    }
    if (params.includeStartupContext !== undefined && params.includeStartupContext !== null) {
      if (typeof params.includeStartupContext !== "boolean") throw new Error("Realtime includeStartupContext must be boolean");
      result.includeStartupContext = params.includeStartupContext;
    }
    if (params.version !== undefined && params.version !== null) {
      if (!["v1", "v2", "v3"].includes(String(params.version))) throw new Error("Realtime version is invalid");
      result.version = params.version;
    }
    if (params.model !== undefined && params.model !== null) {
      if (typeof params.model !== "string" || !params.model || params.model.length > 200 || /[\0\r\n]/.test(params.model)) throw new Error("Realtime model is invalid");
      result.model = params.model;
    }
    if (params.prompt !== undefined && params.prompt !== null) {
      if (typeof params.prompt !== "string" || params.prompt.length > 16_000 || /\0/.test(params.prompt)) throw new Error("Realtime prompt is invalid");
      result.prompt = params.prompt;
    }
    if (params.initialItems !== undefined && params.initialItems !== null) {
      if (params.version !== "v3") throw new Error("Realtime initialItems require version v3");
      if (!Array.isArray(params.initialItems) || params.initialItems.length > 128) throw new Error("Realtime initialItems exceed the 128 item limit");
      let estimatedTokens = 0;
      result.initialItems = params.initialItems.map((item) => {
        if (!isObject(item) || !["user", "developer", "assistant"].includes(String(item.role))
          || typeof item.text !== "string" || !item.text || item.text.length > 16_000 || /\0/.test(item.text)
          || Object.keys(item).some((key) => key !== "role" && key !== "text")) throw new Error("Realtime initial item is invalid");
        estimatedTokens += Math.max([...item.text].length, Math.ceil(Buffer.byteLength(item.text, "utf8") / 4));
        return { role: item.role, text: item.text };
      });
      if (estimatedTokens > 8_192) throw new Error("Realtime initialItems exceed the 8192 estimated token limit");
    }
    return result;
  }
  if (method === "thread/realtime/appendAudio") {
    if (!isObject(params.audio) || typeof params.audio.data !== "string" || params.audio.data.length > 350_000
      || !/^[A-Za-z0-9+/]*={0,2}$/.test(params.audio.data)) throw new Error("Realtime audio is invalid");
    const bytes = Buffer.from(params.audio.data, "base64");
    if (bytes.length === 0 || bytes.length > 256 * 1024 || bytes.length % 2 !== 0) throw new Error("Realtime audio must contain at most 256 KiB of PCM16LE");
    if (params.audio.sampleRate !== 24_000 || params.audio.numChannels !== 1) throw new Error("Realtime audio must be mono PCM16LE at 24 kHz");
    if (params.audio.samplesPerChannel !== null && params.audio.samplesPerChannel !== bytes.length / 2) throw new Error("Realtime audio sample count does not match its bytes");
    const itemId = params.audio.itemId;
    if (itemId !== null && itemId !== undefined && (typeof itemId !== "string" || itemId.length > 256)) throw new Error("Realtime audio itemId is invalid");
    return { threadId, audio: { data: params.audio.data, sampleRate: 24_000, numChannels: 1,
      samplesPerChannel: bytes.length / 2, itemId: itemId ?? null } };
  }
  if (method === "thread/realtime/appendText") {
    if (params.role !== "user") throw new Error("Remote realtime text role must be user");
    if (typeof params.text !== "string" || !params.text || params.text.length > 16_000) throw new Error("Realtime text must contain 1-16000 characters");
    return { threadId, text: params.text, role: "user" };
  }
  if (method === "thread/realtime/appendSpeech") {
    if (typeof params.text !== "string" || !params.text || params.text.length > 16_000) throw new Error("Realtime speech must contain 1-16000 characters");
    return { threadId, text: params.text };
  }
  if (method === "thread/realtime/stop") return { threadId };
  return params;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function assertExactKeys(value: Record<string, unknown>, allowed: readonly string[], label: string): void {
  const unknown = Object.keys(value).find((key) => !allowed.includes(key));
  if (unknown) throw new Error(`${label} parameter is not allowed: ${unknown}`);
}

function requestFingerprint(value: unknown): string {
  const canonical = (item: unknown): unknown => {
    if (Array.isArray(item)) return item.map(canonical);
    if (isObject(item)) return Object.fromEntries(Object.keys(item).sort().map((key) => [key, canonical(item[key])]));
    return item;
  };
  return createHash("sha256").update(JSON.stringify(canonical(value))).digest("hex");
}

function attachmentErrorCode(error: unknown): string {
  const message = error instanceof Error ? error.message : "";
  if (/Unsupported attachment type/.test(message)) return "unsupported_type";
  if (/quota/.test(message)) return "quota_exceeded";
  if (/limit|too large/.test(message)) return "too_large";
  if (/signature|MIME|UTF-8|binary data|JSON attachment|ZIP directory|Office document|Audio content|Video content/.test(message)) return "invalid_content";
  return "upload_failed";
}
