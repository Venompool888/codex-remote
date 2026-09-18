/** The local marker is a UI selection, never an app-server permission profile. */
export const CUSTOM_CONFIG_PERMISSION = "local:config";

type CodexCaller = { call(method: string, params: unknown): Promise<unknown> };

function object(value: unknown): Record<string, unknown> | null {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : null;
}

export async function resolvePermissionSelection(
  method: string, params: Record<string, unknown>, codex: CodexCaller,
): Promise<Record<string, unknown>> {
  if (params.permissions !== CUSTOM_CONFIG_PERMISSION) return params;
  const result = { ...params };
  delete result.permissions;
  delete result.approvalPolicy;
  delete result.approvalsReviewer;
  delete result.sandbox;
  delete result.sandboxPolicy;
  if (method === "thread/start") return result; // App-server loads the effective config for cwd itself.
  if (method !== "turn/start") throw new Error("Custom permissions are only supported when starting a task or turn");

  const thread = object(await codex.call("thread/read", { threadId: params.threadId, includeTurns: false }));
  const cwd = object(thread?.thread)?.cwd;
  if (typeof cwd !== "string" || !cwd) throw new Error("Could not resolve the task working directory for custom permissions");
  // Let Codex resolve all config layers and default_permissions itself. The probe
  // is ephemeral, starts no turn, and its settings never leave the host.
  const probe = object(await codex.call("thread/start", { cwd, ephemeral: true }));
  try {
    const profileId = object(probe?.activePermissionProfile)?.id;
    if (typeof profileId === "string" && profileId) {
      if (probe?.approvalPolicy == null || probe?.approvalsReviewer == null)
        throw new Error("Could not resolve custom approval settings from the host");
      result.permissions = profileId;
      result.approvalPolicy = probe.approvalPolicy;
      result.approvalsReviewer = probe.approvalsReviewer;
      return result;
    }
    const sandbox = object(probe?.sandbox);
    if (!sandbox || probe?.approvalPolicy == null || probe?.approvalsReviewer == null)
      throw new Error("Could not resolve custom permissions from the host");
    result.sandboxPolicy = sandbox;
    result.approvalPolicy = probe.approvalPolicy;
    result.approvalsReviewer = probe.approvalsReviewer;
  } finally {
    const probeId = object(probe?.thread)?.id;
    if (typeof probeId === "string") await codex.call("thread/unsubscribe", { threadId: probeId }).catch(() => undefined);
  }
  return result;
}
