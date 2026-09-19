import { StreamingReplacement } from "./streaming-replacement.js";
import { createHash, randomBytes, randomUUID } from "node:crypto";
import { createReadStream } from "node:fs";
import { mkdir, open, readFile, readdir, rename, rm, writeFile } from "node:fs/promises";
import { basename, dirname, extname, join } from "node:path";

export type AttachmentKind = "image" | "pdf" | "text" | "code" | "archive" | "document" | "audio" | "video";
export type AttachmentStatus = "uploading" | "complete";

export interface AttachmentDescriptor {
  id: string;
  kind: AttachmentKind;
  name: string;
  mimeType: string;
  size: number;
  sha256: string;
  offset: number;
  status: AttachmentStatus;
  expiresAt: string;
}

interface AttachmentRecord extends AttachmentDescriptor {
  ownerDeviceId: string;
  uploadKey?: string;
  createdAt: string;
  partialPath: string;
  path: string | null;
}

export interface AttachmentInit {
  uploadKey?: unknown;
  name: unknown;
  mimeType: unknown;
  size: unknown;
  sha256: unknown;
}

const DEFAULT_CHUNK_BYTES = 1024 * 1024;
const DEFAULT_DEVICE_QUOTA_BYTES = 40 * 1024 * 1024;
const UPLOAD_TTL_MS = 24 * 60 * 60_000;
// Keep the v2 timestamp field compatible with existing clients. Completed files
// are durable conversation material; only unfinished uploads have a TTL.
const COMPLETE_EXPIRES_AT = "9999-12-31T23:59:59.999Z";
const MAX_IMAGE_OR_PDF_BYTES = 20 * 1024 * 1024;
const MAX_TEXT_BYTES = 5 * 1024 * 1024;
export class AttachmentSizeError extends Error {
  constructor(readonly maxBytes: number) { super(`Attachment exceeds the ${maxBytes / 1024 / 1024} MB limit`); }
}

const CODE_EXTENSIONS = new Set([
  ".c", ".cc", ".cpp", ".cs", ".css", ".go", ".gradle", ".h", ".hpp", ".html",
  ".java", ".js", ".jsx", ".kt", ".kts", ".properties", ".py", ".rb", ".rs",
  ".sh", ".sql", ".swift", ".toml", ".ts", ".tsx", ".xml", ".yaml", ".yml",
]);
const TEXT_EXTENSIONS = new Set([".csv", ".json", ".log", ".md", ".markdown", ".txt"]);
// Document providers may retain registered or legacy application/* types for source files.
const CODE_APPLICATION_MIMES: Record<string, readonly string[]> = {
  ".js": ["application/javascript"],
  ".xml": ["application/xml"],
  ".yaml": ["application/yaml", "application/x-yaml"],
  ".yml": ["application/yaml", "application/x-yaml"],
};
const IMAGE_MIMES = new Set(["image/gif", "image/jpeg", "image/png", "image/webp"]);
const OFFICE_MIMES: Record<string, string> = {
  ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  ".xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  ".pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
};
const AUDIO_MIMES: Record<string, readonly string[]> = {
  ".mp3": ["audio/mpeg", "audio/mp3"], ".wav": ["audio/wav", "audio/x-wav"],
  ".ogg": ["audio/ogg"], ".m4a": ["audio/mp4", "audio/x-m4a"],
};
const VIDEO_MIMES: Record<string, readonly string[]> = {
  ".mp4": ["video/mp4"], ".mov": ["video/quicktime"], ".webm": ["video/webm"],
};

export class AttachmentStore {
  createOutboundStream(): StreamingReplacement {
    return new StreamingReplacement(`${join(this.root, "files")}/`, "remote-attachment://");
  }

  readonly chunkBytes: number;
  private readonly activeWrites = new Set<string>();
  private mutationTail: Promise<unknown> = Promise.resolve();
  private serialize<T>(operation: () => Promise<T>): Promise<T> {
    const next = this.mutationTail.then(operation, operation);
    this.mutationTail = next.catch(() => undefined);
    return next;
  }

