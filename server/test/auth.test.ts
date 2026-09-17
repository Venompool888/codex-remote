import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { DeviceAuth } from "../src/auth.js";

test("concurrent authentication saves do not share a temporary file", async () => {
  const directory = await mkdtemp(join(tmpdir(), "codex-auth-concurrent-"));
  try {
    const file = join(directory, "devices.json");
    const auth = new DeviceAuth(file);
    await auth.load();
    const ticket = auth.createPairingTicket();
    const paired = await auth.pair(ticket.code, "Pixel");

    const results = await Promise.all(Array.from({ length: 12 }, () => auth.authenticate(paired.token)));

    assert.ok(results.every(Boolean));
    const reloaded = new DeviceAuth(file);
    await reloaded.load();
    assert.ok(await reloaded.authenticate(paired.token));
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("pairing is one-time and persisted tokens authenticate", async () => {
  const directory = await mkdtemp(join(tmpdir(), "codex-remote-auth-"));
  try {
    const file = join(directory, "devices.json");
    const auth = new DeviceAuth(file);
    await auth.load();
    const ticket = auth.createPairingTicket(60_000);
    const paired = await auth.pair(ticket.code, "Pixel");
    assert.equal(paired.device.name, "Pixel");
    assert.ok(await auth.authenticate(paired.token));
    await assert.rejects(() => auth.pair(ticket.code, "Attacker"));

    const persisted = await readFile(file, "utf8");
    assert.equal(persisted.includes(paired.token), false);
    assert.equal(new DeviceAuth(file).list().length, 0);

    const reloaded = new DeviceAuth(file);
    await reloaded.load();
    assert.ok(await reloaded.authenticate(paired.token));
    assert.equal(await reloaded.revoke(paired.device.id), true);
    assert.equal(await reloaded.authenticate(paired.token), null);
    assert.ok(reloaded.list()[0]?.revokedAt);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("rotating a credential invalidates the old token and renews its expiry", async () => {
  const directory = await mkdtemp(join(tmpdir(), "codex-remote-rotate-"));
  let now = Date.UTC(2026, 0, 1);
  try {
    const auth = new DeviceAuth(join(directory, "devices.json"), 10_000, () => now);
    await auth.load();
    const ticket = auth.createPairingTicket();
    const paired = await auth.pair(ticket.code, "Pixel");
    const originalExpiry = paired.device.expiresAt;
    now += 2_000;
    const rotated = await auth.rotate(paired.token);
    assert.ok(rotated);
    assert.notEqual(rotated.token, paired.token);
    assert.equal(await auth.authenticate(paired.token), null);
    assert.ok(await auth.authenticate(rotated.token));
    assert.ok(Date.parse(rotated.device.expiresAt) > Date.parse(originalExpiry));
    assert.ok(rotated.device.scopes.includes("device:rotate"));
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("expired credentials fail authentication with a deterministic clock", async () => {
  const directory = await mkdtemp(join(tmpdir(), "codex-remote-expiry-"));
  let now = Date.UTC(2026, 0, 1);
  try {
    const auth = new DeviceAuth(join(directory, "devices.json"), 1_000, () => now);
    await auth.load();
    const ticket = auth.createPairingTicket();
    const paired = await auth.pair(ticket.code, "Pixel");
    now += 1_001;
    assert.equal(await auth.authenticate(paired.token), null);
    assert.equal(await auth.isActive(paired.device.id), false);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("loads and migrates v1 device files without storing plaintext tokens", async () => {
  const directory = await mkdtemp(join(tmpdir(), "codex-remote-migration-"));
  try {
    const file = join(directory, "devices.json");
    const token = "legacy-secret";
    const tokenHash = (await import("node:crypto")).createHash("sha256").update(token).digest("hex");
    await writeFile(file, JSON.stringify({
      version: 1,
      devices: [{
        id: "legacy-device",
        name: "Legacy Pixel",
        tokenHash,
        createdAt: "2026-01-01T00:00:00.000Z",
        lastSeenAt: null,
      }],
    }));
    const auth = new DeviceAuth(file, 365 * 24 * 60 * 60_000, () => Date.UTC(2026, 1, 1));
    await auth.load();
    const authenticated = await auth.authenticate(token);
    assert.ok(authenticated);
    assert.deepEqual(authenticated.scopes, [
      "rpc:read", "rpc:write", "attachments:read", "attachments:write",
      "approvals:respond", "device:rotate", "device:revoke",
    ]);
    const migrated = JSON.parse(await readFile(file, "utf8")) as { version: number; devices: Array<Record<string, unknown>> };
    assert.equal(migrated.version, 2);
    assert.equal(typeof migrated.devices[0]?.expiresAt, "string");
    assert.equal((await readFile(file, "utf8")).includes(token), false);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("device scopes can be narrowed and persist across reloads", async () => {
  const directory = await mkdtemp(join(tmpdir(), "codex-remote-scopes-"));
  try {
    const file = join(directory, "devices.json");
    const auth = new DeviceAuth(file);
    await auth.load();
    const ticket = auth.createPairingTicket();
    const paired = await auth.pair(ticket.code, "Read-only Pixel");
    assert.equal(await auth.setScopes(paired.device.id, ["rpc:read", "attachments:read"]), true);
    await assert.rejects(() => auth.setScopes(paired.device.id, ["rpc:read", "unknown"]));
    const reloaded = new DeviceAuth(file);
    await reloaded.load();
    assert.deepEqual((await reloaded.authenticate(paired.token))?.scopes, ["rpc:read", "attachments:read"]);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("background reload cannot erase a pairing while its state file is being written", async () => {
  const directory = await mkdtemp(join(tmpdir(), "codex-auth-reload-"));
  try {
    const auth = new DeviceAuth(join(directory, "nested", "devices.json"));
    const ticket = auth.createPairingTicket();
    const [paired] = await Promise.all([auth.pair(ticket.code, "Pixel"), ...Array.from({ length: 12 }, () => auth.isActive("not-a-device"))]);
    assert.ok(await auth.authenticate(paired.token));
    await auth.load();
    assert.equal(auth.list().length, 1);
  } finally { await rm(directory, { recursive: true, force: true }); }
});
