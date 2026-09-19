import assert from "node:assert/strict";
import test from "node:test";
import { ClientToolRegistry, validateInteractionReply, validateForm } from "../src/interaction-validation.js";

test("multi-question replies require every known answer and no additional IDs", () => {
  const params = { questions: [{ id: "a" }, { id: "b" }] };
  const result = { answers: { a: { answers: ["Choice"] }, b: { answers: ["Free text"] } } };
  assert.doesNotThrow(() => validateInteractionReply("item/tool/requestUserInput", params, result));
  assert.throws(() => validateInteractionReply("item/tool/requestUserInput", params, { answers: { a: { answers: ["x"] } } }), /every question/);
  assert.throws(() => validateInteractionReply("item/tool/requestUserInput", params, { answers: { ...result.answers, extra: {} } }), /Unknown/);
});

test("elicitation validates required, typed, bounded values and rejects nested unsupported input", () => {
  const schema = { type: "object", required: ["count"], properties: { count: { type: "integer", minimum: 1, maximum: 5 },
    choice: { type: "string", enum: ["one", "two"] }, enabled: { type: "boolean" } } };
  assert.doesNotThrow(() => validateForm(schema, { count: 2, choice: "one", enabled: false }));
  for (const value of [{}, { count: 2.5 }, { count: 6 }, { count: 2, choice: "unknown" }, { count: 2, secret: "x" }])
    assert.throws(() => validateForm(schema, value));
});

test("permissions cannot broaden the host's request or scope", () => {
  const permissions = { network: { enabled: true } };
  assert.doesNotThrow(() => validateInteractionReply("item/permissions/requestApproval", { permissions }, { permissions, scope: "turn" }));
  assert.throws(() => validateInteractionReply("item/permissions/requestApproval", { permissions }, { permissions, scope: "session" }));
  assert.throws(() => validateInteractionReply("item/permissions/requestApproval", { permissions }, { permissions: { filesystem: "all" }, scope: "turn" }));
});

test("elicitation validates runtime-supported string formats and fails closed on unknown constraints", () => {
  for (const [format, good, bad] of [["email", "pixel@example.com", "invalid"], ["uri", "https://example.com/proof", "relative"], ["date", "2026-09-06", "2026-02-30"], ["date-time", "2026-09-06T05:00:00+10:00", "yesterday"]]) {
    const schema = {type:"object", properties:{value:{type:"string",format}}};
    assert.doesNotThrow(() => validateForm(schema, {value:good}));
    assert.throws(() => validateForm(schema, {value:bad}), /Invalid/);
  }
  assert.throws(() => validateForm({type:"object",properties:{value:{type:"string",format:"custom"}}}, {value:"x"}), /Unsupported/);
});


test("approval replies respect explicit host choices without inventing legacy defaults", () => {
  const method = "item/commandExecution/requestApproval";
  for (const availableDecisions of [undefined, null]) {
    assert.doesNotThrow(() => validateInteractionReply(method, { availableDecisions }, { decision: "accept" }));
  }
  for (const availableDecisions of [[], ["decline"], ["acceptForSession", "cancel"], "accept", [{ accept: true }]]) {
    assert.throws(() => validateInteractionReply(method, { availableDecisions }, { decision: "accept" }), /not offered/);
  }
  assert.doesNotThrow(() => validateInteractionReply(method, { availableDecisions: ["cancel"] }, { decision: "cancel" }));
  assert.doesNotThrow(() => validateInteractionReply(method, { availableDecisions: ["acceptForSession"] }, { decision: "acceptForSession" }));
});

test("openai forms validate bounded nested objects and safe text patterns", () => {
  const schema = { type: "object", required: ["profile"], properties: { profile: { type: "object", required: ["code"],
    properties: { code: { type: "string", pattern: "^[A-Z]{2}[0-9]{4}$" }, enabled: { type: "boolean" } } } } };
  assert.doesNotThrow(() => validateForm(schema, { profile: { code: "AU2026", enabled: false } }));
  assert.throws(() => validateForm(schema, { profile: { code: "bad" } }), /pattern/);
  assert.throws(() => validateForm({ type: "object", properties: { value: { type: "string", pattern: "^(a+)+$" } } }, { value: "aaa" }), /Unsupported/);
  assert.throws(() => validateForm(schema, { profile: { code: "AU2026", extra: true } }), /Unknown/);
});

test("standard MCP forms reject openai-only nested and pattern schemas", () => {
  for (const schema of [
    { type: "object", properties: { nested: { type: "object", properties: {} } } },
    { type: "object", properties: { text: { type: "string", pattern: "^[A-Z]{2}$" } } },
  ]) assert.throws(() => validateInteractionReply("mcpServer/elicitation/request",
    { mode: "form", requestedSchema: schema }, { action: "accept", content: schema.properties.nested ? { nested: {} } : { text: "AU" } }));
});

test("approval amendments must be exact host offers and exact proposals", () => {
  const method = "item/commandExecution/requestApproval";
  const exec = { acceptWithExecpolicyAmendment: { execpolicy_amendment: ["git", "status"] } };
  const network = { applyNetworkPolicyAmendment: { network_policy_amendment: { host: "example.test", action: "allow" } } };
  const params = { availableDecisions: [exec, network], proposedExecpolicyAmendment: ["git", "status"],
    proposedNetworkPolicyAmendments: [{ host: "example.test", action: "allow" }] };
  assert.doesNotThrow(() => validateInteractionReply(method, params, { decision: exec }));
  assert.doesNotThrow(() => validateInteractionReply(method, params, { decision: network }));
  assert.throws(() => validateInteractionReply(method, params, { decision: {
    acceptWithExecpolicyAmendment: { execpolicy_amendment: ["rm", "-rf"] } } }), /not offered/);
  assert.throws(() => validateInteractionReply(method,
    { ...params, proposedExecpolicyAmendment: ["git"] }, { decision: exec }), /proposal/);
});

test("permission replies may narrow requested categories and arrays but never expand them", () => {
  const requested = { network: { enabled: true }, fileSystem: { read: ["/repo", "/tmp"], write: ["/repo/out"] } };
  assert.doesNotThrow(() => validateInteractionReply("item/permissions/requestApproval", { permissions: requested },
    { scope: "turn", permissions: { fileSystem: { read: ["/repo"] } } }));
  assert.doesNotThrow(() => validateInteractionReply("item/permissions/requestApproval", { permissions: requested },
    { scope: "turn", permissions: {} }));
  assert.throws(() => validateInteractionReply("item/permissions/requestApproval", { permissions: requested },
    { scope: "turn", permissions: { fileSystem: { read: ["/etc"] } } }), /exceeds/);
  assert.throws(() => validateInteractionReply("item/permissions/requestApproval", { permissions: requested },
    { scope: "turn", permissions: { network: { enabled: false } } }), /exceeds/);
});

test("client tool registry dispatches only exact registered identities and validates output", async () => {
  const registry = new ClientToolRegistry();
  registry.register("device", "read_status", (args: any) => ({ success: true,
    contentItems: [{ type: "inputText", text: args.name }] }));
  assert.deepEqual(await registry.dispatch({ namespace: "device", tool: "read_status", arguments: { name: "ready" } }),
    { success: true, contentItems: [{ type: "inputText", text: "ready" }] });
  await assert.rejects(registry.dispatch({ namespace: "device", tool: "shell", arguments: {} }), /Unsupported/);
  registry.register(null, "bad", () => ({ success: true, contentItems: [], command: "run" }));
  await assert.rejects(registry.dispatch({ namespace: null, tool: "bad", arguments: {} }), /Invalid/);
});
