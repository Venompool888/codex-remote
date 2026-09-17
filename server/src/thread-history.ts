import { createReadStream } from "node:fs";
import { readdir } from "node:fs/promises";
import { homedir } from "node:os";
import { isAbsolute, join, relative, resolve } from "node:path";
import { createInterface } from "node:readline";

interface HistoryCommand {
  id: string;
  turnId: string;
  callId: string;
  command: string;
  output: string;
  status: string;
  order: number;
  source: "protocolItem" | "toolWrapper";
}

interface HistoryImage {
  id: string;
  turnId: string;
  path: string;
  status: string;
  order: number;
}

/**
 * Codex app-server intentionally omits code-mode custom tool calls from some
 * thread/read histories. The live notifications still contain them, but after
 * an Android process restart a completed turn would otherwise collapse into a
 * wall of commentary with no auditable actions.
 *
 * The Host is colocated with the canonical Codex session log, so it can restore
 * those real calls without inventing summary rows. Only the app-server supplied
 * session path under ~/.codex/sessions is accepted.
 */
export async function hydrateThreadHistory(
  value: unknown,
  sessionRoot = resolve(homedir(), ".codex", "sessions"),
): Promise<unknown> {
  const result = asRecord(value);
  const thread = asRecord(result?.thread);
  const suppliedPath = typeof thread?.path === "string" ? resolve(thread.path) : "";
  const turns = Array.isArray(thread?.turns) ? thread.turns : null;
  if (!thread || !turns) return value;
  const sessionPath = isSafeSessionPath(suppliedPath, sessionRoot)
    ? suppliedPath
    : await findSessionPath(sessionRoot, typeof thread.id === "string" ? thread.id : "");
  if (!sessionPath) return value;

  let history: { commands: HistoryCommand[]; images: HistoryImage[] };
  try {
    history = await readHistoryItems(sessionPath);
  } catch {
    // History enrichment is best-effort. A missing/rotated session must not
    // make thread/read itself fail.
    return value;
  }
  if (history.commands.length === 0 && history.images.length === 0) return value;

  const byTurn = new Map<string, HistoryCommand[]>();
  for (const command of history.commands) {
    const list = byTurn.get(command.turnId) ?? [];
    list.push(command);
    byTurn.set(command.turnId, list);
  }
  const imagesByTurn = new Map<string, HistoryImage[]>();
  for (const image of history.images) {
    const list = imagesByTurn.get(image.turnId) ?? [];
    list.push(image);
    imagesByTurn.set(image.turnId, list);
  }

  for (const rawTurn of turns) {
    const turn = asRecord(rawTurn);
    if (!turn || typeof turn.id !== "string" || !Array.isArray(turn.items)) continue;
    const missing = (byTurn.get(turn.id) ?? []).filter((command) => !hasCommand(turn.items as unknown[], command));
    let finalIndex = (turn.items as unknown[]).findIndex((item) => {
      const record = asRecord(item);
      return record?.type === "agentMessage" && record.phase === "final_answer";
    });
    const insertion = missing.map(historyCommandItem);
    if (finalIndex >= 0) (turn.items as unknown[]).splice(finalIndex, 0, ...insertion);
    else (turn.items as unknown[]).push(...insertion);

    const generated = (turn.items as unknown[])
      .map(asRecord)
      .filter((item): item is Record<string, any> => item?.type === "imageGeneration");
    for (const [index, image] of (imagesByTurn.get(turn.id) ?? []).entries()) {
      const existing = generated[index];
      if (existing) {
        if (typeof existing.path !== "string" || existing.path.length === 0) existing.path = image.path;
        continue;
      }
      finalIndex = (turn.items as unknown[]).findIndex((item) => {
        const record = asRecord(item);
        return record?.type === "agentMessage" && record.phase === "final_answer";
      });
      const restored = historyImageItem(image);
      if (finalIndex >= 0) (turn.items as unknown[]).splice(finalIndex, 0, restored);
      else (turn.items as unknown[]).push(restored);
    }
  }
  return value;
}

