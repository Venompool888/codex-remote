import assert from "node:assert/strict";
import { mkdtemp, mkdir, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { publicPayload } from "../src/public-payload.js";
import { RichResourceBridge } from "../src/rich-resource.js";

test("assistant file links become task-scoped opaque references with line metadata", async () => {
  const parent = await mkdtemp(join(tmpdir(), "codex-rich-resource-"));
  const cwd = join(parent, "project");
  const outside = join(parent, "outside.txt");
  try {
    await mkdir(cwd);
    await mkdir(join(cwd, "src"));
    await writeFile(join(cwd, "src", "main.ts"), "one\ntwo\nthree\n");
    await writeFile(outside, "outside");
    await symlink(outside, join(cwd, "escape.txt"));
    const calls: Array<[string, unknown]> = [];
    const bridge = new RichResourceBridge({ call: async (method: string, params: unknown) => {
      calls.push([method, params]);
      return { thread: { id: "task-a", cwd } };
    } } as any);
    const input = { thread: { id: "task-a", cwd, turns: [{ items: [{ type: "agentMessage", text:
      `See [main](${join(cwd, "src", "main.ts")}:2:3).\n::code-comment{title="Review" body="Look" file="${join(cwd, "src", "main.ts")}" start="2" end="3"}\n\`\`\`md\n[outside](${join(cwd, "src", "main.ts")})\n::code-comment{file="${join(cwd, "src", "main.ts")}" start="1"}\n\`\`\`` }] }] } };
    const projected = bridge.project(input) as any;
    const text = projected.thread.turns[0].items[0].text as string;
    const references = [...text.matchAll(/remote-file:\/\/[a-f0-9]{64}/g)].map((match) => match[0]);
    assert.equal(references.length, 2);
    assert.match(text, /file="remote-file:\/\/[a-f0-9]{64}" start="2" end="3"/);
    assert.match(text, /```md\n\[outside\]\(/);
    assert.match(text, /&#58;&#58;code-comment/);
    const safeText = (publicPayload({ text }, "", true) as any).text as string;
    assert.equal(safeText.includes(cwd), false);
    const read = await bridge.readFileReference("task-a", references[0]) as any;
    assert.equal(read.path, "src/main.ts");
    assert.equal(read.startLine, 2);
    assert.equal(read.endLine, 3);
    assert.equal(read.text, "one\ntwo\nthree\n");
    assert.deepEqual(calls, [["thread/read", { threadId: "task-a", includeTurns: false }]]);
    await assert.rejects(() => bridge.readFileReference("task-b", references[0]), /another task/);

    const unsafe = bridge.project({ method: "item/completed", params: { threadId: "task-a", item: { type: "agentMessage",
      text: `::code-comment{title="No" body="No" file="${outside}" start="1"}\n[escape](${join(cwd, "escape.txt")})` } } }) as any;
    assert.match(unsafe.params.item.text, /remote-artifact:\/\/unavailable/);
    assert.equal(unsafe.params.item.text.includes("remote-file://"), false);
  } finally { await rm(parent, { recursive: true, force: true }); }
});

test("user prose never becomes an actionable file reference", async () => {
  const parent = await mkdtemp(join(tmpdir(), "codex-rich-user-"));
  try {
    await writeFile(join(parent, "file.txt"), "safe");
    const bridge = new RichResourceBridge({ call: async () => ({}) } as any);
    const projected = bridge.project({ thread: { id: "t", cwd: parent, turns: [{ items: [
      { type: "userMessage", text: `[link](${join(parent, "file.txt")}) ::code-comment{file="${join(parent, "file.txt")}" start="1"}` },
    ] }] } }) as any;
    assert.equal(projected.thread.turns[0].items[0].text.includes("remote-file://"), false);
  } finally { await rm(parent, { recursive: true, force: true }); }
});

test("guardian denial is summarized but cannot be approved without the exact assessment event", () => {
  const bridge = new RichResourceBridge({ call: async () => ({}) } as any);
  const projected = bridge.project({ method: "item/autoApprovalReview/completed", params: {
    threadId: "t", reviewId: "r", completedAtMs: 5, review: { status: "denied", riskLevel: "high", rationale: "unsafe" },
    action: { type: "command", command: "do a sensitive thing" },
  } }) as any;
  assert.equal(projected.params.guardianDenied.approvable, false);
  assert.match(projected.params.guardianDenied.unavailableReason, /exact GuardianAssessmentEvent/);
  assert.equal(projected.params.action, undefined);
  assert.equal(JSON.stringify(projected).includes("do a sensitive thing"), false);
  assert.equal(projected.params.guardianDenied.actionSummary, "Command execution");
});

test("MCP app metadata and actual resource reads preserve safe text and media content", async () => {
  const calls: unknown[] = [];
  const bridge = new RichResourceBridge({ call: async (method: string, params: unknown) => {
    calls.push([method, params]);
    return { contents: [
      { uri: "ui://widget/card", mimeType: "text/html; charset=utf-8", text: "<main>Card</main>" },
      { uri: "ui://widget/card", mimeType: "image/png", blob: Buffer.from("png").toString("base64") },
    ] };
  } } as any);
  const projected = bridge.project({ type: "mcpToolCall", server: "widgets", appContext: {
    connectorId: "connector", linkId: "link", resourceUri: "ui://widget/card", appName: "Cards", actionName: "Show",
  }, input: [{ type: "inputImage", imageUrl: "https://example.invalid/image" },
    { type: "inputAudio", audioUrl: "https://example.invalid/audio" }] }) as any;
  assert.equal(projected.richResource.readable, true);
  assert.equal(projected.input.length, 2);
  const result = await bridge.readMcpResource({ threadId: "t", server: "widgets", uri: "ui://widget/card" }) as any;
  assert.deepEqual(result.contents.map((item: any) => item.kind), ["html", "image"]);
  assert.equal(result.contents[1].dataBase64, Buffer.from("png").toString("base64"));
  assert.deepEqual(calls, [["mcpServer/resource/read", { server: "widgets", uri: "ui://widget/card", threadId: "t" }]]);
  await assert.rejects(() => bridge.readMcpResource({ server: "widgets", uri: "file:///private" }), /scheme/);
  await assert.rejects(() => bridge.readMcpResource({ server: "widgets", uri: "https://user:password@example.invalid/card" }), /credentials/);
  await assert.rejects(() => bridge.readMcpResource({ server: "widgets", uri: "ui://widget/card?access_token=private" }), /credentials/);
});
