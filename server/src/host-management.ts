import { randomUUID } from "node:crypto";
import { basename } from "node:path";
import type { CodexAppServer } from "./codex-app-server.js";
import { validatedWorkspace } from "./workspace-features.js";

type Params = Record<string, unknown>;
type Terminal = { deviceId: string; terminalId: string; processId: string; cwd: string; tty: boolean; startedAt: string };
type PendingOauth = { deviceId: string; expiresAt: number };

export const HOST_MANAGEMENT_METHODS = new Set([
  "host/account/login/start", "host/account/login/cancel", "host/account/logout",
  "host/mcp/status", "host/mcp/oauth/start", "host/mcp/reload",
  "host/plugin/catalog", "host/plugin/install", "host/plugin/uninstall",
  "host/settings/read", "host/settings/set", "host/skill/setEnabled", "host/thread/memoryMode/set",
  "host/terminal/execute", "host/terminal/write", "host/terminal/resize", "host/terminal/kill", "host/terminal/list",
]);

export const HOST_MANAGEMENT_WRITES = new Set([
  "host/account/login/start", "host/account/login/cancel", "host/account/logout",
  "host/mcp/oauth/start", "host/mcp/reload", "host/plugin/install", "host/plugin/uninstall",
  "host/settings/set", "host/skill/setEnabled", "host/thread/memoryMode/set",
  "host/terminal/execute", "host/terminal/write", "host/terminal/resize", "host/terminal/kill",
]);

export class HostManagement {
  private readonly logins = new Map<string, string>();
  private readonly pendingMcpOauth = new Map<string, PendingOauth>();
  private readonly terminals = new Map<string, Terminal>();
  private readonly processOwners = new Map<string, Terminal>();

  constructor(private readonly codex: Pick<CodexAppServer, "call">) {}

  async call(method: string, params: Params, deviceId: string): Promise<unknown> {
    switch (method) {
      case "host/account/login/start": return this.startLogin(params, deviceId);
      case "host/account/login/cancel": return this.cancelLogin(params, deviceId);
      case "host/account/logout":
        confirmation(params, "logout_account", "Codex account");
        await Promise.allSettled([...this.logins.keys()].map((loginId) => this.codex.call("account/login/cancel", { loginId })));
        await this.codex.call("account/logout", {});
        this.logins.clear();
        return { loggedOut: true };
      case "host/mcp/status": return this.mcpStatus(params);
      case "host/mcp/oauth/start": return this.mcpOauth(params, deviceId);
      case "host/mcp/reload":
        confirmation(params, "reload_mcp", "MCP configuration");
        await this.codex.call("config/mcpServer/reload", {});
        return { reloaded: true };
      case "host/plugin/catalog": return this.pluginCatalog();
      case "host/plugin/install": return this.installPlugin(params);
      case "host/plugin/uninstall": return this.uninstallPlugin(params);
      case "host/settings/read": return this.readSettings();
      case "host/settings/set": return this.setSetting(params);
      case "host/skill/setEnabled": return this.setSkill(params);
      case "host/thread/memoryMode/set": return this.setMemoryMode(params);
      case "host/terminal/execute": return this.execute(params, deviceId);
      case "host/terminal/write": return this.terminalWrite(params, deviceId);
      case "host/terminal/resize": return this.terminalResize(params, deviceId);
      case "host/terminal/kill": return this.terminalKill(params, deviceId);
      case "host/terminal/list": return this.terminalList(deviceId);
      default: throw new Error("Unsupported Host management method");
    }
  }

  routeNotification(method: string, value: unknown): { deviceId: string; params: Params; placeholderMethod?: string } | null | undefined {
    if (method === "account/login/completed") {
      if (!object(value) || typeof value.loginId !== "string") return null;
      const deviceId = this.logins.get(value.loginId);
      if (!deviceId) return null;
      this.logins.delete(value.loginId);
      return { deviceId, params: { loginId: value.loginId, success: value.success === true,
        error: typeof value.error === "string" ? value.error.slice(0, 2_000) : null }, placeholderMethod: "host/private/activity" };
    }
    if (method === "mcpServer/oauthLogin/completed") {
      if (!object(value) || typeof value.name !== "string") return null;
      this.cleanupOauth();
      const threadId = typeof value.threadId === "string" ? value.threadId : null;
      const oauthKey = mcpOauthKey(value.name, threadId);
      const pending = this.pendingMcpOauth.get(oauthKey);
      if (!pending) return null;
      this.pendingMcpOauth.delete(oauthKey);
      return { deviceId: pending.deviceId, params: { name: value.name.slice(0, 200), threadId,
        success: value.success === true, error: typeof value.error === "string" ? value.error.slice(0, 2_000) : null },
        placeholderMethod: "host/private/activity" };
    }
    if (method !== "command/exec/outputDelta") return undefined;
    if (!object(value) || typeof value.processId !== "string") return null;
    const terminal = this.processOwners.get(value.processId);
    if (!terminal) return null;
    const params = { ...value, processId: terminal.terminalId };
    return { deviceId: terminal.deviceId, params };
  }

