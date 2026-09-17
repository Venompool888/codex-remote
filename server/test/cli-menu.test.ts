import assert from "node:assert/strict";
import test from "node:test";
import { runMenu } from "../src/cli-menu.js";

const device = (id: string, name = "Test phone") => ({ id, name, scopes: [],
  createdAt: "2026-01-01T00:00:00Z", lastSeenAt: null,
  expiresAt: "2099-01-01T00:00:00Z", rotatedAt: null, revokedAt: null });

function harness(answers: (string | null)[]) {
  const output: string[] = [], revoked: string[] = [];
  let paired = 0;
  let devices = [device("first"), device("second")];
  return {
    output, revoked, get paired() { return paired; },
    actions: {
      ask: async () => { assert.ok(answers.length, "unexpected prompt"); return answers.shift()!; },
      write: (message: string) => { output.push(message); },
      pair: async () => { paired++; },
      list: async () => devices,
      revoke: async (id: string) => { revoked.push(id); devices = devices.filter((d) => d.id !== id); return true; },
    },
  };
}

test("menu never creates a ticket just by opening and viewing devices", async () => {
  const h = harness(["2", "0", "0"]);
  await runMenu(h.actions);
  assert.equal(h.paired, 0);
  assert.deepEqual(h.revoked, []);
});

test("ticket generation is explicit and menu remains available", async () => {
  const h = harness(["1", "0"]);
  await runMenu(h.actions);
  assert.equal(h.paired, 1);
});

test("device deletion requires confirmation and revokes only the chosen ID", async () => {
  const h = harness(["2", "2", "1", "n", "1", "1", "y", "0", "0"]);
  await runMenu(h.actions);
  assert.deepEqual(h.revoked, ["first"]);
});

test("invalid choices and EOF never revoke a device", async () => {
  const h = harness(["2", "999", "1x", "1", "1", null, "0"]);
  await runMenu(h.actions);
  assert.deepEqual(h.revoked, []);
});

test("remote device names cannot inject terminal controls", async () => {
  const h = harness(["2", "0", "0"]);
  h.actions.list = async () => [device("example", "phone\u001b[2J\nspoof\u202e")];
  await runMenu(h.actions);
  assert.equal(h.output.some((line) => /[\u001b\u202e]/.test(line)), false);
});

test("failure does not print potentially private error details", async () => {
  const h = harness(["1", "0"]);
  h.actions.pair = async () => { throw new Error("secret fixture"); };
  await runMenu(h.actions);
  assert.equal(h.output.join("\n").includes("secret fixture"), false);
});
