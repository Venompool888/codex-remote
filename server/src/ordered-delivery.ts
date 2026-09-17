/** Bounds asynchronous projection work retained for one device connection. */
export class OrderedDelivery {
  private pending: Array<{ bytes: number; run: () => Promise<void> }> = [];
  private bytes = 0;
  private active = false;
  private stopped = false;

  constructor(private readonly failed: (overloaded: boolean) => void,
    private readonly maxItems = 1024, private readonly maxBytes = 64 * 1024 * 1024) {}

  enqueue(bytes: number, run: () => Promise<void>): void {
    if (this.stopped) return;
    if (this.pending.length + Number(this.active) >= this.maxItems ||
        !Number.isSafeInteger(bytes) || bytes < 0 || this.bytes + bytes > this.maxBytes) {
      this.stop();
      this.failed(true);
      return;
    }
    this.bytes += bytes;
    this.pending.push({ bytes, run });
    void this.drain();
  }

  stop(): void {
    this.stopped = true;
    for (const item of this.pending) this.bytes -= item.bytes;
    this.pending = [];
  }

  private async drain(): Promise<void> {
    if (this.active || this.stopped) return;
    this.active = true;
    try {
      while (!this.stopped && this.pending.length) {
        const item = this.pending.shift()!;
        try { await item.run(); }
        finally { this.bytes -= item.bytes; }
      }
    } catch {
      this.stop();
      this.failed(false);
    } finally { this.active = false; }
  }
}
