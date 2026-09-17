import { EventEmitter } from "node:events";
import net from "node:net";
import { randomUUID } from "node:crypto";

// Desktop IPC is versioned separately from the public App Server protocol.
// Enable explicitly for a local desktop host; never connect to a remote IPC endpoint.
type RecordValue = Record<string, any>;
const MAX_FRAME = 64 * 1024 * 1024;
const approvalMethods: Record<string, string> = {
  "item/commandExecution/requestApproval": "thread-follower-command-approval-decision",
  "item/fileChange/requestApproval": "thread-follower-file-approval-decision",
  "item/permissions/requestApproval": "thread-follower-permissions-request-approval-response",
  "item/tool/requestUserInput": "thread-follower-submit-user-input",
  "mcpServer/elicitation/request": "thread-follower-submit-mcp-server-elicitation-response",
};

// App Server accepts omitted text_elements, but desktop follower IPC receives
// renderer-ready UserInput and dereferences text_elements before RPC validation.
export function desktopInput(input: unknown): RecordValue[] {
  if (!Array.isArray(input)) throw new Error("Desktop turn input must be an array");
  return input.map(item => {
    if (!item || typeof item !== "object") throw new Error("Invalid desktop turn input");
    if (item.type !== "text") return item;
    if (typeof item.text !== "string") throw new Error("Desktop text input must contain text");
    if (item.text_elements != null && !Array.isArray(item.text_elements))
      throw new Error("Desktop text_elements must be an array");
    return { ...item, text_elements: item.text_elements ?? [] };
  });
}

export function desktopTurns(state: RecordValue): RecordValue[] {
  const history = state.turnHistory?.kind === "canonical" ? state.turnHistory.history : null;
  const turns = history ? history.islands.flatMap((island: RecordValue) =>
    island.entries.map((entry: RecordValue) => history.entitiesByKey[entry.value]).filter(Boolean)) : state.turns ?? [];
  return turns.filter((turn: RecordValue) => turn.turnId).map((turn: RecordValue) => ({
    id: turn.turnId, status: turn.status, error: turn.error ?? null, items: turn.items ?? [],
  }));
}

export function applyDesktopPatches(state: RecordValue, patches: RecordValue[]): RecordValue {
  const result = structuredClone(state);
  for (const patch of patches) {
    if (!Array.isArray(patch.path) || !patch.path.length ||
      patch.path.some((key: unknown) => ["__proto__", "constructor", "prototype"].includes(String(key)))) {
      throw new Error("Invalid desktop state patch path");
    }
    let parent: any = result;
    for (const key of patch.path.slice(0, -1)) {
      if (parent == null || !Object.hasOwn(parent, key)) throw new Error("Missing desktop patch parent");
      parent = parent[key];
    }
    const key = patch.path.at(-1);
    if (patch.op === "remove") {
      if (Array.isArray(parent)) parent.splice(Number(key), 1);
      else delete parent[key];
    } else if (patch.op === "add" || patch.op === "replace") {
      if (patch.op === "add" && Array.isArray(parent)) parent.splice(Number(key), 0, patch.value);
      else parent[key] = patch.value;
    } else throw new Error("Unsupported desktop state patch");
  }
  return result;
}

export class DesktopOwnerBridge extends EventEmitter {
  private socket?: net.Socket;
  private connecting?: Promise<void>;
  private clientId?: string;
  private buffer = Buffer.alloc(0);
  private pending = new Map<string, { resolve: (value: any) => void; reject: (error: Error) => void; timer: NodeJS.Timeout }>();
  private owners = new Map<string, string>();
  private states = new Map<string, { revision: number; value: RecordValue }>();
  private approvals = new Map<string, { threadId: string; request: RecordValue }>();
  private refreshing = new Set<string>();

  constructor(private readonly socketPath: string) { super(); }

