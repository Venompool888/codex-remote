import { readFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import { request } from "node:http";

export interface ChatGptTokens {
  accessToken: string;
  chatgptAccountId: string;
  chatgptPlanType?: string;
}
export interface AuthRefresh {
  previousAccountId?: string;
  rejectedTokenHash?: string;
}
export type TokenProvider = (refresh?: AuthRefresh) => Promise<ChatGptTokens>;

export const tokenHash = (token: string): string => createHash("sha256").update(token).digest("hex");

export async function readCliAuth(path: string): Promise<ChatGptTokens & { expiresAt: number }> {
  const data = JSON.parse(await readFile(path, "utf8"));
  const accessToken = data.tokens?.access_token;
  const chatgptAccountId = data.tokens?.account_id;
  if (data.auth_mode !== "chatgpt" || typeof accessToken !== "string" || !accessToken ||
      typeof chatgptAccountId !== "string" || !chatgptAccountId) throw new Error("CLI ChatGPT login is unavailable");
  const claims = JSON.parse(Buffer.from(accessToken.split(".")[1] ?? "", "base64url").toString());
  if (typeof claims.exp !== "number") throw new Error("CLI token expiry is unavailable");
  const plan = claims["https://api.openai.com/auth"]?.chatgpt_plan_type;
  return { accessToken, chatgptAccountId, expiresAt: claims.exp * 1000,
    ...(typeof plan === "string" ? { chatgptPlanType: plan } : {}) };
}

/** Read the CLI file every time, including after atomic replacement by another CLI. */
export function cliTokenProvider(path: string, refreshCli: () => Promise<void>): TokenProvider {
  let inFlight: Promise<void> | undefined;
  return async (refresh) => {
    let tokens = await readCliAuth(path);
    const checkAccount = () => {
      if (refresh?.previousAccountId && refresh.previousAccountId !== tokens.chatgptAccountId)
        throw new Error("CLI account changed; restart Remote to use the new account");
    };
    checkAccount();
    if (tokens.expiresAt < Date.now() + 60_000 ||
        (refresh?.rejectedTokenHash && refresh.rejectedTokenHash === tokenHash(tokens.accessToken))) {
      inFlight ??= refreshCli().finally(() => { inFlight = undefined; });
      await inFlight;
      tokens = await readCliAuth(path);
      checkAccount();
      if (tokens.expiresAt <= Date.now() ||
          (refresh?.rejectedTokenHash && refresh.rejectedTokenHash === tokenHash(tokens.accessToken)))
        throw new Error("CLI login could not supply a fresh token");
    }
    const { expiresAt: _, ...external } = tokens;
    return external; // Never return the CLI refresh token or other auth-file contents.
  };
}

export function socketTokenProvider(socketPath: string): TokenProvider {
  return (refresh) => new Promise((resolve, reject) => {
    const req = request({ socketPath, path: "/token", method: "POST", headers: { "content-type": "application/json" } }, (res) => {
      let body = "";
      res.setEncoding("utf8");
      res.on("data", (part: string) => {
        body += part;
        if (body.length > 32_768) req.destroy(new Error("CLI auth response too large"));
      });
      res.on("end", () => {
        if (res.statusCode !== 200) return reject(new Error("CLI authentication unavailable; check the host CLI login"));
        try {
          const value = JSON.parse(body);
          if (typeof value.accessToken !== "string" || !value.accessToken ||
              typeof value.chatgptAccountId !== "string" || !value.chatgptAccountId) throw new Error();
          resolve({ accessToken: value.accessToken, chatgptAccountId: value.chatgptAccountId,
            ...(typeof value.chatgptPlanType === "string" ? { chatgptPlanType: value.chatgptPlanType } : {}) });
        } catch { reject(new Error("Invalid CLI authentication response")); }
      });
      res.on("error", () => reject(new Error("CLI authentication response interrupted")));
    });
    const timer = setTimeout(() => req.destroy(new Error("CLI authentication timed out")), 8_000);
    req.on("close", () => clearTimeout(timer));
    req.on("error", () => reject(new Error("CLI authentication bridge unavailable")));
    req.end(JSON.stringify(refresh ?? {}));
  });
}
