import assert from "node:assert/strict";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { hydrateTurnsPage } from "../src/remote-server.js";

test("turn pagination preserves page metadata and enriches only returned turns", async () => {
  const root = await mkdtemp(join(tmpdir(), "codex-turn-page-"));
  try {
    const path = join(root, "session-thread-1.jsonl");
    await writeFile(path, ["turn-page", "turn-outside"].flatMap((turn, index) => [
      JSON.stringify({ type: "response_item", payload: { type: "custom_tool_call", id: `call-${index}`, call_id: `id-${index}`,
        name: "exec", input: `{\"cmd\":\"echo ${index}\"}`, internal_chat_message_metadata_passthrough: { turn_id: turn } } }),
      JSON.stringify({ type: "response_item", payload: { type: "custom_tool_call_output", call_id: `id-${index}`, output: `out-${index}`,
        internal_chat_message_metadata_passthrough: { turn_id: turn } } }),
    ]).join("\n"));
    const calls: Array<{ method: string; params: unknown }> = [];
    const codex = { call: async (method: string, params: unknown) => {
      calls.push({ method, params });
      return { thread: { id: "thread-1", path, cwd: "/private/project", turns: [{ id: "must-not-use" }] } };
    } };
    const page: any = { data: [{ id: "turn-page", items: [{ type: "agentMessage", id: "final", phase: "final_answer" }] }],
      nextCursor: "opaque-next", extra: { stable: true } };
    const result: any = await hydrateTurnsPage(codex as any, { threadId: "thread-1" }, page, root);
    assert.deepEqual(calls, [{ method: "thread/read", params: { threadId: "thread-1", includeTurns: false } }]);
    assert.equal(result.nextCursor, "opaque-next");
    assert.deepEqual(result.extra, { stable: true });
    assert.deepEqual(result.data.map((turn: any) => turn.id), ["turn-page"]);
    assert.deepEqual(result.data[0].items.map((item: any) => item.type), ["commandExecution", "agentMessage"]);
    assert.equal(result.data[0].items[0].command, "echo 0");
  } finally { await rm(root, { recursive: true, force: true }); }
});