  private async connect(): Promise<void> {
    if (this.clientId && this.socket && !this.socket.destroyed) return;
    if (this.connecting) return this.connecting;
    this.connecting = (async () => {
      const socket = net.createConnection(this.socketPath);
      this.socket = socket;
      this.buffer = Buffer.alloc(0);
      socket.on("data", data => {
        try {
          this.buffer = Buffer.concat([this.buffer, data]);
          while (this.buffer.length >= 4) {
            const length = this.buffer.readUInt32LE(0);
            if (length > MAX_FRAME || !length) throw new Error("Invalid desktop IPC frame length");
            if (this.buffer.length < length + 4) break;
            const message = JSON.parse(this.buffer.subarray(4, length + 4).toString());
            this.buffer = this.buffer.subarray(length + 4);
            this.receive(message);
          }
        } catch (error) { socket.destroy(error as Error); }
      });
      socket.on("error", error => this.emit("log", `Desktop IPC: ${error.message}`));
      socket.on("close", () => {
        if (this.socket !== socket) return;
        this.clientId = undefined;
        this.states.clear();
        this.owners.clear();
        for (const id of this.approvals.keys()) this.emit("requestResolved", id);
        this.approvals.clear();
        for (const call of this.pending.values()) {
          clearTimeout(call.timer);
          call.reject(new Error("Desktop IPC disconnected; operation outcome may be unknown. Inspect the task before retrying."));
        }
        this.pending.clear();
      });
      await new Promise<void>((resolve, reject) => { socket.once("connect", resolve); socket.once("error", reject); });
      const result = await this.request("initialize", { clientType: "codex-remote-host" }, 0);
      if (typeof result.result?.clientId !== "string") throw new Error("Desktop IPC initialization returned no client ID");
      this.clientId = result.result.clientId;
    })().catch(error => { this.socket?.destroy(); throw error; }).finally(() => { this.connecting = undefined; });
    return this.connecting;
  }

  private send(message: RecordValue): void {
    if (!this.socket?.writable) throw new Error("Desktop IPC unavailable");
    const body = Buffer.from(JSON.stringify(message));
    if (body.length > MAX_FRAME) throw new Error("Desktop IPC message too large");
    const header = Buffer.alloc(4); header.writeUInt32LE(body.length);
    this.socket.write(Buffer.concat([header, body]));
  }

