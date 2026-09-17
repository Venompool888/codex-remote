/** Incremental counterpart of publicPayload's Bearer / sk- rules. Never buffers token bodies. */
export class StreamingSecrets {
  private pending = '';
  private previous = '';
  private mode: 'bearer' | 'key' | undefined;
  private bearerWaiting = false;

  push(chunk: string): string {
    let output = '';
    for (const character of chunk) {
      if (this.mode) {
        if (this.mode === 'bearer' && this.bearerWaiting && /\s/.test(character)) continue;
        const secretCharacter = this.mode === 'bearer' ? /[A-Za-z0-9._~+\/=\-]/ : /[A-Za-z0-9_\-]/;
        if (secretCharacter.test(character)) {
          this.previous = character;
          this.bearerWaiting = false;
          continue;
        }
        this.mode = undefined;
      }
      this.pending += character;
      while (this.pending) {
        const boundary = !/[A-Za-z0-9_]/.test(this.previous);
        if (boundary && /^bearer\s$/i.test(this.pending)) {
          output += 'Bearer [redacted]';
          this.previous = ' ';
          this.pending = '';
          this.mode = 'bearer';
          this.bearerWaiting = true;
          break;
        }
        if (boundary && this.pending === 'sk-') {
          output += '[redacted]';
          this.previous = '-';
          this.pending = '';
          this.mode = 'key';
          break;
        }
        if (boundary && ('bearer'.startsWith(this.pending.toLowerCase()) || 'sk-'.startsWith(this.pending))) break;
        output += this.pending[0];
        this.previous = this.pending[0];
        this.pending = this.pending.slice(1);
      }
    }
    return output;
  }
  finish(): string {
    const result = this.pending;
    this.pending = '';
    this.previous = '';
    this.mode = undefined;
    this.bearerWaiting = false;
    return result;
  }
}
