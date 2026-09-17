import assert from "node:assert/strict";
import { request as httpRequest } from "node:http";
import { EventEmitter } from "node:events";
import { createHash } from "node:crypto";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import WebSocket from "ws";
import { DeviceAuth } from "../src/auth.js";
import type { CodexAppServer } from "../src/codex-app-server.js";
import { projectAccountStatus, projectInstalledApps, RemoteHost } from "../src/remote-server.js";
import { AttachmentStore } from "../src/attachment-store.js";

class FakeCodex extends EventEmitter {
  readonly calls: Array<{ method: string; params: unknown }> = [];
  readonly responses: Array<{ id: number | string; result?: unknown; error?: { code: number; message: string } }> = [];
  async start(): Promise<void> {}
  async stop(): Promise<void> {}
  async call(method: string, params: unknown): Promise<unknown> {
    this.calls.push({ method, params });
    if (method === "account/read") {
      return {
        account: {
          type: "chatgpt",
          email: "person@example.com",
          planType: "pro",
          accessToken: "must-not-cross-host-boundary",
        },
        requiresOpenaiAuth: true,
      };
    }
    return { method, params };
  }
  respond(id: number | string, result?: unknown, error?: { code: number; message: string }): void {
    this.responses.push({ id, result, error });
  }
}

function waitForClose(socket: WebSocket): Promise<{ code: number; reason: string }> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("Timed out waiting for WebSocket close")), 2_000);
    socket.once("close", (code, reason) => {
      clearTimeout(timer);
      resolve({ code, reason: reason.toString() });
    });
  });
}

interface MessageQueue {
  socket: WebSocket;
  next(): Promise<Record<string, unknown>>;
}

async function connect(url: string, token: string): Promise<MessageQueue> {
  const socket = new WebSocket(url, { headers: { Authorization: `Bearer ${token}` } });
  const messages: Record<string, unknown>[] = [];
  const waiters: Array<(message: Record<string, unknown>) => void> = [];
  socket.on("message", (data) => {
    const message = JSON.parse(data.toString()) as Record<string, unknown>;
    const waiter = waiters.shift();
    if (waiter) waiter(message);
    else messages.push(message);
  });
  await new Promise<void>((resolve, reject) => {
    socket.once("open", resolve);
    socket.once("error", reject);
  });
  return {
    socket,
    next: () => {
      const message = messages.shift();
      if (message) return Promise.resolve(message);
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error("Timed out waiting for WebSocket message")), 2_000);
        waiters.push((value) => {
          clearTimeout(timer);
          resolve(value);
        });
      });
    },
  };
}

