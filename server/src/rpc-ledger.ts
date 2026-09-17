import { createHash, randomUUID } from 'node:crypto';
import { mkdir, readFile, writeFile, rename } from 'node:fs/promises';
import { join } from 'node:path';

export interface DurableOutcome { ok: boolean; result?: unknown; error?: string }
/** Crash-safe at-most-once write dispatch. Unconfirmed operations require reconciliation, never blind replay. */
export class RpcLedger {
  private readonly active = new Map<string, Promise<DurableOutcome>>();
  constructor(private readonly root: string) {}
  run(key: string, operation: () => Promise<DurableOutcome>): Promise<DurableOutcome> {
    const existing = this.active.get(key);
    if (existing) return existing;
    const promise = this.execute(key, operation);
    this.active.set(key, promise);
    void promise.finally(() => this.active.delete(key)).catch(() => {});
    return promise;
  }
  private async execute(key: string, operation: () => Promise<DurableOutcome>): Promise<DurableOutcome> {
    await mkdir(this.root, { recursive: true, mode: 0o700 });
    const path = join(this.root, createHash('sha256').update(key).digest('hex') + '.json');
    let prior: any;
    try { prior = JSON.parse(await readFile(path, 'utf8')); }
    catch (error) { if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw new Error('Cannot verify the prior operation; inspect this task before retrying'); }
    if (prior) {
      if (prior.state === 'done') return prior.outcome;
      return { ok: false, error: 'This operation was dispatched before the host restarted. Inspect the task to confirm its outcome; it will not be submitted twice.' };
    }
    await this.save(path, { state: 'pending', createdAt: Date.now() });
    const outcome = await operation();
    await this.save(path, { state: 'done', completedAt: Date.now(), outcome });
    return outcome;
  }
  private async save(path: string, value: unknown): Promise<void> {
    const temp = path + '.' + randomUUID() + '.tmp';
    await writeFile(temp, JSON.stringify(value), { mode: 0o600, flush: true });
    await rename(temp, path);
  }
}
