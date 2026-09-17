import assert from "node:assert/strict";
import test from "node:test";
import { mkdtemp, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { CapabilityCatalog } from "../src/capability-catalog.js";
import { RoutingReferences } from '../src/routing-references.js';
import { WorkspaceRouting } from '../src/workspace-routing.js';

test("catalog reads real paths, uses scoped IDs, revalidates disabled/deleted skills without exposing paths", async () => {
  const dir = await mkdtemp(join(tmpdir(), "catalog-"));
  const path = join(dir, "SKILL.md");
  await writeFile(path, "test");
  let enabled = true;
  const catalog = new CapabilityCatalog({ async call(method) {
    if (method === "skills/list") return { data: [{ skills: [{ name: "demo", path, enabled, description: "test" }] }] };
    return { marketplaces: [] };
  } });
  try {
    const first = await catalog.list(dir);
    assert.equal(first.entries.length, 1);
    assert.equal(JSON.stringify(first).includes(dir), false);
    const selected = [{ type: "remoteCapability", capabilityId: first.entries[0].id }];
    assert.deepEqual(await catalog.resolve(dir, selected), [{ type: "skill", name: "demo", path }]);
    const wire = new WorkspaceRouting(new RoutingReferences(join(dir, 'routing.json')), 'device');
    const opaqueSkill = await wire.outbound([{ type: 'skill', name: 'demo', path }]) as unknown[];
    assert.equal(JSON.stringify(opaqueSkill).includes(path), false);
    assert.deepEqual(await catalog.resolve(dir, await wire.skillInputs(opaqueSkill)), [{type:'skill',name:'demo',path}]);
    const other = await catalog.list("/different-workspace");
    assert.notEqual(other.entries[0].id, first.entries[0].id);
    await assert.rejects(catalog.resolve("/different-workspace", selected), /no longer exists/);
    enabled = false;
    await assert.rejects(catalog.resolve(dir, selected), /disabled/);
    await assert.rejects(catalog.resolve(dir, await wire.skillInputs(opaqueSkill)), /disabled/);
    enabled = true;
    await rm(path);
    await assert.rejects(catalog.resolve(dir, selected), /unavailable/);
    await assert.rejects(catalog.resolve(dir, await wire.skillInputs(opaqueSkill)), /unavailable/);
  } finally { await rm(dir, { recursive: true, force: true }); }
});

test("plugin structured inputs and dependency states follow fresh host status", async () => {
  const dir = await mkdtemp(join(tmpdir(), "plugin-status-"));
  let runtime: unknown[] = [{ name: "service", authStatus: "notLoggedIn", tools: {} }];
  const catalog = new CapabilityCatalog({ async call(method) {
    if (method === "skills/list") return { data: [] };
    if (method === "plugin/list") return { marketplaces: [{ path: dir, plugins: [{ id: "real", name: "demo", installed: true, enabled: true, source: { type: "local", path: dir } }] }] };
    if (method === "plugin/read") return { plugin: { mcpServers: ["service"] } };
    return { data: runtime };
  } });
  try {
    const first = await catalog.list(dir);
    assert.equal(first.entries[0].state, "authorization_required");
    const input = [{ type: "remoteCapability", capabilityId: first.entries[0].id }];
    await assert.rejects(catalog.resolve(dir, input), /authorization_required/);
    runtime = [];
    assert.equal((await catalog.list(dir, true)).entries[0].state, "missing_dependency");
    runtime = [{ name: "service", authStatus: "unsupported", tools: { echo: {} } }];
    assert.deepEqual(await catalog.resolve(dir, input), [{ type: "mention", name: "demo", path: "plugin://real" }]);
  } finally { await rm(dir, { recursive: true, force: true }); }
});


test("remote plugins use protocol mention IDs without a local package path and revalidate availability", async () => {
  let enabled = true;
  let installed = true;
  let availability = "AVAILABLE";
  const catalog = new CapabilityCatalog({ async call(method, params) {
    if (method === "skills/list") return { data: [] };
    if (method === "plugin/list") return { marketplaces: [{ name: "remote-market", path: null,
      plugins: [{ id: "demo@remote-market", name: "demo", installed, enabled, availability, source: { type: "remote" } }] }] };
    if (method === "plugin/read") {
      assert.deepEqual(params, { marketplacePath: null, remoteMarketplaceName: "remote-market", pluginName: "demo" });
      return { plugin: { mcpServers: [] } };
    }
    return { data: [] };
  } });
  const first = await catalog.list("/workspace");
  assert.equal(first.entries[0].state, "enabled");
  assert.equal(first.entries[0].source, "Connected remote plugin");
  const input = [{ type: "remoteCapability", capabilityId: first.entries[0].id }];
  assert.deepEqual(await catalog.resolve("/workspace", input), [{ type: "mention", name: "demo", path: "plugin://demo@remote-market" }]);
  enabled = false;
  await assert.rejects(catalog.resolve("/workspace", input), /disabled/);
  enabled = true;
  availability = "DISABLED_BY_ADMIN";
  await assert.rejects(catalog.resolve("/workspace", input), /disabled/);
  availability = "AVAILABLE";
  installed = false;
  await assert.rejects(catalog.resolve("/workspace", input), /no longer exists/);
});

test("plugin detail discovery overlaps bounded requests and preserves catalog order", async () => {
  let active = 0;
  let peak = 0;
  const releases: (() => void)[] = [];
  const names = Array.from({ length: 9 }, (_, i) => `plugin-${i}`);
  const catalog = new CapabilityCatalog({ async call(method) {
    if (method === "skills/list") return { data: [] };
    if (method === "plugin/list") return { marketplaces: [{ name: "remote", plugins: names.map(name =>
      ({ id: name, name, installed: true, enabled: true, source: { type: "remote" } })) }] };
    if (method === "plugin/read") {
      peak = Math.max(peak, ++active);
      await new Promise<void>(resolve => releases.push(resolve));
      active--;
      return { plugin: { mcpServers: [] } };
    }
    return { data: [] };
  } });
  const pending = catalog.list("/workspace");
  for (const expected of [8, 1]) {
    await new Promise<void>(resolve => setImmediate(resolve));
    assert.equal(releases.length, expected);
    releases.splice(0).reverse().forEach(release => release());
  }
  const result = await pending;
  assert.equal(peak, 8);
  assert.deepEqual(result.entries.map(e => e.name), names);
  assert.ok(result.entries.every(e => e.state === "enabled"));
});

for (const invalidate of [true, false]) test(`late catalog responses cannot revive disabled capabilities (${invalidate ? "change event" : "newer refresh"})`, async () => {
  let enabled = true;
  let release!: () => void;
  let started!: () => void;
  const reachedDetail = new Promise<void>(resolve => { started = resolve; });
  const catalog = new CapabilityCatalog({ async call(method) {
    if (method === "skills/list") return { data: [] };
    if (method === "plugin/list") return { marketplaces: [{ name: "remote", plugins: [
      { id: "demo@remote", name: "demo", installed: true, enabled, source: { type: "remote" } },
    ] }] };
    if (method === "plugin/read") {
      started();
      await new Promise<void>(resolve => { release = resolve; });
      return { plugin: { mcpServers: [] } };
    }
    return { data: [] };
  } });
  const stale = catalog.list("/workspace", true);
  const rejected = assert.rejects(stale, /Capabilities changed while loading/);
  await reachedDetail;
  enabled = false;
  if (invalidate) catalog.invalidate();
  const current = await catalog.list("/workspace", true);
  assert.equal(current.entries[0].state, "disabled");
  release();
  await rejected;
  assert.deepEqual(await catalog.list("/workspace"), current);
  await assert.rejects(catalog.resolve("/workspace", [{ type: "remoteCapability", capabilityId: current.entries[0].id }]), /disabled/);
});

for (const [failure, expected] of [
  ["Plugin not found (-32600)", "unavailable"],
  ["Codex request timed out: plugin/read", "unavailable"],
  ["Unauthorized (401)", "authorization_required"],
  ["Method not found (-32601)", "enabled"],
]) test(`plugin detail failure is classified without treating every error as legacy: ${expected} ${failure}`, async () => {
  const privateFailure = `/private/host/secret token=do-not-expose ${failure}`;
  const catalog = new CapabilityCatalog({ async call(method) {
    if (method === "skills/list") return { data: [] };
    if (method === "plugin/list") return { marketplaces: [{ name: "market", plugins: [
      { id: "demo@market", name: "demo", installed: true, enabled: true, source: { type: "remote" } },
    ] }] };
    if (method === "plugin/read") throw new Error(privateFailure);
    return { data: [] };
  } });
  const result = await catalog.list("/workspace");
  assert.equal(result.entries[0].state, expected);
  assert.equal(JSON.stringify(result).includes("do-not-expose"), false);
  assert.equal(JSON.stringify(result).includes("/private"), false);
  const input = [{ type: "remoteCapability", capabilityId: result.entries[0].id }];
  if (expected === "enabled") {
    assert.match(result.entries[0].detail, /older host/);
    assert.deepEqual(await catalog.resolve("/workspace", input), [{type: "mention", name: "demo", path: "plugin://demo@market"}]);
  } else await assert.rejects(catalog.resolve("/workspace", input), new RegExp(String(expected)));
});
