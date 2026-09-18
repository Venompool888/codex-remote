import assert from "node:assert/strict";
import test from "node:test";
import { CUSTOM_CONFIG_PERMISSION, resolvePermissionSelection } from "../src/permission-selection.js";

test("new task inherits host config without retaining overrides", async () => {
  const calls: string[] = [];
  const params = await resolvePermissionSelection("thread/start", {cwd: "/test", permissions: CUSTOM_CONFIG_PERMISSION,
    approvalPolicy: "never", approvalsReviewer: "auto_review", sandbox: "danger-full-access"}, {
    async call(method) { calls.push(method); return {}; },
  });
  assert.deepEqual(params, {cwd: "/test"});
  assert.deepEqual(calls, []);
});

test("existing turn uses Codex's named default profile", async () => {
  const calls: string[] = [];
  const params = await resolvePermissionSelection("turn/start", {threadId: "t", permissions: CUSTOM_CONFIG_PERMISSION}, {
    async call(method) {
      calls.push(method);
      if (method === "thread/read") return {thread: {cwd: "/test"}};
      if (method === "thread/start") return {thread: {id: "probe"}, activePermissionProfile: {id: "team-policy"},
        approvalPolicy: "on-request", approvalsReviewer: "user"};
      if (method === "thread/unsubscribe") return {};
      throw new Error("unexpected call");
    },
  });
  assert.deepEqual(calls, ["thread/read", "thread/start", "thread/unsubscribe"]);
  assert.equal(params.permissions, "team-policy");
  assert.equal(params.sandboxPolicy, undefined);
  assert.equal(params.approvalPolicy, "on-request");
  assert.equal(params.approvalsReviewer, "user");
});

test("legacy config restores effective sandbox, approvals and network access", async () => {
  const calls: string[] = [];
  const params = await resolvePermissionSelection("turn/start", {threadId: "t", permissions: CUSTOM_CONFIG_PERMISSION}, {
    async call(method) {
      calls.push(method);
      if (method === "thread/read") return {thread: {cwd: "/test"}};
      if (method === "thread/start") return {thread: {id: "probe"}, sandbox: {type: "readOnly", networkAccess: true}, approvalPolicy: "on-request", approvalsReviewer: "user"};
      if (method === "thread/unsubscribe") return {};
      throw new Error("unexpected call");
    },
  });
  assert.deepEqual(calls, ["thread/read", "thread/start", "thread/unsubscribe"]);
  assert.deepEqual(params.sandboxPolicy, {type: "readOnly", networkAccess: true});
  assert.equal(params.approvalPolicy, "on-request");
  assert.equal(params.approvalsReviewer, "user");
});
