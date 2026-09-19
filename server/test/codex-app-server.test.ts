import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { CodexAppServer } from "../src/codex-app-server.js";

async function eventually(check: () => boolean, timeoutMs = 2_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (!check()) {
    if (Date.now() >= deadline) assert.fail("Condition was not met before timeout");
    await new Promise(resolve => setTimeout(resolve, 10));
  }
}

test("failed proxy initialization kills the live child and permits a concurrent retry", async t => {
  const directory = await mkdtemp(join(tmpdir(), "codex-app-server-"));
  const attemptsPath = join(directory, "attempts");
  const executable = join(directory, "fake-proxy.mjs");
  await writeFile(executable, `#!/usr/bin/env node
import {appendFileSync, existsSync, readFileSync} from 'node:fs';
import {createInterface} from 'node:readline';
const attemptsPath = ${JSON.stringify(attemptsPath)};
const attempt = existsSync(attemptsPath) ? readFileSync(attemptsPath, 'utf8').trim().split('\\n').length + 1 : 1;
appendFileSync(attemptsPath, String(process.pid) + '\\n');
const send = value => process.stdout.write(JSON.stringify(value) + '\\n');
createInterface({input: process.stdin}).on('line', line => {
  const message = JSON.parse(line);
  if (message.method === 'initialize') {
    if (attempt === 1) setTimeout(() => send({id: message.id, error: {code: -32000, message: 'initialize rejected'}}), 25);
    else send({id: message.id, result: {}});
  }
});
`, { mode: 0o700 });

  const app = new CodexAppServer(executable, [], true);
  t.after(async () => {
    await app.stop();
    await rm(directory, { recursive: true, force: true });
  });

  const firstStart = app.start();
  const abandonedCall = assert.rejects(app.call("test/hangs", {}, 5_000), /initialize rejected/);
  await assert.rejects(firstStart, /initialize rejected/);
  await abandonedCall;
  const firstPid = Number((await readFile(attemptsPath, "utf8")).trim());
  await eventually(() => {
    try {
      process.kill(firstPid, 0);
      return false;
    } catch {
      return true;
    }
  });

  await Promise.all([app.start(), app.start()]);
  const attempts = (await readFile(attemptsPath, "utf8")).trim().split("\n");
  assert.equal(attempts.length, 2);
});
