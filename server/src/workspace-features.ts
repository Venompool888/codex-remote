import { readFile, realpath, stat } from "node:fs/promises";
import { isAbsolute, relative, resolve, sep } from "node:path";
import { validateWorkspacePaths } from "./workspaces.js";

export const MAX_WORKSPACE_FILE_BYTES = 512 * 1024;
export const MAX_GIT_DIFF_BYTES = 1024 * 1024;
const MAX_SEARCH_RESULTS = 200;

export async function validatedWorkspace(value: unknown): Promise<string> {
  if (typeof value !== "string" || !isAbsolute(value) || value.length > 4096 || /[\0\r\n]/.test(value)) {
    throw new Error("A valid absolute workspace directory is required");
  }
  const [validation] = await validateWorkspacePaths([value]);
  if (!validation?.available) throw new Error(`Workspace is unavailable${validation?.reason ? `: ${validation.reason}` : ""}`);
  return realpath(value);
}

export async function readWorkspaceText(cwdValue: unknown, pathValue: unknown): Promise<Record<string, unknown>> {
  const cwd = await validatedWorkspace(cwdValue);
  if (typeof pathValue !== "string" || !pathValue || pathValue.length > 4096 || isAbsolute(pathValue) || /[\0\r\n]/.test(pathValue)) {
    throw new Error("A relative workspace file path is required");
  }
  const candidate = resolve(cwd, pathValue);
  const path = await realpath(candidate).catch(() => { throw new Error("Workspace file does not exist"); });
  if (path !== cwd && !path.startsWith(`${cwd}${sep}`)) throw new Error("Workspace file is outside the selected project");
  const info = await stat(path);
  if (!info.isFile()) throw new Error("Workspace path is not a file");
  if (info.size > MAX_WORKSPACE_FILE_BYTES) throw new Error(`Workspace file exceeds ${MAX_WORKSPACE_FILE_BYTES} bytes`);
  const bytes = await readFile(path);
  if (bytes.includes(0)) throw new Error("Workspace file is binary");
  let text: string;
  try { text = new TextDecoder("utf-8", { fatal: true }).decode(bytes); }
  catch { throw new Error("Workspace file is not valid UTF-8 text"); }
  return { path: relative(cwd, path), text, size: bytes.length, truncated: false };
}

export function projectFileSearch(value: unknown, cwd: string): Record<string, unknown> {
  const input = isObject(value) && Array.isArray(value.files) ? value.files : [];
  const files = input.slice(0, MAX_SEARCH_RESULTS).flatMap((entry) => {
    if (!isObject(entry) || typeof entry.path !== "string") return [];
    const absolute = isAbsolute(entry.path) ? entry.path : resolve(typeof entry.root === "string" ? entry.root : cwd, entry.path);
    const path = relative(cwd, absolute);
    if (!path || path === ".." || path.startsWith(`..${sep}`) || isAbsolute(path)) return [];
    return [{ path, fileName: typeof entry.file_name === "string" ? entry.file_name.slice(0, 255) : path.split(sep).pop(),
      score: finiteNumber(entry.score), matchType: safeString(entry.match_type, 64),
      indices: Array.isArray(entry.indices) ? entry.indices.filter((item) => Number.isSafeInteger(item) && item >= 0).slice(0, 256) : null }];
  });
  return { files };
}

export function projectGitDiff(value: unknown): Record<string, unknown> {
  if (!isObject(value)) throw new Error("Git diff response is unavailable");
  const diff = typeof value.diff === "string" ? value.diff : "";
  if (Buffer.byteLength(diff, "utf8") > MAX_GIT_DIFF_BYTES) throw new Error(`Git diff exceeds ${MAX_GIT_DIFF_BYTES} bytes`);
  return { sha: safeString(value.sha, 128), diff };
}

export function validateFeatureParams(method: string, params: Record<string, unknown>): void {
  const threadMethods = new Set(["thread/read", "thread/resume", "thread/fork", "thread/archive", "thread/unarchive",
    "thread/delete", "thread/name/set", "thread/searchOccurrences", "thread/turns/list", "thread/items/list",
    "thread/compact/start", "thread/goal/set", "thread/goal/get", "thread/goal/clear", "review/start",
    "turn/start", "turn/steer", "turn/interrupt"]);
  if (threadMethods.has(method) && (typeof params.threadId !== "string" || !params.threadId || params.threadId.length > 256)) {
    throw new Error("A valid threadId is required");
  }
  if (method === "thread/name/set") {
    if (typeof params.name !== "string" || !params.name.trim() || params.name.length > 200 || /[\0\r\n]/.test(params.name)) {
      throw new Error("Task name must contain 1-200 characters on one line");
    }
  }
  if (method === "thread/search" || method === "thread/searchOccurrences") {
    if (typeof params.searchTerm !== "string" || !params.searchTerm.trim() || params.searchTerm.length > 500) {
      throw new Error("Search text must contain 1-500 characters");
    }
  }
  if (["thread/search", "thread/searchOccurrences", "thread/turns/list", "thread/items/list"].includes(method)
    && params.limit !== undefined && (!Number.isSafeInteger(params.limit) || (params.limit as number) < 1 || (params.limit as number) > 200)) {
    throw new Error("Page limit must be between 1 and 200");
  }
  if (params.cursor !== undefined && params.cursor !== null
    && (typeof params.cursor !== "string" || !params.cursor || params.cursor.length > 4096)) throw new Error("Pagination cursor is invalid");
  if (method === "thread/goal/set") {
    if (params.objective !== undefined && params.objective !== null && (typeof params.objective !== "string" || params.objective.length > 4000)) {
      throw new Error("Goal objective must be at most 4000 characters");
    }
    if (params.tokenBudget !== undefined && params.tokenBudget !== null
      && (!Number.isSafeInteger(params.tokenBudget) || (params.tokenBudget as number) <= 0)) throw new Error("Goal tokenBudget must be a positive integer");
    if (params.status !== undefined && params.status !== null
      && !["active", "paused", "blocked", "usageLimited", "budgetLimited", "complete"].includes(String(params.status))) {
      throw new Error("Goal status is invalid");
    }
  }
  if (method === "review/start") {
    if (!isObject(params.target) || !["uncommittedChanges", "baseBranch", "commit", "custom"].includes(String(params.target.type))) {
      throw new Error("Review target is invalid");
    }
    const targetText = params.target.type === "baseBranch" ? params.target.branch
      : params.target.type === "commit" ? params.target.sha : params.target.type === "custom" ? params.target.instructions : "";
    if (typeof targetText !== "string" || targetText.length > 4000 || /[\0\r\n]/.test(targetText) && params.target.type !== "custom") {
      throw new Error("Review target is invalid");
    }
    if (params.delivery !== undefined && params.delivery !== null && !["inline", "detached"].includes(String(params.delivery))) {
      throw new Error("Review delivery is invalid");
    }
  }
}

function safeString(value: unknown, max: number): string | null {
  return typeof value === "string" ? value.slice(0, max) : null;
}

function finiteNumber(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}