  async removeDevice(deviceId: string): Promise<void> {
    const loginIds = [...this.logins].filter(([, owner]) => owner === deviceId).map(([loginId]) => loginId);
    await Promise.allSettled(loginIds.map((loginId) => this.codex.call("account/login/cancel", { loginId })));
    for (const loginId of loginIds) this.logins.delete(loginId);
    for (const [oauthKey, pending] of this.pendingMcpOauth) if (pending.deviceId === deviceId) this.pendingMcpOauth.delete(oauthKey);
    for (const terminal of this.terminals.values()) if (terminal.deviceId === deviceId) {
      void this.codex.call("command/exec/terminate", { processId: terminal.processId }).catch(() => undefined);
      this.terminals.delete(key(deviceId, terminal.terminalId));
      this.processOwners.delete(terminal.processId);
    }
  }

  ownerDeviceIds(): string[] {
    this.cleanupOauth();
    return [...new Set([...this.logins.values(), ...[...this.pendingMcpOauth.values()].map((item) => item.deviceId),
      ...[...this.terminals.values()].map((item) => item.deviceId)])];
  }

  private async startLogin(params: Params, deviceId: string): Promise<unknown> {
    if (params.flow !== "browser" && params.flow !== "device") throw new Error("Login flow must be browser or device");
    const account = await this.codex.call("account/read", { refreshToken: false }).catch(() => null);
    const source = object(account) && object(account.account) ? account.account.credentialSource : null;
    if (source === "external" || source === "ephemeral") throw new Error("Account login is managed by the Host authentication broker");
    const result = await this.codex.call("account/login/start", params.flow === "browser"
      ? { type: "chatgpt", codexStreamlinedLogin: true, useHostedLoginSuccessPage: true }
      : { type: "chatgptDeviceCode" });
    if (!object(result) || typeof result.loginId !== "string" || !validId(result.loginId)) throw new Error("Account login response is unavailable");
    try {
      const response = params.flow === "browser"
        ? { flow: "browser", loginId: result.loginId, authUrl: safeUrl(result.authUrl) }
        : { flow: "device", loginId: result.loginId, verificationUrl: safeUrl(result.verificationUrl),
          userCode: safeUserCode(result.userCode) };
      this.logins.set(result.loginId, deviceId);
      return response;
    } catch (error) {
      await this.codex.call("account/login/cancel", { loginId: result.loginId }).catch(() => undefined);
      throw error;
    }
  }

  private async cancelLogin(params: Params, deviceId: string): Promise<unknown> {
    const loginId = id(params.loginId, "loginId");
    if (this.logins.get(loginId) !== deviceId) throw new Error("Login belongs to another device or has expired");
    confirmation(params, "cancel_login", loginId);
    await this.codex.call("account/login/cancel", { loginId });
    this.logins.delete(loginId);
    return { cancelled: true };
  }

  private async mcpStatus(params: Params): Promise<unknown> {
    const limit = boundedInteger(params.limit, 1, 100, 50);
    const request: Params = { limit, detail: "toolsAndAuthOnly" };
    if (params.cursor !== undefined && params.cursor !== null) request.cursor = id(params.cursor, "cursor", 4096);
    if (params.threadId !== undefined && params.threadId !== null) request.threadId = id(params.threadId, "threadId");
    const result = await this.codex.call("mcpServerStatus/list", request);
    const data = object(result) && Array.isArray(result.data) ? result.data : [];
    return { servers: data.slice(0, limit).filter(object).map((server) => ({
      name: text(server.name, "MCP server name", 200), authStatus: enumValue(server.authStatus, ["unsupported", "notLoggedIn", "bearerToken", "oAuth"]),
      title: object(server.serverInfo) && typeof server.serverInfo.title === "string" ? server.serverInfo.title.slice(0, 200) : null,
      version: object(server.serverInfo) && typeof server.serverInfo.version === "string" ? server.serverInfo.version.slice(0, 100) : null,
      toolCount: object(server.tools) ? Object.keys(server.tools).length : 0,
    })), nextCursor: object(result) && typeof result.nextCursor === "string" ? result.nextCursor : null };
  }

