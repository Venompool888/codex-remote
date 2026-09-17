import { createHash } from "node:crypto";
import { access } from "node:fs/promises";

interface Caller { call(method: string, params: unknown): Promise<unknown> }
type ObjectValue = Record<string, any>;
export interface CatalogEntry {
  id: string; kind: "skill" | "plugin"; name: string; description: string;
  source: string; state: "enabled" | "disabled" | "unavailable" | "authorization_required" | "missing_dependency";
  detail: string;
}
interface PrivateEntry extends CatalogEntry { path: string }

/** Only this host translates catalog IDs to App Server skill/mention paths. */
export class CapabilityCatalog {
  private cache = new Map<string, { at: number; entries: PrivateEntry[]; errors: string[] }>();
  private generation = 0;
  private nextLoad = 0;
  private latestLoads = new Map<string, number>();
  constructor(private readonly codex: Caller) {}
  invalidate(): void { this.generation++; this.cache.clear(); this.latestLoads.clear(); }

  async list(cwd: string, refresh = false): Promise<{ entries: CatalogEntry[]; errors: string[]; revision: string }> {
    const value = await this.load(cwd, refresh);
    const entries = value.entries.map(({ path: _path, ...entry }) => entry);
    return { entries, errors: value.errors, revision: hash(JSON.stringify(entries)) };
  }

  async resolve(cwd: string, input: unknown[]): Promise<unknown[]> {
    if (!input.some((item) => object(item)?.type === "remoteCapability" || ["skill", "mention"].includes(object(item)?.type))) return input;
    // Always reload before dispatch. A UI cache is never authority for a runnable capability.
    const { entries } = await this.load(cwd, true);
    return input.map((item) => {
      const value = object(item);
      if (!value || !["remoteCapability", "skill", "mention"].includes(value.type)) return item;
      const selected = entries.find((entry) => value.type === "remoteCapability" ? entry.id === value.capabilityId :
        entry.path === value.path && (entry.kind === "skill" ? "skill" : "mention") === value.type);
      if (!selected) throw new Error("Selected capability no longer exists in this working directory. Refresh the catalog.");
      if (selected.state !== "enabled") throw new Error(`Selected capability is ${selected.state}. ${selected.detail}`);
      return { type: selected.kind === "skill" ? "skill" : "mention", name: selected.name, path: selected.path };
    });
  }

