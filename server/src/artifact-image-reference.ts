import { createHash } from 'node:crypto';

// A selector, not an access credential. Download still requires a device-owned
// ArtifactStore snapshot ID issued from the authoritative thread's linked outputs.
export function artifactImageReference(source: string): string {
  return createHash('sha256').update(source).digest('hex');
}
export function rewriteArtifactImages(text: string): string {
  return text.replace(/!\[([^\]]*)\]\(<?([^\n]+?)>?\)/g, (whole, alt, source: string) => {
    if (/^[a-z][a-z0-9+.-]*:/i.test(source) && !source.startsWith('sandbox:')) return whole;
    return `![${alt}](remote-artifact-image://${artifactImageReference(source)})`;
  });
}

/** Hold image Markdown until its source can be replaced, across arbitrary chunks. */
export class StreamingArtifactImages {
  private pending = '';
  private discarded = false;
  push(chunk: string): string {
    let output = '';
    for (const char of chunk) {
      if (this.discarded) {
        if (char === '\n' || char === ')') { this.discarded = false; output += '[Image reference unavailable]' + (char === '\n' ? '\n' : ''); }
        continue;
      }
      if (!this.pending) { if (char === '!') this.pending = char; else output += char; continue; }
      if (this.pending === '!' && char !== '[') {
        output += '!'; this.pending = '';
        if (char === '!') this.pending = '!'; else output += char;
        continue;
      }
      this.pending += char;
      if (char === '\n') { output += '[Image reference unavailable]\n'; this.pending = ''; }
      else if (char === ')') { output += rewriteArtifactImages(this.pending); this.pending = ''; }
      else if (this.pending.length > 4096) { this.pending = ''; this.discarded = true; }
    }
    return output;
  }
  finish(): string {
    const result = this.discarded || this.pending.length > 1 ? '[Image reference unavailable]' : this.pending;
    this.pending = ''; this.discarded = false; return result;
  }
}
