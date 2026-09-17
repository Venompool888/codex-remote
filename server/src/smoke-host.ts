import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import WebSocket from "ws";
import { DeviceAuth } from "./auth.js";
import { CodexAppServer } from "./codex-app-server.js";
import { RemoteHost } from "./remote-server.js";

const stateDirectory = await mkdtemp(join(tmpdir(), "codex-remote-host-smoke-"));
const auth = new DeviceAuth(join(stateDirectory, "devices.json"));
const codex = new CodexAppServer(process.env.CODEX_REMOTE_CODEX_BIN ?? "codex");
const host = new RemoteHost({ host: "127.0.0.1", port: 0, auth, codex });

try {
  const started = await host.start();
  const origin = `http://127.0.0.1:${started.port}`;
  const pairResponse = await fetch(`${origin}/v1/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code: started.pairing.code, deviceName: "Host smoke test" }),
  });
  if (!pairResponse.ok) throw new Error(`Pairing failed: ${pairResponse.status}`);
  const paired = await pairResponse.json() as { token: string };

  const result = await new Promise<{ data?: Array<{ id?: string }> }>((resolve, reject) => {
    const socket = new WebSocket(`ws://127.0.0.1:${started.port}/v1/ws`, {
      headers: { authorization: `Bearer ${paired.token}` },
    });
    const timeout = setTimeout(() => {
      socket.terminate();
      reject(new Error("Host smoke test timed out"));
    }, 15_000);
    socket.on("error", reject);
    socket.on("message", (raw) => {
      const message = JSON.parse(raw.toString()) as Record<string, unknown>;
      if (message.type === "hello") {
        socket.send(JSON.stringify({
          type: "rpc",
          id: "smoke-thread-list",
          method: "thread/list",
          params: { limit: 3, sortKey: "updated_at", sortDirection: "desc" },
        }));
      }
      if (message.type === "rpc_result" && message.id === "smoke-thread-list") {
        clearTimeout(timeout);
        socket.close();
        resolve(message.result as { data?: Array<{ id?: string }> });
      }
      if (message.type === "rpc_error" || message.type === "protocol_error") {
        clearTimeout(timeout);
        socket.close();
        reject(new Error(String(message.error)));
      }
    });
  });
  const firstThreadId = result.data?.[0]?.id;
  let historyRead = false;
  if (firstThreadId) {
    historyRead = await new Promise<boolean>((resolve, reject) => {
      const socket = new WebSocket(`ws://127.0.0.1:${started.port}/v1/ws`, {
        headers: { authorization: `Bearer ${paired.token}` },
      });
      const timeout = setTimeout(() => {
        socket.terminate();
        reject(new Error("History smoke test timed out"));
      }, 15_000);
      socket.on("error", reject);
      socket.on("message", (raw) => {
        const message = JSON.parse(raw.toString()) as Record<string, unknown>;
        if (message.type === "hello") {
          socket.send(JSON.stringify({
            type: "rpc",
            id: "smoke-thread-read",
            method: "thread/read",
            params: { threadId: firstThreadId, includeTurns: true },
          }));
        }
        if (message.type === "rpc_result" && message.id === "smoke-thread-read") {
          clearTimeout(timeout);
          socket.close();
          resolve(Boolean((message.result as { thread?: unknown }).thread));
        }
      });
    });
  }
  console.log(JSON.stringify({
    ok: true,
    paired: true,
    websocket: true,
    threadCount: result.data?.length ?? 0,
    historyRead,
  }));
} finally {
  await host.stop();
  await rm(stateDirectory, { recursive: true, force: true });
}