  constructor(
    private readonly root: string,
    private readonly now: () => number = Date.now,
    chunkBytes = DEFAULT_CHUNK_BYTES,
    private readonly deviceQuotaBytes = DEFAULT_DEVICE_QUOTA_BYTES,
  ) {
    if (!Number.isSafeInteger(chunkBytes) || chunkBytes <= 0) throw new Error("Chunk size must be a positive integer");
    this.chunkBytes = chunkBytes;
  }

  init(ownerDeviceId: string, value: AttachmentInit): Promise<AttachmentDescriptor> {
    return this.serialize(() => this.initInternal(ownerDeviceId, value));
  }

  private async initInternal(ownerDeviceId: string, value: AttachmentInit): Promise<AttachmentDescriptor> {
    const name = sanitizeName(value.name);
    const requestedMime = normalizeMime(value.mimeType);
    const kind = classify(name, requestedMime);
    const size = value.size;
    if (!Number.isSafeInteger(size) || (size as number) <= 0) throw new Error("Attachment size must be a positive integer");
    const maxBytes = kind === "text" || kind === "code" ? MAX_TEXT_BYTES : MAX_IMAGE_OR_PDF_BYTES;
    if ((size as number) > maxBytes) throw new AttachmentSizeError(maxBytes);
    const sha256 = typeof value.sha256 === "string" ? value.sha256.toLowerCase() : "";
    if (!/^[a-f0-9]{64}$/.test(sha256)) throw new Error("A lowercase SHA-256 digest is required");
    await this.cleanupExpiredInternal();
    const records = (await this.listRecords()).filter((record) => record.ownerDeviceId === ownerDeviceId);
    const uploadKey = value.uploadKey;
    if (uploadKey !== undefined && (typeof uploadKey !== "string" || !/^[a-zA-Z0-9_-]{1,128}$/.test(uploadKey))) throw new Error("Invalid upload key");
    const prior = uploadKey ? records.find((record) => record.uploadKey === uploadKey) : undefined;
    if (prior) {
      if (prior.name !== name || prior.size !== size || prior.sha256 !== sha256 || prior.mimeType !== requestedMime) throw new Error("Upload key metadata mismatch");
      return descriptor(prior);
    }
    const used = records.reduce((total, record) => total + record.size, 0);
    if (used + (size as number) > this.deviceQuotaBytes) throw new Error("Device attachment quota exceeded");

    const id = randomUUID();
    const partialPath = join(this.root, "partial", `${id}.part`);
    const createdAt = new Date(this.now()).toISOString();
    const record: AttachmentRecord = {
      id,
      ownerDeviceId,
      uploadKey: typeof uploadKey === "string" ? uploadKey : undefined,
      kind,
      name,
      mimeType: requestedMime,
      size: size as number,
      sha256,
      offset: 0,
      status: "uploading",
      createdAt,
      expiresAt: new Date(this.now() + UPLOAD_TTL_MS).toISOString(),
      partialPath,
      path: null,
    };
    await mkdir(dirname(partialPath), { recursive: true, mode: 0o700 });
    await writeFile(partialPath, Buffer.alloc(0), { mode: 0o600, flag: "wx" });
    await this.saveRecord(record);
    return descriptor(record);
  }

  writeChunk(ownerDeviceId: string, id: string, start: number, total: number, bytes: Buffer, chunkSha256: string): Promise<AttachmentDescriptor> {
    return this.serialize(() => this.writeChunkInternal(ownerDeviceId, id, start, total, bytes, chunkSha256));
  }

