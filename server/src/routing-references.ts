import { randomBytes } from 'node:crypto';
import { mkdir, open, readFile, rename, rm, stat } from 'node:fs/promises';
import { basename, dirname, isAbsolute } from 'node:path';

export type RoutingKind = 'workspace' | 'path';
interface Entry { id: string; device: string; kind: RoutingKind; path: string }
export interface RoutingReference { reference: string; name: string }
const LIMIT = 10_000;
const MAX_FILE_BYTES = 48 * 1024 * 1024;

/** Host-private, single-process store. Resolving a reference is not a filesystem permission grant. */
export class RoutingReferences {
  private entries: Entry[] | undefined;
  private revoked = new Set<string>();
  private tail: Promise<unknown> = Promise.resolve();
  constructor(private readonly file: string) {}

  private serialize<T>(operation: () => Promise<T>): Promise<T> {
    const next = this.tail.then(operation, operation);
    this.tail = next.catch(() => undefined);
    return next;
  }

  issue(device: string, kind: RoutingKind, path: string): Promise<RoutingReference> {
    return this.serialize(async () => {
      this.validateScope(device, kind);
      if (!isAbsolute(path) || path.length > 4096 || /[\u0000\r\n]/.test(path)) throw new Error('Invalid routing target');
      const entries = await this.load();
      if (this.revoked.has(device)) throw new Error('Routing device revoked');
      const existing = entries.find(entry => entry.device === device && entry.kind === kind && entry.path === path);
      if (existing) return this.publicReference(existing);
      if (entries.length >= LIMIT) throw new Error('Routing reference limit reached; manage devices on the host');
      let id: string;
      do { id = randomBytes(32).toString('hex'); } while (entries.some(entry => entry.id === id));
      const entry = {id, device, kind, path};
      const next = [...entries, entry];
      // Never return an ID that has not been durably saved for restart recovery.
      await this.save(next);
      this.entries = next;
      return this.publicReference(entry);
    });
  }

  resolve(device: string, kind: RoutingKind, reference: string): Promise<string> {
    return this.serialize(async () => {
      this.validateScope(device, kind);
      const match = /^remote-(workspace|path):\/\/([a-f0-9]{64})$/.exec(reference);
      const entries = await this.load();
      if (this.revoked.has(device)) throw new Error('Routing reference unavailable; device revoked');
      const entry = match?.[1] === kind ? entries.find(value => value.id === match[2] && value.device === device && value.kind === kind) : undefined;
      if (!entry) throw new Error('Routing reference unavailable; refresh the task or project');
      return entry.path;
    });
  }

  revokeDevice(device: string): Promise<void> {
    return this.serialize(async () => {
      this.validateScope(device, 'workspace');
      const entries = await this.load();
      const next = entries.filter(entry => entry.device !== device);
      if (this.revoked.has(device) && next.length === entries.length) return;
      const revoked = new Set(this.revoked).add(device);
      if (revoked.size > LIMIT) throw new Error('Routing revocation limit reached');
      await this.save(next, revoked);
      this.entries = next;
      this.revoked = revoked;
    });
  }

  private validateScope(device: string, kind: RoutingKind): void {
    if (!device || device.length > 256 || /[\u0000\r\n]/.test(device) || !['workspace', 'path'].includes(kind))
      throw new Error('Invalid routing scope');
  }

  private publicReference(entry: Entry): RoutingReference {
    return {reference: `remote-${entry.kind}://${entry.id}`, name: basename(entry.path) || 'Workspace'};
  }

  private async load(): Promise<Entry[]> {
    if (this.entries) return this.entries;
    try {
      if ((await stat(this.file)).size > MAX_FILE_BYTES) throw new Error('oversize');
      const parsed = JSON.parse(await readFile(this.file, 'utf8'));
      if (parsed.version !== 1 || !Array.isArray(parsed.entries) || parsed.entries.length > LIMIT) throw new Error('format');
      const revoked = parsed.revokedDevices ?? [];
      if (!Array.isArray(revoked) || revoked.length > LIMIT || new Set(revoked).size !== revoked.length) throw new Error('revocations');
      for (const device of revoked) {
        if (typeof device !== 'string') throw new Error('revocation');
        this.validateScope(device, 'workspace');
      }
      const ids = new Set<string>();
      const scopes = new Set<string>();
      for (const entry of parsed.entries) {
        if (!entry || typeof entry.device !== 'string' || typeof entry.path !== 'string' ||
            typeof entry.id !== 'string' || !/^[a-f0-9]{64}$/.test(entry.id)) throw new Error('entry');
        this.validateScope(entry.device, entry.kind);
        if (!isAbsolute(entry.path) || entry.path.length > 4096 || /[\u0000\r\n]/.test(entry.path)) throw new Error('path');
        const scope = JSON.stringify([entry.device, entry.kind, entry.path]);
        if (ids.has(entry.id) || scopes.has(scope)) throw new Error('duplicate');
        ids.add(entry.id); scopes.add(scope);
      }
      this.entries = parsed.entries;
      this.revoked = new Set(revoked);
      return this.entries!;
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === 'ENOENT') return this.entries = [];
      throw new Error('Routing reference store unavailable; repair it on the host');
    }
  }

  private async save(entries: Entry[], revoked = this.revoked): Promise<void> {
    const temporary = `${this.file}.${randomBytes(12).toString('hex')}.tmp`;
    try {
      await mkdir(dirname(this.file), {recursive: true, mode: 0o700});
      const handle = await open(temporary, 'wx', 0o600);
      try { await handle.writeFile(JSON.stringify({version: 1, entries, revokedDevices: [...revoked]})); await handle.sync(); }
      finally { await handle.close(); }
      await rename(temporary, this.file);
    } catch {
      throw new Error('Could not persist routing references; retry on the host');
    } finally { await rm(temporary, {force: true}).catch(() => undefined); }
  }
}
