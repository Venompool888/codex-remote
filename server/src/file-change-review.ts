import { isAbsolute, relative, sep } from "node:path";

type Value = Record<string, any>;
const object = (value: unknown): Value | null => value && typeof value === "object" && !Array.isArray(value) ? value as Value : null;

/** Resolve an approval only against its own canonical file-change item, never a nearby tool call. */
export function fileChangeReview(params: unknown, events: readonly unknown[], cwd?: string): Value {
  const request = object(params);
  const missing = (message: string) => ({ status: "unavailable", message });
  if (!request || ![request.threadId, request.turnId, request.itemId].every(v => typeof v === "string" && v.length > 0))
    return missing("File review identifiers are missing. Review this request on the host.");
  let item: Value | null = null;
  for (let i = events.length - 1; i >= 0; i--) {
    const event = object(events[i]);
    const p = object(event?.params);
    if (event?.method === "item/started" && p?.threadId === request.threadId && p?.turnId === request.turnId &&
      p?.item?.id === request.itemId && p?.item?.type === "fileChange") { item = p.item; break; }
  }
  if (!item || !Array.isArray(item.changes) || !item.changes.length)
    return missing("The host did not provide the pending file patch. Review this request on the host.");
  if (!cwd || !isAbsolute(cwd)) return missing("The host project location is unavailable. Review this request on the host.");
  const displayPath = (path: unknown): string | null => {
    if (typeof path !== "string" || !path || /[\r\n\0]/.test(path)) return null;
    const name = relative(cwd, isAbsolute(path) ? path : `${cwd}${sep}${path}`);
    return name && name !== ".." && !name.startsWith(`..${sep}`) && !isAbsolute(name) ? name : null;
  };
  const changes: Value[] = [];
  for (const raw of item.changes) {
    const c = object(raw);
    const path = displayPath(c?.path);
    const kind = c?.kind?.type;
    const movePath = c?.kind?.move_path == null ? null : displayPath(c.kind.move_path);
    if (!path || !["add", "update", "delete"].includes(kind) || typeof c?.diff !== "string" || !c.diff.trim() ||
      (c?.kind?.move_path != null && !movePath))
      return missing("The complete change cannot be reviewed within this project. Review it on the host.");
    // App Server appends a display-only rename footer to the unified diff.
    // Normalize only that exact trailing footer; never rewrite patch content lines.
    let diff = c.diff;
    if (movePath) {
      const footer = `\nMoved to: ${c.kind.move_path}`;
      const index = diff.lastIndexOf(footer);
      if (index >= 0 && !diff.slice(index + footer.length).trim()) {
        diff = `${diff.slice(0, index)}\nMoved to: ${movePath}${diff.slice(index + footer.length)}`;
      }
    }
    changes.push({ path, kind, ...(movePath ? { movePath } : {}), diff });
  }
  if (Buffer.byteLength(JSON.stringify(changes)) > 128 * 1024)
    return missing("The pending patch is too large for phone approval. Review it on the host.");
  return { status: "available", changes };
}
