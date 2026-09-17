/** Shared, bounded budget: spoofed forwarding headers cannot create unlimited buckets. */
export class RequestBudget {
  private remaining: number;
  private resetAt = 0;
  constructor(private readonly capacity: number, private readonly windowMs = 60_000, private readonly now = Date.now) {
    this.remaining = capacity;
  }
  take(): boolean {
    const time = this.now();
    if (time >= this.resetAt) { this.remaining = this.capacity; this.resetAt = time + this.windowMs; }
    if (this.remaining <= 0) return false;
    this.remaining--;
    return true;
  }
}
