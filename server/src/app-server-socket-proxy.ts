#!/usr/bin/env node
import net from "node:net";
import { isAbsolute } from "node:path";
import { createInterface } from "node:readline";
import WebSocket from "ws";

// Adapt the Host's JSONL stdio transport to the existing daemon's WebSocket
// control socket. Never start, log in to, or stop the shared daemon.
const socketPath = process.env.CODEX_REMOTE_APP_SERVER_SOCKET;
if (!socketPath || !isAbsolute(socketPath)) {
  throw new Error("CODEX_REMOTE_APP_SERVER_SOCKET must be an absolute Unix socket path");
}
const socket = new WebSocket("ws://localhost/", {
  createConnection: () => net.createConnection(socketPath),
  perMessageDeflate: false,
  handshakeTimeout: 10_000,
  maxPayload: 64 * 1024 * 1024,
});
let stopping = false;
function stop(failed = false): void {
  if (stopping) return;
  stopping = true;
  process.exitCode = failed ? 1 : 0;
  process.stdin.destroy();
  socket.close();
  setTimeout(() => socket.terminate(), 1_000).unref();
}
socket.on("open", () => {
  const lines = createInterface({ input: process.stdin });
  lines.on("line", (line) => {
    if (!line.trim() || stopping) return;
    if (socket.bufferedAmount > 64 * 1024 * 1024) {
      process.stderr.write("Shared app-server send queue exceeded limit\n");
      stop(true);
      return;
    }
    socket.send(line, (error) => { if (error) stop(true); });
  });
  lines.on("close", () => stop());
});
socket.on("message", (data, binary) => {
  if (binary) { stop(true); return; }
  if (!process.stdout.write(`${data.toString()}\n`)) socket.pause();
});
process.stdout.on("drain", () => { if (!stopping) socket.resume(); });
process.stdout.on("error", () => stop(true));
socket.on("error", () => {
  process.stderr.write("Shared app-server socket connection failed\n");
  stop(true);
});
socket.on("close", () => {
  if (!stopping) stop(true);
});
process.on("SIGTERM", () => stop());
process.on("SIGINT", () => stop());