  private async mcpOauth(params: Params, deviceId: string): Promise<unknown> {
    const name = text(params.name, "MCP server name", 200);
    confirmation(params, "start_mcp_oauth", name);
    const request: Params = { name, timeoutSecs: 300 };
    const threadId = params.threadId !== undefined && params.threadId !== null ? id(params.threadId, "threadId") : null;
    if (threadId) request.threadId = threadId;
    if (params.scopes !== undefined && params.scopes !== null) {
      if (!Array.isArray(params.scopes) || params.scopes.length > 20) throw new Error("MCP scopes are invalid");
      request.scopes = params.scopes.map((scope) => text(scope, "MCP scope", 200));
    }
    this.cleanupOauth();
    const oauthKey = mcpOauthKey(name, threadId);
    if (this.pendingMcpOauth.has(oauthKey)) throw new Error("An MCP authorization flow is already pending for this server and task");
    this.pendingMcpOauth.set(oauthKey, { deviceId, expiresAt: Date.now() + 300_000 });
    try {
      const result = await this.codex.call("mcpServer/oauth/login", request);
      return { authorizationUrl: safeUrl(object(result) ? result.authorizationUrl : null) };
    } catch (error) {
      this.pendingMcpOauth.delete(oauthKey);
      throw error;
    }
  }

  private cleanupOauth(): void {
    const now = Date.now();
    for (const [oauthKey, pending] of this.pendingMcpOauth) if (pending.expiresAt <= now) this.pendingMcpOauth.delete(oauthKey);
  }

  private async pluginCatalog(): Promise<unknown> {
    const result = await this.codex.call("plugin/list", {});
    const marketplaces = object(result) && Array.isArray(result.marketplaces) ? result.marketplaces : [];
    return { marketplaces: marketplaces.slice(0, 50).filter(object).map((market) => ({
      name: text(market.name, "Marketplace name", 200), plugins: (Array.isArray(market.plugins) ? market.plugins : []).slice(0, 500).filter(object).map(pluginSummary),
    })), featuredPluginIds: object(result) && Array.isArray(result.featuredPluginIds)
      ? result.featuredPluginIds.filter((item): item is string => typeof item === "string").slice(0, 1000) : [] };
  }

  private async installPlugin(params: Params): Promise<unknown> {
    const pluginName = text(params.pluginName, "Plugin name", 200);
    confirmation(params, "install_plugin", pluginName);
    const request: Params = { pluginName };
    if (params.remoteMarketplaceName !== undefined && params.remoteMarketplaceName !== null) {
      request.remoteMarketplaceName = text(params.remoteMarketplaceName, "Remote marketplace name", 200);
    }
    const result = await this.codex.call("plugin/install", request);
    return { installed: true, authPolicy: object(result) ? enumValue(result.authPolicy, ["ON_INSTALL", "ON_USE"]) : null,
      appsNeedingAuth: object(result) && Array.isArray(result.appsNeedingAuth) ? result.appsNeedingAuth.slice(0, 100).filter(object)
        .map((app) => ({ id: text(app.id, "App id", 200), name: text(app.name, "App name", 200) })) : [] };
  }

  private async uninstallPlugin(params: Params): Promise<unknown> {
    const pluginId = id(params.pluginId, "pluginId");
    confirmation(params, "uninstall_plugin", pluginId);
    await this.codex.call("plugin/uninstall", { pluginId });
    return { uninstalled: true };
  }

  private async setSetting(params: Params): Promise<unknown> {
    const setting = enumValue(params.setting, ["web_search", "model_verbosity", "model_reasoning_summary"]);
    const values: Record<string, readonly string[]> = {
      web_search: ["disabled", "cached", "indexed", "live"], model_verbosity: ["low", "medium", "high"],
      model_reasoning_summary: ["auto", "concise", "detailed", "none"],
    };
    const value = enumValue(params.value, values[setting]);
    confirmation(params, "change_setting", setting);
    const result = await this.codex.call("config/value/write", { keyPath: setting, value, mergeStrategy: "replace" });
    return writeResult(result, { setting, value });
  }

  private async readSettings(): Promise<unknown> {
    const result = await this.codex.call("config/read", { includeLayers: false });
    const config = object(result) && object(result.config) ? result.config : {};
    return { settings: {
      web_search: enumOrNull(config.web_search, ["disabled", "cached", "indexed", "live"]),
      model_verbosity: enumOrNull(config.model_verbosity, ["low", "medium", "high"]),
      model_reasoning_summary: enumOrNull(config.model_reasoning_summary, ["auto", "concise", "detailed", "none"]),
    } };
  }

