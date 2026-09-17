import WebSocket from "ws";

const remoteUrl = required("CODEX_REMOTE_SMOKE_URL").replace(/\/$/, "");
const pairingCode = required("CODEX_REMOTE_SMOKE_PAIRING_CODE");
const httpBase = remoteUrl.replace(/^ws:/, "http:").replace(/^wss:/, "https:");
const websocketBase = remoteUrl.replace(/^http:/, "ws:").replace(/^https:/, "wss:");

const pairResponse = await fetch(`${httpBase}/v1/pair`, {
  method: "POST",
  headers: { "content-type": "application/json" },
  body: JSON.stringify({ code: pairingCode, deviceName: "deployment-smoke" }),
});
if (!pairResponse.ok) throw new Error(`Pairing failed: HTTP ${pairResponse.status}`);
const paired = await pairResponse.json() as { token: string; device: { id: string } };

const result = await new Promise<{ data?: Array<{ id?: string }> }>((resolve, reject) => {
  const socket = new WebSocket(`${websocketBase}/v1/ws`, {
    headers: { authorization: `Bearer ${paired.token}` },
  });
  const timeout = setTimeout(() => {
    socket.terminate();
    reject(new Error("Remote smoke test timed out"));
  }, 20_000);
  socket.on("error", reject);
  socket.on("message", (raw) => {
    const message = JSON.parse(raw.toString()) as Record<string, unknown>;
    if (message.type === "hello") {
      socket.send(JSON.stringify({
        type: "rpc",
        id: "remote-smoke-list",
        method: "thread/list",
        params: { limit: 3, sortKey: "updated_at", sortDirection: "desc" },
      }));
    }
    if (message.type === "rpc_result" && message.id === "remote-smoke-list") {
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

console.log(JSON.stringify({
  ok: true,
  deviceId: paired.device.id,
  paired: true,
  websocket: true,
  threadCount: result.data?.length ?? 0,
}));

function required(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is required`);
  return value;
}

