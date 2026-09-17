import assert from "node:assert/strict";
import { mkdtemp, mkdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { hydrateThreadHistory } from "../src/thread-history.js";

test("restores omitted exec calls from the canonical session log before the final answer", async () => {
  const root = await mkdtemp(join(tmpdir(), "codex-history-"));
  try {
    await mkdir(join(root, "2026"));
    const path = join(root, "2026", "rollout.jsonl");
    await writeFile(path, [
      row("response_item", {
        type: "custom_tool_call",
        id: "ctc-1",
        call_id: "call-1",
        name: "exec",
        status: "completed",
        input: "const r = await tools.exec_command({ cmd: \"find /tmp -type f\\nwc -l\", workdir: \"/tmp\" });",
        internal_chat_message_metadata_passthrough: { turn_id: "turn-1" },
      }),
      row("response_item", {
        type: "custom_tool_call_output",
        call_id: "call-1",
        output: [{ type: "input_text", text: "Script completed\nOutput:\n3\n" }],
        internal_chat_message_metadata_passthrough: { turn_id: "turn-1" },
      }),
    ].join("\n"));
    const value: any = {
      thread: {
        path,
        turns: [{ id: "turn-1", items: [
          { type: "userMessage", id: "u", content: [{ type: "text", text: "inspect" }] },
          { type: "agentMessage", id: "c", text: "I will inspect.", phase: "commentary" },
          { type: "agentMessage", id: "f", text: "Done.", phase: "final_answer" },
        ] }],
      },
    };

    await hydrateThreadHistory(value, root);

    assert.deepEqual(value.thread.turns[0].items.map((item: any) => item.type), [
      "userMessage", "agentMessage", "commandExecution", "agentMessage",
    ]);
    assert.equal(value.thread.turns[0].items[2].command, "find /tmp -type f\nwc -l");
    assert.match(value.thread.turns[0].items[2].aggregatedOutput, /Output:\n3/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("prefers each canonical CommandExecution over a grouped code-mode wrapper", async () => {
  const root = await mkdtemp(join(tmpdir(), "codex-history-"));
  try {
    const path = join(root, "rollout.jsonl");
    await writeFile(path, [
      row("response_item", {
        type: "custom_tool_call",
        id: "ctc-batch",
        call_id: "call-batch",
        name: "exec",
        status: "completed",
        input: "const results = await Promise.all([tools.exec_command({cmd:\"free -h\"}), tools.exec_command({cmd:\"ps aux\"})]);",
        internal_chat_message_metadata_passthrough: { turn_id: "turn-batch" },
      }),
      row("event_msg", {
        type: "item_completed",
        turn_id: "turn-batch",
        item: {
          type: "CommandExecution",
          id: "exec-free",
          command: ["/bin/zsh", "-lc", "free -h"],
          status: "completed",
          aggregated_output: "memory output",
        },
      }),
      row("event_msg", {
        type: "item_completed",
        turn_id: "turn-batch",
        item: {
          type: "CommandExecution",
          id: "exec-ps",
          command: ["/bin/zsh", "-lc", "ps aux"],
          status: "completed",
          aggregated_output: "process output",
        },
      }),
    ].join("\n"));
    const value: any = { thread: { path, turns: [{ id: "turn-batch", items: [
      { type: "agentMessage", id: "final", text: "Done.", phase: "final_answer" },
    ] }] } };

    await hydrateThreadHistory(value, root);

    const commands = value.thread.turns[0].items.filter((item: any) => item.type === "commandExecution");
    assert.deepEqual(commands.map((item: any) => item.command), ["free -h", "ps aux"]);
    assert.deepEqual(commands.map((item: any) => item.aggregatedOutput), ["memory output", "process output"]);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("does not duplicate a command already returned by app-server", async () => {
  const root = await mkdtemp(join(tmpdir(), "codex-history-"));
  try {
    const path = join(root, "rollout.jsonl");
    await writeFile(path, row("response_item", {
      type: "custom_tool_call",
      id: "ctc-1",
      call_id: "call-1",
      name: "exec",
      input: "tools.exec_command({cmd:'pwd'})",
      internal_chat_message_metadata_passthrough: { turn_id: "turn-1" },
    }));
    const value: any = { thread: { path, turns: [{ id: "turn-1", items: [
      { type: "commandExecution", id: "existing", command: "pwd", status: "completed" },
    ] }] } };

    await hydrateThreadHistory(value, root);

    assert.equal(value.thread.turns[0].items.length, 1);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("does not restore an exec command when app-server returned its code-mode wrapper", async () => {
  const root = await mkdtemp(join(tmpdir(), "codex-history-"));
  try {
    const path = join(root, "rollout.jsonl");
    await writeFile(path, row("response_item", {
      type: "custom_tool_call",
      id: "ctc-wrapper",
      call_id: "call-wrapper",
      name: "exec_command",
      input: "const r = await tools.exec_command({cmd:\"sed -n '1,20p' /tmp/SKILL.md\"}); text(r.output)",
      internal_chat_message_metadata_passthrough: { turn_id: "turn-wrapper" },
    }));
    const value: any = { thread: { path, turns: [{ id: "turn-wrapper", items: [
      {
        type: "commandExecution",
        id: "existing-wrapper",
        command: "const r = await tools.exec_command({\"cmd\":\"sed -n '1,20p' /tmp/SKILL.md\"}); text(r.output)",
        status: "completed",
      },
    ] }] } };

    await hydrateThreadHistory(value, root);

    assert.equal(value.thread.turns[0].items.length, 1);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("ignores session paths outside the configured root", async () => {
  const root = await mkdtemp(join(tmpdir(), "codex-history-root-"));
  const outside = await mkdtemp(join(tmpdir(), "codex-history-outside-"));
  try {
    const path = join(outside, "rollout.jsonl");
    await writeFile(path, row("response_item", {}));
    const value: any = { thread: { path, turns: [{ id: "turn-1", items: [] }] } };
    await hydrateThreadHistory(value, root);
    assert.deepEqual(value.thread.turns[0].items, []);
  } finally {
    await rm(root, { recursive: true, force: true });
    await rm(outside, { recursive: true, force: true });
  }
});

test("finds the canonical session by thread id when app-server omits path", async () => {
  const root = await mkdtemp(join(tmpdir(), "codex-history-"));
  try {
    const threadId = "01a025da-0a1f-7522-8b84-512122cdb02c";
    const directory = join(root, "2026", "08", "22");
    await mkdir(directory, { recursive: true });
    const path = join(directory, `rollout-2026-08-22T00-00-00-${threadId}.jsonl`);
    await writeFile(path, row("response_item", {
      type: "custom_tool_call",
      id: "ctc-fallback",
      call_id: "call-fallback",
      name: "exec_command",
      input: "tools.exec_command({cmd:'du -sh /root'})",
      internal_chat_message_metadata_passthrough: { turn_id: "turn-fallback" },
    }));
    const value: any = { thread: { id: threadId, turns: [{ id: "turn-fallback", items: [] }] } };

    await hydrateThreadHistory(value, root);

    assert.equal(value.thread.turns[0].items[0].type, "commandExecution");
    assert.equal(value.thread.turns[0].items[0].command, "du -sh /root");
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("restores generated image paths from the canonical session log", async () => {
  const root = await mkdtemp(join(tmpdir(), "codex-history-"));
  try {
    const path = join(root, "rollout.jsonl");
    const generatedPath = "/root/.codex/generated_images/thread/image.png";
    await writeFile(path, [
      row("response_item", {
        type: "custom_tool_call",
        call_id: "tool-call",
        name: "exec",
        internal_chat_message_metadata_passthrough: { turn_id: "turn-image" },
      }),
      row("event_msg", {
        type: "image_generation_end",
        call_id: "generated-call",
        status: "completed",
        saved_path: generatedPath,
      }),
    ].join("\n"));
    const value: any = { thread: { path, turns: [{ id: "turn-image", items: [
      { type: "imageGeneration", id: "existing-image" },
      { type: "agentMessage", id: "final", text: "Done.", phase: "final_answer" },
    ] }] } };

    await hydrateThreadHistory(value, root);

    assert.equal(value.thread.turns[0].items[0].path, generatedPath);
    assert.equal(value.thread.turns[0].items.filter((item: any) => item.type === "imageGeneration").length, 1);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

function row(type: string, payload: object): string {
  return JSON.stringify({ timestamp: "2026-08-22T00:00:00Z", type, payload });
}