test("v2 requires negotiation, advertises capabilities, and accepts RPC after hello_ack", async (t) => {
  const directory = await mkdtemp(join(tmpdir(), "codex-remote-v2-test-"));
  const auth = new DeviceAuth(join(directory, "devices.json"));
  const host = new RemoteHost({
    host: "127.0.0.1",
    port: 0,
    auth,
    codex: new FakeCodex() as unknown as CodexAppServer,
    authRecheckIntervalMs: 20,
  });
  const started = await host.start();
  const baseUrl = `http://127.0.0.1:${started.port}`;
  const pairResponse = await fetch(`${baseUrl}/v2/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code: started.pairing.code, deviceName: "Test Pixel" }),
  });
  assert.equal(pairResponse.status, 201);
  const paired = await pairResponse.json() as { token: string; protocolVersion: number; device: { id: string } };
  assert.equal(paired.protocolVersion, 2);

  const connection = await connect(`ws://127.0.0.1:${started.port}/v2/ws`, paired.token);
  t.after(async () => {
    connection.socket.close();
    await host.stop();
    await rm(directory, { recursive: true, force: true });
  });

  const offer = await connection.next();
  assert.equal(offer.type, "server_hello");
  assert.deepEqual(offer.supportedProtocolVersions, [2, 1]);
  assert.equal((offer.capabilities as { events: { replay: boolean } }).events.replay, true);

  connection.socket.send(JSON.stringify({ type: "rpc", id: "too-early", method: "thread/list", params: {} }));
  const earlyError = await connection.next();
  assert.equal(earlyError.type, "protocol_error");

  connection.socket.send(JSON.stringify({
    type: "client_hello",
    supportedProtocolVersions: [2],
    client: { name: "test", version: "1.0" },
  }));
  const acknowledgement = await connection.next();
  assert.equal(acknowledgement.type, "hello_ack");
  assert.equal(acknowledgement.protocolVersion, 2);

  connection.socket.send(JSON.stringify({ type: "rpc", id: "ready", method: "thread/list", params: { limit: 5 } }));
  const result = await connection.next();
  assert.equal(result.type, "rpc_result");
  assert.equal(result.id, "ready");
  assert.deepEqual(result.result, { method: "thread/list", params: { limit: 5 } });

  connection.socket.send(JSON.stringify({ type: "rpc", id: "account", method: "host/account/status", params: {} }));
  const accountResult = await connection.next();
  assert.equal(accountResult.type, "rpc_result");
  assert.deepEqual(accountResult.result, {
    ready: true,
    authenticated: true,
    requiresOpenaiAuth: true,
    authMode: "chatgpt",
    email: "person@example.com",
    planType: "pro",
    credentialSource: null,
  });
  assert.equal(JSON.stringify(accountResult).includes("must-not-cross-host-boundary"), false);

  const statusResponse = await fetch(`${baseUrl}/v2/status`, {
    headers: { Authorization: `Bearer ${paired.token}` },
  });
  assert.equal(statusResponse.status, 200);
  const status = await statusResponse.json() as Record<string, unknown>;
  assert.equal(status.ok, true);
  assert.equal(status.connectedSockets, 1);
  assert.equal(JSON.stringify(status).includes(paired.token), false);

  const closeAfterRotation = waitForClose(connection.socket);
  const rotateResponse = await fetch(`${baseUrl}/v2/device/rotate`, {
    method: "POST",
    headers: { Authorization: `Bearer ${paired.token}` },
  });
  assert.equal(rotateResponse.status, 200);
  const rotated = await rotateResponse.json() as { token: string; credential: { expiresAt: string } };
  assert.notEqual(rotated.token, paired.token);
  assert.ok(rotated.credential.expiresAt);
  assert.deepEqual(await closeAfterRotation, { code: 4001, reason: "Device credential rotated" });

  const replacementConnection = await connect(`ws://127.0.0.1:${started.port}/v2/ws`, rotated.token);
  await replacementConnection.next();
  const closeAfterRevocation = waitForClose(replacementConnection.socket);
  const revokeResponse = await fetch(`${baseUrl}/v2/device`, {
    method: "DELETE",
    headers: { Authorization: `Bearer ${rotated.token}` },
  });
  assert.equal(revokeResponse.status, 200);
  const revokedClose = await closeAfterRevocation;
  assert.equal(revokedClose.code, 4001);
  // The periodic credential audit may close the socket before DELETE finishes.
  assert.match(revokedClose.reason, /^Device credential (?:expired or )?revoked$/);
});

test("v1 pairing and WebSocket hello remain available", async (t) => {
  const directory = await mkdtemp(join(tmpdir(), "codex-remote-v1-test-"));
  const auth = new DeviceAuth(join(directory, "devices.json"));
  const host = new RemoteHost({
    host: "127.0.0.1",
    port: 0,
    auth,
    codex: new FakeCodex() as unknown as CodexAppServer,
    authRecheckIntervalMs: 20,
  });
  const started = await host.start();
  const pairResponse = await fetch(`http://127.0.0.1:${started.port}/v1/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code: started.pairing.code, deviceName: "Old client" }),
  });
  const paired = await pairResponse.json() as { token: string; protocolVersion: number; device: { id: string } };
  assert.equal(paired.protocolVersion, 1);
  const connection = await connect(`ws://127.0.0.1:${started.port}/v1/ws`, paired.token);
  t.after(async () => {
    connection.socket.close();
    await host.stop();
    await rm(directory, { recursive: true, force: true });
  });
  const hello = await connection.next();
  assert.equal(hello.type, "hello");
  assert.equal(hello.protocolVersion, 1);
  const closeAfterAdminRevocation = waitForClose(connection.socket);
  assert.equal(await auth.revoke(paired.device.id), true);
  assert.deepEqual(await closeAfterAdminRevocation, {
    code: 4001,
    reason: "Device credential expired or revoked",
  });
});

test("chunked attachment HTTP is device-bound and turn inputs use verified Host paths", async (t) => {
  const directory = await mkdtemp(join(tmpdir(), "codex-remote-attachment-http-test-"));
  const auth = new DeviceAuth(join(directory, "devices.json"));
  const attachments = new AttachmentStore(join(directory, "attachments"), Date.now, 5);
  const host = new RemoteHost({
    host: "127.0.0.1",
    port: 0,
    auth,
    codex: new FakeCodex() as unknown as CodexAppServer,
    attachments,
  });
  const started = await host.start();
  const baseUrl = `http://127.0.0.1:${started.port}`;
  const firstPair = await fetch(`${baseUrl}/v2/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code: started.pairing.code, deviceName: "Attachment Pixel" }),
  });
  const first = await firstPair.json() as { token: string };
  const secondTicket = host.createPairingTicket();
  const secondPair = await fetch(`${baseUrl}/v2/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code: secondTicket.code, deviceName: "Other device" }),
  });
  const second = await secondPair.json() as { token: string };
  const bytes = Buffer.from("hello from a verified attachment\n", "utf8");
  const sha256 = createHash("sha256").update(bytes).digest("hex");
  const initResponse = await fetch(`${baseUrl}/v2/attachments`, {
    method: "POST",
    headers: { Authorization: `Bearer ${first.token}`, "content-type": "application/json" },
    body: JSON.stringify({ uploadKey: "http-resume-proof", name: "notes.txt", mimeType: "text/plain", size: bytes.length, sha256 }),
  });
  assert.equal(initResponse.status, 201);
  const initialized = await initResponse.json() as { attachment: { id: string }; chunkBytes: number };
  assert.equal(initialized.chunkBytes, 5);
  const replay = await fetch(`${baseUrl}/v2/attachments`, {
    method: "POST",
    headers: { Authorization: `Bearer ${first.token}`, "content-type": "application/json" },
    body: JSON.stringify({ uploadKey: "http-resume-proof", name: "notes.txt", mimeType: "text/plain", size: bytes.length, sha256 }),
  });
  assert.equal(replay.status, 201);
  assert.equal((await replay.json() as { attachment: { id: string } }).attachment.id, initialized.attachment.id);
  const oversized = await fetch(`${baseUrl}/v2/attachments`, {
    method: "POST",
    headers: { Authorization: `Bearer ${first.token}`, "content-type": "application/json" },
    body: JSON.stringify({ name: "large.txt", mimeType: "text/plain", size: 6 * 1024 * 1024, sha256 }),
  });
  assert.equal(oversized.status, 413);
  const limitError = await oversized.json() as {code:string;maxBytes:number};
  assert.equal(limitError.code, 'too_large');
  assert.equal(limitError.maxBytes, 5 * 1024 * 1024);

  for (let start = 0; start < bytes.length; start += initialized.chunkBytes) {
    const chunk = bytes.subarray(start, Math.min(start + initialized.chunkBytes, bytes.length));
    const response = await fetch(`${baseUrl}/v2/attachments/${initialized.attachment.id}`, {
      method: "PUT",
      headers: {
        Authorization: `Bearer ${first.token}`,
        "content-range": `bytes ${start}-${start + chunk.length - 1}/${bytes.length}`,
        "x-chunk-sha256": createHash("sha256").update(chunk).digest("hex"),
      },
      body: chunk,
    });
    assert.equal(response.status, 200);
  }
  const forbiddenStatus = await fetch(`${baseUrl}/v2/attachments/${initialized.attachment.id}`, {
    headers: { Authorization: `Bearer ${second.token}` },
  });
  assert.equal(forbiddenStatus.status, 403);
  const complete = await fetch(`${baseUrl}/v2/attachments/${initialized.attachment.id}/complete`, {
    method: "POST",
    headers: { Authorization: `Bearer ${first.token}` },
  });
  assert.equal(complete.status, 200);

  const downloadUrl = `${baseUrl}/v2/attachments/${initialized.attachment.id}/download`;
  const download = await fetch(downloadUrl, { headers: { Authorization: `Bearer ${first.token}` } });
  assert.equal(download.status, 200);
  assert.equal(download.headers.get("X-Content-SHA256"), sha256);
  assert.equal(download.headers.get("Content-Type"), "text/plain");
  assert.deepEqual(Buffer.from(await download.arrayBuffer()), bytes);
  assert.equal((await fetch(downloadUrl, { headers: { Authorization: `Bearer ${second.token}` } })).status, 403);
  assert.equal((await fetch(downloadUrl)).status, 401);

  const connection = await connect(`ws://127.0.0.1:${started.port}/v2/ws`, first.token);
  t.after(async () => {
    connection.socket.close();
    await host.stop();
    await rm(directory, { recursive: true, force: true });
  });
  const offer = await connection.next();
  assert.equal((offer.capabilities as { attachments: { chunkedHttp: boolean } }).attachments.chunkedHttp, true);
  connection.socket.send(JSON.stringify({
    type: "client_hello",
    supportedProtocolVersions: [2],
    client: { name: "test", version: "1.0" },
  }));
  await connection.next();
  connection.socket.send(JSON.stringify({
    type: "rpc",
    id: "attachment-turn",
    idempotencyKey: "attachment-turn-once",
    method: "turn/start",
    params: {
      threadId: "thread-1",
      input: [
        { type: "text", text: "Summarize it" },
        { type: "remoteAttachment", attachmentId: initialized.attachment.id },
      ],
    },
  }));
  const turn = await connection.next();
  assert.equal(turn.type, "rpc_result");
  const forwarded = turn.result as { params: { input: Array<Record<string, string>> } };
  assert.equal(forwarded.params.input[1].type, "text");
  assert.equal(forwarded.params.input[1].text, "Attached file: notes.txt");
  assert.doesNotMatch(JSON.stringify(turn), /attachments\/files/);

  connection.socket.send(JSON.stringify({
    type: "rpc",
    id: "raw-path",
    idempotencyKey: "raw-path-once",
    method: "turn/start",
    params: { threadId: "thread-1", input: [{ type: "localImage", path: "/etc/passwd" }] },
  }));
  const rejected = await connection.next();
  assert.equal(rejected.type, "rpc_error");
  assert.match(String(rejected.error), /remote attachment id/);
  attachments.init = async () => {
    throw Object.assign(new Error("ENOENT: open '/custom-mount/private-tenant/secret.bin'"),
      {code:'ENOENT', path:'/custom-mount/private-tenant/secret.bin', syscall:'open'});
  };
  const failedRead = await fetch(`${baseUrl}/v2/attachments`, {
    method:'POST', headers:{Authorization:`Bearer ${first.token}`, 'content-type':'application/json'},
    body:JSON.stringify({name:'safe.txt',mimeType:'text/plain',size:1,sha256}),
  });
  assert.equal(failedRead.status,404);
  const safeFailure = await failedRead.json() as {error:string};
  assert.equal(safeFailure.error,'Host file operation failed (ENOENT)');
  assert.doesNotMatch(JSON.stringify(safeFailure), /custom-mount|private-tenant|secret\.bin/);
});