  private async writeChunkInternal(
    ownerDeviceId: string,
    id: string,
    start: number,
    total: number,
    bytes: Buffer,
    chunkSha256: string,
  ): Promise<AttachmentDescriptor> {
    if (this.activeWrites.has(id)) throw new Error("Another chunk write is already in progress");
    this.activeWrites.add(id);
    try {
      const record = await this.ownedRecord(ownerDeviceId, id);
      if (record.status !== "uploading") throw new Error("Attachment upload is already complete");
      if (record.offset !== start) throw new Error(`Unexpected chunk offset; expected ${record.offset}`);
      if (record.size !== total) throw new Error("Content-Range total does not match the declared size");
      if (bytes.length === 0 || bytes.length > this.chunkBytes) throw new Error(`Chunk must contain 1-${this.chunkBytes} bytes`);
      if (start + bytes.length > record.size) throw new Error("Chunk exceeds the declared attachment size");
      if (!/^[a-f0-9]{64}$/.test(chunkSha256) || digest(bytes) !== chunkSha256) {
        throw new Error("Chunk SHA-256 mismatch");
      }
      const file = await open(record.partialPath, "r+");
      try {
        await file.write(bytes, 0, bytes.length, start);
        await file.sync();
      } finally {
        await file.close();
      }
      record.offset += bytes.length;
      await this.saveRecord(record);
      return descriptor(record);
    } finally {
      this.activeWrites.delete(id);
    }
  }

  complete(ownerDeviceId: string, id: string): Promise<AttachmentDescriptor> {
    return this.serialize(() => this.completeInternal(ownerDeviceId, id));
  }

