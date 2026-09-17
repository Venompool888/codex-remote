import assert from "node:assert/strict";
import { mkdtemp, mkdir, realpath, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { listWorkspaceDirectories, validateWorkspacePaths } from "../src/workspaces.js";

test("keeps existing project directories and rejects stale or reserved host directories", async () => {
  const root = await mkdtemp(join(tmpdir(), "codex-remote-workspaces-"));
  const project = join(root, "project");
  const host = join(root, "server");
  const missing = join(root, "deleted");
  await mkdir(project);
  await mkdir(host);
  await writeFile(join(host, "package.json"), JSON.stringify({ name: "codex-remote-host" }));

  const result = await validateWorkspacePaths([project, host, missing], join(root, "runtime"));

  assert.deepEqual(result, [
    { path: project, available: true },
    { path: host, available: false, reason: "reserved" },
    { path: missing, available: false, reason: "missing" },
  ]);
});

test("lists child directories for the remote project picker", async () => {
  const root = await mkdtemp(join(tmpdir(), "codex-remote-picker-"));
  await mkdir(join(root, "Zulu"));
  await mkdir(join(root, "alpha"));
  await writeFile(join(root, "notes.txt"), "not a directory");

  const result = await listWorkspaceDirectories(root, join(root, "runtime"));
  const resolvedRoot = await realpath(root);

  assert.equal(result.path, resolvedRoot);
  assert.deepEqual(result.directories, [
    { name: "alpha", path: join(resolvedRoot, "alpha") },
    { name: "Zulu", path: join(resolvedRoot, "Zulu") },
  ]);
});

test("an existing directory without access is unavailable", { skip: process.getuid?.() === 0 }, async () => {
  const { chmod, rm } = await import("node:fs/promises");
  const root = await mkdtemp(join(tmpdir(), "codex-workspace-denied-"));
  try {
    await chmod(root, 0);
    const [result] = await validateWorkspacePaths([root], join(tmpdir(), "other-host"));
    assert.equal(result.available, false);
    assert.equal(result.reason, "permission_denied");
  } finally { await chmod(root, 0o700); await rm(root, { recursive: true }); }
});
