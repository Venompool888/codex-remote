import type { DeviceScope } from "./auth.js";
import { HOST_MANAGEMENT_WRITES } from "./host-management.js";
import { WORKSPACE_MUTATION_WRITES, WORKSPACE_TEXT_WRITES_SUPPORTED } from "./workspace-mutations.js";

export const REMOTE_PROTOCOL_VERSION = 2;
export const MIN_REMOTE_PROTOCOL_VERSION = 1;
export const SUPPORTED_REMOTE_PROTOCOL_VERSIONS = [2, 1] as const;

export const ALLOWED_CODEX_METHODS = new Set([
  "host/workspace/validate",
  "host/workspace/migrate",
  "host/workspace/list",
  "host/image/read",
  "host/image/upload",
  "host/account/status",
  "host/account/usage",
  "host/administration/status",
  "host/account/login/start",
  "host/account/login/cancel",
  "host/account/logout",
  "host/mcp/status",
  "host/mcp/oauth/start",
  "host/mcp/reload",
  "host/plugin/catalog",
  "host/plugin/install",
  "host/plugin/uninstall",
  "host/settings/read",
  "host/settings/set",
  "host/skill/setEnabled",
  "host/thread/memoryMode/set",
  "host/terminal/execute",
  "host/terminal/write",
  "host/terminal/resize",
  "host/terminal/kill",
  "host/terminal/list",
  "host/workspace/files/search",
  "host/workspace/file/read",
  "host/file/readReference",
  "host/mcp/resource/read",
  "host/workspace/text/save",
  "host/workspace/text/create",
  "host/memory/reset",
  "host/thread/backgroundTerminals/list",
  "host/thread/backgroundTerminals/terminate",
  "host/thread/backgroundTerminals/clean",
  "host/git/diff",
  "host/apps/installed",
  "host/capabilities/list",
  "host/artifacts/list",
  "thread/list",
  "thread/read",
  "thread/start",
  "thread/resume",
  "thread/fork",
  "thread/archive",
  "thread/unarchive",
  "thread/delete",
  "thread/name/set",
  "thread/search",
  "thread/searchOccurrences",
  "thread/turns/list",
  "thread/items/list",
  "thread/compact/start",
  "thread/goal/set",
  "thread/goal/get",
  "thread/goal/clear",
  "review/start",
  "thread/realtime/listVoices",
  "thread/realtime/start",
  "thread/realtime/appendAudio",
  "thread/realtime/appendText",
  "thread/realtime/appendSpeech",
  "thread/realtime/stop",
  "turn/start",
  "turn/steer",
  "turn/interrupt",
  "model/list",
  "collaborationMode/list",
  "permissionProfile/list",
  "skills/list",
  "plugin/list",
]);

export interface ClientRpcMessage {
  type: "rpc";
  id: string;
  method: string;
  params?: Record<string, unknown>;
  idempotencyKey?: string;
}

export interface ClientServerResponseMessage {
  type: "server_response";
  requestId: string;
  result?: unknown;
  error?: { code: number; message: string };
}

export interface ClientHelloMessage {
  type: "client_hello";
  supportedProtocolVersions: number[];
  client: {
    name: string;
    version: string;
    platform?: string;
  };
  lastSequence?: number;
  lastSessionId?: string;
  features?: { opaqueWorkspaceRouting?: boolean };
}