  private async completeInternal(ownerDeviceId: string, id: string): Promise<AttachmentDescriptor> {
    const record = await this.ownedRecord(ownerDeviceId, id);
    if (record.status === "complete") return descriptor(record);
    if (record.offset !== record.size) throw new Error(`Upload is incomplete; expected ${record.size}, received ${record.offset}`);
    const finalPath = join(this.root, "files", `${record.id}-${record.name}`);
    let source = record.partialPath;
    let verifiedHash: string;
    try { verifiedHash = await hashFile(source); }
    catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
      source = finalPath; // Recover a promotion that completed just before a host crash.
      verifiedHash = await hashFile(source);
    }
    if (verifiedHash !== record.sha256) throw new Error("Attachment SHA-256 mismatch");
    await validateFile({ ...record, partialPath: source });
    await mkdir(dirname(finalPath), { recursive: true, mode: 0o700 });
    if (source !== finalPath) await rename(source, finalPath);
    record.path = finalPath;
    record.partialPath = "";
    record.status = "complete";
    record.expiresAt = COMPLETE_EXPIRES_AT;
    await this.saveRecord(record);
    return descriptor(record);
  }

  async status(ownerDeviceId: string, id: string): Promise<AttachmentDescriptor> {
    return descriptor(await this.ownedRecord(ownerDeviceId, id));
  }

  async resolveForTurn(ownerDeviceId: string, id: string): Promise<AttachmentDescriptor & { path: string }> {
    const record = await this.ownedRecord(ownerDeviceId, id);
    if (record.status !== "complete" || !record.path) throw new Error("Attachment is not complete");
    return { ...descriptor(record), path: record.path };
  }

  async preview(ownerDeviceId: string, id: string) {
    const attachment = await this.resolveForTurn(ownerDeviceId, id);
    if (attachment.kind !== "image") throw new Error("Attachment is not an image");
    return { descriptor: { ...attachment, path: undefined }, stream: createReadStream(attachment.path) };
  }

  async download(ownerDeviceId: string, id: string) {
    const attachment = await this.resolveForTurn(ownerDeviceId, id);
    return { descriptor: { ...attachment, path: undefined }, stream: createReadStream(attachment.path) };
  }

  delete(ownerDeviceId: string, id: string): Promise<boolean> {
    return this.serialize(() => this.deleteInternal(ownerDeviceId, id));
  }

  private async deleteInternal(ownerDeviceId: string, id: string): Promise<boolean> {
    let record: AttachmentRecord;
    try {
      record = await this.ownedRecord(ownerDeviceId, id);
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === "ENOENT") return false;
      throw error;
    }
    await Promise.all([
      record.partialPath ? rm(record.partialPath, { force: true }) : Promise.resolve(),
      record.path ? rm(record.path, { force: true }) : Promise.resolve(),
      rm(this.metadataPath(id), { force: true }),
    ]);
    return true;
  }

  cleanupExpired(): Promise<number> {
    return this.serialize(() => this.cleanupExpiredInternal());
  }

  private async cleanupExpiredInternal(): Promise<number> {
    const records = await this.listRecords();
    const expired = records.filter((record) => record.status !== "complete" && Date.parse(record.expiresAt) <= this.now());
    await Promise.all(expired.map(async (record) => {
      await Promise.all([
        record.partialPath ? rm(record.partialPath, { force: true }) : Promise.resolve(),
        record.path ? rm(record.path, { force: true }) : Promise.resolve(),
        rm(this.metadataPath(record.id), { force: true }),
      ]);
    }));
    return expired.length;
  }

  redactOutbound(value: unknown): unknown {
    if (typeof value === "string") {
      const withoutInstructions = value.replace(
        /\[\[CODEX_REMOTE_ATTACHMENT:([A-Za-z0-9_-]+)\]\][\s\S]*?\[\[\/CODEX_REMOTE_ATTACHMENT\]\]/g,
        (_match, encoded: string) => {
          try { return `Attached file: ${Buffer.from(encoded, "base64url").toString("utf8")}`; }
          catch { return "Attached remote file"; }
        },
      );
      return withoutInstructions.split(`${join(this.root, "files")}/`).join("remote-attachment://");
    }
    if (Array.isArray(value)) return value.map((item) => this.redactOutbound(item));
    if (value && typeof value === "object") {
      const input = value as Record<string, unknown>;
      const projected = Object.fromEntries(Object.entries(value).map(([key, item]) => [key, this.redactOutbound(item)]));
      const image = input.type === "localImage" && typeof input.path === "string";
      const file = input.type === "text" && typeof input.text === "string" &&
        /^\[\[CODEX_REMOTE_ATTACHMENT:[A-Za-z0-9_-]+\]\][\s\S]*\[\[\/CODEX_REMOTE_ATTACHMENT\]\]$/.test(input.text);
      if (image || file) {
        const source = String(image ? input.path : input.text);
        const prefix = `${join(this.root, "files")}/`;
        const start = image ? (source.startsWith(prefix) ? prefix.length : -1) : source.indexOf(prefix) + prefix.length;
        if (start >= prefix.length) {
          const match = source.slice(start).match(/^([a-f0-9-]{36})-/);
          if (match) {
            let name = image ? basename(source).slice(37) : "";
            if (file) {
              const encoded = source.match(/^\[\[CODEX_REMOTE_ATTACHMENT:([A-Za-z0-9_-]+)\]\]/)?.[1];
              if (encoded) name = Buffer.from(encoded, "base64url").toString("utf8");
            }
            if (name && name.length <= 255 && !/[\/\\\u0000-\u001f]/.test(name)) {
              projected.remoteAttachment = { id: match[1], name, kind: image ? "image" : "file" };
              if (image) { projected.type = "text"; projected.text = `Attached image: ${name}`; delete projected.path; }
            }
          }
        }
      }
      return projected;
    }
    return value;
  }

  private async ownedRecord(ownerDeviceId: string, id: string): Promise<AttachmentRecord> {
    const record = await this.readRecord(id);
    if (record.ownerDeviceId !== ownerDeviceId) throw new Error("Attachment does not belong to this device");
    // Also preserve completed records created by older hosts with a seven-day TTL.
    if (record.status !== "complete" && Date.parse(record.expiresAt) <= this.now()) throw new Error("Attachment has expired");
    return record;
  }

  private async listRecords(): Promise<AttachmentRecord[]> {
    let names: string[];
    try {
      names = await readdir(join(this.root, "metadata"));
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === "ENOENT") return [];
      throw error;
    }
    const records = await Promise.all(names.filter((name) => name.endsWith(".json")).map(async (name) => {
      try { return await this.readRecord(name.slice(0, -5)); } catch { return null; }
    }));
    return records.filter((record): record is AttachmentRecord => Boolean(record));
  }

  private async readRecord(id: string): Promise<AttachmentRecord> {
    if (!/^[a-f0-9-]{36}$/.test(id)) throw new Error("Invalid attachment id");
    let text: string;
    try {
      text = await readFile(this.metadataPath(id), "utf8");
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
      // This lookup happens before turn dispatch. Preserve the HTTP/delete code,
      // but do not leak the metadata path or collapse a known missing attachment
      // into an ambiguous host filesystem failure.
      throw Object.assign(new Error("Attachment expired or was removed; remove and select it again"), { code: "ENOENT" });
    }
    return JSON.parse(text) as AttachmentRecord;
  }

  private async saveRecord(record: AttachmentRecord): Promise<void> {
    const path = this.metadataPath(record.id);
    await mkdir(dirname(path), { recursive: true, mode: 0o700 });
    const temporary = `${path}.tmp-${process.pid}-${randomBytes(4).toString("hex")}`;
    await writeFile(temporary, `${JSON.stringify(record, null, 2)}\n`, { mode: 0o600 });
    await rename(temporary, path);
  }

  private metadataPath(id: string): string {
    return join(this.root, "metadata", `${id}.json`);
  }
}

