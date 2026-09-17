import { writeFileSync } from "node:fs";
import { publicPayload } from "./public-payload.js";
import { homedir } from "node:os";
import { join, resolve } from "node:path";
import qrcode from "qrcode-terminal";
import { DeviceAuth } from "./auth.js";
import { CodexAppServer } from "./codex-app-server.js";
import { RemoteHost } from "./remote-server.js";
import { RpcLedger } from "./rpc-ledger.js";
import { ArtifactStore } from "./artifact-store.js";
import { AttachmentStore } from "./attachment-store.js";
import { RoutingReferences } from "./routing-references.js";
import { socketTokenProvider } from "./cli-auth.js";
import { AdminControlServer } from "./admin-control.js";

const args = parseArgs(process.argv.slice(2));
const host = args.host ?? process.env.CODEX_REMOTE_HOST ?? "127.0.0.1";
const port = Number(args.port ?? process.env.CODEX_REMOTE_PORT ?? "8787");
const publicUrl = args.url ?? process.env.CODEX_REMOTE_PUBLIC_URL ?? `http://${host}:${port}`;
const defaultCwd = args.cwd ? resolve(args.cwd) : process.env.CODEX_REMOTE_DEFAULT_CWD;
const stateDir = process.env.CODEX_REMOTE_STATE_DIR ?? join(homedir(), ".codex-remote");
const codexExecutable = process.env.CODEX_REMOTE_CODEX_BIN ?? "codex";
const controlSocket = process.env.CODEX_REMOTE_CONTROL_SOCKET ?? join(stateDir, "control.sock");

if (!Number.isInteger(port) || port < 0 || port > 65_535) throw new Error("Invalid --port");
if (host !== "127.0.0.1" && !publicUrl.startsWith("https://")) {
  console.warn("WARNING: non-loopback Remote Host without HTTPS. Use only on a trusted LAN or behind TLS.");
}

const codex = new CodexAppServer(
  codexExecutable,
  [],
  process.env.CODEX_REMOTE_CODEX_PROXY === "1",
  process.env.CODEX_REMOTE_AUTH_SOCKET ? socketTokenProvider(process.env.CODEX_REMOTE_AUTH_SOCKET) : undefined,
);
codex.on("log", (line) => console.error(`[codex] ${publicPayload(line, "message")}`));
const remote = new RemoteHost({
  host,
  port,
  defaultCwd,
  auth: new DeviceAuth(join(stateDir, "devices.json")),
  codex,
  imageRoot: join(stateDir, "uploads"),
  attachments: new AttachmentStore(join(stateDir, "attachments")),
  artifacts: new ArtifactStore(join(stateDir, "artifacts")),
  ledger: new RpcLedger(join(stateDir, "rpc-ledger")),
  routingReferences: new RoutingReferences(join(stateDir, "routing-references.json")),
});

const started = await remote.start();
const admin = new AdminControlServer({
  socketPath: controlSocket,
  publicUrl,
  createPairingTicket: () => remote.createPairingTicket(),
});
try {
  await admin.start();
} catch (error) {
  await remote.stop();
  throw error;
}
console.log(`Codex Remote Host listening on ${started.host}:${started.port}`);
printPairing(started.pairing);

const shutdown = async () => {
  process.off("SIGINT", shutdown);
  process.off("SIGTERM", shutdown);
  await admin.stop();
  await remote.stop();
  process.exit(0);
};
process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
process.on("SIGUSR1", () => printPairing(remote.createPairingTicket()));

function printPairing(pairing: { code: string; expiresAt: number }): void {
  if (process.env.CODEX_REMOTE_PAIRING_FILE) {
    writeFileSync(process.env.CODEX_REMOTE_PAIRING_FILE, JSON.stringify({ ...pairing, url: publicUrl }), { mode: 0o600 });
    console.log(`Pairing ticket refreshed; expires at ${new Date(pairing.expiresAt).toISOString()}`);
    return;
  }
  const pairingUrl = `${publicUrl.replace(/\/$/, "")}/pair?code=${encodeURIComponent(pairing.code)}`;
  console.log(`Pairing expires at ${new Date(pairing.expiresAt).toISOString()}`);
  console.log(`Pairing code: ${pairing.code}`);
  qrcode.generate(pairingUrl, { small: true });
}

function parseArgs(values: string[]): Record<string, string> {
  const parsed: Record<string, string> = {};
  for (let index = 0; index < values.length; index += 1) {
    const key = values[index];
    if (!key.startsWith("--")) continue;
    const value = values[index + 1];
    if (!value || value.startsWith("--")) throw new Error(`Missing value for ${key}`);
    parsed[key.slice(2)] = value;
    index += 1;
  }
  return parsed;
}
