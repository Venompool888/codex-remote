import test from "node:test";
import assert from "node:assert/strict";
import net from "node:net";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { DesktopOwnerBridge, applyDesktopPatches, desktopTurns, desktopInput } from "../src/desktop-owner-bridge.js";

const state = () => ({ id: "thread", createdAt: 1000, updatedAt: 2000, cwd: "/tmp", latestModel: "test",
  threadRuntimeStatus: { type: "active" }, requests: [], turnHistory: { kind: "canonical", history: {
    entitiesByKey: { b: { turnId: "two", status: "inProgress", items: [{ id: "m", type: "agentMessage", text: "hi" }] },
      a: { turnId: "one", status: "completed", items: [{ id: "done", type: "agentMessage", text: "finished" }] } },
    islands: [{ entries: [{ value: "a" }, { value: "b" }] }],
  } } });

test("desktop history uses canonical island order and patches do not mutate prior state", () => {
  const original = state();
  const next = applyDesktopPatches(original, [{ op: "replace", path: ["turnHistory", "history", "entitiesByKey", "b", "items", 0, "text"], value: "hello" }]);
  assert.deepEqual(desktopTurns(next).map(t => t.id), ["one", "two"]);
  assert.equal(desktopTurns(next)[1].items[0].text, "hello");
  assert.equal(desktopTurns(original)[1].items[0].text, "hi");
  assert.throws(() => applyDesktopPatches(original, [{ op: "add", path: ["__proto__", "polluted"], value: true }]));
});

