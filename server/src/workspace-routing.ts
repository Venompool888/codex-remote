import { isAbsolute } from 'node:path';
import { RoutingReferences } from './routing-references.js';
import { validateInteractionReply } from './interaction-validation.js';
import { publicPayload } from './public-payload.js';
import { validateWorkspacePaths } from './workspaces.js';
import { redactPrivatePaths } from './streaming-private-paths.js';

// Only typed path slots are references. User text and arbitrary MCP form values
// are not file-system instructions and must not be resolved as such.
const PATH_FIELDS = new Set(['path', 'savedPath', 'saved_path', 'movePath', 'move_path', 'grantRoot']);

/** Per-device wire projection; internal Codex results/journal/ledger keep canonical cwd. */
export class WorkspaceRouting {
  constructor(private readonly references: RoutingReferences, private readonly device: string) {}

  async migrate(paths: unknown): Promise<unknown> {
    if (!Array.isArray(paths) || paths.length > 100 || paths.some(path =>
      typeof path !== 'string' || !isAbsolute(path) || path.length > 4096 || /[\0\r\n]/.test(path))) {
      throw new Error('Invalid workspace migration input');
    }
    const validation = await validateWorkspacePaths(paths);
    const byPath = new Map(validation.map(entry => [entry.path, entry]));
    return Promise.all(paths.map(async (path, index) => {
      const entry = byPath.get(path)!;
      if (!entry.available) return {index, available: false, reason: entry.reason};
      const ref = await this.references.issue(this.device, 'workspace', path);
      return {index, available: true, cwd: ref.reference, cwdName: ref.name};
    }));
  }

  async outbound(value: unknown, workspacePaths = false): Promise<unknown> {
    // Unknown/free-text fields must not bypass the private-root filter just
    // because an upstream version introduced a new field name. Typed paths
    // below are projected before recursion, preserving their routing semantics.
    if (typeof value === 'string') return redactPrivatePaths(value);
    if (Array.isArray(value)) return Promise.all(value.map(item => this.outbound(item, workspacePaths)));
    if (!value || typeof value !== 'object') return value;
    const result: Record<string, unknown> = {};
    for (const [key, item] of Object.entries(value)) {
      if (key === 'permissions' || key === 'additionalPermissions') {
        const names: Record<string, string> = {};
        result[key] = await this.permissionProjection(item, names);
        result[`${key}PathNames`] = names;
      } else if (key === 'permissionsPathNames' || key === 'additionalPermissionsPathNames') {
        // Display metadata is generated from canonical grants, never supplied upstream.
        continue;
      } else if ((key === 'cwd' || PATH_FIELDS.has(key)) && typeof item === 'string' && isAbsolute(item)) {
        const kind = key === 'cwd' || (workspacePaths && key === 'path') ? 'workspace' : 'path';
        const reference = await this.references.issue(this.device, kind, item);
        result[key] = reference.reference;
        result[`${key}Name`] = reference.name;
      } else if (!(key === 'cwdName' || [...PATH_FIELDS].some(field => key === `${field}Name`)) || result[key] === undefined) {
        const safeKey = redactPrivatePaths(key);
        // Redacted object keys can collide; reject the response instead of
        // silently combining unrelated tool results.
        if (Object.hasOwn(result, safeKey)) throw new Error('Private result keys cannot be displayed safely');
        result[safeKey] = await this.outbound(item, workspacePaths);
      }
    }
    return result;
  }

  private async permissionProjection(value: unknown, names: Record<string, string>): Promise<unknown> {
    if (typeof value === 'string' && isAbsolute(value)) {
      const ref = await this.references.issue(this.device, 'path', value);
      names[ref.reference] = ref.name;
      return ref.reference;
    }
    if (Array.isArray(value)) {
      const result = [];
      for (const item of value) result.push(await this.permissionProjection(item, names));
      return result;
    }
    if (value && typeof value === 'object') {
      const entries: [string, unknown][] = [];
      for (const [key, item] of Object.entries(value)) {
        const projectedKey = await this.permissionProjection(key, names) as string;
        entries.push([projectedKey, await this.permissionProjection(item, names)]);
      }
      return Object.fromEntries(entries);
    }
    return value;
  }

  /** A reply can approve exactly this pending request or deny it. Never resolve
   * client-supplied permission paths into a new grant, even if their IDs exist. */
  async permissionReply(params: unknown, result: unknown): Promise<unknown> {
    const visible = await this.outbound(publicPayload(params));
    validateInteractionReply('item/permissions/requestApproval', visible, result);
    const reply = result as {permissions: Record<string, unknown>; scope: string};
    return {permissions: Object.keys(reply.permissions).length
      ? (params as {permissions: unknown}).permissions : {}, scope: 'turn'};
  }

  /** Only structured Skill input may resolve a legacy discovery path reference.
   * Catalog IDs still go through the catalog's fresh availability check. */
  async skillInputs(input: unknown[]): Promise<unknown[]> {
    return Promise.all(input.map(async item => {
      if (!item || typeof item !== 'object' || Array.isArray(item) || (item as any).type !== 'skill') return item;
      const value = item as Record<string, unknown>;
      if (typeof value.path !== 'string') throw new Error('Invalid Skill reference');
      return {...value, path: await this.references.resolve(this.device, 'path', value.path)};
    }));
  }

  async inbound(value: unknown, workspacePaths = false): Promise<unknown> {
    if (Array.isArray(value)) return Promise.all(value.map(item => this.inbound(item, workspacePaths)));
    if (!value || typeof value !== 'object') return value;
    const result: Record<string, unknown> = {};
    for (const [key, item] of Object.entries(value)) {
      if (key === 'cwd' && item !== null && item !== undefined && item !== '') {
        if (typeof item !== 'string') throw new Error('Invalid workspace reference');
        result[key] = await this.references.resolve(this.device, 'workspace', item);
      } else if (key === 'cwds' || (workspacePaths && key === 'paths')) {
        if (!Array.isArray(item) || item.some(entry => typeof entry !== 'string')) throw new Error('Invalid workspace references');
        result[key] = await Promise.all(item.map(entry => this.references.resolve(this.device, 'workspace', entry)));
      } else result[key] = await this.inbound(item, workspacePaths);
    }
    return result;
  }
}