test("account projection drops unknown and credential-bearing fields", () => {
  assert.deepEqual(projectAccountStatus({
    account: { type: "apiKey", apiKey: "secret", nested: { token: "secret" } },
    requiresOpenaiAuth: true,
    accessToken: "secret",
  }), {
    ready: true,
    authenticated: true,
    requiresOpenaiAuth: true,
    authMode: "apiKey",
    email: null,
    planType: null,
    credentialSource: null,
  });
});

test("replays missed events in the same Host session and deduplicates write RPCs", async (t) => {
  const directory = await mkdtemp(join(tmpdir(), "codex-remote-replay-test-"));
  const auth = new DeviceAuth(join(directory, "devices.json"));
  const codex = new FakeCodex();
  const host = new RemoteHost({
    host: "127.0.0.1",
    port: 0,
    auth,
    codex: codex as unknown as CodexAppServer,
  });
  const started = await host.start();
  const pair = await fetch(`http://127.0.0.1:${started.port}/v2/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code: started.pairing.code, deviceName: "Replay Pixel" }),
  });
  const { token } = await pair.json() as { token: string };
  let connection = await connect(`ws://127.0.0.1:${started.port}/v2/ws`, token);
  t.after(async () => {
    connection.socket.close();
    await host.stop();
    await rm(directory, { recursive: true, force: true });
  });
  const offer = await connection.next();
  const sessionId = String(offer.sessionId);
  connection.socket.send(JSON.stringify({
    type: "client_hello",
    supportedProtocolVersions: [2],
    client: { name: "test", version: "1" },
  }));
  await connection.next();

  connection.socket.send(JSON.stringify({
    type: "rpc", id: "archive-1", idempotencyKey: "archive-once", method: "thread/archive",
    params: { threadId: "thread-1" },
  }));
  assert.equal((await connection.next()).type, "rpc_result");
  connection.socket.send(JSON.stringify({
    type: "rpc", id: "archive-2", idempotencyKey: "archive-once", method: "thread/archive",
    params: { threadId: "thread-1" },
  }));
  const duplicate = await connection.next();
  assert.equal(duplicate.id, "archive-2");
  assert.equal(codex.calls.filter((call) => call.method === "thread/archive").length, 1);

  codex.emit("notification", { method: "test/changed", params: { value: 1 } });
  const event = await connection.next();
  const sequence = Number(event.sequence);
  const closed = waitForClose(connection.socket);
  connection.socket.close();
  await closed;
  connection = await connect(`ws://127.0.0.1:${started.port}/v2/ws`, token);
  await connection.next();
  connection.socket.send(JSON.stringify({
    type: "client_hello",
    supportedProtocolVersions: [2],
    client: { name: "test", version: "1" },
    lastSessionId: sessionId,
    lastSequence: sequence - 1,
  }));
  const acknowledgement = await connection.next();
  assert.equal(acknowledgement.resyncRequired, false);
  const replayed = await connection.next();
  assert.equal(replayed.sequence, sequence);
  assert.equal(replayed.method, "test/changed");
});

test("installed app projection exposes status but drops unknown runtime data", () => {
  assert.deepEqual(projectInstalledApps({ apps: [{
    id: "connector_1",
    runtimeName: "Calendar",
    enabled: true,
    callable: false,
    accessToken: "secret",
    tools: [{ name: "private" }],
  }] }), {
    source: "app/installed",
    apps: [{ id: "connector_1", name: "Calendar", enabled: true, callable: false }],
  });
});

test("approval requests only reach authorized devices and the first response wins", async (t) => {
  const directory = await mkdtemp(join(tmpdir(), "codex-remote-approval-test-"));
  const auth = new DeviceAuth(join(directory, "devices.json"));
  const codex = new FakeCodex();
  const host = new RemoteHost({ host: "127.0.0.1", port: 0, auth, codex: codex as unknown as CodexAppServer });
  const started = await host.start();
  const baseUrl = `http://127.0.0.1:${started.port}`;

  const pair = async (name: string) => {
    const ticket = host.createPairingTicket();
    const response = await fetch(`${baseUrl}/v2/pair`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ code: ticket.code, deviceName: name }),
    });
    return await response.json() as { token: string; device: { id: string } };
  };
  const authorized = await pair("Approval Pixel");
  const readOnly = await pair("Read-only Pixel");
  await auth.setScopes(readOnly.device.id, ["rpc:read"]);
  const approvalConnection = await connect(`ws://127.0.0.1:${started.port}/v2/ws`, authorized.token);
  const readOnlyConnection = await connect(`ws://127.0.0.1:${started.port}/v2/ws`, readOnly.token);
  t.after(async () => {
    approvalConnection.socket.close();
    readOnlyConnection.socket.close();
    await host.stop();
    await rm(directory, { recursive: true, force: true });
  });
  for (const connection of [approvalConnection, readOnlyConnection]) {
    await connection.next();
    connection.socket.send(JSON.stringify({
      type: "client_hello", supportedProtocolVersions: [2], client: { name: "test", version: "1" },
    }));
    await connection.next();
  }

  codex.emit("request", {
    id: 91,
    method: "item/commandExecution/requestApproval",
    params: { command: "pwd", secretPath: "/private/example", availableDecisions: ["accept", "decline"] },
  });
  const request = await approvalConnection.next();
  assert.equal(request.type, "codex_request");
  assert.equal(request.method, "item/commandExecution/requestApproval");

  const readOnlyMessage = await Promise.race([
    readOnlyConnection.next().then(() => "unexpected"),
    new Promise<string>((resolve) => setTimeout(() => resolve("none"), 75)),
  ]);
  assert.equal(readOnlyMessage, "none");

  approvalConnection.socket.send(JSON.stringify({
    type: "server_response", requestId: request.requestId, result: { decision: "cancel" },
  }));
  const invalid = await approvalConnection.next();
  assert.equal(invalid.type, "server_response_ack");
  assert.equal(invalid.status, "invalid");
  assert.equal(codex.responses.length, 0);

  approvalConnection.socket.send(JSON.stringify({
    type: "server_response", requestId: request.requestId, result: { decision: "accept" },
  }));
  await new Promise((resolve) => setTimeout(resolve, 20));
  assert.deepEqual(codex.responses, [{ id: 91, result: { decision: "accept" }, error: undefined }]);

  approvalConnection.socket.send(JSON.stringify({
    type: "server_response", requestId: request.requestId, result: { decision: "decline" },
  }));
  const stale = await approvalConnection.next();
  assert.equal(stale.type, "server_response_ack");
  assert.equal(stale.status, "answered");
  assert.equal(codex.responses.length, 1);
});

