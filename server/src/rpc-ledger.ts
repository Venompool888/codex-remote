import { createHash, randomUUID } from 'node:crypto';
import { mkdir, readFile, writeFile, rename } from 'node:fs/promises';
import { join } from 'node:path';

export interface DurableOutcome { ok: boolean; result?: unknown; error?: string }

interface ActiveOperation {
  fingerprint: string;
  promise: Promise<DurableOutcome>;
}

const MISMATCH_ERROR = 'Idempotency key was reused with different request parameters';
const UNCONFIRMED_ERROR = 'This operation was dispatched before the host restarted. Inspect the task to confirm its outcome; it will not be submitted twice.';
const LEGACY_ERROR = 'This operation was recorded by an older host that did not bind request parameters. Inspect the task to confirm its outcome; it will not be submitted twice.';

/** Fingerprint a JSON request independent of object insertion order. Arrays retain order. */
export function rpcPayloadFingerprint(payload: unknown): string {
  return createHash('sha256').update(canonicalJson(payload, new WeakSet<object>())).digest('hex');
}

/** Crash-safe at-most-once write dispatch. Unconfirmed operations require reconciliation, never blind replay. */
export class RpcLedger {
  private readonly active = new Map<string, ActiveOperation>();
  constructor(private readonly root: string) {}

  run(key: string, payload: unknown, operation: () => Promise<DurableOutcome>): Promise<DurableOutcome> {
    const fingerprint = rpcPayloadFingerprint(payload);
    const existing = this.active.get(key);
    if (existing) {
      if (existing.fingerprint !== fingerprint) return Promise.reject(new Error(MISMATCH_ERROR));
      return existing.promise;
    }
    const promise = this.execute(key, fingerprint, operation);
    this.active.set(key, { fingerprint, promise });
    void promise.finally(() => this.active.delete(key)).catch(() => {});
    return promise;
  }

  private async execute(key: string, fingerprint: string, operation: () => Promise<DurableOutcome>): Promise<DurableOutcome> {
    await mkdir(this.root, { recursive: true, mode: 0o700 });
    const path = join(this.root, createHash('sha256').update(key).digest('hex') + '.json');
    let prior: any;
    try { prior = JSON.parse(await readFile(path, 'utf8')); }
    catch (error) { if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw new Error('Cannot verify the prior operation; inspect this task before retrying'); }
    if (prior) {
      if (typeof prior.fingerprint !== 'string') return { ok: false, error: LEGACY_ERROR };
      if (prior.fingerprint !== fingerprint) throw new Error(MISMATCH_ERROR);
      if (prior.state === 'done') return prior.outcome;
      return { ok: false, error: UNCONFIRMED_ERROR };
    }
    await this.save(path, { version: 2, state: 'pending', fingerprint, createdAt: Date.now() });
    const outcome = await operation();
    await this.save(path, { version: 2, state: 'done', fingerprint, completedAt: Date.now(), outcome });
    return outcome;
  }
  private async save(path: string, value: unknown): Promise<void> {
    const temp = path + '.' + randomUUID() + '.tmp';
    await writeFile(temp, JSON.stringify(value), { mode: 0o600, flush: true });
    await rename(temp, path);
  }
}

function canonicalJson(value: unknown, ancestors: WeakSet<object>): string {
  if (value === null || typeof value === 'string' || typeof value === 'boolean') return JSON.stringify(value);
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) throw new Error('RPC payload contains a non-finite number');
    return JSON.stringify(value);
  }
  if (typeof value !== 'object') throw new Error('RPC payload contains a non-JSON value');
  if (ancestors.has(value)) throw new Error('RPC payload contains a cycle');
  ancestors.add(value);
  try {
    if (Array.isArray(value)) return `[${value.map((item) => canonicalJson(item, ancestors)).join(',')}]`;
    const object = value as Record<string, unknown>;
    return `{${Object.keys(object).sort().map((key) =>
      `${JSON.stringify(key)}:${canonicalJson(object[key], ancestors)}`).join(',')}}`;
  } finally {
    ancestors.delete(value);
  }
}
