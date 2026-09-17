import { DesktopOwnerBridge } from "./desktop-owner-bridge.js";
import { EventEmitter } from "node:events";
import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { createInterface } from "node:readline";
import { tokenHash, type TokenProvider, type ChatGptTokens } from "./cli-auth.js";

export interface JsonRpcError {
  code: number;
  message: string;
  data?: unknown;
}

export interface AppServerRequest {
  id: number | string;
  method: string;
  params: unknown;
}

interface PendingCall {
  resolve: (value: unknown) => void;
  reject: (error: Error) => void;
  timeout: NodeJS.Timeout;
}

export class CodexAppServer extends EventEmitter {
  private child: ChildProcessWithoutNullStreams | null = null;
  private nextId = 1;
  private pending = new Map<number, PendingCall>();
  private stopping = false;
  private desktop?: DesktopOwnerBridge;
  private externalTokens: ChatGptTokens | undefined;

  constructor(
    private readonly executable = "codex",
    private readonly extraArgs: string[] = [],
    private readonly useDaemonProxy = false,
    private readonly tokenProvider?: TokenProvider,
  ) {
    super();
    if (process.env.CODEX_REMOTE_DESKTOP_IPC_SOCKET) {
      this.desktop = new DesktopOwnerBridge(process.env.CODEX_REMOTE_DESKTOP_IPC_SOCKET);
      for (const event of ["notification", "request", "log", "requestResolved"]) this.desktop.on(event, value => this.emit(event, value));
    }
  }

  async start(): Promise<void> {
    if (this.child) return;
    if (this.tokenProvider && this.useDaemonProxy) throw new Error("External CLI auth requires a dedicated app-server");
    this.stopping = false;
    const appServerArgs = this.useDaemonProxy
      ? ["app-server", "proxy", ...this.extraArgs]
      : ["app-server", "--stdio", ...this.extraArgs,
          ...(this.tokenProvider ? ["-c", 'cli_auth_credentials_store="ephemeral"'] : [])];
    const child = spawn(this.executable, appServerArgs, {
      stdio: ["pipe", "pipe", "pipe"],
      env: process.env,
    });
    this.child = child;

    createInterface({ input: child.stdout }).on("line", (line) => this.handleLine(line));
    createInterface({ input: child.stderr }).on("line", (line) => this.emit("log", line));
    child.once("error", (error) => this.failAll(error));
    child.once("exit", (code, signal) => {
      this.child = null;
      const error = new Error(`codex app-server exited (code=${code ?? "null"}, signal=${signal ?? "null"})`);
      this.failAll(error);
      if (!this.stopping) this.emit("exit", error);
    });

    await this.call("initialize", {
      clientInfo: {
        name: "codex_remote_host",
        title: "Codex Remote Host",
        version: "0.1.0",
      },
      capabilities: { experimentalApi: true },
    });
    this.notify("initialized", {});
    if (this.tokenProvider) {
      try {
        this.externalTokens = await this.tokenProvider();
        await this.call("account/login/start", { type: "chatgptAuthTokens", ...this.externalTokens });
      } catch {
        await this.stop();
        throw new Error("Could not use host CLI authentication");
      }
    }
  }

  async call(method: string, params: unknown, timeoutMs = 30_000): Promise<unknown> {
    const values = params as Record<string, any> | null;
    const threadId = values?.threadId;
    if (this.desktop && typeof threadId === "string") {
      if (["thread/resume", "turn/start"].includes(method)) {
        // Discover before attempting a second writer. A failed write is never retried through IPC.
        if (await this.desktop.discover(threadId)) return this.desktop.call(method, values!);
      } else if (this.desktop.has(threadId) && ["thread/read", "turn/steer", "turn/interrupt", "thread/compact/start"].includes(method)) {
        return this.desktop.call(method, values!);
      }
    }
    return this.callDirect(method, params, timeoutMs);
  }

  private callDirect(method: string, params: unknown, timeoutMs = 30_000): Promise<unknown> {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`Codex request timed out: ${method}`));
      }, timeoutMs);
      this.pending.set(id, { resolve, reject, timeout });
      try {
        this.send({ id, method, params });
      } catch (error) {
        clearTimeout(timeout);
        this.pending.delete(id);
        reject(error);
      }
    });
  }

  notify(method: string, params: unknown): void {
    this.send({ method, params });
  }

  respond(id: number | string, result?: unknown, error?: JsonRpcError): void | Promise<void> {
    if (this.desktop?.handlesResponse(id)) return this.desktop.respond(id, result, error);
    this.send(error ? { id, error } : { id, result: result ?? {} });
  }

  async stop(): Promise<void> {
    this.stopping = true;
    this.desktop?.stop();
    this.externalTokens = undefined;
    const child = this.child;
    this.child = null;
    if (!child) return;
    child.kill("SIGTERM");
    await new Promise<void>((resolve) => {
      const timer = setTimeout(() => {
        child.kill("SIGKILL");
        resolve();
      }, 2_000);
      child.once("exit", () => {
        clearTimeout(timer);
        resolve();
      });
    });
  }

  private send(message: object): void {
    if (!this.child?.stdin.writable) throw new Error("codex app-server is not running");
    this.child.stdin.write(`${JSON.stringify(message)}\n`);
  }

  private handleLine(line: string): void {
    let message: Record<string, unknown>;
    try {
      message = JSON.parse(line) as Record<string, unknown>;
    } catch {
      this.emit("log", `Ignoring non-JSON app-server output: ${line.slice(0, 200)}`);
      return;
    }

    if (message.id !== undefined && ("result" in message || "error" in message)) {
      const numericId = typeof message.id === "number" ? message.id : Number(message.id);
      const pending = this.pending.get(numericId);
      if (pending) {
        clearTimeout(pending.timeout);
        this.pending.delete(numericId);
        if (message.error) {
          const rpcError = message.error as JsonRpcError;
          pending.reject(new Error(`${rpcError.message} (${rpcError.code})`));
        } else {
          pending.resolve(message.result);
        }
        return;
      }
    }

    if (message.id !== undefined && typeof message.method === "string") {
      if (message.method === "account/chatgptAuthTokens/refresh" && this.tokenProvider) {
        void this.refreshExternalAuth(message as unknown as AppServerRequest);
        return;
      }
      this.emit("request", message as unknown as AppServerRequest);
      return;
    }
    if (typeof message.method === "string") this.emit("notification", message);
  }

  private async refreshExternalAuth(request: AppServerRequest): Promise<void> {
    const child = this.child;
    try {
      const params = request.params as { previousAccountId?: string } | null;
      const tokens = await this.tokenProvider!({
        previousAccountId: params?.previousAccountId ?? this.externalTokens?.chatgptAccountId,
        rejectedTokenHash: this.externalTokens ? tokenHash(this.externalTokens.accessToken) : undefined,
      });
      if (this.child !== child || !child) return;
      this.externalTokens = tokens;
      this.respond(request.id, tokens);
    } catch {
      if (this.child === child && child) this.respond(request.id, undefined,
        { code: -32000, message: "Host CLI authentication could not be refreshed" });
    }
  }

  private failAll(error: Error): void {
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timeout);
      pending.reject(error);
    }
    this.pending.clear();
  }
}
