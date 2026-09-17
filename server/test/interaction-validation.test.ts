import assert from "node:assert/strict";
import test from "node:test";
import { validateInteractionReply, validateForm } from "../src/interaction-validation.js";

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
  assert.throws(() => validateInteractionReply(method, { availableDecisions: ["acceptForSession"] }, { decision: "acceptForSession" }), /Unsupported/);
});