  private request(method: string, params: RecordValue, version: number, targetClientId?: string, timeoutMs = 30_000): Promise<RecordValue> {
    const requestId = randomUUID();
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(requestId);
        reject(new Error(`Desktop IPC timed out: ${method}. Operation outcome may be unknown; do not automatically retry.`));
      }, timeoutMs);
      this.pending.set(requestId, { resolve, reject, timer });
      try { this.send({ type: "request", requestId, sourceClientId: this.clientId, method, params, version, targetClientId }); }
      catch (error) { clearTimeout(timer); this.pending.delete(requestId); reject(error); }
    });
  }

  private follow(threadId: string): void {
    this.send({ type: "broadcast", sourceClientId: this.clientId, method: "thread-stream-following-changed", version: 1,
      params: { hostId: "local", conversationId: threadId, following: true } });
  }

  async discover(threadId: string): Promise<boolean> {
    await this.connect();
    const response = await this.request("thread-owner-discovery", { hostId: "local", conversationId: threadId }, 1);
    if (response.resultType === "error") {
      if (String(response.error).startsWith("no-client-found")) { this.owners.delete(threadId); this.states.delete(threadId); return false; }
      throw new Error(String(response.error));
    }
    if (typeof response.handledByClientId !== "string") throw new Error("Desktop owner discovery returned no owner");
    if (this.owners.get(threadId) !== response.handledByClientId) this.states.delete(threadId);
    this.owners.set(threadId, response.handledByClientId);
    this.follow(threadId);
    return true;
  }

  has(threadId: string): boolean { return this.owners.has(threadId); }

  private async action(threadId: string, method: string, params: RecordValue, version = 1): Promise<any> {
    const owner = this.owners.get(threadId);
    if (!owner) throw new Error("Desktop conversation owner unavailable");
    const response = await this.request(method, { conversationId: threadId, ...params }, version, owner);
    if (response.resultType !== "success") throw new Error(String(response.error ?? "Desktop action failed"));
    return response.result;
  }

  async call(method: string, params: RecordValue): Promise<any> {
    const threadId = params.threadId;
    if (method === "thread/resume" || method === "thread/read") {
      if (method === "thread/resume" || !this.states.has(threadId))
        await this.action(threadId, "thread-follower-load-complete-history", {});
      const state = this.states.get(threadId)?.value;
      if (!state) throw new Error("Desktop did not publish a conversation snapshot");
      const settings = state.latestThreadSettings ?? {};
      return { ...settings, model: state.latestModel, modelProvider: state.modelProvider,
        reasoningEffort: state.latestReasoningEffort, cwd: state.cwd,
        thread: { id: threadId, sessionId: state.sessionId, name: state.title, preview: state.title ?? "", ephemeral: state.ephemeral ?? false,
          modelProvider: state.modelProvider, createdAt: Math.floor(state.createdAt / 1000), updatedAt: Math.floor(state.updatedAt / 1000),
          path: state.rolloutPath, cwd: state.cwd, source: state.source, gitInfo: state.gitInfo,
          status: state.threadRuntimeStatus ?? { type: "idle" }, turns: params.includeTurns === false ? [] : desktopTurns(state) } };
    }
    if (method === "turn/start") {
      const result = await this.action(threadId, "thread-follower-start-turn", {
        turnStart: { request: { ...params, input: desktopInput(params.input), clientUserMessageId: params.clientUserMessageId ?? randomUUID() },
          context: { inheritThreadSettings: true } },
      }, 2);
      return result.result;
    }
    if (method === "turn/steer") {
      const state = this.states.get(threadId)?.value;
      const active = state && desktopTurns(state).reverse().find(turn => turn.status === "inProgress");
      if (!active || active.id !== params.expectedTurnId) throw new Error("Desktop active turn no longer matches expectedTurnId");
      const result = await this.action(threadId, "thread-follower-steer-turn", {
        input: desktopInput(params.input), clientUserMessageId: randomUUID(), serviceTier: params.serviceTier,
        restoreMessage: { text: "", cwd: state?.cwd, context: { workspaceRoots: [state?.cwd].filter(Boolean), collaborationMode: state?.latestCollaborationMode } },
      });
      return result.result;
    }
    if (method === "turn/interrupt") {
      await this.action(threadId, "thread-follower-interrupt-turn", { mode: "user-stop", expectedTurnId: params.turnId }, 4);
      return {};
    }
    if (method === "thread/compact/start") { await this.action(threadId, "thread-follower-compact-thread", {}); return {}; }
    throw new Error(`Desktop-owned conversations do not yet support ${method}`);
  }

  handlesResponse(id: number | string): boolean { return String(id).startsWith("desktop:"); }

  async respond(id: number | string, result: unknown, error?: unknown): Promise<void> {
    const approval = this.approvals.get(String(id));
    if (!approval) throw new Error("Desktop approval is no longer pending");
    if (error) throw new Error("Respond to this approval in the desktop app");
    const { threadId, request } = approval;
    const method = approvalMethods[request.method];
    const value = result as RecordValue;
    await this.action(threadId, method, { requestId: request.id,
      ...(method.endsWith("-decision") ? { decision: value?.decision } : { response: result }) });
    this.approvals.delete(String(id));
  }

  private receive(message: RecordValue): void {
    if (message.type === "response") {
      const pending = this.pending.get(message.requestId);
      if (pending) { clearTimeout(pending.timer); this.pending.delete(message.requestId); pending.resolve(message); }
      return;
    }
    if (message.type === "client-discovery-request") {
      this.send({ type: "client-discovery-response", requestId: message.requestId, response: { canHandle: false } }); return;
    }
    if (message.type !== "broadcast") return;
    const params = message.params ?? {};
    if (params.hostId !== "local") return;
    const threadId = params.conversationId;
    if (!this.has(threadId)) return;
    if (message.method === "thread-stream-following-status-requested") { this.follow(threadId); return; }
    if (message.method !== "thread-stream-state-changed" || message.version !== 11 || message.sourceClientId !== this.owners.get(threadId)) return;
    const previous = this.states.get(threadId);
    const change = params.change;
    let value: RecordValue;
    try {
      if (change.type === "snapshot") value = change.conversationState;
      else {
        if (!previous || previous.revision !== change.baseRevision) throw new Error("Desktop stream revision gap");
        value = applyDesktopPatches(previous.value, change.patches);
      }
      this.states.set(threadId, { revision: change.revision, value });
      this.publish(threadId, previous?.value, value);
    } catch {
      if (!this.refreshing.has(threadId)) {
        this.refreshing.add(threadId);
        void this.action(threadId, "thread-follower-load-complete-history", {}).catch(error => this.emit("log", error.message))
          .finally(() => this.refreshing.delete(threadId));
      }
    }
  }

  private publish(threadId: string, previous: RecordValue | undefined, state: RecordValue): void {
    const oldTurns = new Map((previous ? desktopTurns(previous) : []).map(turn => [turn.id, turn]));
    const notify = (method: string, params: RecordValue) => this.emit("notification", { method, params: { threadId, ...params } });
    for (const turn of desktopTurns(state)) {
      const old = oldTurns.get(turn.id);
      // Historical turns are already in the resume response, not new completions.
      if (!previous && turn.status !== "inProgress") continue;
      if (!old || old.status !== turn.status) notify(turn.status === "inProgress" ? "turn/started" : "turn/completed", { turn });
      else {
        const items = new Map((old.items as RecordValue[]).map((item, index) => [item.id, { item, index }]));
        for (const [index, item] of (turn.items as RecordValue[]).entries()) {
          const terminal = turn.status !== "inProgress" || ["completed", "failed", "declined", "interrupted"].includes(item.status) ||
            item.type === "userMessage" || (item.status == null && index < turn.items.length - 1);
          const prior = items.get(item.id);
          const wasTerminal = prior && (old.status !== "inProgress" ||
            ["completed", "failed", "declined", "interrupted"].includes(prior.item.status) ||
            prior.item.type === "userMessage" || (prior.item.status == null && prior.index < old.items.length - 1));
          if (prior && JSON.stringify(prior.item) === JSON.stringify(item) && terminal === wasTerminal) continue;
          const field = item.type === "commandExecution" ? "aggregatedOutput" : "text";
          const deltaMethod = ({ agentMessage: "item/agentMessage/delta", plan: "item/plan/delta",
            commandExecution: "item/commandExecution/outputDelta" } as Record<string, string>)[item.type];
          if (!terminal && prior && deltaMethod && typeof item[field] === "string" && typeof prior.item[field] === "string" &&
            item[field].startsWith(prior.item[field]) &&
            JSON.stringify({ ...prior.item, [field]: item[field] }) === JSON.stringify(item)) {
            const delta = item[field].slice(prior.item[field].length);
            if (delta) notify(deltaMethod, { turnId: turn.id, itemId: item.id, delta });
          } else {
            notify(terminal ? "item/completed" : "item/started", { turnId: turn.id, item });
          }
        }
      }
    }
    if (JSON.stringify(previous?.threadRuntimeStatus) !== JSON.stringify(state.threadRuntimeStatus))
      notify("thread/status/changed", { status: state.threadRuntimeStatus ?? { type: "idle" } });
    const requestIds = new Set((state.requests ?? []).map((request: RecordValue) => `desktop:${threadId}:${request.id}`));
    for (const [id, approval] of this.approvals) {
      if (approval.threadId === threadId && !requestIds.has(id)) {
        this.approvals.delete(id);
        this.emit("requestResolved", id);
      }
    }
    for (const request of state.requests ?? []) {
      if (!approvalMethods[request.method]) continue;
      const id = `desktop:${threadId}:${request.id}`;
      if (this.approvals.has(id)) continue;
      this.approvals.set(id, { threadId, request });
      this.emit("request", { ...request, id });
    }
  }

  stop(): void { this.socket?.destroy(); }
}
