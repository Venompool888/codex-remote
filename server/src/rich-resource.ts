import { createHash } from "node:crypto";
import { realpathSync } from "node:fs";
import { isAbsolute, relative, resolve, sep } from "node:path";
import type { CodexAppServer } from "./codex-app-server.js";
import { readWorkspaceText, validatedWorkspace } from "./workspace-features.js";

type Json = Record<string, unknown>;
interface FileReference { reference: string; threadId: string; cwd: string; path: string; startLine: number | null; endLine: number | null; expiresAt: number }

const FILE_TTL_MS = 24 * 60 * 60_000;
const MAX_FILE_REFS = 5_000;
const MAX_RESOURCE_TEXT = 512 * 1024;
const MAX_RESOURCE_BINARY = 2 * 1024 * 1024;

export class RichResourceBridge {
  private readonly workspaces = new Map<string, string>();
  private readonly files = new Map<string, FileReference>();

  constructor(private readonly codex: Pick<CodexAppServer, "call">, private readonly now = Date.now) {}

  project(value: unknown): unknown {
    this.observeWorkspaces(value);
    const agentStream = object(value) && typeof value.method === "string" && /^item\/agentMessage\//.test(value.method);
    const guardianCompleted = object(value) && value.method === "item/autoApprovalReview/completed";
    return this.projectValue(value, "", "", agentStream, guardianCompleted);
  }

  async readFileReference(threadIdValue: unknown, referenceValue: unknown): Promise<unknown> {
    const threadId = safeId(threadIdValue, "threadId");
    if (typeof referenceValue !== "string" || !/^remote-file:\/\/[a-f0-9]{64}$/.test(referenceValue)) throw new Error("File reference is invalid");
    this.cleanup();
    const entry = this.files.get(referenceValue);
    if (!entry || entry.threadId !== threadId || entry.expiresAt <= this.now()) throw new Error("File reference expired or belongs to another task");
    const current = await this.codex.call("thread/read", { threadId, includeTurns: false });
    const thread = object(current) && object(current.thread) ? current.thread : null;
    if (!thread || typeof thread.cwd !== "string") throw new Error("Task workspace is unavailable");
    const cwd = await validatedWorkspace(thread.cwd);
    if (realpathSync(cwd) !== realpathSync(entry.cwd)) throw new Error("File reference no longer matches the task workspace");
    const result = await readWorkspaceText(cwd, entry.path) as Json;
    return { ...result, reference: entry.reference, startLine: entry.startLine, endLine: entry.endLine };
  }

  async readMcpResource(params: Json): Promise<unknown> {
    const server = safeText(params.server, "MCP server", 200);
    const uri = safeUri(params.uri);
    const request: Json = { server, uri };
    if (params.threadId !== undefined && params.threadId !== null) request.threadId = safeId(params.threadId, "threadId");
    const response = await this.codex.call("mcpServer/resource/read", request);
    const contents = object(response) && Array.isArray(response.contents) ? response.contents : [];
    if (contents.length > 50) throw new Error("MCP resource returned too many content blocks");
    return { contents: contents.map((content) => projectResourceContent(content, uri)) };
  }

  private projectValue(value: unknown, field: string, inheritedThreadId: string, assistantContent: boolean, guardianCompleted: boolean): unknown {
    if (typeof value === "string") {
      return assistantContent && ["text", "delta", "message"].includes(field)
        ? this.rewriteFileLinks(value, inheritedThreadId) : value;
    }
    if (Array.isArray(value)) return value.map((item) => this.projectValue(item, field, inheritedThreadId, assistantContent, guardianCompleted));
    if (!object(value)) return value;
    const localThreadId = typeof value.threadId === "string" ? value.threadId
      : object(value.thread) && typeof value.thread.id === "string" ? value.thread.id : inheritedThreadId;
    const localAssistantContent = assistantContent || value.type === "agentMessage";
    const output: Json = {};
    for (const [key, item] of Object.entries(value)) output[key] = this.projectValue(item, key, localThreadId, localAssistantContent, guardianCompleted);
    if (value.type === "mcpToolCall") {
      const context = object(value.appContext) ? value.appContext : null;
      const legacyUri = typeof value.mcpAppResourceUri === "string" ? value.mcpAppResourceUri : null;
      if (context || legacyUri) output.richResource = {
        server: typeof value.server === "string" ? value.server.slice(0, 200) : null,
        connectorId: context && typeof context.connectorId === "string" ? context.connectorId.slice(0, 300) : null,
        linkId: context && typeof context.linkId === "string" ? context.linkId.slice(0, 300) : null,
        appName: context && typeof context.appName === "string" ? context.appName.slice(0, 200) : null,
        actionName: context && typeof context.actionName === "string" ? context.actionName.slice(0, 200) : null,
        resourceUri: safeUriOrNull(context?.resourceUri ?? legacyUri),
        readable: Boolean(typeof value.server === "string" && safeUriOrNull(context?.resourceUri ?? legacyUri)),
      };
    }
    if (guardianCompleted && field === "params" && isGuardianCompleted(value)) {
      output.guardianDenied = {
        reviewId: typeof value.reviewId === "string" ? value.reviewId.slice(0, 256) : null,
        threadId: typeof value.threadId === "string" ? value.threadId.slice(0, 256) : null,
        riskLevel: object(value.review) && typeof value.review.riskLevel === "string" ? value.review.riskLevel : null,
        rationale: object(value.review) && typeof value.review.rationale === "string" ? value.review.rationale.slice(0, 4_000) : null,
        actionSummary: actionSummary(value.action), approvable: false,
        unavailableReason: "This App Server notification does not expose the exact GuardianAssessmentEvent required for approval.",
      };
      delete output.action;
    }
    return output;
  }