test("approval requests fail closed when no authorized device is connected or the request times out", async (t) => {
  const directory = await mkdtemp(join(tmpdir(), "codex-remote-approval-timeout-test-"));
  const auth = new DeviceAuth(join(directory, "devices.json"));
  const codex = new FakeCodex();
  const host = new RemoteHost({
    host: "127.0.0.1", port: 0, auth, codex: codex as unknown as CodexAppServer, approvalTimeoutMs: 25,
  });
  const started = await host.start();
  t.after(async () => {
    await host.stop();
    await rm(directory, { recursive: true, force: true });
  });

  codex.emit("request", { id: 101, method: "item/fileChange/requestApproval", params: {} });
  assert.equal(codex.responses.length, 0);
  await new Promise((resolve) => setTimeout(resolve, 50));
  assert.equal(codex.responses[0]?.error?.code, -32002);

  const ticket = host.createPairingTicket();
  const pairResponse = await fetch(`http://127.0.0.1:${started.port}/v2/pair`, {
    method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ code: ticket.code, deviceName: "Timeout Pixel" }),
  });
  const paired = await pairResponse.json() as { token: string };
  const connection = await connect(`ws://127.0.0.1:${started.port}/v2/ws`, paired.token);
  t.after(() => connection.socket.close());
  await connection.next();
  connection.socket.send(JSON.stringify({
    type: "client_hello", supportedProtocolVersions: [2], client: { name: "test", version: "1" },
  }));
  await connection.next();
  codex.emit("request", { id: 102, method: "item/fileChange/requestApproval", params: {} });
  await connection.next();
  await new Promise((resolve) => setTimeout(resolve, 50));
  assert.equal(codex.responses[1]?.error?.code, -32002);
});

test("pending questions replay after reconnect and invalid or duplicate replies do not resume twice", async (t) => {
  const directory = await mkdtemp(join(tmpdir(), "codex-question-replay-"));
  const auth = new DeviceAuth(join(directory, "devices.json"));
  const codex = new FakeCodex();
  const host = new RemoteHost({ host: "127.0.0.1", port: 0, auth, codex: codex as unknown as CodexAppServer });
  const started = await host.start();
  const pair = await fetch(`http://127.0.0.1:${started.port}/v2/pair`, {
    method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ code: started.pairing.code, deviceName: "Reconnect Pixel" }),
  });
  const { token } = await pair.json() as { token: string };
  const sockets: WebSocket[] = [];
  t.after(async () => { sockets.forEach(socket => socket.close()); await host.stop(); await rm(directory, { recursive: true, force: true }); });
  const open = async () => {
    const connection = await connect(`ws://127.0.0.1:${started.port}/v2/ws`, token);
    sockets.push(connection.socket);
    await connection.next();
    connection.socket.send(JSON.stringify({ type: "client_hello", supportedProtocolVersions: [2], client: { name: "test", version: "1" } }));
    await connection.next();
    return connection;
  };
  const first = await open();
  codex.emit("request", { id: 900, method: "item/tool/requestUserInput", params: { questions: [{ id: "color", question: "Color?", options: [{label: "Blue", description: "Blue"}] }] } });
  const original = await first.next();
  const closed = waitForClose(first.socket); first.socket.close(); await closed;
  const restored = await open();
  const replay = await restored.next();
  assert.equal(replay.requestId, original.requestId);
  restored.socket.send(JSON.stringify({ type: "server_response", requestId: replay.requestId, result: { answers: {} } }));
  assert.equal((await restored.next()).status, "invalid");
  assert.equal(codex.responses.length, 0);
  const reply = { type: "server_response", requestId: replay.requestId, result: { answers: { color: { answers: ["Blue"] } } } };
  restored.socket.send(JSON.stringify(reply));
  assert.equal((await restored.next()).status, "answered");
  restored.socket.send(JSON.stringify(reply));
  assert.equal((await restored.next()).status, "answered");
  assert.equal(codex.responses.length, 1);
});

test("a restarted app-server resumes a persisted thread only after an explicit not-found rejection", async (t) => {
  class RestartedCodex extends FakeCodex {
    loaded = false;
    executed = 0;
    override async call(method: string, params: unknown): Promise<unknown> {
      this.calls.push({ method, params });
      if (method === "thread/resume") this.loaded = true;
      if (method === "turn/start") {
        if (!this.loaded) throw new Error("thread not found: persisted-id (-32600)");
        this.executed++;
        return { turn: { id: "one-turn" } };
      }
      return {};
    }
  }
  const directory = await mkdtemp(join(tmpdir(), "codex-resume-test-"));
  const codex = new RestartedCodex();
  const host = new RemoteHost({ host: "127.0.0.1", port: 0, auth: new DeviceAuth(join(directory, "devices.json")), codex: codex as unknown as CodexAppServer });
  const started = await host.start();
  const paired = await fetch(`http://127.0.0.1:${started.port}/v2/pair`, { method: "POST", headers: {"content-type":"application/json"}, body: JSON.stringify({code:started.pairing.code,deviceName:"Pixel"}) });
  const {token} = await paired.json() as {token:string};
  const connection = await connect(`ws://127.0.0.1:${started.port}/v2/ws`,token);
  t.after(async()=>{connection.socket.close();await host.stop();await rm(directory,{recursive:true,force:true});});
  await connection.next();
  connection.socket.send(JSON.stringify({type:"client_hello",supportedProtocolVersions:[2],client:{name:"test",version:"1"}}));await connection.next();
  const rpc = {type:"rpc",id:"turn",method:"turn/start",idempotencyKey:"once",params:{threadId:"persisted-id",input:[{type:"text",text:"test"}]}};
  connection.socket.send(JSON.stringify(rpc));assert.equal((await connection.next()).type,"rpc_result");
  connection.socket.send(JSON.stringify(rpc));assert.equal((await connection.next()).type,"rpc_result");
  assert.equal(codex.executed,1);
  assert.equal(codex.calls.filter(call=>call.method==="thread/resume").length,1);
});

