#!/usr/bin/env node
import { homedir } from "node:os";
import { join } from "node:path";
import { realpathSync } from "node:fs";
import { pathToFileURL } from "node:url";
import { createInterface } from "node:readline/promises";
import qrcode from "qrcode-terminal";
import { requestPairingTicket } from "./admin-control.js";
import { DEVICE_SCOPES, DeviceAuth } from "./auth.js";
import { runMenu } from "./cli-menu.js";
import { pairingPublicUrl, readCliConfig, validatePublicUrl, writeCliConfig } from "./cli-config.js";

const stateDirectory = process.env.CODEX_REMOTE_STATE_DIR ?? join(homedir(), ".codex-remote");
const controlSocket = process.env.CODEX_REMOTE_CONTROL_SOCKET ?? join(stateDirectory, "control.sock");
const configPath = process.env.CODEX_REMOTE_CLI_CONFIG ?? join(stateDirectory, "cli-config.json");

export async function runCli(args: string[]): Promise<void> {
  const [command = process.stdin.isTTY && process.stdout.isTTY ? "menu" : "help", ...rest] = args;
  if (command === "menu") return menuCommand();
  if (command === "pair") return pairCommand(rest);
  if (command === "devices") return devicesCommand(rest);
  if (command === "config") return configCommand(rest);
  if (command === "help" || command === "--help" || command === "-h") return printHelp();
  throw new Error(`Unknown command: ${command}\n\n${helpText()}`);
}

async function menuCommand(): Promise<void> {
  if (!process.stdin.isTTY || !process.stdout.isTTY) throw new Error("Interactive menu requires a terminal. Use codexremote --help for scripting commands.");
  const terminal = createInterface({ input: process.stdin, output: process.stdout });
  let closed = false;
  terminal.on("close", () => { closed = true; });
  terminal.on("SIGINT", () => terminal.close());
  const auth = new DeviceAuth(join(stateDirectory, "devices.json"));
  try {
    await runMenu({
      ask: async (prompt) => {
        if (closed) return null;
        try { return await terminal.question(prompt); } catch { return null; }
      },
      write: (message) => console.log(message),
      pair: () => pairCommand([]),
      list: async () => { await auth.load(); return auth.list(); },
      revoke: (id) => auth.revoke(id),
      configure: async () => {
        console.log(`配置文件：${configPath}`);
        try {
          const config = await readCliConfig(configPath);
          console.log(`当前公网地址：${config.publicUrl ?? process.env.CODEX_REMOTE_PUBLIC_URL ?? "未设置（沿用 Host 启动地址）"}`);
        } catch { console.log("现有配置无效，输入新地址可修复。"); }
        if (closed) return;
        let value: string;
        try { value = await terminal.question("输入手机可访问的完整公网地址（例如 CF Tunnel HTTPS 地址；留空取消）："); } catch { return; }
        if (!value.trim()) return;
        try {
          await writeCliConfig(configPath, { publicUrl: validatePublicUrl(value) });
          console.log("已保存。下一次生成二维码即生效，无需重启 Host。之前的二维码不会自动更新。");
        } catch { console.log("保存失败。请检查地址格式和配置文件权限。"); }
      },
    });
  } finally { terminal.close(); }
}

async function pairCommand(args: string[]): Promise<void> {
  const known = new Set(["--json", "--no-qr"]);
  const unknown = args.find((argument) => !known.has(argument));
  if (unknown) throw new Error(`Unknown pair option: ${unknown}`);
  const config = await readCliConfig(configPath);
  // Validate overrides before issuing a ticket so bad configuration does not replace a valid code.
  if (config.publicUrl !== undefined || process.env.CODEX_REMOTE_PUBLIC_URL !== undefined) {
    pairingPublicUrl(config, process.env.CODEX_REMOTE_PUBLIC_URL, "");
  }
  const ticket = await requestPairingTicket(controlSocket);
  const pairing = { ...ticket, url: pairingPublicUrl(config, process.env.CODEX_REMOTE_PUBLIC_URL, ticket.url) };
  const pairingUri = createPairingUri(pairing.url, pairing.code);
  if (args.includes("--json")) {
    console.log(JSON.stringify({ ...pairing, pairingUri }, null, 2));
    return;
  }
  console.log(`Host: ${pairing.url}`);
  console.log(`Pairing expires at: ${new Date(pairing.expiresAt).toISOString()}`);
  console.log(`Pairing code: ${pairing.code}`);
  if (!args.includes("--no-qr")) qrcode.generate(pairingUri, { small: true });
}

