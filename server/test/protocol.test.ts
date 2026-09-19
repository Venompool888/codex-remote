import assert from "node:assert/strict";
import test from "node:test";
import {
  ALLOWED_CODEX_METHODS,
  currentRemoteCapabilities,
  negotiateProtocolVersion,
  parseClientMessage,
  REMOTE_PROTOCOL_VERSION,
  requiredScopeForMethod,
} from "../src/protocol.js";
import { WORKSPACE_TEXT_WRITES_SUPPORTED } from "../src/workspace-mutations.js";

test("parses an allowed RPC envelope", () => {
  const message = parseClientMessage({ type: "rpc", id: "1", method: "thread/list", params: { limit: 20 } });
  assert.equal(message.type, "rpc");
  assert.equal(ALLOWED_CODEX_METHODS.has("thread/list"), true);
  assert.equal(ALLOWED_CODEX_METHODS.has("skills/list"), true);
  assert.equal(ALLOWED_CODEX_METHODS.has("plugin/list"), true);
  assert.equal(ALLOWED_CODEX_METHODS.has("host/workspace/validate"), true);
  assert.equal(ALLOWED_CODEX_METHODS.has("host/workspace/list"), true);
  assert.equal(ALLOWED_CODEX_METHODS.has("host/image/read"), true);
  assert.equal(ALLOWED_CODEX_METHODS.has("host/image/upload"), true);
  assert.equal(ALLOWED_CODEX_METHODS.has("host/account/status"), true);
  for (const method of ["host/account/usage", "host/administration/status", "host/workspace/files/search", "host/workspace/file/read",
    "host/file/readReference", "host/mcp/resource/read", "host/git/diff",
    "host/workspace/text/save", "host/workspace/text/create", "host/memory/reset", "host/thread/backgroundTerminals/list",
    "host/thread/backgroundTerminals/terminate", "host/thread/backgroundTerminals/clean",
    "thread/search", "thread/searchOccurrences", "thread/name/set", "thread/delete", "thread/compact/start", "review/start",
    "thread/goal/set", "thread/goal/get", "thread/goal/clear", "thread/turns/list", "thread/items/list"]) {
    assert.equal(ALLOWED_CODEX_METHODS.has(method), true, method);
  }
  assert.equal(ALLOWED_CODEX_METHODS.has("thread/shellCommand"), false);
  assert.equal(ALLOWED_CODEX_METHODS.has("plugin/install"), false);
});

test("maps remote methods onto least-privilege device scopes", () => {
  assert.equal(requiredScopeForMethod("thread/list"), "rpc:read");
  assert.equal(requiredScopeForMethod("host/account/status"), "rpc:read");
  assert.equal(requiredScopeForMethod("thread/start"), "rpc:write");
  assert.equal(requiredScopeForMethod("turn/interrupt"), "rpc:write");
  for (const method of ["thread/name/set", "thread/delete", "thread/compact/start", "review/start", "thread/goal/set", "thread/goal/clear"])
    assert.equal(requiredScopeForMethod(method), "rpc:write", method);
  for (const method of ["thread/search", "thread/searchOccurrences", "thread/goal/get", "thread/turns/list", "thread/items/list",
    "host/account/usage", "host/workspace/files/search", "host/workspace/file/read", "host/file/readReference", "host/mcp/resource/read", "host/git/diff"])
    assert.equal(requiredScopeForMethod(method), "rpc:read", method);
  assert.equal(requiredScopeForMethod("host/thread/backgroundTerminals/list"), "rpc:read");
  for (const method of ["host/workspace/text/save", "host/workspace/text/create", "host/memory/reset",
    "host/thread/backgroundTerminals/terminate", "host/thread/backgroundTerminals/clean"])
    assert.equal(requiredScopeForMethod(method), "rpc:write", method);
  assert.equal(requiredScopeForMethod("host/image/read"), "attachments:read");
  assert.equal(requiredScopeForMethod("host/image/upload"), "attachments:write");
});

