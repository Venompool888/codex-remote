// Known private service/home roots. Routing fields are handled separately by the
// protocol; this transform is only for human-facing text after image rewriting.
const ROOTS = ['/Users/', '/home/', '/root/', '/var/lib/', '/var/log/', '/var/cache/', '/opt/', '/srv/', '/etc/', '/private/var/', '/tmp/', '/var/tmp/'];
const END = /[\s"'`<>{}\],;)]/;

/** Bounded prefix lookbehind; never stores the private path body. */
export class StreamingPrivatePaths {
  private pending = '';
  private previous = '';
  private hiding = false;
  private quote = '';
  private escaped = false;
  push(chunk: string): string {
    let output = '';
    for (const character of chunk) {
      if (this.hiding) {
        if (this.quote) {
          if (this.escaped) { this.escaped = false; continue; }
          if (character === '\\') { this.escaped = true; continue; }
          if (character !== this.quote && character !== '\n') continue;
        } else if (!END.test(character)) continue;
        this.hiding = false; this.quote = '';
      }
      this.pending += character;
      while (this.pending) {
        if (ROOTS.includes(this.pending)) {
          output += '[private host path]';
          this.quote = /["'`]/.test(this.previous) ? this.previous : '';
          this.pending = ''; this.hiding = true; break;
        }
        if (ROOTS.some(root => root.startsWith(this.pending))) break;
        output += this.pending[0]; this.previous = this.pending[0];
        this.pending = this.pending.slice(1);
      }
    }
    return output;
  }
  finish(): string {
    const result = this.pending.length > 1 ? '[private host path]' : this.pending;
    this.pending = ''; this.previous = ''; this.hiding = false; this.quote = ''; this.escaped = false;
    return result;
  }
}
export function redactPrivatePaths(text: string): string {
  const stream = new StreamingPrivatePaths();
  return stream.push(text) + stream.finish();
}
