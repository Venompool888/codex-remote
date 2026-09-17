import { chmod, mkdir, readFile, rename, unlink, writeFile } from "node:fs/promises";
import { dirname } from "node:path";
import { randomUUID } from "node:crypto";

export interface CliConfig { publicUrl?: string }

export function validatePublicUrl(value: string): string {
  try {
    const input = value.trim();
    const url = new URL(input);
    if (!/^https?:$/.test(url.protocol) || !url.hostname || url.username || url.password ||
        url.search || url.hash || /[\s\\]/.test(input)) throw new Error();
    return url.toString().replace(/\/+$/, "");
  } catch {
    throw new Error("请输入完整 HTTP/HTTPS 服务地址，可包含端口和根路径，不可包含账号、查询参数或片段。");
  }
}

export async function readCliConfig(path: string): Promise<CliConfig> {
  let raw: string;
  try { raw = await readFile(path, "utf8"); }
  catch (error) { if ((error as NodeJS.ErrnoException).code === "ENOENT") return {}; throw new Error("无法读取 CLI 配置文件。"); }
  try {
    const value = JSON.parse(raw);
    if (!value || typeof value !== "object" || Array.isArray(value) ||
        Object.keys(value).some((key) => key !== "publicUrl")) throw new Error();
    if (value.publicUrl === undefined) return {};
    if (typeof value.publicUrl !== "string") throw new Error();
    return { publicUrl: validatePublicUrl(value.publicUrl) };
  } catch { throw new Error("CLI 配置无效；请修正 JSON 的 publicUrl，或运行 codexremote config reset。"); }
}

export async function writeCliConfig(path: string, config: CliConfig): Promise<void> {
  const value = config.publicUrl === undefined ? {} : { publicUrl: validatePublicUrl(config.publicUrl) };
  await mkdir(dirname(path), { recursive: true, mode: 0o700 });
  const temporary = path + "." + randomUUID() + ".tmp";
  try {
    await writeFile(temporary, JSON.stringify(value, null, 2) + "\n", { mode: 0o600, flag: "wx" });
    await chmod(temporary, 0o600);
    await rename(temporary, path);
  } finally { await unlink(temporary).catch(() => undefined); }
}

export function pairingPublicUrl(config: CliConfig, environment: string | undefined, hostUrl: string): string {
  return validatePublicUrl(config.publicUrl ?? environment ?? hostUrl);
}