  private observeWorkspaces(value: unknown): void {
    if (Array.isArray(value)) { for (const item of value) this.observeWorkspaces(item); return; }
    if (!object(value)) return;
    if (object(value.thread) && typeof value.thread.id === "string" && typeof value.thread.cwd === "string" && isAbsolute(value.thread.cwd)) {
      this.workspaces.set(value.thread.id, value.thread.cwd);
    }
    if (typeof value.threadId === "string" && typeof value.cwd === "string" && isAbsolute(value.cwd)) this.workspaces.set(value.threadId, value.cwd);
    for (const item of Object.values(value)) this.observeWorkspaces(item);
  }

  private rewriteFileLinks(text: string, threadId: string): string {
    const cwd = this.workspaces.get(threadId);
    if (!cwd || text.length > 2 * 1024 * 1024) return text;
    return mapOutsideCodeFences(text, (segment) => {
      let rewritten = segment.replace(/\[([^\]]+)]\(<?(?:sandbox:|file:\/\/)?(\/[^\n>)]*)>?\)/g,
        (whole, label: string, target: string) => this.fileMarkdown(label, target, threadId, cwd) ?? whole);
      rewritten = rewritten.replace(/:{1,2}codex-file-citation\{([^}]*)\}/g, (whole, body: string) => {
        const path = /\bpath="([^"]+)"/.exec(body)?.[1];
        const line = /\bline=(?:"(\d+)"|(\d+))/.exec(body);
        if (!path) return whole;
        return this.fileMarkdown("View file", `${path}${line ? `:${line[1] ?? line[2]}` : ""}`, threadId, cwd) ?? whole;
      });
      return rewritten.replace(/::code-comment\{([^}]*)\}/g, (whole, body: string) => {
        const path = /\bfile="([^"]+)"/.exec(body)?.[1];
        if (!path || !isAbsolute(path)) return whole;
        const start = /\bstart=(?:"(\d+)"|(\d+))/.exec(body);
        const end = /\bend=(?:"(\d+)"|(\d+))/.exec(body);
        const target = `${path}${start ? `:${start[1] ?? start[2]}${end ? `:${end[1] ?? end[2]}` : ""}` : ""}`;
        const reference = this.fileReference(target, threadId, cwd);
        if (!reference) return "[File reference unavailable](remote-artifact://unavailable)";
        return whole.replace(`file="${path}"`, `file="${reference}"`);
      });
    }, (fence) => fence.split(cwd).join("[workspace]"));
  }

  private fileMarkdown(label: string, target: string, threadId: string, cwd: string): string | null {
    const reference = this.fileReference(target, threadId, cwd);
    return reference ? `[${label}](${reference})` : null;
  }

  private fileReference(target: string, threadId: string, cwd: string): string | null {
    const parsed = parseFileTarget(target);
    if (!parsed) return null;
    let root: string; let path: string;
    try { root = realpathSync(cwd); path = realpathSync(parsed.path); } catch { return null; }
    const relativePath = relative(root, path);
    if (!relativePath || relativePath === ".." || relativePath.startsWith(`..${sep}`) || isAbsolute(relativePath)) return null;
    this.cleanup();
    const stable = createHash("sha256").update(JSON.stringify([threadId, root, path, parsed.startLine, parsed.endLine])).digest("hex");
    const reference = `remote-file://${stable}`;
    this.files.set(reference, { reference, threadId, cwd: root, path: relativePath,
      startLine: parsed.startLine, endLine: parsed.endLine, expiresAt: this.now() + FILE_TTL_MS });
    if (this.files.size > MAX_FILE_REFS) this.files.delete(this.files.keys().next().value!);
    return reference;
  }

  private cleanup(): void { for (const [id, item] of this.files) if (item.expiresAt <= this.now()) this.files.delete(id); }
}