async function configCommand(args: string[]): Promise<void> {
  const [command = "show", value] = args;
  if (command === "show" && args.length <= 1) {
    console.log(JSON.stringify({ configFile: configPath, ...await readCliConfig(configPath) }, null, 2));
  } else if (command === "set-url" && args.length === 2) {
    await writeCliConfig(configPath, { publicUrl: validatePublicUrl(value) });
    console.log("公网地址已保存，下一次生成二维码即生效。");
  } else if (command === "reset" && args.length === 1) {
    await writeCliConfig(configPath, {});
    console.log("已清除地址覆盖，恢复使用环境变量或 Host 地址。");
  } else throw new Error("Usage: codexremote config [show | set-url <public-url> | reset]");
}

async function devicesCommand(args: string[]): Promise<void> {
  const [command = "list", deviceId, scopeList] = args;
  const auth = new DeviceAuth(join(stateDirectory, "devices.json"));
  await auth.load();
  if (command === "list") {
    if (args.length > 1) throw new Error("Usage: codex-remote devices list");
    console.log(JSON.stringify({ devices: auth.list() }, null, 2));
    return;
  }
  if (command === "revoke") {
    if (!deviceId || args.length > 2) throw new Error("Usage: codex-remote devices revoke <device-id>");
    if (!await auth.revoke(deviceId)) throw new Error(`Unknown or already revoked device: ${deviceId}`);
    console.log(JSON.stringify({ revoked: deviceId }));
    return;
  }
  if (command === "scopes") {
    if (!deviceId || scopeList === undefined || args.length > 3) {
      throw new Error(`Usage: codex-remote devices scopes <device-id> <comma-separated-scopes>\nAvailable: ${DEVICE_SCOPES.join(",")}`);
    }
    const scopes = scopeList ? scopeList.split(",").map((scope) => scope.trim()).filter(Boolean) : [];
    if (!await auth.setScopes(deviceId, scopes)) throw new Error(`Unknown device: ${deviceId}`);
    console.log(JSON.stringify({ updated: deviceId, scopes }));
    return;
  }
  throw new Error("Usage: codex-remote devices [list | revoke <device-id> | scopes <device-id> <comma-separated-scopes>]");
}

export function createPairingUri(serverUrl: string, code: string): string {
  const uri = new URL("codexremote://pair");
  uri.searchParams.set("server", serverUrl.replace(/\/$/, ""));
  uri.searchParams.set("code", code);
  return uri.toString();
}

function printHelp(): void {
  console.log(helpText());
}

function helpText(): string {
  return [
    "Codex Remote Host administration",
    "",
    "Usage:",
    "  codexremote                       Interactive connection menu",
    "  codexremote menu",
    "  codexremote config [show | set-url <public-url> | reset]",
    "  codex-remote pair [--no-qr | --json]",
    "  codex-remote devices list",
    "  codex-remote devices revoke <device-id>",
    "  codex-remote devices scopes <device-id> <comma-separated-scopes>",
  ].join("\n");
}

if (process.argv[1] && import.meta.url === pathToFileURL(realpathSync(process.argv[1])).href) {
  runCli(process.argv.slice(2)).catch((error: Error) => {
    console.error(error.message);
    process.exitCode = 1;
  });
}
