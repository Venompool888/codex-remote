import assert from "node:assert/strict";
import { mkdtemp } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { AdminControlServer, requestPairingTicket } from "../src/admin-control.js";

test("admin control requests a fresh pairing ticket from the running host", async (t) => {
  const directory = await mkdtemp(join(tmpdir(), "codex-remote-admin-"));
  const socketPath = join(directory, "control.sock");
  let issued = 0;
  const server = new AdminControlServer({
    socketPath,
    publicUrl: "https://remote.example.test",
    createPairingTicket: () => ({ code: `ticket-${++issued}`, expiresAt: 1_800_000 + issued }),
  });
  await server.start();
  t.after(() => server.stop());

  assert.deepEqual(await requestPairingTicket(socketPath), {
    code: "ticket-1",
    expiresAt: 1_800_001,
    url: "https://remote.example.test",
  });
  assert.equal((await requestPairingTicket(socketPath)).code, "ticket-2");
});

test("a second admin control server cannot replace an active socket", async (t) => {
  const directory = await mkdtemp(join(tmpdir(), "codex-remote-admin-active-"));
  const socketPath = join(directory, "control.sock");
  const first = new AdminControlServer({ socketPath, publicUrl: "https://one.test", createPairingTicket: () => ({ code: "one", expiresAt: 1 }) });
  const second = new AdminControlServer({ socketPath, publicUrl: "https://two.test", createPairingTicket: () => ({ code: "two", expiresAt: 2 }) });
  await first.start();
  t.after(() => first.stop());
  await assert.rejects(() => second.start(), /already active/);
  assert.equal((await requestPairingTicket(socketPath)).code, "one");
});
