import assert from "node:assert/strict";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { readImageAsset, uploadImageAsset } from "../src/image-assets.js";

test("reads a supported image from an allowed workspace", async () => {
  const directory = await mkdtemp(join(tmpdir(), "codex-remote-image-"));
  try {
    const path = join(directory, "sample.png");
    const png = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3]);
    await writeFile(path, png);
    const result = await readImageAsset(path, directory);
    assert.equal(result.mimeType, "image/png");
    assert.equal(Buffer.from(result.data, "base64").equals(png), true);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("rejects non-images", async () => {
  const directory = await mkdtemp(join(tmpdir(), "codex-remote-image-"));
  try {
    const path = join(directory, "secret.txt");
    await writeFile(path, "not an image");
    await assert.rejects(() => readImageAsset(path, directory), /Only PNG/);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("uploads an image into the private image root and reads it back", async () => {
  const directory = await mkdtemp(join(tmpdir(), "codex-remote-upload-"));
  try {
    const png = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 7, 8, 9]);
    const uploaded = await uploadImageAsset(png.toString("base64"), "phone capture.png", directory);
    const loaded = await readImageAsset(uploaded.path, undefined, directory);
    assert.equal(loaded.mimeType, "image/png");
    assert.equal(Buffer.from(loaded.data, "base64").equals(png), true);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("reads generated images from the dedicated Codex image root", async () => {
  const directory = await mkdtemp(join(tmpdir(), "codex-generated-image-"));
  try {
    const path = join(directory, "generated.png");
    const png = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 4, 5, 6]);
    await writeFile(path, png);

    const result = await readImageAsset(path, undefined, undefined, directory);

    assert.equal(result.mimeType, "image/png");
    assert.equal(Buffer.from(result.data, "base64").equals(png), true);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});