export interface RemoteCapabilities {
  rpcMethods: string[];
  attachments: {
    imageBase64: boolean;
    chunkedHttp: boolean;
    restrictedImagePreview: boolean;
    restrictedArtifactImages: boolean;
    acceptedMimeTypes: string[];
    maxFilesPerTurn: number;
    maxBytesPerFile: number;
    maxBytesByKind?: Record<string, number>;
  };
  events: { sequenced: boolean; replay: boolean };
  interactions: { userInput: boolean; elicitation: boolean; approvals: boolean; replay: boolean; acknowledgement: boolean; fileChangeReview: boolean };
  skills: { list: boolean; changedEvents: boolean };
  plugins: { installedApps: boolean; experimentalList: boolean };
  account: { status: boolean; usage: boolean; remoteLogin: boolean };
  workspaceFiles: { search: boolean; read: boolean; maxReadBytes: number };
  richResources: { opaqueFileReferences: boolean; mcpRead: boolean; guardianDeniedApproval: boolean };
  workspaceMutations: { textSave: boolean; memoryReset: boolean; backgroundTerminals: boolean };
  git: { diff: boolean; maxDiffBytes: number };
  administration: { status: boolean; authFlows: boolean; mcp: boolean; plugins: boolean; namedSettings: boolean; terminalControl: boolean };
  auth: {
    deviceBearer: boolean;
    expires: boolean;
    scoped: boolean;
    rotation: boolean;
    selfRevocation: boolean;
    revocationClosesSockets: boolean;
  };
}

export type ClientMessage = ClientRpcMessage | ClientServerResponseMessage | ClientHelloMessage;

export function parseClientMessage(value: unknown): ClientMessage {
  if (!value || typeof value !== "object") throw new Error("Message must be an object");
  const message = value as Record<string, unknown>;
  if (message.type === "client_hello") {
    if (!Array.isArray(message.supportedProtocolVersions)
      || message.supportedProtocolVersions.length === 0
      || message.supportedProtocolVersions.some((version) => !Number.isInteger(version) || version < 1)) {
      throw new Error("client_hello supportedProtocolVersions must contain positive integers");
    }
    if (!isObject(message.client)
      || typeof message.client.name !== "string" || !message.client.name
      || typeof message.client.version !== "string" || !message.client.version) {
      throw new Error("client_hello client name and version are required");
    }
    if (message.lastSequence !== undefined
      && (!Number.isSafeInteger(message.lastSequence) || (message.lastSequence as number) < 0)) {
      throw new Error("client_hello lastSequence must be a non-negative safe integer");
    }
    if (message.lastSessionId !== undefined && (typeof message.lastSessionId !== "string" || !message.lastSessionId)) {
      throw new Error("client_hello lastSessionId must be a non-empty string");
    }
    if (message.features !== undefined && (!isObject(message.features) ||
        (message.features.opaqueWorkspaceRouting !== undefined && typeof message.features.opaqueWorkspaceRouting !== 'boolean'))) {
      throw new Error('client_hello features must contain boolean feature flags');
    }
    return {
      type: "client_hello",
      supportedProtocolVersions: [...new Set(message.supportedProtocolVersions as number[])],
      client: {
        name: message.client.name,
        version: message.client.version,
        platform: typeof message.client.platform === "string" ? message.client.platform : undefined,
      },
      lastSequence: message.lastSequence as number | undefined,
      lastSessionId: message.lastSessionId as string | undefined,
      features: isObject(message.features) ? {opaqueWorkspaceRouting: message.features.opaqueWorkspaceRouting === true} : undefined,
    };
  }
  if (message.type === "rpc") {
    if (typeof message.id !== "string" || !message.id) throw new Error("RPC id is required");
    if (typeof message.method !== "string" || !message.method) throw new Error("RPC method is required");
    if (message.idempotencyKey !== undefined
      && (typeof message.idempotencyKey !== "string" || !message.idempotencyKey || message.idempotencyKey.length > 200)) {
      throw new Error("RPC idempotencyKey must be a non-empty string of at most 200 characters");
    }
    return {
      type: "rpc",
      id: message.id,
      method: message.method,
      params: isObject(message.params) ? message.params : {},
      idempotencyKey: message.idempotencyKey as string | undefined,
    };
  }
  if (message.type === "server_response") {
    if (typeof message.requestId !== "string" || !message.requestId) {
      throw new Error("Server response requestId is required");
    }
    return {
      type: "server_response",
      requestId: message.requestId,
      result: message.result,
      error: isObject(message.error) && typeof message.error.message === "string"
        ? { code: typeof message.error.code === "number" ? message.error.code : -32000, message: message.error.message }
        : undefined,
    };
  }
  throw new Error("Unsupported message type");
}

