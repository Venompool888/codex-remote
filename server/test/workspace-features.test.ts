import assert from "node:assert/strict";
import { mkdtemp, mkdir, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import {
  projectFileSearch,
  projectGitDiff,
  readWorkspaceText,
  validateFeatureParams,
} from "../src/workspace-features.js";

test("workspace text reads stay inside one validated root and reject binary and oversized files", async () => {
  const parent = await mkdtemp(join(tmpdir(), "codex-workspace-feature-"));
  const workspace = join(parent, "project");
  const outside = join(parent, "outside.txt");
  try {
    await mkdir(workspace);
    await writeFile(join(workspace, "notes.txt"), "hello\n");
    await writeFile(join(workspace, "binary.bin"), Buffer.from([1, 0, 2]));
    await writeFile(join(workspace, "large.txt"), Buffer.alloc(512 * 1024 + 1, 65));
    await writeFile(outside, "private");
    await symlink(outside, join(workspace, "escape.txt"));
    assert.deepEqual(await readWorkspaceText(workspace, "notes.txt"), { path: "notes.txt", text: "hello\n", size: 6, truncated: false });
    await assert.rejects(() => readWorkspaceText(workspace, "../outside.txt"), /outside/);
    await assert.rejects(() => readWorkspaceText(workspace, "escape.txt"), /outside/);
    await assert.rejects(() => readWorkspaceText(workspace, "binary.bin"), /binary/);
    await assert.rejects(() => readWorkspaceText(workspace, "large.txt"), /exceeds/);
  } finally { await rm(parent, { recursive: true, force: true }); }
});

test("file search and git diff projections remove roots and enforce response bounds", () => {
  const cwd = "/project";
  assert.deepEqual(projectFileSearch({ files: [
    { root: cwd, path: "src/main.ts", file_name: "main.ts", score: 7, match_type: "full", indices: [0, 2] },
    { root: cwd, path: "../secret", file_name: "secret", score: 9 },
  ] }, cwd), { files: [{ path: "src/main.ts", fileName: "main.ts", score: 7, matchType: "full", indices: [0, 2] }] });
  assert.deepEqual(projectGitDiff({ sha: "abc", diff: "patch" }), { sha: "abc", diff: "patch" });
  assert.throws(() => projectGitDiff({ sha: "abc", diff: "x".repeat(1024 * 1024 + 1) }), /exceeds/);
});

test("feature RPC validation bounds names, searches, pagination and goals", () => {
  validateFeatureParams("thread/name/set", { threadId: "t", name: "Renamed" });
  assert.throws(() => validateFeatureParams("thread/name/set", { threadId: "t", name: "bad\nname" }), /Task name/);
  assert.throws(() => validateFeatureParams("thread/search", { searchTerm: "x", limit: 201 }), /Page limit/);
  assert.throws(() => validateFeatureParams("thread/goal/set", { threadId: "t", tokenBudget: 0 }), /positive/);
});