  private async setSkill(params: Params): Promise<unknown> {
    const name = text(params.name, "Skill name", 200);
    if (typeof params.enabled !== "boolean") throw new Error("Skill enabled must be boolean");
    confirmation(params, "set_skill_enabled", name);
    const result = await this.codex.call("skills/config/write", { name, enabled: params.enabled });
    return { name, effectiveEnabled: object(result) && result.effectiveEnabled === true };
  }

  private async setMemoryMode(params: Params): Promise<unknown> {
    const threadId = id(params.threadId, "threadId");
    const mode = enumValue(params.mode, ["enabled", "disabled"]);
    confirmation(params, "set_memory_mode", threadId);
    await this.codex.call("thread/memoryMode/set", { threadId, mode });
    return { threadId, mode };
  }

  private async execute(params: Params, deviceId: string): Promise<unknown> {
    const terminalId = id(params.terminalId, "terminalId", 64);
    if (this.terminals.has(key(deviceId, terminalId))) throw new Error("Terminal id is already active");
    if (!Array.isArray(params.argv) || params.argv.length < 1 || params.argv.length > 64) throw new Error("Command argv must contain 1-64 entries");
    const argv = params.argv.map((part) => text(part, "Command argument", 4096));
    if (argv.reduce((sum, part) => sum + part.length, 0) > 16_384) throw new Error("Command argv is too large");
    confirmation(params, "execute_command", argv.join(" ").slice(0, 500));
    const cwd = await validatedWorkspace(params.cwd);
    const tty = params.tty === true;
    const timeoutMs = boundedInteger(params.timeoutMs, 1_000, 30 * 60_000, 120_000);
    const processId = `remote-${randomUUID()}`;
    const terminal: Terminal = { deviceId, terminalId, processId, cwd, tty, startedAt: new Date().toISOString() };
    this.terminals.set(key(deviceId, terminalId), terminal);
    this.processOwners.set(processId, terminal);
    try {
      const request: Params = { command: argv, processId, cwd, tty, streamStdin: true, streamStdoutStderr: true,
        outputBytesCap: 1024 * 1024, timeoutMs };
      if (tty) request.size = terminalSize(params.size);
      const result = await this.codex.call("command/exec", request, timeoutMs + 5_000);
      return { terminalId, exitCode: object(result) && Number.isInteger(result.exitCode) ? result.exitCode : null,
        stdout: object(result) && typeof result.stdout === "string" ? result.stdout.slice(0, 1024 * 1024) : "",
        stderr: object(result) && typeof result.stderr === "string" ? result.stderr.slice(0, 1024 * 1024) : "" };
    } catch (error) {
      await this.codex.call("command/exec/terminate", { processId }).catch(() => undefined);
      throw error;
    } finally {
      this.terminals.delete(key(deviceId, terminalId));
      this.processOwners.delete(processId);
    }
  }

  private async terminalWrite(params: Params, deviceId: string): Promise<unknown> {
    const terminal = this.ownedTerminal(params.terminalId, deviceId);
    const request: Params = { processId: terminal.processId };
    if (params.deltaBase64 !== undefined && params.deltaBase64 !== null) {
      if (typeof params.deltaBase64 !== "string" || params.deltaBase64.length > 90_000 || !/^[A-Za-z0-9+/]*={0,2}$/.test(params.deltaBase64)) throw new Error("Terminal input is invalid");
      if (Buffer.from(params.deltaBase64, "base64").length > 64 * 1024) throw new Error("Terminal input exceeds 64 KiB");
      request.deltaBase64 = params.deltaBase64;
    }
    if (params.closeStdin !== undefined && typeof params.closeStdin !== "boolean") throw new Error("closeStdin must be boolean");
    request.closeStdin = params.closeStdin === true;
    if (!request.deltaBase64 && !request.closeStdin) throw new Error("Terminal input or closeStdin is required");
    await this.codex.call("command/exec/write", request);
    return { written: true };
  }

  private async terminalResize(params: Params, deviceId: string): Promise<unknown> {
    const terminal = this.ownedTerminal(params.terminalId, deviceId);
    if (!terminal.tty) throw new Error("Only a TTY terminal can be resized");
    const size = terminalSize({ rows: params.rows, cols: params.cols });
    await this.codex.call("command/exec/resize", { processId: terminal.processId, size });
    return { resized: true, ...size };
  }