function parseFileTarget(target: string): { path: string; startLine: number | null; endLine: number | null } | null {
  let decoded: string;
  try { decoded = decodeURI(target); } catch { return null; }
  const match = decoded.match(/^(.*?)(?::(\d+)(?::(\d+))?|#L(\d+)(?:-L?(\d+))?)?$/);
  if (!match || !isAbsolute(match[1])) return null;
  const start = Number(match[2] ?? match[4] ?? 0) || null;
  const end = Number(match[3] ?? match[5] ?? 0) || start;
  if ((start !== null && (!Number.isSafeInteger(start) || start < 1 || start > 10_000_000))
    || (end !== null && (!Number.isSafeInteger(end) || end < (start ?? 1) || end > 10_000_000))) return null;
  return { path: resolve(match[1]), startLine: start, endLine: end };
}

function mapOutsideCodeFences(text: string, transform: (segment: string) => string, redactFence: (fence: string) => string): string {
  const fence = /(^|\n)(```|~~~)[^\n]*(?:\n|$)[\s\S]*?(?:\n\2(?:\n|$)|$)/g;
  let output = ""; let offset = 0;
  for (const match of text.matchAll(fence)) {
    const index = match.index ?? 0;
    const inertFence = redactFence(match[0]).replace(/::(?=code-comment\{|codex-file-citation\{)/g, "&#58;&#58;");
    output += transform(text.slice(offset, index)) + inertFence;
    offset = index + match[0].length;
  }
  return output + transform(text.slice(offset));
}

function projectResourceContent(value: unknown, expectedUri: string): Json {
  if (!object(value) || typeof value.uri !== "string" || value.uri !== expectedUri) throw new Error("MCP resource returned an unexpected URI");
  const mimeType = typeof value.mimeType === "string" ? value.mimeType.toLowerCase().split(";", 1)[0] : "text/plain";
  if (typeof value.text === "string") {
    const bytes = Buffer.byteLength(value.text, "utf8");
    if (bytes > MAX_RESOURCE_TEXT) throw new Error("MCP text resource exceeds 512 KiB");
    const kind = mimeType === "text/html" ? "html" : "text";
    if (kind === "html" && /<(?:script|iframe|object|embed)\b/i.test(value.text)) throw new Error("Active HTML resources are not renderable remotely");
    return { uri: expectedUri, mimeType, kind, text: value.text, truncated: false };
  }
  if (typeof value.blob !== "string" || !value.blob || value.blob.length % 4 !== 0
    || value.blob.length > Math.ceil(MAX_RESOURCE_BINARY * 4 / 3) + 4
    || !/^[A-Za-z0-9+/]*={0,2}$/.test(value.blob)) throw new Error("MCP binary resource is invalid or too large");
  const bytes = Buffer.from(value.blob, "base64");
  if (bytes.toString("base64") !== value.blob) throw new Error("MCP binary resource is invalid or too large");
  if (bytes.length > MAX_RESOURCE_BINARY) throw new Error("MCP binary resource exceeds 2 MiB");
  const kind = mimeType.startsWith("image/") ? "image" : mimeType.startsWith("audio/") ? "audio" : null;
  if (!kind) throw new Error("MCP binary resource MIME type is not renderable remotely");
  return { uri: expectedUri, mimeType, kind, dataBase64: value.blob, size: bytes.length, truncated: false };
}

function isGuardianCompleted(value: Json): boolean {
  return typeof value.threadId === "string" && typeof value.reviewId === "string" && object(value.review)
    && value.review.status === "denied" && object(value.action) && typeof value.completedAtMs === "number";
}
function actionSummary(value: unknown): string {
  if (!object(value) || typeof value.type !== "string") return "Guardian denied an action";
  if (value.type === "command") return "Command execution";
  if (value.type === "execve") return "Program execution";
  if (value.type === "applyPatch" && Array.isArray(value.files)) return `File changes: ${Math.min(value.files.length, 100)} file(s)`;
  if (value.type === "networkAccess") return "Network access";
  if (value.type === "mcpToolCall") return `MCP tool: ${String(value.server ?? "").slice(0, 100)}/${String(value.toolName ?? "").slice(0, 100)}`;
  if (value.type === "requestPermissions") return "Permission change request";
  return "Guardian denied an action";
}
function safeUri(value: unknown): string {
  if (typeof value !== "string" || !value || value.length > 4096 || /[\0\r\n]/.test(value)) throw new Error("MCP resource URI is invalid");
  let uri: URL; try { uri = new URL(value); } catch { throw new Error("MCP resource URI is invalid"); }
  if (!uri.protocol || uri.protocol === "file:" || uri.protocol === "javascript:" || uri.protocol === "data:") throw new Error("MCP resource URI scheme is not allowed");
  if (uri.username || uri.password || [...uri.searchParams.keys()].some((key) => /token|secret|password|api.?key|auth|signature/i.test(key))) {
    throw new Error("MCP resource URI contains credentials and cannot be exposed remotely");
  }
  return value;
}
function safeUriOrNull(value: unknown): string | null { try { return safeUri(value); } catch { return null; } }
function safeId(value: unknown, label: string): string {
  if (typeof value !== "string" || !value || value.length > 256 || !/^[A-Za-z0-9._:-]+$/.test(value)) throw new Error(`${label} is invalid`);
  return value;
}
function safeText(value: unknown, label: string, max: number): string {
  if (typeof value !== "string" || !value || value.length > max || /[\0\r\n]/.test(value)) throw new Error(`${label} is invalid`);
  return value;
}
function object(value: unknown): value is Json { return Boolean(value) && typeof value === "object" && !Array.isArray(value); }