test("reissued approval retires the old request and only the replacement can execute", async (t) => {
  const directory = await mkdtemp(join(tmpdir(), "codex-approval-reissue-"));
  const auth = new DeviceAuth(join(directory, "devices.json"));
  const codex = new FakeCodex();
  const host = new RemoteHost({ host: "127.0.0.1", port: 0, auth, codex: codex as unknown as CodexAppServer });
  const started = await host.start();
  const pair = await fetch(`http://127.0.0.1:${started.port}/v2/pair`, {
    method: "POST", headers: {"content-type":"application/json"},
    body: JSON.stringify({code:started.pairing.code,deviceName:"Reissue Pixel"}),
  });
  const {token} = await pair.json() as {token:string};
  const connection = await connect(`ws://127.0.0.1:${started.port}/v2/ws`, token);
  t.after(async () => { connection.socket.close(); await host.stop(); await rm(directory, {recursive:true,force:true}); });
  await connection.next();
  connection.socket.send(JSON.stringify({type:"client_hello",supportedProtocolVersions:[2],client:{name:"test",version:"1"}}));
  await connection.next();
  const request = {method:"item/commandExecution/requestApproval",params:{threadId:"thread",itemId:"item"}};
  codex.emit("request", {id:901,...request});
  const old = await connection.next();
  codex.emit("request", {id:902,...request});
  const expired = await connection.next();
  assert.equal(expired.requestId, old.requestId);
  assert.equal(expired.status,"expired");
  assert.equal(expired.reason, "superseded");
  const replacement = await connection.next();
  connection.socket.send(JSON.stringify({type:"server_response",requestId:old.requestId,result:{decision:"accept"}}));
  assert.equal((await connection.next()).status,"expired");
  assert.equal(codex.responses.length,0);
  connection.socket.send(JSON.stringify({type:"server_response",requestId:replacement.requestId,result:{decision:"accept"}}));
  assert.equal((await connection.next()).status,"answered");
  assert.deepEqual(codex.responses.map(response=>response.id),[902]);
});

test("attachment path split across wire events is redacted before journaling and replay", async (t) => {
  const directory = await mkdtemp(join(tmpdir(), "codex-stream-test-"));
  const root = join(directory, "attachments");
  const codex = new FakeCodex();
  const host = new RemoteHost({ host: "127.0.0.1", port: 0, auth: new DeviceAuth(join(directory, "devices.json")),
    codex: codex as unknown as CodexAppServer, attachments: new AttachmentStore(root) });
  const started = await host.start();
  const response = await fetch(`http://127.0.0.1:${started.port}/v2/pair`, { method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ code: started.pairing.code, deviceName: "Stream test" }) });
  const { token } = await response.json() as { token: string };
  let connection = await connect(`ws://127.0.0.1:${started.port}/v2/ws`, token);
  t.after(async () => { connection.socket.close(); await host.stop(); await rm(directory, {recursive:true,force:true}); });
  const offer = await connection.next();
  connection.socket.send(JSON.stringify({type:"client_hello", supportedProtocolVersions:[2], client:{name:"test",version:"1"}}));
  await connection.next();
  const raw = `![Image](${root}/files/12345678-1234-4234-8234-123456789abc-image.png) Bearer stream-secret-123; sk-proj-stream_1234567890!`;
  for (const delta of raw) codex.emit("notification", {method:"item/agentMessage/delta",params:{threadId:"t",turnId:"u",itemId:"i",delta}});
  codex.emit("notification", {method:"item/completed",params:{threadId:"t",turnId:"u",item:{id:"i",type:"agentMessage",text:raw}}});
  const events: Record<string, unknown>[] = [];
  while (true) { const event = await connection.next(); events.push(event); if (event.method === "item/completed") break; }
  const text = events.filter(e => e.method === "item/agentMessage/delta").map(e => (e.params as any).delta).join('');
  assert.equal(text, '![Image](remote-attachment://12345678-1234-4234-8234-123456789abc-image.png) Bearer [redacted]; [redacted]!');
  assert.equal(JSON.stringify(events).includes('stream-secret-123'), false);
  assert.equal(JSON.stringify(events).includes('sk-proj-stream_1234567890'), false);
  assert.equal(JSON.stringify(events).includes(root), false);
  const closed = waitForClose(connection.socket); connection.socket.close(); await closed;
  connection = await connect(`ws://127.0.0.1:${started.port}/v2/ws`, token); await connection.next();
  connection.socket.send(JSON.stringify({type:"client_hello",supportedProtocolVersions:[2],client:{name:"test",version:"1"},lastSessionId:offer.sessionId,lastSequence:0}));
  await connection.next();
  for (const event of events) assert.deepEqual(await connection.next(), event);
});

test("file approval carries the matching event patch with project-relative names", async t => {
  const directory = await mkdtemp(join(tmpdir(), "codex-file-review-"));
  const auth = new DeviceAuth(join(directory, "devices.json"));
  const codex = new FakeCodex();
  const host = new RemoteHost({ host: "127.0.0.1", port: 0, auth, codex: codex as unknown as CodexAppServer, defaultCwd: "/project" });
  const started = await host.start();
  const pair = await fetch(`http://127.0.0.1:${started.port}/v2/pair`, { method: "POST", headers: {"content-type":"application/json"}, body: JSON.stringify({code:started.pairing.code,deviceName:"File review"}) });
  const {token} = await pair.json() as {token:string};
  const connection = await connect(`ws://127.0.0.1:${started.port}/v2/ws`, token);
  t.after(async () => { connection.socket.close(); await host.stop(); await rm(directory,{recursive:true,force:true}); });
  await connection.next();
  connection.socket.send(JSON.stringify({type:"client_hello",supportedProtocolVersions:[2],client:{name:"test",version:"1"}}));
  await connection.next();
  const params = {threadId:"t",turnId:"u",itemId:"i"};
  codex.emit("notification", {method:"item/started",params:{threadId:"t",turnId:"u",item:{id:"i",type:"fileChange",changes:[{path:"/project/report.txt",kind:{type:"add"},diff:"+hello"}]}}});
  await connection.next();
  codex.emit("request", {id:903,method:"item/fileChange/requestApproval",params});
  const request = await connection.next();
  assert.deepEqual((request.params as any).fileChangeReview,{status:"available",changes:[{path:"report.txt",kind:"add",diff:"+hello"}]});
  assert.equal(JSON.stringify(request).includes("/project"),false);
  connection.socket.send(JSON.stringify({type:"server_response",requestId:request.requestId,result:{decision:"accept"}}));
  assert.equal((await connection.next()).status,"answered");
  assert.deepEqual(codex.responses[0].result,{decision:"accept"});
});


test("rejected websocket upgrades have complete empty HTTP responses", async () => {
  const directory = await mkdtemp(join(tmpdir(), "codex-upgrade-rejection-"));
  const host = new RemoteHost({ host: "127.0.0.1", port: 0,
    auth: new DeviceAuth(join(directory, "devices.json")), codex: new FakeCodex() as unknown as CodexAppServer });
  const started = await host.start();
  try {
    for (const [path, expected] of [["/v2/ws", 401], ["/unsupported/ws", 404]] as const) {
      const result = await new Promise<{ status?: number; length?: string; body: string }>((resolve, reject) => {
        const req = httpRequest({ hostname: "127.0.0.1", port: started.port, path,
          headers: { Connection: "Upgrade", Upgrade: "websocket", Authorization: "Bearer deliberately-invalid",
            "Sec-WebSocket-Version": "13", "Sec-WebSocket-Key": Buffer.alloc(16, 0x42).toString("base64") } }, response => {
          let body = "";
          response.on("data", chunk => { body += chunk; });
          response.on("end", () => resolve({ status: response.statusCode, length: response.headers["content-length"], body }));
          response.on("error", reject);
        });
        req.on("error", reject);
        req.setTimeout(2000, () => req.destroy(new Error("Rejection response did not finish")));
        req.end();
      });
      assert.equal(result.status, expected);
      assert.equal(result.length, "0");
      assert.equal(result.body, "");
    }
  } finally { await host.stop(); await rm(directory, { recursive: true, force: true }); }
});


