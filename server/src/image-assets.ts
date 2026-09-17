import { mkdir, readFile, realpath, stat, writeFile } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { homedir, tmpdir } from "node:os";
import { isAbsolute, relative, resolve } from "node:path";

const MAX_IMAGE_BYTES = 12 * 1024 * 1024;

export interface ImageAsset {
  path: string;
  mimeType: string;
  data: string;
}

export async function readImageAsset(
  requestedPath: unknown,
  workspace?: string,
  imageRoot?: string,
  generatedImageRoot = resolve(homedir(), ".codex", "generated_images"),
): Promise<ImageAsset> {
  if (typeof requestedPath !== "string" || !isAbsolute(requestedPath)) {
    throw new Error("An absolute image path is required");
  }

  const resolvedPath = await realpath(requestedPath);
  const roots = await Promise.all(
    [workspace, imageRoot, generatedImageRoot, tmpdir(), "/tmp"].filter((value): value is string => Boolean(value)).map(async (value) => {
      try { return await realpath(resolve(value)); } catch { return resolve(value); }
    }),
  );
  if (!roots.some((root) => isInside(root, resolvedPath))) {
    throw new Error("Image is outside the workspace and temporary directories");
  }

  const metadata = await stat(resolvedPath);
  if (!metadata.isFile()) throw new Error("Image path is not a file");
  if (metadata.size > MAX_IMAGE_BYTES) throw new Error("Image exceeds the 12 MB limit");

  const bytes = await readFile(resolvedPath);
  const mimeType = detectImageMime(bytes);
  if (!mimeType) throw new Error("Only PNG, JPEG, GIF and WebP images are supported");
  return { path: resolvedPath, mimeType, data: bytes.toString("base64") };
}

export async function uploadImageAsset(data: unknown, name: unknown, imageRoot: string): Promise<ImageAsset> {
  if (typeof data !== "string" || data.length > MAX_IMAGE_BYTES * 1.5) throw new Error("Image data is missing or too large");
  const bytes = Buffer.from(data, "base64");
  if (bytes.length === 0 || bytes.length > MAX_IMAGE_BYTES) throw new Error("Image exceeds the 12 MB limit");
  const mimeType = detectImageMime(bytes);
  if (!mimeType) throw new Error("Only PNG, JPEG, GIF and WebP images are supported");
  const extension = mimeType === "image/jpeg" ? "jpg" : mimeType.substring("image/".length);
  const safeName = typeof name === "string" ? name.replace(/[^a-zA-Z0-9._-]/g, "-").slice(-80) : "image";
  await mkdir(imageRoot, { recursive: true, mode: 0o700 });
  const path = resolve(imageRoot, `${randomUUID()}-${safeName.replace(/\.[^.]+$/, "")}.${extension}`);
  await writeFile(path, bytes, { mode: 0o600 });
  return { path, mimeType, data: bytes.toString("base64") };
}

function isInside(root: string, target: string): boolean {
  const value = relative(root, target);
  return value === "" || (!value.startsWith("..") && !isAbsolute(value));
}

function detectImageMime(bytes: Buffer): string | null {
  if (bytes.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) return "image/png";
  if (bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return "image/jpeg";
  if (bytes.subarray(0, 6).toString("ascii") === "GIF87a" || bytes.subarray(0, 6).toString("ascii") === "GIF89a") return "image/gif";
  if (bytes.subarray(0, 4).toString("ascii") === "RIFF" && bytes.subarray(8, 12).toString("ascii") === "WEBP") return "image/webp";
  return null;
}
