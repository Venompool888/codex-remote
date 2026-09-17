import { redactPrivatePaths } from './streaming-private-paths.js';
import { rewriteArtifactImages } from './artifact-image-reference.js';
const TEXT_FIELDS = new Set(['text', 'delta', 'message', 'error', 'command', 'aggregatedOutput', 'output', 'reason', 'title', 'preview', 'description', 'arguments', 'source', 'detail', 'summary']);
const SECRET_FIELD = /^(?:authorization|access[_-]?token|refresh[_-]?token|api[_-]?key|client[_-]?secret|password)$/i;
// Artifact-less legacy hosts still route generated images by path. Their extra
// service-root filtering stays diagnostic-only; modern hosts filter all text.
const DIAGNOSTIC_FIELDS = new Set(['message', 'error', 'command', 'aggregatedOutput', 'output', 'reason', 'arguments', 'source', 'detail', 'summary']);

/** Keep routing identifiers (cwd / legacy skill paths) intact; redact human-facing content and credentials. */
export function publicPayload(value: unknown, field = '', artifactImages = false, restrictedPaths = artifactImages): unknown {
  if (SECRET_FIELD.test(field)) return '[redacted]';
  if (typeof value === 'string') {
    let text = (artifactImages && TEXT_FIELDS.has(field) ? rewriteArtifactImages(value) : value).replace(/\bBearer\s+[A-Za-z0-9._~+\/-]+=*/gi, 'Bearer [redacted]')
      .replace(/\bsk-[A-Za-z0-9_-]{12,}/g, '[redacted]');
    if (TEXT_FIELDS.has(field)) {
      text = text.replace(/(?<!!)\[([^\]]+)\]\(<?(?:sandbox:)?\/[^\n]+?>?\)/g, '[$1](remote-artifact://available)')
        .replace(/:{1,2}codex-file-citation\{[^}]*\}/g, '[View artifact](remote-artifact://available)');
      text = restrictedPaths || DIAGNOSTIC_FIELDS.has(field) ? redactPrivatePaths(text) : text.replace(/\/(?:Users\/[^/\s]+|home\/[^/\s]+|root)(?:\/[^\s"'`<>{}\],;)]+)*/g, '[private host path]');
    }
    return text;
  }
  if (Array.isArray(value)) return value.map((item) => publicPayload(item, field, artifactImages, restrictedPaths));
  if (value && typeof value === 'object') {
    // User prose may quote image Markdown as instructions; do not replace that
    // text with an implementation selector intended for rendered agent images.
    const rewriteImages = artifactImages && (value as { type?: string }).type !== 'userMessage';
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, publicPayload(item, key, rewriteImages, restrictedPaths)]));
  }
  return value;
}