function descriptor(record: AttachmentRecord): AttachmentDescriptor {
  const { id, kind, name, mimeType, size, sha256, offset, status, expiresAt } = record;
  return { id, kind, name, mimeType, size, sha256, offset, status,
    expiresAt: status === "complete" ? COMPLETE_EXPIRES_AT : expiresAt };
}

function sanitizeName(value: unknown): string {
  if (typeof value !== "string") throw new Error("Attachment name is required");
  const name = basename(value.trim()).replace(/[^a-zA-Z0-9._ -]/g, "-").slice(0, 120);
  if (!name || name === "." || name === "..") throw new Error("Attachment name is invalid");
  return name;
}

function normalizeMime(value: unknown): string {
  if (typeof value !== "string") throw new Error("Attachment MIME type is required");
  return value.toLowerCase().split(";", 1)[0].trim();
}

function classify(name: string, mimeType: string): AttachmentKind {
  const extension = extname(name).toLowerCase();
  if (extension === ".zip" && ["application/zip", "application/x-zip-compressed", "application/octet-stream"].includes(mimeType)) return "archive";
  if (OFFICE_MIMES[extension] === mimeType) return "document";
  if (AUDIO_MIMES[extension]?.includes(mimeType)) return "audio";
  if (VIDEO_MIMES[extension]?.includes(mimeType)) return "video";
  if (IMAGE_MIMES.has(mimeType)) return "image";
  if (mimeType === "application/pdf" && extension === ".pdf") return "pdf";
  if (CODE_EXTENSIONS.has(extension) && (mimeType.startsWith("text/") || mimeType === "application/octet-stream"
    || CODE_APPLICATION_MIMES[extension]?.includes(mimeType))) return "code";
  if (TEXT_EXTENSIONS.has(extension) && (mimeType.startsWith("text/")
    || ["application/json", "application/octet-stream"].includes(mimeType))) return "text";
  throw new Error("Unsupported attachment type; use supported images, PDF, ZIP, Office, audio, video, text, or source-code files");
}

