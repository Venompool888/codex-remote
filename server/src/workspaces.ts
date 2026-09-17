import { constants } from "node:fs";
import { access, readFile, readdir, realpath, stat } from "node:fs/promises";
import { dirname, isAbsolute, join, sep } from "node:path";

export interface WorkspaceValidation {
  path: string;
  available: boolean;
  reason?: "invalid" | "missing" | "not_directory" | "reserved" | "permission_denied";
}

export interface WorkspaceDirectoryListing {
  path: string;
  parent: string | null;
  directories: Array<{ name: string; path: string }>;
}

export async function listWorkspaceDirectories(
  value: unknown,
  reservedDirectory = process.cwd(),
): Promise<WorkspaceDirectoryListing> {
  if (typeof value !== "string" || !isAbsolute(value)) throw new Error("An absolute directory path is required");
  const path = await realpath(value).catch(() => { throw new Error("Directory does not exist"); });
  const info = await stat(path);
  if (!info.isDirectory()) throw new Error("Path is not a directory");
  const reservedRealPath = await realpath(reservedDirectory).catch(() => reservedDirectory);
  if (path === reservedRealPath || path.startsWith(`${reservedRealPath}${sep}`) || await isRemoteHostDirectory(path)) {
    throw new Error("Directory is reserved by Remote Host");
  }
  const directories = (await readdir(path, { withFileTypes: true }))
    .filter((entry) => entry.isDirectory())
    .map((entry) => ({ name: entry.name, path: join(path, entry.name) }))
    .sort((left, right) => left.name.localeCompare(right.name, undefined, { sensitivity: "base" }))
    .slice(0, 200);
  const parent = dirname(path);
  return { path, parent: parent === path ? null : parent, directories };
}

export async function validateWorkspacePaths(
  value: unknown,
  reservedDirectory = process.cwd(),
): Promise<WorkspaceValidation[]> {
  const paths = Array.isArray(value)
    ? [...new Set(value.filter((item): item is string => typeof item === "string" && item.length > 0))].slice(0, 100)
    : [];
  const reservedRealPath = await realpath(reservedDirectory).catch(() => reservedDirectory);
  return Promise.all(paths.map(async (path): Promise<WorkspaceValidation> => {
    if (!isAbsolute(path)) return { path, available: false, reason: "invalid" };
    let info;
    try {
      info = await stat(path);
    } catch {
      return { path, available: false, reason: "missing" };
    }
    if (!info.isDirectory()) return { path, available: false, reason: "not_directory" };
    const realPath = await realpath(path).catch(() => path);
    if (realPath === reservedRealPath || realPath.startsWith(`${reservedRealPath}${sep}`) || await isRemoteHostDirectory(realPath)) {
      return { path, available: false, reason: "reserved" };
    }
    try { await access(realPath, constants.R_OK | constants.X_OK); }
    catch { return { path, available: false, reason: "permission_denied" }; }
    return { path, available: true };
  }));
}

async function isRemoteHostDirectory(path: string): Promise<boolean> {
  try {
    const packageJson = JSON.parse(await readFile(join(path, "package.json"), "utf8")) as { name?: unknown };
    return packageJson.name === "codex-remote-host";
  } catch {
    return false;
  }
}
