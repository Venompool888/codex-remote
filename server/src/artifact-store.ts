import { artifactImageReference } from './artifact-image-reference.js';
import { createHash, randomUUID } from "node:crypto";
import { createReadStream, constants } from "node:fs";
import { mkdir, open, readFile, realpath, readdir, rm, stat, lstat, writeFile, rename } from "node:fs/promises";
import { basename, extname, isAbsolute, join, relative, resolve, sep } from "node:path";

export interface ArtifactDescriptor { imageReference?: string; id: string; name: string; size: number; sha256: string; mimeType: string; expiresAt: string }
interface RecordValue extends ArtifactDescriptor { deviceId: string; threadId: string; source: string }
const MAX_BYTES = 50 * 1024 * 1024;
const MIMES: Record<string, string> = { '.pdf': 'application/pdf', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg',
  '.webp': 'image/webp', '.gif': 'image/gif', '.txt': 'text/plain', '.md': 'text/markdown', '.json': 'application/json',
  '.csv': 'text/csv', '.log': 'text/plain', '.html': 'text/html', '.zip': 'application/zip', '.docx': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  '.xlsx': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', '.pptx': 'application/vnd.openxmlformats-officedocument.presentationml.presentation' };

/** Issues device-scoped snapshots only for assistant-linked outputs inside the authoritative thread cwd. */
export class ArtifactStore {
  constructor(private readonly root: string) {}
  async list(deviceId: string, threadId: string, thread: any): Promise<ArtifactDescriptor[]> {
    if (typeof thread?.cwd !== 'string') return [];
    await mkdir(this.root, { recursive: true, mode: 0o700 });
    await this.cleanup();
    const cwd = await realpath(thread.cwd);
    const paths = new Set<string>();
    for (const turn of thread.turns || []) for (const item of turn.items || []) {
      if (item.type !== 'agentMessage' || typeof item.text !== 'string') continue;
      for (const match of item.text.matchAll(/\[[^\]]*\]\(<?([^\n]+?)>?\)/g)) paths.add(match[1]);
      for (const match of item.text.matchAll(/:{1,2}(?:code|file|artifact)[\w-]*\{[^}]*\bpath="([^"]+)"[^}]*\}/g)) paths.add(match[1]);
    }
    const result: ArtifactDescriptor[] = [];
    for (const raw of [...paths].slice(0, 30)) {
      try {
        let path = decodeURIComponent(raw.replace(/^sandbox:/, '').replace(/:\d+$/, ''));
        if (/^[a-z]+:\/\//i.test(path)) continue;
        path = resolve(cwd, path);
        const rel = relative(cwd, path);
        if (!rel || rel.startsWith('..' + sep) || isAbsolute(rel) || rel.split(sep).some((x) => x.startsWith('.'))) continue;
        if (!MIMES[extname(path).toLowerCase()] && !/\.(py|js|ts|tsx|kt|java|rs|go|c|cpp|h|css|xml|yaml|yml|sh|sql)$/.test(path)) continue;
        const canonical = await realpath(path);
        if (canonical !== path) continue; // reject symlink paths, including directory symlinks
        const expected = await stat(path);
        const handle = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
        let bytes: Buffer;
        try {
          const info = await handle.stat();
          if (!info.isFile() || info.size <= 0 || info.size > MAX_BYTES || info.dev !== expected.dev || info.ino !== expected.ino || await realpath(path) !== canonical) continue;
          bytes = await handle.readFile();
          if (bytes.length > MAX_BYTES) continue;
        } finally { await handle.close(); }
        const sha256 = createHash('sha256').update(bytes).digest('hex');
        const key = createHash('sha256').update(`${deviceId}\0${threadId}\0${canonical}\0${sha256}`).digest('hex');
        const existing = await this.read(key).catch(() => null);
        if (existing && Date.parse(existing.expiresAt) > Date.now()) { result.push({ ...descriptor(existing), imageReference: artifactImageReference(raw) }); continue; }
        const record: RecordValue = { id: key, deviceId, threadId, source: canonical,
          name: basename(path), size: bytes.length, sha256, mimeType: MIMES[extname(path).toLowerCase()] || 'text/plain',
          expiresAt: new Date(Date.now() + 7 * 24 * 60 * 60_000).toISOString() };
        const temp = join(this.root, `${randomUUID()}.tmp`);
        await writeFile(temp, bytes, { mode: 0o600 });
        await rename(temp, join(this.root, `${key}.data`));
        await writeFile(join(this.root, `${key}.json`), JSON.stringify(record), { mode: 0o600 });
        result.push({ ...descriptor(record), imageReference: artifactImageReference(raw) });
      } catch { /* Invalid, missing or inaccessible output is never exposed as a host path. */ }
    }
    return result;
  }
  async download(deviceId: string, id: string): Promise<{ descriptor: ArtifactDescriptor; stream: ReturnType<typeof createReadStream> }> {
    const record = await this.read(id);
    if (record.deviceId !== deviceId || Date.parse(record.expiresAt) <= Date.now()) throw new Error('Artifact unavailable for this device');
    return { descriptor: descriptor(record), stream: createReadStream(join(this.root, `${id}.data`)) };
  }
  private async read(id: string): Promise<RecordValue> {
    if (!/^[a-f0-9]{64}$/.test(id)) throw new Error('Invalid artifact ID');
    return JSON.parse(await readFile(join(this.root, `${id}.json`), 'utf8'));
  }
  private async cleanup(): Promise<void> {
    const orphanCutoff = Date.now() - 24 * 60 * 60_000;
    for (const name of await readdir(this.root)) {
      const match = /^([a-f0-9]{64})\.(json|data)$/.exec(name);
      const temporary = /^[a-f0-9-]{36}\.tmp$/.test(name);
      if (!match && !temporary) continue;
      const file = join(this.root, name);
      const info = await lstat(file).catch(() => null);
      if (!info?.isFile()) continue;
      const record = match ? await this.read(match[1]).catch(() => null) : null;
      const expiry = record ? Date.parse(record.expiresAt) : NaN;
      if (match && Number.isFinite(expiry) && expiry <= Date.now()) {
        // Derive deletion paths from the validated filename, never metadata content.
        await rm(join(this.root, `${match[1]}.data`), { force: true });
        await rm(join(this.root, `${match[1]}.json`), { force: true });
      } else if (!Number.isFinite(expiry) && info.mtimeMs < orphanCutoff) {
        // Preserve recent data while a concurrent snapshot is still writing its index.
        await rm(file, { force: true });
      }
    }
  }
}
function descriptor({ id, name, size, sha256, mimeType, expiresAt }: RecordValue): ArtifactDescriptor { return { id, name, size, sha256, mimeType, expiresAt }; }