async function findSessionPath(root: string, threadId: string): Promise<string> {
  if (!threadId || !/^[a-zA-Z0-9-]+$/.test(threadId)) return "";
  const directories = [resolve(root)];
  while (directories.length > 0) {
    const directory = directories.pop()!;
    let entries;
    try {
      entries = await readdir(directory, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const entry of entries) {
      const path = join(directory, entry.name);
      if (entry.isDirectory()) directories.push(path);
      else if (entry.isFile() && entry.name.endsWith(`${threadId}.jsonl`) && isSafeSessionPath(path, root)) return path;
    }
  }
  return "";
}

async function readHistoryItems(path: string): Promise<{ commands: HistoryCommand[]; images: HistoryImage[] }> {
  const calls = new Map<string, HistoryCommand>();
  const protocolItems: HistoryCommand[] = [];
  const images: HistoryImage[] = [];
  let latestTurnId = "";
  let order = 0;
  const reader = createInterface({ input: createReadStream(path, { encoding: "utf8" }), crlfDelay: Infinity });
  for await (const line of reader) {
    order += 1;
    let entry: Record<string, unknown> | null = null;
    try {
      entry = asRecord(JSON.parse(line));
    } catch {
      continue;
    }
    const payload = asRecord(entry?.payload);
    if (!payload) continue;
    const metadata = asRecord(payload.internal_chat_message_metadata_passthrough);
    const eventTurnId = typeof payload.turn_id === "string" ? payload.turn_id : "";
    const metadataTurnId = typeof metadata?.turn_id === "string" ? metadata.turn_id : "";
    if (eventTurnId || metadataTurnId) latestTurnId = eventTurnId || metadataTurnId;

    if (entry?.type === "event_msg" && payload.type === "image_generation_end") {
      const savedPath = typeof payload.saved_path === "string" ? payload.saved_path : "";
      if (latestTurnId && savedPath) images.push({
        id: typeof payload.call_id === "string" ? payload.call_id : `history-image-${order}`,
        turnId: latestTurnId,
        path: savedPath,
        status: normalizeStatus(payload.status),
        order,
      });
      continue;
    }

    // This is the same canonical item lifecycle record consumed by the desktop
    // transcript. Prefer it over the surrounding code-mode custom-tool wrapper:
    // one wrapper can launch several shell calls, while every CommandExecution
    // carries the exact command, status, output, and protocol ordering.
    if (entry?.type === "event_msg" && payload.type === "item_completed") {
      const item = asRecord(payload.item);
      const turnId = typeof payload.turn_id === "string" ? payload.turn_id : "";
      if (item?.type === "CommandExecution" && turnId) {
        const command = commandText(item.command);
        if (command) protocolItems.push({
          id: typeof item.id === "string" ? item.id : `history-command-${order}`,
          turnId,
          callId: "",
          command,
          output: firstText(item, ["aggregated_output", "formatted_output", "stdout"]),
          status: normalizeStatus(item.status),
          order,
          source: "protocolItem",
        });
      }
      continue;
    }

    if (entry?.type !== "response_item") continue;
    const turnId = typeof metadata?.turn_id === "string" ? metadata.turn_id : "";
    const callId = typeof payload.call_id === "string" ? payload.call_id : "";
    if (payload.type === "custom_tool_call" && turnId && callId) {
      const name = typeof payload.name === "string" ? payload.name : "tool";
      if (name !== "exec" && name !== "exec_command" && name !== "shell" && name !== "terminal") continue;
      const input = typeof payload.input === "string" ? payload.input : "";
      calls.set(callId, {
        id: typeof payload.id === "string" ? payload.id : `history-${callId}`,
        turnId,
        callId,
        command: extractExecCommand(input) || input.trim().slice(0, MAX_COMMAND),
        output: "",
        status: normalizeStatus(payload.status),
        order,
        source: "toolWrapper",
      });
    } else if (payload.type === "custom_tool_call_output" && callId) {
      const call = calls.get(callId);
      if (!call) continue;
      call.output = outputText(payload.output).slice(-MAX_OUTPUT);
      if (/\bexit_code\s*=\s*[1-9]\d*\b/i.test(call.output) || /\bScript (?:failed|timed out)\b/i.test(call.output)) {
        call.status = "failed";
      }
    }
  }
  const turnsWithProtocolItems = new Set(protocolItems.map((item) => item.turnId));
  const commands = [
    ...protocolItems,
    ...[...calls.values()].filter((call) => !turnsWithProtocolItems.has(call.turnId)),
  ].filter((call) => call.command.length > 0).sort((left, right) => left.order - right.order);
  return { commands, images: images.sort((left, right) => left.order - right.order) };
}

function commandText(value: unknown): string {
  if (typeof value === "string") return value.trim().slice(0, MAX_COMMAND);
  if (!Array.isArray(value)) return "";
  const command = [...value].reverse().find((part): part is string => typeof part === "string" && part.trim().length > 0);
  return command?.trim().slice(0, MAX_COMMAND) ?? "";
}

function firstText(record: Record<string, any>, keys: string[]): string {
  for (const key of keys) {
    if (typeof record[key] === "string" && record[key].length > 0) return record[key].slice(-MAX_OUTPUT);
  }
  return "";
}

function historyCommandItem(command: HistoryCommand): Record<string, unknown> {
  return {
    type: "commandExecution",
    id: command.id,
    command: command.command,
    aggregatedOutput: command.output,
    status: command.status,
    historySource: "sessionLog",
  };
}

function historyImageItem(image: HistoryImage): Record<string, unknown> {
  return {
    type: "imageGeneration",
    id: image.id,
    path: image.path,
    status: image.status,
    historySource: "sessionLog",
  };
}

function hasCommand(items: unknown[], command: HistoryCommand): boolean {
  return items.some((item) => {
    const record = asRecord(item);
    if (!record) return false;
    if (record.id === command.id || record.callId === command.callId || record.call_id === command.callId) return true;
    if (record.type !== "commandExecution" || typeof record.command !== "string") return false;
    const existing = extractExecCommand(record.command) || record.command.trim();
    return existing === command.command;
  });
}

function outputText(value: unknown): string {
  if (typeof value === "string") return value;
  if (!Array.isArray(value)) return "";
  return value.map((part) => {
    const record = asRecord(part);
    return typeof record?.text === "string" ? record.text : "";
  }).join("");
}

function extractExecCommand(input: string): string {
  const match = /(?:\bcmd\b|["']cmd["'])\s*:\s*/m.exec(input);
  if (!match) return "";
  const start = match.index + match[0].length;
  const quote = input[start];
  if (quote === '"') {
    let escaped = false;
    for (let index = start + 1; index < input.length; index += 1) {
      const character = input[index];
      if (character === '"' && !escaped) {
        try {
          return JSON.parse(input.slice(start, index + 1)).slice(0, MAX_COMMAND);
        } catch {
          return "";
        }
      }
      escaped = character === "\\" && !escaped;
      if (character !== "\\") escaped = false;
    }
  }
  if (quote === "'" || quote === "`") {
    let escaped = false;
    for (let index = start + 1; index < input.length; index += 1) {
      const character = input[index];
      if (character === quote && !escaped) {
        return input.slice(start + 1, index).replace(/\\n/g, "\n").replace(/\\([\\'`])/g, "$1").slice(0, MAX_COMMAND);
      }
      escaped = character === "\\" && !escaped;
      if (character !== "\\") escaped = false;
    }
  }
  return "";
}

function isSafeSessionPath(path: string, root: string): boolean {
  if (!path || !isAbsolute(path)) return false;
  const candidate = relative(resolve(root), path);
  return candidate.length > 0 && candidate !== ".." && !candidate.startsWith(`..${process.platform === "win32" ? "\\" : "/"}`) && !isAbsolute(candidate) && path.endsWith(".jsonl");
}

function normalizeStatus(value: unknown): string {
  const status = typeof value === "string" ? value.toLowerCase() : "completed";
  return status === "failed" || status === "declined" ? "failed" : status === "inprogress" ? "inProgress" : "completed";
}

function asRecord(value: unknown): Record<string, any> | null {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, any>
    : null;
}

const MAX_COMMAND = 32 * 1024;
const MAX_OUTPUT = 64 * 1024;
