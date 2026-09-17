import assert from "node:assert/strict";
import test from "node:test";
import { mkdtemp, readFile, rm, stat, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { pairingPublicUrl, readCliConfig, validatePublicUrl, writeCliConfig } from "../src/cli-config.js";
import { createPairingUri } from "../src/cli.js";
import { AdminControlServer } from "../src/admin-control.js";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { fileURLToPath } from "node:url";

test("config URL takes precedence and preserves tunnel port and root path in QR", () => {
  const url = pairingPublicUrl({ publicUrl: "https://host.example:8443/remote/nested/" }, "http://localhost:8787", "http://127.0.0.1:8787");
  assert.equal(new URL(createPairingUri(url, "synthetic-code")).searchParams.get("server"), "https://host.example:8443/remote/nested");
  assert.equal(pairingPublicUrl({}, "https://host.example/prefix", "http://localhost:8787"), "https://host.example/prefix");
  assert.equal(pairingPublicUrl({}, undefined, "http://localhost:8787"), "http://localhost:8787");
});

test("invalid public URLs do not silently fall back to local addresses", () => {
  for (const url of ["host.example", "file:///tmp/file", "https://user:password@host.example", "https://host.example/?code=secret", "https://host.example/#fragment", "https://host.example:99999", "https://host.example/a\\b"]) {
    assert.throws(() => validatePublicUrl(url));
    assert.throws(() => pairingPublicUrl({ publicUrl: url }, undefined, "http://localhost:8787"));
  }
});

test("config persists privately, can be hand edited and reset, and rejects malformed content", async () => {
  const directory = await mkdtemp(join(tmpdir(), "cli-config-"));
  const path = join(directory, "cli-config.json");
  try {
    assert.deepEqual(await readCliConfig(path), {});
    await writeCliConfig(path, { publicUrl: "https://host.example/remote" });
    assert.equal((await stat(path)).mode & 0o777, 0o600);
    assert.equal((await readCliConfig(path)).publicUrl, "https://host.example/remote");
    assert.equal((await readFile(path, "utf8")).includes("publicUrl"), true);
    await writeFile(path, '{"publicUrl":"https://other.example/prefix"}');
    assert.equal((await readCliConfig(path)).publicUrl, "https://other.example/prefix");
    await writeFile(path, '{"publicUrl":42}');
    await assert.rejects(readCliConfig(path));
    await writeCliConfig(path, {});
    assert.deepEqual(await readCliConfig(path), {});
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test("real CLI applies config to pairing output before exposing the URI", async () => {
  const directory = await mkdtemp(join(tmpdir(), "cli-qr-"));
  const socketPath = join(directory, "control.sock");
  const config = join(directory, "cli-config.json");
  let tickets = 0;
  const server = new AdminControlServer({ socketPath, publicUrl: "http://127.0.0.1:8787",
    createPairingTicket: () => { tickets++; return { code: "synthetic-fixture-code", expiresAt: Date.now() + 300000 }; } });
  await server.start();
  try {
    await writeCliConfig(config, { publicUrl: "https://host.example:8443/remote" });
    const args = ["--import", "tsx", fileURLToPath(new URL("../src/cli.ts", import.meta.url)), "pair", "--json"];
    const options = { env: { ...process.env, CODEX_REMOTE_CLI_CONFIG: config, CODEX_REMOTE_CONTROL_SOCKET: socketPath } };
    const { stdout } = await promisify(execFile)(process.execPath, args, options);
    const result = JSON.parse(stdout);
    assert.equal(result.url, "https://host.example:8443/remote");
    assert.equal(new URL(result.pairingUri).searchParams.get("server"), result.url);
    assert.equal(tickets, 1);
    await writeFile(config, "invalid JSON");
    await assert.rejects(promisify(execFile)(process.execPath, args, options));
    assert.equal(tickets, 1, "invalid config must not replace a pairing ticket");
  } finally { await server.stop(); await rm(directory, { recursive: true, force: true }); }
});
