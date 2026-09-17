import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, writeFile, rename, rm, symlink } from "node:fs/promises";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { cliTokenProvider, socketTokenProvider, tokenHash } from "../src/cli-auth.js";
import { authBroker } from "../src/cli-auth-broker.js";
import { CodexAppServer } from "../src/codex-app-server.js";

const expiry = Math.floor(Date.now() / 1000) + 3600;
const jwt = (tag: string, exp = expiry) =>
  `header.${Buffer.from(JSON.stringify({ exp, tag })).toString("base64url")}.signature`;

async function fixture(t: test.TestContext) {
  const dir = await mkdtemp(join(tmpdir(), "remote-cli-auth-"));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const path = join(dir, "auth.json");
  const save = async (token: string, account = "owner") => {
    await writeFile(path + ".tmp", JSON.stringify({ auth_mode: "chatgpt", tokens: {
      access_token: token, refresh_token: "DO_NOT_FORWARD", account_id: account,
    } }), { mode: 0o600 });
    await rename(path + ".tmp", path);
  };
  return { dir, path, save };
}

test("reads current CLI auth after atomic replacement without leaking refresh tokens", async t => {
  const f = await fixture(t);
  await f.save(jwt("one"));
  const provider = cliTokenProvider(f.path, async () => assert.fail("Unexpected refresh"));
  const first = await provider();
  await f.save(jwt("two"));
  const second = await provider({ previousAccountId: "owner", rejectedTokenHash: tokenHash(first.accessToken) });
  assert.equal(second.accessToken, jwt("two"));
  assert.equal(JSON.stringify(second).includes("DO_NOT_FORWARD"), false);
});

test("expired or rejected tokens refresh the original CLI cache once for concurrent callers", async t => {
  const f = await fixture(t);
  const old = jwt("old", 1);
  await f.save(old);
  let calls = 0;
  const provider = cliTokenProvider(f.path, async () => {
    calls++;
    await new Promise(resolve => setTimeout(resolve, 30));
    await f.save(jwt(`fresh-${calls}`));
  });
  const result = await Promise.all([provider(), provider(), provider()]);
  assert.equal(calls, 1);
  assert.ok(result.every(r => r.accessToken === jwt("fresh-1")));
  await provider({ previousAccountId: "owner", rejectedTokenHash: tokenHash(result[0].accessToken) });
  assert.equal(calls, 2);
});

test("failed refresh and account change fail closed", async t => {
  const f = await fixture(t);
  await f.save(jwt("old"));
  let calls = 0;
  const provider = cliTokenProvider(f.path, async () => { calls++; });
  await assert.rejects(provider({ previousAccountId: "other" }), /account changed/);
  assert.equal(calls, 0);
  await assert.rejects(provider({ rejectedTokenHash: tokenHash(jwt("old")) }), /fresh token/);
  await f.save(jwt("expired", 1));
  await assert.rejects(provider(), /fresh token/);
});

test("socket bridge returns only access credentials and hides upstream error details", async t => {
  const f = await fixture(t);
  await f.save(jwt("current"));
  const socket = join(f.dir, "auth.sock");
  let fail = false;
  const server = authBroker(async refresh => {
    if (fail) throw new Error("secret DO_NOT_FORWARD");
    return cliTokenProvider(f.path, async () => {})(refresh);
  });
  await new Promise<void>(resolve => server.listen(socket, resolve));
  t.after(() => new Promise<void>(resolve => server.close(() => resolve())));
  const get = socketTokenProvider(socket);
  assert.equal((await get()).accessToken, jwt("current"));
  fail = true;
  await assert.rejects(get(), error => error instanceof Error && !error.message.includes("DO_NOT_FORWARD"));
});

test("broker starts through a deployment symlink", async t => {
  const f = await fixture(t);
  await f.save(jwt("current"));
  const entry = join(f.dir, "current.ts");
  await symlink(fileURLToPath(new URL("../src/cli-auth-broker.ts", import.meta.url)), entry);
  const socket = join(f.dir, "auth.sock");
  const child = spawn(process.execPath, ["--import", "tsx", entry], {
    env: { ...process.env, CODEX_HOME: f.dir, CODEX_REMOTE_AUTH_SOCKET: socket, CODEX_REMOTE_AUTH_CODEX_BIN: "/not-used" },
    stdio: ["ignore", "pipe", "pipe"],
  });
  t.after(() => { child.kill(); });
  await new Promise<void>((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error("Broker startup timed out")), 5000);
    child.once("exit", () => { clearTimeout(timeout); reject(new Error("Broker exited before listening")); });
    child.stdout.once("data", () => { clearTimeout(timeout); resolve(); });
  });
  assert.equal((await socketTokenProvider(socket)()).accessToken, jwt("current"));
});

test("app-server consumes refresh requests locally instead of forwarding them to phones", async t => {
  const f = await fixture(t);
  const fake = join(f.dir, "fake.mjs");
  await writeFile(fake, `import {createInterface} from 'node:readline';
const send=x=>console.log(JSON.stringify(x));
createInterface({input:process.stdin}).on('line',l=>{
 const m=JSON.parse(l);
 if(m.method==='initialize') send({id:m.id,result:{}});
 if(m.method==='account/login/start') {
  send({id:m.id,result:{type:'chatgptAuthTokens'}});
  send({id:'refresh-proof',method:'account/chatgptAuthTokens/refresh',params:{previousAccountId:'owner'}});
 }
 if(m.id==='refresh-proof') send({method:'refresh-proof-result',params:m.result??m.error});
});`);
  // Node accepts the script before the app-server arguments via this tiny wrapper.
  const wrapper = join(f.dir, "codex");
  await writeFile(wrapper, `#!/bin/sh\nexec '${process.execPath}' '${fake}'\n`, { mode: 0o700 });
  let calls = 0;
  const app = new CodexAppServer(wrapper, [], false, async refresh => {
    calls++;
    if (refresh) assert.equal(refresh.previousAccountId, "owner");
    return { accessToken: jwt(String(calls)), chatgptAccountId: "owner" };
  });
  t.after(() => app.stop());
  let forwarded = false;
  app.on("request", () => { forwarded = true; });
  const result = new Promise<any>(resolve => app.on("notification", m => {
    if (m.method === "refresh-proof-result") resolve(m.params);
  }));
  await app.start();
  assert.equal((await result).accessToken, jwt("2"));
  assert.equal(forwarded, false);
  assert.equal(calls, 2);
});
