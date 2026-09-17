import { createServer } from "node:http";
import { chmod, unlink } from "node:fs/promises";
import { realpathSync } from "node:fs";
import { resolve, join } from "node:path";
import { fileURLToPath } from "node:url";
import { CodexAppServer } from "./codex-app-server.js";
import { cliTokenProvider, type TokenProvider } from "./cli-auth.js";

/** A local-only credential endpoint. Socket ownership restricts callers to Remote. */
export function authBroker(provider: TokenProvider) {
  return createServer(async (req, res) => {
    res.setHeader("Cache-Control", "no-store");
    if (req.method !== "POST" || req.url !== "/token") { res.writeHead(404).end(); return; }
    try {
      let body = "";
      for await (const chunk of req) {
        body += chunk;
        if (body.length > 1024) { res.writeHead(413).end(); return; }
      }
      const input = JSON.parse(body || "{}");
      if (!input || typeof input !== "object" || Array.isArray(input) ||
          Object.keys(input).some(k => !["previousAccountId", "rejectedTokenHash"].includes(k)) ||
          (input.previousAccountId !== undefined && typeof input.previousAccountId !== "string") ||
          (input.rejectedTokenHash !== undefined && !/^[a-f0-9]{64}$/.test(input.rejectedTokenHash))) {
        res.writeHead(400).end(); return;
      }
      const tokens = await provider(input);
      res.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify(tokens));
    } catch {
      // Never log upstream errors, auth JSON, or token-bearing responses.
      res.writeHead(503).end('{"error":"CLI authentication unavailable"}');
    }
  });
}

async function main() {
  const home = process.env.CODEX_HOME;
  const socket = process.env.CODEX_REMOTE_AUTH_SOCKET;
  const executable = process.env.CODEX_REMOTE_AUTH_CODEX_BIN;
  if (!home || !socket || !executable) throw new Error("CLI auth broker configuration missing");
  const provider = cliTokenProvider(join(home, "auth.json"), async () => {
    // A fresh instance reads the current CLI cache; only account/read is invoked.
    const codex = new CodexAppServer(executable);
    let timeout: NodeJS.Timeout | undefined;
    try {
      await Promise.race([
        (async () => {
          await codex.start();
          await codex.call("account/read", { refreshToken: true }, 6_000);
        })(),
        new Promise((_, reject) => { timeout = setTimeout(() => reject(new Error("CLI refresh timeout")), 7_000); }),
      ]);
    } finally { clearTimeout(timeout); await codex.stop(); }
  });
  await unlink(socket).catch((error: NodeJS.ErrnoException) => { if (error.code !== "ENOENT") throw error; });
  const server = authBroker(provider);
  server.requestTimeout = 10_000;
  server.headersTimeout = 10_000;
  server.listen(socket, async () => {
    await chmod(socket, 0o660);
    console.log("CLI authentication bridge listening on local socket");
  });
}

if (process.argv[1] && realpathSync(resolve(process.argv[1])) === fileURLToPath(import.meta.url)) await main();