test("rejects malformed client messages", () => {
  assert.throws(() => parseClientMessage({ type: "rpc", method: "thread/list" }));
  assert.throws(() => parseClientMessage({ type: "unknown" }));
});

test("parses and negotiates a v2 client hello", () => {
  const message = parseClientMessage({
    type: "client_hello",
    supportedProtocolVersions: [2, 1, 2],
    client: { name: "Codex Remote Android", version: "0.1.0", platform: "Android 36" },
    lastSequence: 42,
  });
  assert.equal(message.type, "client_hello");
  if (message.type !== "client_hello") return;
  assert.deepEqual(message.supportedProtocolVersions, [2, 1]);
  assert.equal(message.lastSequence, 42);
  assert.equal(negotiateProtocolVersion(message.supportedProtocolVersions), 2);
  assert.equal(negotiateProtocolVersion([999]), null);
});

test("advertises only capabilities that are implemented today", () => {
  const capabilities = currentRemoteCapabilities();
  assert.equal(REMOTE_PROTOCOL_VERSION, 2);
  assert.equal(capabilities.attachments.imageBase64, true);
  assert.equal(capabilities.attachments.chunkedHttp, false);
  assert.equal(capabilities.events.sequenced, true);
  assert.equal(capabilities.events.replay, true);
  assert.equal(capabilities.plugins.experimentalList, true);
  assert.equal(capabilities.plugins.installedApps, false);
  assert.equal(capabilities.account.status, true);
  assert.equal(capabilities.account.usage, true);
  assert.deepEqual(capabilities.workspaceFiles, { search: true, read: true, maxReadBytes: 512 * 1024 });
  assert.deepEqual(capabilities.richResources, { opaqueFileReferences: true, mcpRead: true, guardianDeniedApproval: false });
  assert.deepEqual(capabilities.workspaceMutations, { textSave: WORKSPACE_TEXT_WRITES_SUPPORTED, memoryReset: true, backgroundTerminals: true });
  assert.equal(capabilities.rpcMethods.includes("host/workspace/text/save"), WORKSPACE_TEXT_WRITES_SUPPORTED);
  assert.equal(capabilities.rpcMethods.includes("host/workspace/text/create"), WORKSPACE_TEXT_WRITES_SUPPORTED);
  assert.deepEqual(capabilities.git, { diff: true, maxDiffBytes: 1024 * 1024 });
  assert.deepEqual(capabilities.administration, { status: true, authFlows: true, mcp: true, plugins: true,
    namedSettings: true, terminalControl: true });
  assert.equal(capabilities.account.remoteLogin, true);
  assert.equal(capabilities.auth.rotation, true);
  assert.equal(capabilities.auth.scoped, true);
  assert.equal(capabilities.auth.revocationClosesSockets, true);
});


test("attachment negotiation includes supported document-provider MIME aliases", () => {
  const accepted = currentRemoteCapabilities({ chunkedHttp: true }).attachments.acceptedMimeTypes;
  for (const mime of ["application/javascript", "application/xml", "application/yaml", "application/x-yaml",
    "application/octet-stream", "application/x-zip-compressed", "audio/mpeg", "video/mp4",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"]) assert.ok(accepted.includes(mime), mime);
  assert.ok(!accepted.includes("*/*"));
  assert.ok(!accepted.includes("application/x-executable"));
});

test('workspace routing requires an explicit boolean client feature', () => {
  const hello={type:'client_hello',supportedProtocolVersions:[2],client:{name:'test',version:'1'}};
  assert.deepEqual((parseClientMessage({...hello,features:{opaqueWorkspaceRouting:true}}) as any).features,
    {opaqueWorkspaceRouting:true});
  assert.equal((parseClientMessage(hello) as any).features,undefined);
  for (const features of ['yes',null,{opaqueWorkspaceRouting:'true'},{opaqueWorkspaceRouting:1}])
    assert.throws(()=>parseClientMessage({...hello,features}),/boolean feature flags/);
});
