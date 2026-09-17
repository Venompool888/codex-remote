import { createConnection, createServer, Server, Socket } from "node:net";
import { chmod, lstat, mkdir, unlink } from "node:fs/promises";
import { dirname } from "node:path";
import { PairingTicket } from "./auth.js";

const MAX_MESSAGE_BYTES = 16 * 1024;
const CLIENT_TIMEOUT_MS = 3_000;

interface PairingResponse extends PairingTicket {
  url: string;
}

interface AdminControlOptions {
  socketPath: string;
  publicUrl: string;
  createPairingTicket: () => PairingTicket;
}

export class AdminControlServer {
  private readonly server: Server;
  private started = false;

  constructor(private readonly options: AdminControlOptions) {
    this.server = createServer((socket) => this.handle(socket));
  }

  async start(): Promise<void> {
    await mkdir(dirname(this.options.socketPath), { recursive: true, mode: 0o700 });
    await removeStaleSocket(this.options.socketPath);
    await new Promise<void>((resolve, reject) => {
      const onError = (error: Error) => reject(error);
      this.server.once("error", onError);
      this.server.listen(this.options.socketPath, () => {
        this.server.off("error", onError);
        resolve();
      });
    });
    this.started = true;
    try {
      await chmod(this.options.socketPath, 0o600);
    } catch (error) {
      await this.stop();
      throw error;
    }
  }

  async stop(): Promise<void> {
    if (!this.started) return;
    await new Promise<void>((resolve) => this.server.close(() => resolve()));
    this.started = false;
    await unlink(this.options.socketPath).catch((error: NodeJS.ErrnoException) => {
      if (error.code !== "ENOENT") throw error;
    });
  }

  private handle(socket: Socket): void {
    socket.setEncoding("utf8");
    let input = "";
    let answered = false;
    const respond = (value: unknown) => {
      if (answered) return;
      answered = true;
      socket.end(`${JSON.stringify(value)}\n`);
    };
    socket.on("data", (chunk: string) => {
      input += chunk;
      if (Buffer.byteLength(input, "utf8") > MAX_MESSAGE_BYTES) return respond({ ok: false, error: "Admin request is too large" });
      const newline = input.indexOf("\n");
      if (newline < 0) return;
      try {
        const request = JSON.parse(input.slice(0, newline)) as { command?: unknown };
        if (request.command !== "pair") return respond({ ok: false, error: "Unknown admin command" });
        const pairing = this.options.createPairingTicket();
        respond({ ok: true, pairing: { ...pairing, url: this.options.publicUrl } });
      } catch {
        respond({ ok: false, error: "Invalid admin request" });
      }
    });
    socket.on("error", () => undefined);
  }
}

export async function requestPairingTicket(socketPath: string): Promise<PairingResponse> {
  return await new Promise<PairingResponse>((resolve, reject) => {
    const socket = createConnection(socketPath);
    socket.setEncoding("utf8");
    socket.setTimeout(CLIENT_TIMEOUT_MS);
    let input = "";
    let settled = false;
    const fail = (error: Error) => {
      if (settled) return;
      settled = true;
      socket.destroy();
      reject(error);
    };
    socket.on("connect", () => socket.write(`${JSON.stringify({ command: "pair" })}\n`));
    socket.on("data", (chunk: string) => {
      input += chunk;
      if (Buffer.byteLength(input, "utf8") > MAX_MESSAGE_BYTES) return fail(new Error("Host admin response is too large"));
      const newline = input.indexOf("\n");
      if (newline < 0) return;
      try {
        const response = JSON.parse(input.slice(0, newline)) as {
          ok?: unknown;
          error?: unknown;
          pairing?: Partial<PairingResponse>;
        };
        if (response.ok !== true || !response.pairing) {
          return fail(new Error(typeof response.error === "string" ? response.error : "Host rejected the admin request"));
        }
        const { code, expiresAt, url } = response.pairing;
        if (typeof code !== "string" || !code || typeof expiresAt !== "number" || typeof url !== "string" || !url) {
          return fail(new Error("Host returned an invalid pairing ticket"));
        }
        settled = true;
        socket.end();
        resolve({ code, expiresAt, url });
      } catch {
        fail(new Error("Host returned an invalid admin response"));
      }
    });
    socket.on("timeout", () => fail(new Error("Timed out waiting for the Remote Host admin socket")));
    socket.on("error", (error) => fail(new Error(`Cannot reach the running Remote Host: ${error.message}`)));
    socket.on("end", () => {
      if (!settled) fail(new Error("Remote Host closed the admin socket without a response"));
    });
  });
}

async function removeStaleSocket(socketPath: string): Promise<void> {
  try {
    const stat = await lstat(socketPath);
    if (!stat.isSocket()) throw new Error(`Admin socket path exists and is not a socket: ${socketPath}`);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return;
    throw error;
  }

  const active = await new Promise<boolean>((resolve) => {
    const probe = createConnection(socketPath);
    const done = (value: boolean) => {
      probe.removeAllListeners();
      probe.destroy();
      resolve(value);
    };
    probe.setTimeout(500);
    probe.once("connect", () => done(true));
    probe.once("error", () => done(false));
    probe.once("timeout", () => done(true));
  });
  if (active) throw new Error(`A Remote Host admin socket is already active: ${socketPath}`);
  await unlink(socketPath);
}