test("identical reissued question preserves draft id and responds to newest RPC", async (t) => {
  const directory = await mkdtemp(join(tmpdir(), "codex-approval-reissue-"));
  const auth = new DeviceAuth(join(directory, "devices.json"));
  const codex = new FakeCodex();
  const host = new RemoteHost({ host: "127.0.0.1", port: 0, auth, codex: codex as unknown as CodexAppServer });
  const started = await host.start();
  const pair = await fetch(`http://127.0.0.1:${started.port}/v2/pair`, {
    method: "POST", headers: {"content-type":"application/json"},
    body: JSON.stringify({code:started.pairing.code,deviceName:"Reissue Pixel"}),
  });
  const {token} = await pair.json() as {token:string};
  const connection = await connect(`ws://127.0.0.1:${started.port}/v2/ws`, token);
  t.after(async () => { connection.socket.close(); await host.stop(); await rm(directory, {recursive:true,force:true}); });
  await connection.next();
  connection.socket.send(JSON.stringify({type:"client_hello",supportedProtocolVersions:[2],client:{name:"test",version:"1"}}));
  await connection.next();
  const request = {method:"item/tool/requestUserInput",params:{threadId:"thread",itemId:"item",questions:[{id:"color",question:"Choose",options:[{label:"Blue"}]}]}};
  codex.emit("request", {id:901,...request});
  const old = await connection.next();
  codex.emit("request", {id:902,...request});
  const replay = await connection.next();
  assert.equal(replay.type,"codex_request");
  assert.equal(replay.requestId,old.requestId);
  assert.equal(replay.expiresAt,old.expiresAt);
  connection.socket.send(JSON.stringify({type:"server_response",requestId:old.requestId,result:{answers:{color:{answers:["Blue"]}}}}));
  assert.equal((await connection.next()).status,"answered");
  assert.deepEqual(codex.responses.map(response=>response.id),[902]);
});

for (const scenario of ["mcp-answer", "changed-form", "timeout"] as const) {
  test(`reissued MCP request safely handles ${scenario}`, async (t) => {
    const directory = await mkdtemp(join(tmpdir(), "codex-mcp-reissue-"));
    const codex = new FakeCodex();
    const host = new RemoteHost({ host: "127.0.0.1", port: 0,
      auth: new DeviceAuth(join(directory, "devices.json")), codex: codex as unknown as CodexAppServer,
      approvalTimeoutMs: scenario === "timeout" ? 500 : 5000 });
    const started = await host.start();
    const pair = await fetch(`http://127.0.0.1:${started.port}/v2/pair`, {
      method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify({ code: started.pairing.code, deviceName: "MCP Reissue QA" }),
    });
    const { token } = await pair.json() as { token: string };
    const connection = await connect(`ws://127.0.0.1:${started.port}/v2/ws`, token);
    t.after(async () => { connection.socket.close(); await host.stop(); await rm(directory, { recursive: true, force: true }); });
    await connection.next();
    connection.socket.send(JSON.stringify({ type: "client_hello", supportedProtocolVersions: [2], client: { name: "test", version: "1" } }));
    await connection.next();
    const request = { method: "mcpServer/elicitation/request", params: {
      threadId: "thread", itemId: "item", serverName: "qa", mode: "form", message: "Choose a color",
      requestedSchema: { type: "object", required: ["color"], properties: { color: { type: "string", enum: ["blue", "green"] } } },
    } };
    codex.emit("request", { id: 801, ...request });
    const original = await connection.next();
    codex.emit("request", { id: 802, ...request });
    const replay = await connection.next();
    assert.equal(replay.type, "codex_request");
    assert.equal(replay.requestId, original.requestId);
    assert.equal(replay.expiresAt, original.expiresAt);
    assert.equal(codex.responses.length, 0, "Replay must never answer automatically");
    if (scenario === "timeout") {
      const expired = await connection.next();
      assert.equal(expired.requestId, original.requestId);
      assert.equal(expired.status, "expired");
      assert.deepEqual(codex.responses.map(response => [response.id, response.error?.code]), [[802, -32002]]);
      return;
    }
    let active = original;
    let upstreamId = 802;
    if (scenario === "changed-form") {
      codex.emit("request", { id: 803, ...request, params: { ...request.params, message: "A different question requires fresh consent" } });
      const expired = await connection.next();
      assert.equal(expired.requestId, original.requestId);
      assert.equal(expired.status, "expired");
      active = await connection.next();
      assert.notEqual(active.requestId, original.requestId);
      connection.socket.send(JSON.stringify({ type: "server_response", requestId: original.requestId, result: { action: "accept", content: { color: "blue" } } }));
      assert.equal((await connection.next()).status, "expired");
      assert.equal(codex.responses.length, 0, "Stale draft cannot answer changed request");
      upstreamId = 803;
    }
    connection.socket.send(JSON.stringify({ type: "server_response", requestId: active.requestId, result: { action: "accept", content: { color: "blue" } } }));
    assert.equal((await connection.next()).status, "answered");
    assert.deepEqual(codex.responses.map(response => response.id), [upstreamId]);
    connection.socket.send(JSON.stringify({ type: "server_response", requestId: active.requestId, result: { action: "accept", content: { color: "blue" } } }));
    assert.equal((await connection.next()).status, "answered");
    assert.equal(codex.responses.length, 1, "Duplicate response is acknowledged without a second upstream submission");
  });
}

test('artifact-enabled authenticated sessions cannot call the legacy path image reader', async (t) => {
  const directory = await mkdtemp(join(tmpdir(), 'restricted-image-rpc-'));
  const auth = new DeviceAuth(join(directory, 'devices.json'));
  const { ArtifactStore } = await import('../src/artifact-store.js');
  const host = new RemoteHost({ host:'127.0.0.1', port:0, auth,
    codex:new FakeCodex() as unknown as CodexAppServer, artifacts:new ArtifactStore(join(directory,'artifacts')) });
  const started = await host.start();
  t.after(async () => { await host.stop(); await rm(directory,{recursive:true,force:true}); });
  const response = await fetch(`http://127.0.0.1:${started.port}/v2/pair`, {method:'POST',headers:{'content-type':'application/json'},
    body:JSON.stringify({code:started.pairing.code,deviceName:'Image test'})});
  const paired = await response.json() as {token:string};
  const connection = await connect(`ws://127.0.0.1:${started.port}/v2/ws`,paired.token);
  await connection.next();
  connection.socket.send(JSON.stringify({type:'client_hello',supportedProtocolVersions:[2],client:{name:'test',version:'1'}}));
  await connection.next();
  connection.socket.send(JSON.stringify({type:'rpc',id:'legacy-image',method:'host/image/read',params:{path:'/tmp/private.png'}}));
  const rejected = await connection.next();
  assert.equal(rejected.type,'rpc_error');
  assert.match(String(rejected.error),/Path-based image reads are disabled/);
  assert.equal(JSON.stringify(rejected).includes('/tmp/private.png'),false);
  connection.socket.close();
});