  private async load(cwd: string, refresh: boolean) {
    if (!cwd || !cwd.startsWith("/")) throw new Error("An absolute working directory is required for capability discovery");
    const cached = this.cache.get(cwd);
    if (!refresh && cached && Date.now() - cached.at < 30_000) return cached;
    const generation = this.generation;
    const loadId = ++this.nextLoad;
    this.latestLoads.set(cwd, loadId);
    const entries: PrivateEntry[] = [];
    const errors: string[] = [];
    const [skills, plugins, runtime] = await Promise.allSettled([
      this.codex.call("skills/list", { cwds: [cwd], forceReload: refresh }),
      this.codex.call("plugin/list", { cwds: [cwd], forceRemoteSync: refresh }),
      this.codex.call("mcpServerStatus/list", { limit: 100 }),
    ]);
    const servers = runtime.status === "fulfilled" ? array(object(runtime.value)?.data) : null;
    function dependencyState(names: string[]): CatalogEntry["state"] {
      if (!names.length) return "enabled";
      if (!servers) return "unavailable";
      if (names.some((name) => !servers.some((server) => server.name === name))) return "missing_dependency";
      if (names.some((name) => servers.some((server) => server.name === name && server.authStatus === "notLoggedIn"))) return "authorization_required";
      if (names.some((name) => servers.some((server) => server.name === name && !Object.keys(server.tools || {}).length))) return "unavailable";
      return "enabled";
    }
    if (skills.status === "fulfilled") {
      for (const group of array(object(skills.value)?.data)) for (const skill of array(group.skills)) {
        if (typeof skill.path !== "string" || typeof skill.name !== "string") continue;
        const exists = await access(skill.path).then(() => true, () => false);
        const dependencies = array(skill.dependencies?.tools).filter((tool) => tool.type === "mcp").map((tool) => String(tool.value));
        const state = !exists ? "unavailable" : skill.enabled === false ? "disabled" : dependencyState(dependencies);
        entries.push({ id: hash(`${cwd}\0skill\0${skill.path}`), kind: "skill", path: skill.path, name: skill.name,
          description: skill.interface?.shortDescription || skill.shortDescription || skill.description || "",
          source: `${String(skill.scope || "host")} · ${skill.path.includes("/.agents/") ? "agents skills" : "Codex skills"}`, state,
          detail: state === "enabled" ? "Available on this host" : "Enable or repair this skill on the host" });
      }
    } else errors.push("Skill discovery is unavailable on this host");
    if (plugins.status === "fulfilled") {
      const installed = array(object(plugins.value)?.marketplaces).flatMap((marketplace) =>
        array(marketplace.plugins).filter((plugin) => plugin.installed).map((plugin) => ({ marketplace, plugin })));
      const pluginEntries = await mapConcurrent(installed, 8, async ({ marketplace, plugin }): Promise<PrivateEntry> => {
        // A mention identifies the installed plugin, not its package directory.
        // Remote/git/npm sources need not expose a local materialization path.
        const pluginId = typeof plugin.id === "string" ? plugin.id : "";
        const path = pluginId ? `plugin://${pluginId}` : "";
        const localPath = plugin.source?.type === "local" ? plugin.source.path : null;
        const exists = !!path && (localPath === null || (typeof localPath === "string" &&
          await access(localPath).then(() => true, () => false)));
        let state: CatalogEntry["state"] = plugin.enabled === false || plugin.availability === "DISABLED_BY_ADMIN" ? "disabled" : !exists ? "unavailable" : "enabled";
        let discoveryDetail: string | undefined;
        if (state === "enabled") {
          try {
            const detail = object(await this.codex.call("plugin/read", { marketplacePath: marketplace.path ?? null,
              ...(marketplace.path ? {} : { remoteMarketplaceName: marketplace.name }), pluginName: plugin.name }));
            const names = detail?.plugin?.mcpServers;
            if (Array.isArray(names)) state = dependencyState(names.filter((name: unknown): name is string => typeof name === "string"));
          } catch (error) {
            const message = error instanceof Error ? error.message : "";
            if (/\(-32601\)$/.test(message)) {
              discoveryDetail = "This older host cannot verify plugin dependencies; authorization may be requested when called";
            } else {
              state = /\((401|403)\)$|unauthorized|forbidden|not logged in|authentication required|authorization required/i.test(message)
                ? "authorization_required" : "unavailable";
              discoveryDetail = state === "authorization_required"
                ? "Authorize this plugin on the host, then refresh the catalog"
                : "Plugin details could not be verified; refresh or repair the plugin on the host";
            }
          }
        }
        return { id: hash(`${cwd}\0plugin\0${plugin.id || path}`), kind: "plugin", path,
          name: plugin.interface?.displayName || plugin.name || "Plugin",
          description: plugin.interface?.shortDescription || plugin.interface?.description || "",
          source: plugin.source?.type === "remote" ? "Connected remote plugin" : "Installed on host", state,
          detail: discoveryDetail ?? (state === "enabled" ? "Selected plugin may request host-side authorization when called" :
            "Enable, authenticate or repair this plugin on the host; phone installation is not supported") };
      });
      entries.push(...pluginEntries);
    } else errors.push("Plugin discovery is unavailable on this host");
    if (generation !== this.generation || this.latestLoads.get(cwd) !== loadId) {
      throw new Error("Capabilities changed while loading. Refresh the catalog and try again.");
    }
    const result = { at: Date.now(), entries, errors };
    this.cache.set(cwd, result);
    return result;
  }
}
function hash(value: string): string { return createHash("sha256").update(value).digest("hex"); }
function object(value: unknown): ObjectValue | null { return value && typeof value === "object" && !Array.isArray(value) ? value as ObjectValue : null; }
function array(value: unknown): ObjectValue[] { return Array.isArray(value) ? value.map(object).filter((v): v is ObjectValue => !!v) : []; }

/** Bound remote lookups while keeping the catalog stable regardless of completion order. */
async function mapConcurrent<T, R>(values: T[], limit: number, transform: (value: T) => Promise<R>): Promise<R[]> {
  const results = new Array<R>(values.length);
  let next = 0;
  await Promise.all(Array.from({ length: Math.min(limit, values.length) }, async () => {
    while (next < values.length) {
      const index = next++;
      results[index] = await transform(values[index]);
    }
  }));
  return results;
}
