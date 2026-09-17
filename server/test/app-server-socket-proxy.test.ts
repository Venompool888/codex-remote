import { strict as assert } from "node:assert";
import { spawn } from "node:child_process";
import { createServer } from "node:http";
import { mkdtemp, rm } from "node:fs/promises";
import { join } from "node:path";
import { createInterface } from "node:readline";
import { once } from "node:events";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { WebSocketServer } from "ws";

const source = fileURLToPath(new URL("../src/app-server-socket-proxy.ts", import.meta.url));
test("shared daemon adapter exchanges RPC and notifications without owning daemon lifetime", { timeout: 15_000 }, async () => {
  const dir = await mkdtemp("/tmp/codex-sock-");
  const path = join(dir, "control.sock");
  const http = createServer();
  const ws = new WebSocketServer({ server: http });
  http.listen(path);
  await once(http, "listening");
  const child = spawn(process.execPath, ["--import", "tsx", source], {
    env: { ...process.env, CODEX_REMOTE_APP_SERVER_SOCKET: path },
    stdio: ["pipe", "pipe", "pipe"],
  });
  const exited = once(child, "exit");
  const lines = createInterface({ input: child.stdout });
  try {
    const [connection] = await once(ws, "connection");
    const request = once(connection, "message");
    child.stdin.write('{"id":1,"method":"thread/list","params":{}}\n');
    assert.equal(JSON.parse(String((await request)[0])).method, "thread/list");
    const response = once(lines, "line");
    connection.send('{"id":1,"result":{"data":[]}}');
    assert.deepEqual(JSON.parse((await response)[0]), { id: 1, result: { data: [] } });
    const notification = once(lines, "line");
    connection.send('{"method":"thread/status/changed","params":{"threadId":"existing"}}');
    assert.equal(JSON.parse((await notification)[0]).params.threadId, "existing");
    child.kill("SIGTERM");
    assert.equal((await exited)[0], 0);
    assert.equal(http.listening, true);
  } finally {
    child.kill("SIGKILL");
    lines.close();
    for (const client of ws.clients) client.terminate();
    ws.close();
    await new Promise<void>(resolve => http.close(() => resolve()));
    await rm(dir, { recursive: true, force: true });
  }
});

test("missing shared daemon fails closed without starting a replacement", { timeout: 15_000 }, async () => {
  const child = spawn(process.execPath, ["--import", "tsx", source], {
    env: { ...process.env, CODEX_REMOTE_APP_SERVER_SOCKET: `/tmp/missing-codex-${process.pid}.sock` },
    stdio: ["pipe", "pipe", "pipe"],
  });
  child.stderr.resume();
  assert.equal((await once(child, "exit"))[0], 1);
});