  private async terminalKill(params: Params, deviceId: string): Promise<unknown> {
    const terminal = this.ownedTerminal(params.terminalId, deviceId);
    confirmation(params, "terminate_command", terminal.terminalId);
    await this.codex.call("command/exec/terminate", { processId: terminal.processId });
    return { terminated: true };
  }

  private terminalList(deviceId: string): unknown {
    return { terminals: [...this.terminals.values()].filter((item) => item.deviceId === deviceId).map((item) => ({
      terminalId: item.terminalId, cwdName: basename(item.cwd), tty: item.tty, startedAt: item.startedAt,
    })) };
  }

  private ownedTerminal(value: unknown, deviceId: string): Terminal {
    const terminalId = id(value, "terminalId", 64);
    const terminal = this.terminals.get(key(deviceId, terminalId));
    if (!terminal) throw new Error("Terminal belongs to another device or is no longer active");
    return terminal;
  }
}

function confirmation(params: Params, action: string, subject: string): void {
  const value = params.confirmation;
  if (!object(value) || value.action !== action || value.subject !== subject) throw new Error(`Explicit ${action} confirmation is required`);
}
function key(deviceId: string, terminalId: string): string { return `${deviceId}\0${terminalId}`; }
function validId(value: string): boolean { return value.length > 0 && value.length <= 256 && /^[A-Za-z0-9._:-]+$/.test(value); }
function id(value: unknown, label: string, max = 256): string {
  if (typeof value !== "string" || !value || value.length > max || !/^[A-Za-z0-9._:-]+$/.test(value)) throw new Error(`${label} is invalid`);
  return value;
}
function text(value: unknown, label: string, max: number): string {
  if (typeof value !== "string" || !value.trim() || value.length > max || /[\0\r\n]/.test(value)) throw new Error(`${label} is invalid`);
  return value;
}
function enumValue<T extends string>(value: unknown, allowed: readonly T[]): T {
  if (typeof value !== "string" || !allowed.includes(value as T)) throw new Error("Value is not supported");
  return value as T;
}
function enumOrNull<T extends string>(value: unknown, allowed: readonly T[]): T | null {
  return typeof value === "string" && allowed.includes(value as T) ? value as T : null;
}
function boundedInteger(value: unknown, min: number, max: number, fallback: number): number {
  if (value === undefined || value === null) return fallback;
  if (!Number.isSafeInteger(value) || (value as number) < min || (value as number) > max) throw new Error(`Value must be between ${min} and ${max}`);
  return value as number;
}
function terminalSize(value: unknown): { rows: number; cols: number } {
  if (!object(value)) return { rows: 24, cols: 80 };
  return { rows: boundedInteger(value.rows, 2, 500, 24), cols: boundedInteger(value.cols, 2, 500, 80) };
}
function safeUrl(value: unknown): string {
  if (typeof value !== "string" || value.length > 4096) throw new Error("Authorization URL is unavailable");
  let url: URL;
  try { url = new URL(value); } catch { throw new Error("Authorization URL is invalid"); }
  if (url.protocol !== "https:" && !(url.protocol === "http:" && ["127.0.0.1", "localhost", "[::1]"].includes(url.hostname))) {
    throw new Error("Authorization URL must use HTTPS");
  }
  return value;
}
function safeUserCode(value: unknown): string {
  if (typeof value !== "string" || !value || value.length > 128 || /[\0\r\n]/.test(value)) throw new Error("Device login response is unavailable");
  return value;
}
function mcpOauthKey(name: string, threadId: string | null): string { return `${name}\0${threadId ?? ""}`; }
function pluginSummary(plugin: Params): Params {
  return { id: text(plugin.id, "Plugin id", 300), name: text(plugin.name, "Plugin name", 300),
    version: typeof plugin.version === "string" ? plugin.version.slice(0, 100) : null,
    localVersion: typeof plugin.localVersion === "string" ? plugin.localVersion.slice(0, 100) : null,
    installed: plugin.installed === true, enabled: plugin.enabled === true,
    authPolicy: enumValue(plugin.authPolicy, ["ON_INSTALL", "ON_USE"]),
    availability: typeof plugin.availability === "string" ? plugin.availability.slice(0, 100) : null };
}
function writeResult(result: unknown, extra: Params): Params {
  return { ...extra, status: object(result) && typeof result.status === "string" ? result.status.slice(0, 100) : null,
    version: object(result) && typeof result.version === "string" ? result.version.slice(0, 200) : null };
}
function object(value: unknown): value is Params { return Boolean(value) && typeof value === "object" && !Array.isArray(value); }