export function negotiateProtocolVersion(clientVersions: readonly number[]): number | null {
  return SUPPORTED_REMOTE_PROTOCOL_VERSIONS.find((version) => clientVersions.includes(version)) ?? null;
}

export function currentRemoteCapabilities(options: { chunkedHttp?: boolean; installedApps?: boolean; artifacts?: boolean } = {}): RemoteCapabilities {
  return {
    rpcMethods: [...ALLOWED_CODEX_METHODS].filter((method) => (method !== "host/artifacts/list" || options.artifacts === true) &&
      (method !== "host/image/read" || options.artifacts !== true) &&
      (!["host/workspace/text/save", "host/workspace/text/create"].includes(method) || WORKSPACE_TEXT_WRITES_SUPPORTED)).sort(),
    attachments: {
      imageBase64: true,
      chunkedHttp: options.chunkedHttp === true,
      restrictedImagePreview: options.chunkedHttp === true,
      restrictedArtifactImages: options.artifacts === true,
      acceptedMimeTypes: [
        "application/json",
        "application/javascript",
        "application/xml",
        "application/yaml",
        "application/x-yaml",
        // Generic provider MIME types still require a supported extension and valid content.
        "application/octet-stream",
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/zip",
        "application/x-zip-compressed",
        "image/gif",
        "image/jpeg",
        "image/png",
        "image/webp",
        "audio/mpeg",
        "audio/mp3",
        "audio/mp4",
        "audio/ogg",
        "audio/wav",
        "audio/x-m4a",
        "audio/x-wav",
        "video/mp4",
        "video/quicktime",
        "video/webm",
        "text/*",
      ],
      maxFilesPerTurn: 4,
      maxBytesPerFile: 20 * 1024 * 1024,
      maxBytesByKind: {image:20*1024*1024,pdf:20*1024*1024,archive:20*1024*1024,document:20*1024*1024,
        audio:20*1024*1024,video:20*1024*1024,text:5*1024*1024,code:5*1024*1024},
    },
    events: { sequenced: true, replay: true },
    interactions: { userInput: true, elicitation: true, approvals: true, replay: true, acknowledgement: true, fileChangeReview: true },
    skills: { list: true, changedEvents: true },
    plugins: { installedApps: options.installedApps === true, experimentalList: true },
    account: { status: true, usage: true, remoteLogin: true },
    workspaceFiles: { search: true, read: true, maxReadBytes: 512 * 1024 },
    richResources: { opaqueFileReferences: true, mcpRead: true, guardianDeniedApproval: false },
    workspaceMutations: { textSave: WORKSPACE_TEXT_WRITES_SUPPORTED, memoryReset: true, backgroundTerminals: true },
    git: { diff: true, maxDiffBytes: 1024 * 1024 },
    administration: { status: true, authFlows: true, mcp: true, plugins: true, namedSettings: true, terminalControl: true },
    auth: {
      deviceBearer: true,
      expires: true,
      scoped: true,
      rotation: true,
      selfRevocation: true,
      revocationClosesSockets: true,
    },
  };
}

export function requiredScopeForMethod(method: string): DeviceScope {
  if (HOST_MANAGEMENT_WRITES.has(method)) return "rpc:write";
  if (WORKSPACE_MUTATION_WRITES.has(method)) return "rpc:write";
  if (method === "host/workspace/migrate") return "rpc:write";
  if (method === "host/image/upload") return "attachments:write";
  if (method === "host/image/read") return "attachments:read";
  if (method.startsWith("turn/") || (method.startsWith("thread/realtime/") && method !== "thread/realtime/listVoices")
    || ["thread/start", "thread/resume", "thread/fork", "thread/archive", "thread/unarchive", "thread/delete",
      "thread/name/set", "thread/compact/start", "thread/goal/set", "thread/goal/clear", "review/start"].includes(method)) {
    return "rpc:write";
  }
  return "rpc:read";
}

function isObject(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}
