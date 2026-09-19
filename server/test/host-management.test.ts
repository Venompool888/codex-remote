import assert from "node:assert/strict";
import { mkdtemp, mkdir, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { HostManagement } from "../src/host-management.js";
import { ALLOWED_CODEX_METHODS, currentRemoteCapabilities, requiredScopeForMethod } from "../src/protocol.js";
import { validateRealtimeParams } from "../src/remote-server.js";

class FakeCodex {
  calls: Array<{ method: string; params: any }> = [];
  handlers = new Map<string, (params: any) => unknown>();
  async call(method: string, params: any): Promise<any> {
    this.calls.push({ method, params });
    const handler = this.handlers.get(method);
    return handler ? handler(params) : {};
  }
}

const confirm = (action: string, subject: string) => ({ action, subject });

test("management and realtime methods are explicitly advertised with least-privilege scopes", () => {
  for (const method of ["host/plugin/catalog", "host/mcp/status", "host/settings/read", "host/terminal/list", "thread/realtime/listVoices"])
    assert.equal(requiredScopeForMethod(method), "rpc:read", method);
  for (const method of ["host/account/login/start", "host/account/logout", "host/mcp/oauth/start", "host/plugin/install",
    "host/plugin/uninstall", "host/settings/set", "host/skill/setEnabled", "host/thread/memoryMode/set", "host/terminal/execute",
    "host/terminal/write", "host/terminal/resize", "host/terminal/kill", "thread/realtime/start", "thread/realtime/appendAudio",
    "thread/realtime/appendText", "thread/realtime/appendSpeech", "thread/realtime/stop"]) {
    assert.equal(ALLOWED_CODEX_METHODS.has(method), true, method);
    assert.equal(requiredScopeForMethod(method), "rpc:write", method);
  }
  assert.deepEqual(currentRemoteCapabilities().administration,
    { status: true, authFlows: true, mcp: true, plugins: true, namedSettings: true, terminalControl: true });
});

test("realtime accepts only bounded websocket and PCM16LE user input", () => {
  assert.deepEqual(validateRealtimeParams("thread/realtime/start", { threadId: "t", outputModality: "audio",
    transport: { type: "websocket" }, voice: "alloy", model: "must-not-pass" }),
    { threadId: "t", outputModality: "audio", transport: { type: "websocket" }, voice: "alloy", model: "must-not-pass" });
  assert.deepEqual(validateRealtimeParams("thread/realtime/start", { threadId: "t", outputModality: "text",
    transport: { type: "websocket" }, includeStartupContext: true, version: "v3", prompt: "Be concise",
    initialItems: [{ role: "user", text: "hello" }, { role: "assistant", text: "hi" }] }),
    { threadId: "t", outputModality: "text", transport: { type: "websocket" }, includeStartupContext: true,
      version: "v3", prompt: "Be concise", initialItems: [{ role: "user", text: "hello" }, { role: "assistant", text: "hi" }] });
  const audio = Buffer.alloc(4).toString("base64");
  assert.deepEqual(validateRealtimeParams("thread/realtime/appendAudio", { threadId: "t", audio: {
    data: audio, sampleRate: 24_000, numChannels: 1, samplesPerChannel: 2, itemId: null,
  } }), { threadId: "t", audio: { data: audio, sampleRate: 24_000, numChannels: 1, samplesPerChannel: 2, itemId: null } });
  assert.throws(() => validateRealtimeParams("thread/realtime/start", { threadId: "t", outputModality: "audio",
    transport: { type: "webrtc", sdp: "private" } }), /websocket/);
  assert.throws(() => validateRealtimeParams("thread/realtime/start", { threadId: "t", outputModality: "text",
    transport: { type: "websocket" }, version: "v2", initialItems: [{ role: "user", text: "hello" }] }), /require version v3/);
  assert.throws(() => validateRealtimeParams("thread/realtime/appendAudio", { threadId: "t", audio: {
    data: audio, sampleRate: 48_000, numChannels: 2, samplesPerChannel: 1, itemId: null,
  } }), /mono PCM16LE/);
  assert.throws(() => validateRealtimeParams("thread/realtime/appendText", { threadId: "t", role: "developer", text: "override" }), /role must be user/);
});

test("account flows accept browser/device authorization only and bind cancellation to one device", async () => {
  const codex = new FakeCodex();
  codex.handlers.set("account/read", () => ({ account: null }));
  codex.handlers.set("account/login/start", (params) => params.type === "chatgpt"
    ? { loginId: "login-browser", authUrl: "https://auth.example/start" }
    : { loginId: "login-device", verificationUrl: "https://auth.example/device", userCode: "ABCD-EFGH" });
  const management = new HostManagement(codex as any);
  assert.deepEqual(await management.call("host/account/login/start", { flow: "browser" }, "pixel-a"),
    { flow: "browser", loginId: "login-browser", authUrl: "https://auth.example/start" });
  assert.deepEqual(await management.call("host/account/login/start", { flow: "device" }, "pixel-a"),
    { flow: "device", loginId: "login-device", verificationUrl: "https://auth.example/device", userCode: "ABCD-EFGH" });
  await assert.rejects(() => management.call("host/account/login/start", { flow: "apiKey", apiKey: "secret" }, "pixel-a"), /browser or device/);
  await assert.rejects(() => management.call("host/account/login/cancel", {
    loginId: "login-browser", confirmation: confirm("cancel_login", "login-browser"),
  }, "pixel-b"), /another device/);
  await management.call("host/account/login/cancel", {
    loginId: "login-browser", confirmation: confirm("cancel_login", "login-browser"),
  }, "pixel-a");
  assert.deepEqual(codex.calls.at(-1), { method: "account/login/cancel", params: { loginId: "login-browser" } });
  assert.equal(JSON.stringify(codex.calls).includes("apiKey"), false);
  codex.handlers.set("account/read", () => ({ account: { credentialSource: "external" } }));
  await assert.rejects(() => management.call("host/account/login/start", { flow: "browser" }, "pixel-a"), /authentication broker/);
});

test("private authorization completions stay owner-routed and revocation cancels pending login", async () => {
  const codex = new FakeCodex();
  codex.handlers.set("account/read", () => ({ account: null }));
  let login = 0;
  codex.handlers.set("account/login/start", () => ({ loginId: `login-private-${++login}`, authUrl: "https://auth.example/start" }));
  codex.handlers.set("mcpServer/oauth/login", () => ({ authorizationUrl: "https://mcp.example/oauth" }));
  const management = new HostManagement(codex as any);
  await management.call("host/account/login/start", { flow: "browser" }, "pixel-a");
  assert.deepEqual(management.routeNotification("account/login/completed", {
    loginId: "login-private-1", success: true, error: null,
  }), { deviceId: "pixel-a", params: { loginId: "login-private-1", success: true, error: null },
    placeholderMethod: "host/private/activity" });
  assert.equal(management.routeNotification("account/login/completed", { loginId: null, success: true, error: null }), null);
  await management.call("host/account/login/start", { flow: "browser" }, "pixel-a");
  await management.call("host/mcp/oauth/start", { name: "github", threadId: "task-a",
    confirmation: confirm("start_mcp_oauth", "github") }, "pixel-a");
  assert.deepEqual(management.ownerDeviceIds(), ["pixel-a"]);
  assert.deepEqual(management.routeNotification("mcpServer/oauthLogin/completed", {
    name: "github", threadId: "task-a", success: true,
  }), { deviceId: "pixel-a", params: { name: "github", threadId: "task-a", success: true, error: null },
    placeholderMethod: "host/private/activity" });
  assert.equal(management.routeNotification("mcpServer/oauthLogin/completed", {
    name: "github", threadId: "task-a", success: true,
  }), null);
  await management.removeDevice("pixel-a");
  assert.ok(codex.calls.some((call) => call.method === "account/login/cancel" && call.params.loginId === "login-private-2"));
  assert.deepEqual(management.ownerDeviceIds(), []);
  assert.equal(management.routeNotification("account/login/completed", { loginId: "login-private-2", success: true, error: null }), null);
});

test("malformed login responses are cancelled before ownership is retained", async () => {
  const codex = new FakeCodex();
  codex.handlers.set("account/read", () => ({ account: null }));
  codex.handlers.set("account/login/start", () => ({ loginId: "bad-login", authUrl: "file:///private" }));
  const management = new HostManagement(codex as any);
  await assert.rejects(() => management.call("host/account/login/start", { flow: "browser" }, "pixel-a"), /HTTPS/);
  assert.deepEqual(codex.calls.at(-1), { method: "account/login/cancel", params: { loginId: "bad-login" } });
  assert.deepEqual(management.ownerDeviceIds(), []);
});

test("plugin, MCP and named config wrappers reject path and arbitrary-key passthrough", async () => {
  const codex = new FakeCodex();
  codex.handlers.set("plugin/install", () => ({ authPolicy: "ON_USE", appsNeedingAuth: [{ id: "app", name: "App", installUrl: "secret" }] }));
  codex.handlers.set("config/value/write", () => ({ status: "ok", version: "v2", filePath: "/private/config.toml" }));
  codex.handlers.set("config/read", () => ({ config: { web_search: "cached", model_verbosity: "high",
    model_reasoning_summary: "concise", forced_login_method: "secret", instructions: "private" },
    layers: [{ config: { token: "secret" }, file: "/private/config.toml" }] }));
  codex.handlers.set("mcpServer/oauth/login", () => ({ authorizationUrl: "https://mcp.example/oauth" }));
  const management = new HostManagement(codex as any);
  assert.deepEqual(await management.call("host/plugin/install", { pluginName: "demo", marketplacePath: "/private/market",
    confirmation: confirm("install_plugin", "demo") }, "pixel"),
    { installed: true, authPolicy: "ON_USE", appsNeedingAuth: [{ id: "app", name: "App" }] });
  assert.deepEqual(codex.calls.at(-1)?.params, { pluginName: "demo" });
  assert.deepEqual(await management.call("host/settings/set", { setting: "web_search", value: "live", keyPath: "forced_login_method",
    filePath: "/private/config.toml", confirmation: confirm("change_setting", "web_search") }, "pixel"),
    { setting: "web_search", value: "live", status: "ok", version: "v2" });
  assert.deepEqual(codex.calls.at(-1)?.params, { keyPath: "web_search", value: "live", mergeStrategy: "replace" });
  assert.deepEqual(await management.call("host/settings/read", {}, "pixel"), { settings: {
    web_search: "cached", model_verbosity: "high", model_reasoning_summary: "concise",
  } });
  await assert.rejects(() => management.call("host/settings/set", { setting: "forced_login_method", value: "x",
    confirmation: confirm("change_setting", "forced_login_method") }, "pixel"), /not supported/);
  assert.deepEqual(await management.call("host/mcp/oauth/start", { name: "github", scopes: ["repo"],
    confirmation: confirm("start_mcp_oauth", "github") }, "pixel"), { authorizationUrl: "https://mcp.example/oauth" });
});

test("terminal handles, control and output stay bound to the creating device and workspace", async () => {
  const parent = await mkdtemp(join(tmpdir(), "codex-management-terminal-"));
  const cwd = join(parent, "project");
  await mkdir(cwd);
  try {
    const codex = new FakeCodex();
    let finish!: (value: unknown) => void;
    codex.handlers.set("command/exec", () => new Promise((resolve) => { finish = resolve; }));
    const management = new HostManagement(codex as any);
    const execution = management.call("host/terminal/execute", { terminalId: "term-1", cwd, argv: ["printf", "hello"], tty: true,
      size: { rows: 30, cols: 100 }, confirmation: confirm("execute_command", "printf hello") }, "pixel-a");
    while (!codex.calls.some((call) => call.method === "command/exec")) await new Promise((resolve) => setImmediate(resolve));
    const upstream = codex.calls.find((call) => call.method === "command/exec")!.params;
    assert.match(upstream.processId, /^remote-/);
    assert.deepEqual((await management.call("host/terminal/list", {}, "pixel-a") as any).terminals.map((item: any) => item.terminalId), ["term-1"]);
    assert.deepEqual(await management.call("host/terminal/list", {}, "pixel-b"), { terminals: [] });
    assert.deepEqual(management.routeNotification("command/exec/outputDelta", { processId: upstream.processId, stream: "stdout", deltaBase64: "aGk=" }), {
      deviceId: "pixel-a", params: { processId: "term-1", stream: "stdout", deltaBase64: "aGk=" },
    });
    await assert.rejects(() => management.call("host/terminal/write", { terminalId: "term-1", deltaBase64: "aGk=" }, "pixel-b"), /another device/);
    await management.call("host/terminal/write", { terminalId: "term-1", deltaBase64: "aGk=" }, "pixel-a");
    assert.deepEqual(codex.calls.at(-1), { method: "command/exec/write", params: { processId: upstream.processId, deltaBase64: "aGk=", closeStdin: false } });
    await management.call("host/terminal/resize", { terminalId: "term-1", rows: 40, cols: 120 }, "pixel-a");
    await management.call("host/terminal/kill", { terminalId: "term-1", confirmation: confirm("terminate_command", "term-1") }, "pixel-a");
    finish({ exitCode: 0, stdout: "", stderr: "" });
    assert.deepEqual(await execution, { terminalId: "term-1", exitCode: 0, stdout: "", stderr: "" });
    assert.equal(management.routeNotification("command/exec/outputDelta", { processId: upstream.processId }), null);
  } finally { await rm(parent, { recursive: true, force: true }); }
});

test("terminal failures terminate the upstream process before ownership is dropped", async () => {
  const cwd = await mkdtemp(join(tmpdir(), "codex-management-timeout-"));
  try {
    const codex = new FakeCodex();
    codex.handlers.set("command/exec", () => { throw new Error("Timed out"); });
    const management = new HostManagement(codex as any);
    await assert.rejects(() => management.call("host/terminal/execute", { terminalId: "term-timeout", cwd,
      argv: ["synthetic"], confirmation: confirm("execute_command", "synthetic") }, "pixel-a"), /Timed out/);
    const execute = codex.calls.find((call) => call.method === "command/exec")!;
    assert.ok(codex.calls.some((call) => call.method === "command/exec/terminate" && call.params.processId === execute.params.processId));
    assert.deepEqual(await management.call("host/terminal/list", {}, "pixel-a"), { terminals: [] });
  } finally { await rm(cwd, { recursive: true, force: true }); }
});