test('private path fragments never enter live or replayed artifact-host events', async (t) => {
  const directory = await mkdtemp(join(tmpdir(), 'private-stream-rpc-'));
  const auth = new DeviceAuth(join(directory,'devices.json'));
  const { ArtifactStore } = await import('../src/artifact-store.js');
  const codex = new FakeCodex();
  const host = new RemoteHost({host:'127.0.0.1',port:0,auth,codex:codex as unknown as CodexAppServer,
    artifacts:new ArtifactStore(join(directory,'artifacts'))});
  const started = await host.start();
  t.after(async()=>{await host.stop();await rm(directory,{recursive:true,force:true});});
  const response = await fetch(`http://127.0.0.1:${started.port}/v2/pair`,{method:'POST',headers:{'content-type':'application/json'},
    body:JSON.stringify({code:started.pairing.code,deviceName:'Path stream test'})});
  const paired = await response.json() as {token:string};
  const connection = await connect(`ws://127.0.0.1:${started.port}/v2/ws`,paired.token);
  const offer = await connection.next();
  const hello = {type:'client_hello',supportedProtocolVersions:[2],client:{name:'test',version:'1'}};
  connection.socket.send(JSON.stringify(hello));await connection.next();
  const raw = 'Before "/var/lib/private folder/secret-proof.txt" after';
  for (const delta of raw) codex.emit('notification',{method:'item/agentMessage/delta',params:{threadId:'t',itemId:'i',delta}});
  codex.emit('notification',{method:'item/completed',params:{threadId:'t',item:{id:'i',type:'agentMessage',text:raw}}});
  async function readEvents(queue: MessageQueue) {
    const events: Record<string,unknown>[]=[];
    while(true) { const event=await queue.next();events.push(event);if(event.method==='item/completed')return events; }
  }
  const events=await readEvents(connection);
  assert.equal(events.slice(0,-1).map(e=>(e.params as any).delta).join(''),'Before "[private host path]" after');
  assert.equal(JSON.stringify(events).includes('secret-proof'),false);
  assert.equal((events.at(-1)!.params as any).item.text,'Before "[private host path]" after');
  connection.socket.close();
  const replay=await connect(`ws://127.0.0.1:${started.port}/v2/ws`,paired.token);await replay.next();
  replay.socket.send(JSON.stringify({...hello,lastSessionId:offer.sessionId,lastSequence:0}));await replay.next();
  assert.deepEqual(await readEvents(replay),events);
  replay.socket.close();
});

test('host-local catalog RPC crosses the same metadata sanitization boundary', async (t) => {
  const directory = await mkdtemp(join(tmpdir(), 'catalog-metadata-rpc-'));
  const auth = new DeviceAuth(join(directory, 'devices.json'));
  const host = new RemoteHost({host:'127.0.0.1',port:0,auth,codex:new FakeCodex() as unknown as CodexAppServer});
  const fixture = {entries:[{id:'opaque-capability',kind:'skill',name:'Sample',state:'enabled',
    source:'/home/private-owner/plugins/sample',detail:'Missing /var/lib/private-owner/dependency',
    description:'Read /home/private-owner/notes'}],errors:[]};
  (host as any).catalog.list = async () => fixture;
  const started = await host.start();
  t.after(async()=>{await host.stop();await rm(directory,{recursive:true,force:true});});
  const response = await fetch(`http://127.0.0.1:${started.port}/v2/pair`,{method:'POST',headers:{'content-type':'application/json'},
    body:JSON.stringify({code:started.pairing.code,deviceName:'Metadata test'})});
  const paired = await response.json() as {token:string};
  const connection = await connect(`ws://127.0.0.1:${started.port}/v2/ws`,paired.token);
  await connection.next();
  connection.socket.send(JSON.stringify({type:'client_hello',supportedProtocolVersions:[2],client:{name:'test',version:'1'}}));
  await connection.next();
  connection.socket.send(JSON.stringify({type:'rpc',id:'catalog',method:'host/capabilities/list',params:{cwd:'/tmp/fixture'}}));
  const result = await connection.next();
  assert.equal(result.type,'rpc_result');
  assert.equal(JSON.stringify(result).includes('private-owner'),false);
  const entry=(result.result as any).entries[0];
  assert.equal(entry.id,'opaque-capability');
  assert.equal(entry.source,'[private host path]');
  assert.equal(entry.detail,'Missing [private host path]');
  assert.equal(fixture.entries[0].source,'/home/private-owner/plugins/sample');
  connection.socket.close();
});