test("owner IPC framing, history, live patches, actions, approvals and disconnect", async () => {
  const directory = await mkdtemp(join(tmpdir(), "owner-"));
  const path = join(directory, "ipc.sock");
  const messages: any[] = [];
  let peer: net.Socket;
  let revision = 1;
  let current: any = state();
  const frame = (message: any) => {
    const body = Buffer.from(JSON.stringify(message)); const header = Buffer.alloc(4); header.writeUInt32LE(body.length);
    return Buffer.concat([header, body]);
  };
  const broadcast = (change: any, owner = "owner") => peer.write(frame({ type: "broadcast", method: "thread-stream-state-changed", version: 11,
    sourceClientId: owner, params: { hostId: "local", conversationId: "thread", change } }));
  const server = net.createServer(socket => {
    peer = socket; let buffer = Buffer.alloc(0);
    socket.on("data", data => {
      buffer = Buffer.concat([buffer, data]);
      while (buffer.length >= 4 && buffer.length >= buffer.readUInt32LE(0) + 4) {
        const size = buffer.readUInt32LE(0); const message = JSON.parse(buffer.subarray(4, size + 4).toString()); buffer = buffer.subarray(size + 4);
        messages.push(message);
        if (message.type !== "request") continue;
        let result: any = {};
        if (message.method === "initialize") result = { clientId: "remote" };
        if (message.method === "thread-follower-load-complete-history") {
          broadcast({ type: "snapshot", revision, conversationState: current }); result = { revision };
        }
        if (message.method === "thread-follower-start-turn") result = { result: { turn: { id: "new", status: "inProgress", items: [] } } };
        const response = frame({ type: "response", requestId: message.requestId, resultType: "success", handledByClientId: "owner", result });
        // Exercise fragmented headers and bodies on every response.
        socket.write(response.subarray(0, 2)); socket.write(response.subarray(2));
      }
    });
  });
  await new Promise<void>(resolve => server.listen(path, resolve));
  const bridge = new DesktopOwnerBridge(path);
  const events: any[] = []; const requests: any[] = []; const resolved: any[] = [];
  bridge.on("notification", event => events.push(event)); bridge.on("request", request => requests.push(request));
  bridge.on("requestResolved", id => resolved.push(id));
  try {
    assert.equal(await bridge.discover("thread"), true);
    const result = await bridge.call("thread/resume", { threadId: "thread" });
    assert.equal(result.thread.turns.length, 2);
    assert.equal(result.thread.turns[1].id, "two");
    const patch = { op: "replace", path: ["turnHistory", "history", "entitiesByKey", "b", "items", 0, "text"], value: "hello" };
    broadcast({ type: "patches", baseRevision: 1, revision: 2, patches: [patch] });
    await new Promise(resolve => setTimeout(resolve, 20));
    assert.equal(events.at(-1).method, "item/started"); assert.equal(events.at(-1).params.item.text, "hello");
    broadcast({ type: "patches", baseRevision: 2, revision: 3, patches: [{ ...patch, value: "hello world" }] });
    await new Promise(resolve => setTimeout(resolve, 20));
    assert.equal(events.at(-1).method, "item/agentMessage/delta");
    assert.equal(events.at(-1).params.delta, " world");
    const historyCalls = messages.filter(m => m.method === "thread-follower-load-complete-history").length;
    const cached = await bridge.call("thread/read", { threadId: "thread" });
    assert.equal(cached.thread.turns[1].items[0].text, "hello world");
    assert.equal(messages.filter(m => m.method === "thread-follower-load-complete-history").length, historyCalls);
    const before = events.length;
    broadcast({ type: "snapshot", revision: 9, conversationState: state() }, "impostor");
    await new Promise(resolve => setTimeout(resolve, 20)); assert.equal(events.length, before);
    assert.equal((await bridge.call("turn/start", { threadId: "thread", input: [{ type: "text", text: "test" }] })).turn.id, "new");
    const start = messages.find(m => m.method === "thread-follower-start-turn");
    assert.equal(start.targetClientId, "owner"); assert.equal(start.version, 2);
    assert.equal(start.params.turnStart.request.input[0].text, "test");
    assert.deepEqual(start.params.turnStart.request.input[0].text_elements, []);
    await bridge.call("turn/steer", { threadId: "thread", expectedTurnId: "two", input: [{ type: "text", text: "continue" }] });
    assert.deepEqual(messages.find(m => m.method === "thread-follower-steer-turn").params.input[0].text_elements, []);
    await bridge.call("turn/interrupt", { threadId: "thread", turnId: "two" });
    assert.equal(messages.find(m => m.method === "thread-follower-interrupt-turn").params.expectedTurnId, "two");
    await assert.rejects(bridge.call("turn/steer", { threadId: "thread", expectedTurnId: "wrong", input: [] }), /no longer matches/);
    current.requests = [{ id: 42, method: "item/commandExecution/requestApproval", params: { threadId: "thread", turnId: "two" } }];
    revision = 3; await bridge.call("thread/resume", { threadId: "thread" });
    assert.equal(requests.length, 1);
    await bridge.respond(requests[0].id, { decision: "accept" });
    assert.equal(messages.find(m => m.method === "thread-follower-command-approval-decision").params.requestId, 42);
    current.requests = [{ id: 43, method: "item/tool/requestUserInput", params: {} }];
    revision++; await bridge.call("thread/resume", { threadId: "thread" });
    const stable = events.length;
    await bridge.call("thread/resume", { threadId: "thread" });
    assert.equal(events.length, stable, "identical snapshots do not repeat completed items");
    current.requests = []; revision++; await bridge.call("thread/resume", { threadId: "thread" });
    assert.ok(resolved.includes("desktop:thread:43"));
    await assert.rejects(bridge.respond("desktop:thread:43", {}), /no longer pending/);
    peer!.destroy(); await new Promise(resolve => setTimeout(resolve, 20));
    assert.equal(bridge.has("thread"), false);
  } finally {
    bridge.stop(); await new Promise<void>(resolve => server.close(() => resolve())); await rm(directory, { recursive: true });
  }
});


test("phone text is normalized for desktop rendering without dropping annotations or images", () => {
  const input = [{ type: "text", text: "稍微有点延迟" }];
  assert.deepEqual(desktopInput(input), [{ ...input[0], text_elements: [] }]);
  assert.equal("text_elements" in input[0], false);
  const annotated = { type: "text", text: "file", text_elements: [{ byteRange: { start: 0, end: 4 }, placeholder: "file" }] };
  const image = { type: "localImage", path: "/tmp/example.png" };
  assert.deepEqual(desktopInput([annotated, image]), [annotated, image]);
  assert.throws(() => desktopInput([{ type: "text", text: "bad", text_elements: {} }]), /must be an array/);
});