async function validateFile(record: AttachmentRecord): Promise<void> {
  const path = record.partialPath;
  const bytes = await readFile(path);
  if (record.kind === "image") {
    const detected = detectImageMime(bytes);
    if (detected !== record.mimeType) throw new Error("Image content does not match its MIME type");
  } else if (record.kind === "pdf") {
    if (bytes.subarray(0, 5).toString("ascii") !== "%PDF-") throw new Error("PDF signature is invalid");
  } else if (record.kind === "archive" || record.kind === "document") {
    // Transport only: never extract uploaded archives on the HTTP server.
    const signature = bytes.subarray(0, 4).toString("hex");
    if (bytes.length < 22 || !["504b0304", "504b0506"].includes(signature)) throw new Error("ZIP signature is invalid");
    let end = -1;
    for (let i = bytes.length - 22; i >= Math.max(0, bytes.length - 65557); i--) {
      if (bytes.readUInt32LE(i) === 0x06054b50 && i + 22 + bytes.readUInt16LE(i + 20) === bytes.length) { end = i; break; }
    }
    if (end < 0 || bytes.readUInt16LE(end + 4) !== 0 || bytes.readUInt16LE(end + 6) !== 0
      || bytes.readUInt32LE(end + 16) + bytes.readUInt32LE(end + 12) > end) throw new Error("ZIP directory is invalid or unsupported");
    if (record.kind === "document") {
      const marker = extname(record.name).toLowerCase() === ".docx" ? "word/"
        : extname(record.name).toLowerCase() === ".xlsx" ? "xl/" : "ppt/";
      if (!bytes.includes(Buffer.from("[Content_Types].xml")) || !bytes.includes(Buffer.from(marker))) {
        throw new Error("Office document structure is invalid");
      }
    }
  } else if (record.kind === "audio") {
    if (!validAudio(bytes, record.mimeType)) throw new Error("Audio content does not match its MIME type");
  } else if (record.kind === "video") {
    if (!validVideo(bytes, record.mimeType)) throw new Error("Video content does not match its MIME type");
  } else {
    if (bytes.includes(0)) throw new Error("Text attachment contains binary data");
    try {
      new TextDecoder("utf-8", { fatal: true }).decode(bytes);
    } catch {
      throw new Error("Text attachment must be valid UTF-8");
    }
    if (record.mimeType === "application/json") {
      try { JSON.parse(bytes.toString("utf8")); } catch { throw new Error("JSON attachment is invalid"); }
    }
  }
}

function validAudio(bytes: Buffer, mime: string): boolean {
  if (mime === "audio/mpeg" || mime === "audio/mp3") return bytes.subarray(0, 3).toString("ascii") === "ID3"
    || (bytes.length >= 2 && bytes[0] === 0xff && (bytes[1] & 0xe0) === 0xe0);
  if (mime === "audio/wav" || mime === "audio/x-wav") return bytes.subarray(0, 4).toString("ascii") === "RIFF"
    && bytes.subarray(8, 12).toString("ascii") === "WAVE";
  if (mime === "audio/ogg") return bytes.subarray(0, 4).toString("ascii") === "OggS";
  return (mime === "audio/mp4" || mime === "audio/x-m4a") && bytes.subarray(4, 8).toString("ascii") === "ftyp";
}

function validVideo(bytes: Buffer, mime: string): boolean {
  if (mime === "video/webm") return bytes.subarray(0, 4).equals(Buffer.from([0x1a, 0x45, 0xdf, 0xa3]));
  return (mime === "video/mp4" || mime === "video/quicktime") && bytes.subarray(4, 8).toString("ascii") === "ftyp";
}

function detectImageMime(bytes: Buffer): string | null {
  if (bytes.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) return "image/png";
  if (bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return "image/jpeg";
  if (["GIF87a", "GIF89a"].includes(bytes.subarray(0, 6).toString("ascii"))) return "image/gif";
  if (bytes.subarray(0, 4).toString("ascii") === "RIFF" && bytes.subarray(8, 12).toString("ascii") === "WEBP") return "image/webp";
  return null;
}

function digest(bytes: Buffer): string {
  return createHash("sha256").update(bytes).digest("hex");
}

async function hashFile(path: string): Promise<string> {
  const hash = createHash("sha256");
  await new Promise<void>((resolve, reject) => {
    const stream = createReadStream(path);
    stream.on("data", (chunk) => hash.update(chunk));
    stream.once("error", reject);
    stream.once("end", resolve);
  });
  return hash.digest("hex");
}
