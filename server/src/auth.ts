import { createHash, randomBytes, timingSafeEqual } from "node:crypto";
import { chmod, mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { dirname } from "node:path";

export const DEVICE_SCOPES = [
  "rpc:read",
  "rpc:write",
  "attachments:read",
  "attachments:write",
  "approvals:respond",
  "device:rotate",
  "device:revoke",
] as const;

export type DeviceScope = typeof DEVICE_SCOPES[number];

export interface DeviceRecord {
  id: string;
  name: string;
  tokenHash: string;
  scopes: DeviceScope[];
  createdAt: string;
  lastSeenAt: string | null;
  expiresAt: string;
  rotatedAt: string | null;
  revokedAt: string | null;
}

interface StoredDeviceRecord {
  id?: unknown;
  name?: unknown;
  tokenHash?: unknown;
  scopes?: unknown;
  createdAt?: unknown;
  lastSeenAt?: unknown;
  expiresAt?: unknown;
  rotatedAt?: unknown;
  revokedAt?: unknown;
}

interface StoredDeviceFile {
  version?: number;
  devices?: StoredDeviceRecord[];
}

interface DeviceFile {
  version: 2;
  devices: DeviceRecord[];
}

export interface PairingTicket {
  code: string;
  expiresAt: number;
}

export interface IssuedDeviceCredential {
  device: DeviceRecord;
  token: string;
}

const DEFAULT_CREDENTIAL_TTL_MS = 180 * 24 * 60 * 60_000;

export class DeviceAuth {
  private devices: DeviceRecord[] = [];
  private pairing: PairingTicket | null = null;

  constructor(
    private readonly filePath: string,
    private readonly credentialTtlMs = DEFAULT_CREDENTIAL_TTL_MS,
    private readonly now: () => number = Date.now,
  ) {
    if (!Number.isSafeInteger(credentialTtlMs) || credentialTtlMs <= 0) {
      throw new Error("Credential TTL must be a positive safe integer");
    }
  }

  private operationTail: Promise<unknown> = Promise.resolve();
  private serialize<T>(operation: () => Promise<T>): Promise<T> {
    const next = this.operationTail.then(operation, operation);
    this.operationTail = next.catch(() => undefined);
    return next;
  }
  load(): Promise<void> { return this.serialize(() => this.loadInternal()); }
  pair(code: string, requestedName: string): Promise<IssuedDeviceCredential> { return this.serialize(() => this.pairInternal(code, requestedName)); }
  authenticate(token: string): Promise<DeviceRecord | null> { return this.serialize(() => this.authenticateInternal(token)); }
  isActive(deviceId: string): Promise<boolean> { return this.serialize(() => this.isActiveInternal(deviceId)); }
  rotate(token: string): Promise<IssuedDeviceCredential | null> { return this.serialize(() => this.rotateInternal(token)); }
  revoke(deviceId: string): Promise<boolean> { return this.serialize(() => this.revokeInternal(deviceId)); }
  setScopes(deviceId: string, scopes: readonly string[]): Promise<boolean> { return this.serialize(() => this.setScopesInternal(deviceId, scopes)); }

  private async loadInternal(): Promise<void> {
    try {
      const parsed = JSON.parse(await readFile(this.filePath, "utf8")) as StoredDeviceFile;
      const rawDevices = Array.isArray(parsed.devices) ? parsed.devices : [];
      this.devices = rawDevices.map((device) => this.normalizeDevice(device)).filter((device): device is DeviceRecord => Boolean(device));
      if (parsed.version !== 2 || this.devices.length !== rawDevices.length) await this.save();
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
      this.devices = [];
    }
  }

  createPairingTicket(ttlMs = 5 * 60_000): PairingTicket {
    const ticket = {
      code: randomBytes(18).toString("base64url"),
      expiresAt: this.now() + ttlMs,
    };
    this.pairing = ticket;
    return ticket;
  }

  private async pairInternal(code: string, requestedName: string): Promise<IssuedDeviceCredential> {
    await this.loadInternal();
    const ticket = this.pairing;
    if (!ticket || this.now() > ticket.expiresAt || !safeEqual(code, ticket.code)) {
      throw new Error("Pairing code is invalid or expired");
    }
    this.pairing = null;
    const token = randomBytes(32).toString("base64url");
    const now = new Date(this.now()).toISOString();
    const device: DeviceRecord = {
      id: randomBytes(12).toString("hex"),
      name: requestedName.trim().slice(0, 80) || "Android device",
      tokenHash: hashToken(token),
      scopes: [...DEVICE_SCOPES],
      createdAt: now,
      lastSeenAt: null,
      expiresAt: new Date(this.now() + this.credentialTtlMs).toISOString(),
      rotatedAt: null,
      revokedAt: null,
    };
    this.devices.push(device);
    await this.save();
    return { device, token };
  }

  private async authenticateInternal(token: string): Promise<DeviceRecord | null> {
    if (!token) return null;
    // Device administration may run in a separate process. Reload before every
    // new handshake so revocations and rotations take effect without restart.
    await this.loadInternal();
    const digest = hashToken(token);
    const device = this.devices.find((candidate) => safeEqual(candidate.tokenHash, digest));
    if (!device || !this.isActiveRecord(device)) return null;
    device.lastSeenAt = new Date(this.now()).toISOString();
    await this.save();
    return cloneDevice(device);
  }

  private async isActiveInternal(deviceId: string): Promise<boolean> {
    await this.loadInternal();
    const device = this.devices.find((candidate) => candidate.id === deviceId);
    return Boolean(device && this.isActiveRecord(device));
  }

  list(): Omit<DeviceRecord, "tokenHash">[] {
    return this.devices.map(({ tokenHash: _tokenHash, ...device }) => ({ ...device, scopes: [...device.scopes] }));
  }

  private async rotateInternal(token: string): Promise<IssuedDeviceCredential | null> {
    const authenticated = await this.authenticateInternal(token);
    if (!authenticated || !authenticated.scopes.includes("device:rotate")) return null;
    const device = this.devices.find((candidate) => candidate.id === authenticated.id);
    if (!device || !this.isActiveRecord(device)) return null;
    const replacement = randomBytes(32).toString("base64url");
    const now = new Date(this.now()).toISOString();
    device.tokenHash = hashToken(replacement);
    device.rotatedAt = now;
    device.expiresAt = new Date(this.now() + this.credentialTtlMs).toISOString();
    await this.save();
    return { device: cloneDevice(device), token: replacement };
  }

  private async revokeInternal(deviceId: string): Promise<boolean> {
    await this.loadInternal();
    const device = this.devices.find((candidate) => candidate.id === deviceId);
    if (!device || device.revokedAt) return false;
    device.revokedAt = new Date(this.now()).toISOString();
    await this.save();
    return true;
  }

  private async setScopesInternal(deviceId: string, scopes: readonly string[]): Promise<boolean> {
    const normalized = [...new Set(scopes)].filter((scope): scope is DeviceScope => DEVICE_SCOPES.includes(scope as DeviceScope));
    if (normalized.length !== new Set(scopes).size) throw new Error("One or more device scopes are invalid");
    await this.loadInternal();
    const device = this.devices.find((candidate) => candidate.id === deviceId);
    if (!device) return false;
    device.scopes = normalized;
    await this.save();
    return true;
  }

  private isActiveRecord(device: DeviceRecord): boolean {
    return !device.revokedAt && Date.parse(device.expiresAt) > this.now();
  }

  private normalizeDevice(raw: StoredDeviceRecord): DeviceRecord | null {
    if (typeof raw.id !== "string" || !raw.id
      || typeof raw.name !== "string" || !raw.name
      || typeof raw.tokenHash !== "string" || !raw.tokenHash
      || typeof raw.createdAt !== "string" || !Number.isFinite(Date.parse(raw.createdAt))) return null;
    const storedScopes = Array.isArray(raw.scopes)
      ? raw.scopes.filter((scope): scope is DeviceScope => typeof scope === "string" && DEVICE_SCOPES.includes(scope as DeviceScope))
      : [...DEVICE_SCOPES];
    const legacyExpiry = new Date(Date.parse(raw.createdAt) + this.credentialTtlMs).toISOString();
    return {
      id: raw.id,
      name: raw.name.slice(0, 80),
      tokenHash: raw.tokenHash,
      scopes: [...new Set(storedScopes)],
      createdAt: new Date(raw.createdAt).toISOString(),
      lastSeenAt: typeof raw.lastSeenAt === "string" && Number.isFinite(Date.parse(raw.lastSeenAt))
        ? new Date(raw.lastSeenAt).toISOString()
        : null,
      expiresAt: typeof raw.expiresAt === "string" && Number.isFinite(Date.parse(raw.expiresAt))
        ? new Date(raw.expiresAt).toISOString()
        : legacyExpiry,
      rotatedAt: typeof raw.rotatedAt === "string" && Number.isFinite(Date.parse(raw.rotatedAt))
        ? new Date(raw.rotatedAt).toISOString()
        : null,
      revokedAt: typeof raw.revokedAt === "string" && Number.isFinite(Date.parse(raw.revokedAt))
        ? new Date(raw.revokedAt).toISOString()
        : null,
    };
  }

  private async save(): Promise<void> {
    await mkdir(dirname(this.filePath), { recursive: true, mode: 0o700 });
    const temporary = `${this.filePath}.tmp-${process.pid}-${randomBytes(6).toString("hex")}`;
    const value: DeviceFile = { version: 2, devices: this.devices };
    await writeFile(temporary, `${JSON.stringify(value, null, 2)}\n`, { encoding: "utf8", mode: 0o600 });
    await rename(temporary, this.filePath);
    await chmod(this.filePath, 0o600);
  }
}

function cloneDevice(device: DeviceRecord): DeviceRecord {
  return { ...device, scopes: [...device.scopes] };
}

function hashToken(token: string): string {
  return createHash("sha256").update(token, "utf8").digest("hex");
}

function safeEqual(left: string, right: string): boolean {
  const a = Buffer.from(left);
  const b = Buffer.from(right);
  return a.length === b.length && timingSafeEqual(a, b);
}