test('negotiated workspace references preserve ordered replay, device scope and legacy routing', async t => {
  const directory=await mkdtemp(join(tmpdir(),'workspace-wire-'));
  const {RoutingReferences}=await import('../src/routing-references.js');
  const codex=new FakeCodex();
  const original=codex.call.bind(codex);
  codex.call=async (method,params)=>method==='thread/read' ? {thread:{id:'t',cwd:'/var/lib/private-owner/workspace',
    turns:[{items:[{type:'fileChange',changes:[{path:'/var/lib/private-owner/workspace/report.pdf',
      kind:{type:'update',move_path:'/var/lib/private-owner/workspace/final.pdf'}}]}]}]}} : original(method,params);
  const auth=new DeviceAuth(join(directory,'devices.json'));
  const host=new RemoteHost({host:'127.0.0.1',port:0,auth,
    codex:codex as unknown as CodexAppServer,routingReferences:new RoutingReferences(join(directory,'routing.json'))});
  const started=await host.start();
  t.after(async()=>{await host.stop();await rm(directory,{recursive:true,force:true});});
  async function pair(code:string) {
    return await (await fetch(`http://127.0.0.1:${started.port}/v2/pair`,{method:'POST',headers:{'content-type':'application/json'},
      body:JSON.stringify({code,deviceName:'Workspace test'})})).json() as {token:string};
  }
  const a=await pair(started.pairing.code),b=await pair(host.createPairingTicket().code);
  const url=`ws://127.0.0.1:${started.port}/v2/ws`;
  async function negotiated(token:string,opaque:boolean,extra={}) {
    const c=await connect(url,token);const offer=await c.next();
    assert.equal((offer.capabilities as any).routing.opaqueWorkspaceReferences,true);
    c.socket.send(JSON.stringify({type:'client_hello',supportedProtocolVersions:[2],client:{name:'test',version:'1'},
      features:{opaqueWorkspaceRouting:opaque},...extra}));
    const ack=await c.next();assert.equal((ack.features as any).opaqueWorkspaceRouting,opaque);
    return {c,offer};
  }
  const {c,offer}=await negotiated(a.token,true);
  c.socket.send(JSON.stringify({type:'rpc',id:'migrate',method:'host/workspace/migrate',params:{paths:[directory]}}));
  const migration=await c.next();
  assert.equal(migration.type,'rpc_result');
  const migrated=(migration.result as any).workspaces[0];
  assert.equal(migrated.index,0);assert.equal(migrated.available,true);
  assert.match(migrated.cwd,/^remote-workspace:\/\/[a-f0-9]{64}$/);
  assert.equal(JSON.stringify(migration).includes(directory),false);
  c.socket.send(JSON.stringify({type:'rpc',id:'read',method:'thread/read',params:{threadId:'t'}}));
  const read=await c.next();const thread=(read.result as any).thread;
  assert.match(thread.cwd,/^remote-workspace:\/\/[a-f0-9]{64}$/);assert.equal(thread.cwdName,'workspace');
  assert.equal(JSON.stringify(read).includes('private-owner'),false);
  const change=thread.turns[0].items[0].changes[0];
  assert.match(change.path,/^remote-path:\/\/[a-f0-9]{64}$/);
  assert.equal(change.pathName,'report.pdf');
  assert.equal(change.kind.move_pathName,'final.pdf');
  c.socket.send(JSON.stringify({type:'rpc',id:'validate',method:'host/workspace/validate',params:{paths:[thread.cwd]}}));
  const validation=await c.next();
  assert.equal(validation.type,'rpc_result');
  assert.equal((validation.result as any).workspaces[0].path,thread.cwd);
  assert.equal(JSON.stringify(validation).includes('private-owner'),false);
  c.socket.send(JSON.stringify({type:'rpc',id:'browse',method:'host/workspace/list',params:{path:'/'}}));
  const browsing=await c.next();assert.equal(browsing.type,'rpc_error');
  assert.match(String(browsing.error),/directory browsing is unavailable/);
  c.socket.send(JSON.stringify({type:'rpc',id:'start',method:'thread/start',idempotencyKey:'workspace-start',params:{cwd:thread.cwd}}));
  assert.equal((await c.next()).type,'rpc_result');
  assert.equal((codex.calls.find(call=>call.method==='thread/start')!.params as any).cwd,'/var/lib/private-owner/workspace');
  const before=codex.calls.length;
  const {c:other}=await negotiated(b.token,true);
  other.socket.send(JSON.stringify({type:'rpc',id:'bad',method:'thread/start',idempotencyKey:'wrong-device',params:{cwd:thread.cwd}}));
  assert.equal((await other.next()).type,'rpc_error');assert.equal(codex.calls.length,before);
  other.socket.send(JSON.stringify({type:'rpc',id:'own-read',method:'thread/read',params:{threadId:'t'}}));
  const otherCwd=((await other.next()).result as any).thread.cwd;
  assert.notEqual(otherCwd,thread.cwd);
  c.socket.send(JSON.stringify({type:'rpc',id:'raw',method:'thread/start',idempotencyKey:'raw-path',params:{cwd:'/var/lib/private-owner/workspace'}}));
  assert.equal((await c.next()).type,'rpc_error');assert.equal(codex.calls.length,before);
  const {c:legacy}=await negotiated(a.token,false);
  legacy.socket.send(JSON.stringify({type:'rpc',id:'legacy-migration',method:'host/workspace/migrate',params:{paths:[directory]}}));
  assert.equal((await legacy.next()).type,'rpc_error');
  legacy.socket.send(JSON.stringify({type:'rpc',id:'legacy',method:'thread/read',params:{threadId:'t'}}));
  assert.equal(((await legacy.next()).result as any).thread.cwd,'/var/lib/private-owner/workspace');
  legacy.socket.close();other.socket.close();
  codex.emit('notification',{method:'thread/updated',params:{threadId:'t',cwd:'/var/lib/private-owner/workspace'}});
  codex.emit('notification',{method:'thread/updated',params:{threadId:'u',cwd:'/var/lib/private-owner/second'}});
  const first=await c.next(),second=await c.next();
  assert.equal((first.params as any).cwd,thread.cwd);assert.equal((first.sequence as number)+1,second.sequence);
  c.socket.close();
  const {c:replay}=await negotiated(a.token,true,{lastSessionId:offer.sessionId,lastSequence:0});
  assert.deepEqual(await replay.next(),first);assert.deepEqual(await replay.next(),second);
  const permissions={fileSystem:{read:['/var/lib/private-owner/workspace'],write:['/var/lib/private-owner/workspace/out'],
    locations:{'/var/lib/private-owner/workspace/report':true}}};
  codex.emit('request',{id:992,method:'item/permissions/requestApproval',params:{permissions}});
  const permissionRequest=await replay.next();
  assert.equal(permissionRequest.type,'codex_request');
  assert.equal(JSON.stringify(permissionRequest).includes('private-owner'),false);
  assert.ok(Object.values((permissionRequest.params as any).permissionsPathNames).includes('report'));
  replay.socket.send(JSON.stringify({type:'server_response',requestId:permissionRequest.requestId,
    result:{permissions,scope:'turn'}}));
  assert.equal((await replay.next()).status,'invalid');
  assert.equal(codex.responses.length,0);
  const answer={type:'server_response',requestId:permissionRequest.requestId,
    result:{permissions:(permissionRequest.params as any).permissions,scope:'turn'}};
  replay.socket.send(JSON.stringify(answer));replay.socket.send(JSON.stringify(answer));
  assert.equal((await replay.next()).status,'answered');
  assert.equal((await replay.next()).status,'answered');
  assert.deepEqual(codex.responses,[{id:992,result:{permissions,scope:'turn'},error:undefined}]);
  codex.emit('request',{id:993,method:'item/permissions/requestApproval',params:{permissions}});
  const revokedRequest=await replay.next();
  const device=await auth.authenticate(a.token);assert.ok(device);
  await auth.revoke(device.id);
  const closed=new Promise<number>(resolve=>replay.socket.once('close',code=>resolve(code)));
  replay.socket.send(JSON.stringify({type:'server_response',requestId:revokedRequest.requestId,
    result:{permissions:(revokedRequest.params as any).permissions,scope:'turn'}}));
  assert.equal(await closed,4001);
  assert.equal(codex.responses.length,1);
  replay.socket.close();
  const otherDevice=await auth.authenticate(b.token);assert.ok(otherDevice);
  const revoked=await fetch(`http://127.0.0.1:${started.port}/v2/device`,{
    method:'DELETE',headers:{authorization:`Bearer ${b.token}`}});
  assert.equal(revoked.status,200);
  assert.equal((await revoked.json() as any).referenceCleanup,true);
  assert.equal(await auth.authenticate(b.token),null);
  const persistedRefs=new RoutingReferences(join(directory,'routing.json'));
  await assert.rejects(persistedRefs.resolve(otherDevice.id,'workspace',otherCwd),/unavailable/);
  await assert.rejects(persistedRefs.issue(otherDevice.id,'workspace',directory),/revoked/);
  await (host as any).recheckConnectedDevices();
  const afterCleanup=new RoutingReferences(join(directory,'routing.json'));
  await assert.rejects(afterCleanup.resolve(device.id,'workspace',thread.cwd),/unavailable/);
  await assert.rejects(afterCleanup.issue(device.id,'workspace',directory),/revoked/);
});
