/** Literal replacement across arbitrary chunks; pending storage is bounded by prefix length. */
export class StreamingReplacement {
  private pending = '';
  constructor(private readonly prefix: string, private readonly replacement: string) {
    if (!prefix) throw new Error('Streaming prefix must not be empty');
  }
  push(chunk: string): string {
    const text = (this.pending + chunk).split(this.prefix).join(this.replacement);
    let held = Math.min(text.length, this.prefix.length - 1);
    while (held > 0 && !text.endsWith(this.prefix.slice(0, held))) held--;
    this.pending = held ? text.slice(-held) : '';
    return held ? text.slice(0, -held) : text;
  }
  finish(): string {
    // An interrupted private prefix must not be exposed at completion.
    const tail = this.pending.length > 1 ? '[incomplete attachment reference]' : this.pending;
    this.pending = '';
    return tail;
  }
}
