import type { DeviceScope } from "./auth.js";

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
  account: { status: boolean; remoteLogin: boolean };
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
      (method !== "host/image/read" || options.artifacts !== true)).sort(),
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
        "application/zip",
        "application/x-zip-compressed",
        "image/gif",
        "image/jpeg",
        "image/png",
        "image/webp",
        "text/*",
      ],
      maxFilesPerTurn: 4,
      maxBytesPerFile: 20 * 1024 * 1024,
      maxBytesByKind: {image:20*1024*1024,pdf:20*1024*1024,archive:20*1024*1024,text:5*1024*1024,code:5*1024*1024},
    },
    events: { sequenced: true, replay: true },
    interactions: { userInput: true, elicitation: true, approvals: true, replay: true, acknowledgement: true, fileChangeReview: true },
    skills: { list: true, changedEvents: true },
    plugins: { installedApps: options.installedApps === true, experimentalList: true },
    account: { status: true, remoteLogin: false },
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
  if (method === "host/workspace/migrate") return "rpc:write";
  if (method === "host/image/upload") return "attachments:write";
  if (method === "host/image/read") return "attachments:read";
  if (method.startsWith("turn/")
    || ["thread/start", "thread/resume", "thread/fork", "thread/archive", "thread/unarchive"].includes(method)) {
    return "rpc:write";
  }
  return "rpc:read";
}

function isObject(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}
