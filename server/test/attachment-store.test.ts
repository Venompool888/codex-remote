import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtemp, rm, mkdir, rename, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { AttachmentStore } from "../src/attachment-store.js";

const sha256 = (bytes: Buffer) => createHash("sha256").update(bytes).digest("hex");

test("a cleaned attachment is an explicit pre-dispatch rejection without a private path", async () => {
  const directory = await mkdtemp(join(tmpdir(), "codex-attachment-missing-"));
  try {
    let now = Date.now();
    const store = new AttachmentStore(directory, () => now);
    const bytes = Buffer.from("expiry proof");
    const created = await store.init("pixel", { name: "proof.txt", mimeType: "text/plain", size: bytes.length, sha256: sha256(bytes) });
    await store.writeChunk("pixel", created.id, 0, bytes.length, bytes, sha256(bytes));
    // Unfinished uploads still expire; completed conversation files do not.
    now += 8 * 24 * 60 * 60_000;
    await store.cleanupExpired();
    for (const operation of [() => store.status("pixel", created.id), () => store.resolveForTurn("pixel", created.id)]) {
      await assert.rejects(operation, (error: any) => {
        assert.equal(error.message, "Attachment expired or was removed; remove and select it again");
        assert.equal(error.code, "ENOENT");
        assert.equal("path" in error, false);
        assert.equal("dest" in error, false);
        assert.equal(String(error).includes(directory), false);
        return true;
      });
    }
    assert.equal(await store.delete("pixel", created.id), false);
    await assert.rejects(() => store.resolveForTurn("pixel", "../metadata"), /Invalid attachment id/);
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test("completed historical attachments survive legacy TTL and restart but remain device-bound and deletable", async () => {
  const directory = await mkdtemp(join(tmpdir(), "codex-retained-attachment-"));
  try {
    let now = Date.now();
    let store = new AttachmentStore(directory, () => now);
    const bytes = Buffer.from("historical document");
    const created = await store.init("pixel", { name: "notes.txt", mimeType: "text/plain", size: bytes.length, sha256: sha256(bytes) });
    await store.writeChunk("pixel", created.id, 0, bytes.length, bytes, sha256(bytes));
    await store.complete("pixel", created.id);
    const path = join(directory, "metadata", `${created.id}.json`);
    const legacy = JSON.parse(await readFile(path, "utf8"));
    legacy.expiresAt = new Date(now + 7 * 24 * 60 * 60_000).toISOString();
    await writeFile(path, JSON.stringify(legacy));
    now += 45 * 24 * 60 * 60_000;
    store = new AttachmentStore(directory, () => now);
    assert.equal(await store.cleanupExpired(), 0);
    assert.equal((await store.status("pixel", created.id)).expiresAt, "9999-12-31T23:59:59.999Z");
    const download = await store.download("pixel", created.id);
    const chunks: Buffer[] = [];
    for await (const chunk of download.stream) chunks.push(Buffer.from(chunk));
    assert.deepEqual(Buffer.concat(chunks), bytes);
    assert.equal(download.descriptor.path, undefined);
    await assert.rejects(() => store.download("other", created.id), /does not belong/);
    assert.equal(await store.delete("pixel", created.id), true);
    await assert.rejects(() => store.download("pixel", created.id), /expired or was removed/);
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test("resumes a text upload by offset and verifies whole-file integrity", async () => {
  const directory = await mkdtemp(join(tmpdir(), "codex-attachments-"));
  try {
    const bytes = Buffer.from("hello from a resumable attachment\n", "utf8");
    const store = new AttachmentStore(directory, Date.now, 8);
    const created = await store.init("pixel", {
      name: "notes.md",
      mimeType: "text/markdown",
      size: bytes.length,
      sha256: sha256(bytes),
    });
    let offset = 0;
    while (offset < bytes.length) {
      const chunk = bytes.subarray(offset, Math.min(offset + 8, bytes.length));
      const progress = await store.writeChunk("pixel", created.id, offset, bytes.length, chunk, sha256(chunk));
      offset = progress.offset;
    }
    const complete = await store.complete("pixel", created.id);
    assert.equal(complete.status, "complete");
    assert.equal(complete.kind, "text");
    assert.equal((await store.resolveForTurn("pixel", created.id)).path.endsWith("-notes.md"), true);
    await assert.rejects(() => store.resolveForTurn("another-device", created.id), /does not belong/);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("validated audio and video uploads retain their media kinds", async () => {
  const directory = await mkdtemp(join(tmpdir(), "codex-media-attachments-"));
  try {
    const store = new AttachmentStore(directory);
    for (const fixture of [
      { name: "voice.mp3", mimeType: "audio/mpeg", bytes: Buffer.from("ID3\u0004\u0000\u0000media") , kind: "audio" },
      { name: "clip.mp4", mimeType: "video/mp4", bytes: Buffer.from([0, 0, 0, 16, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6f, 0x6d]), kind: "video" },
    ]) {
      const created = await store.init("pixel", { ...fixture, size: fixture.bytes.length, sha256: sha256(fixture.bytes) });
      await store.writeChunk("pixel", created.id, 0, fixture.bytes.length, fixture.bytes, sha256(fixture.bytes));
      assert.equal((await store.complete("pixel", created.id)).kind, fixture.kind);
    }
    const spoof = Buffer.from("not audio");
    const created = await store.init("pixel", { name: "spoof.mp3", mimeType: "audio/mpeg", size: spoof.length, sha256: sha256(spoof) });
    await store.writeChunk("pixel", created.id, 0, spoof.length, spoof, sha256(spoof));
    await assert.rejects(() => store.complete("pixel", created.id), /Audio content/);
    const fakeOffice = Buffer.from("PK\u0003\u0004not an office package");
    const office = await store.init("pixel", { name: "notes.docx",
      mimeType: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
      size: fakeOffice.length, sha256: sha256(fakeOffice) });
    assert.equal(office.kind, "document");
    await store.writeChunk("pixel", office.id, 0, fakeOffice.length, fakeOffice, sha256(fakeOffice));
    await assert.rejects(() => store.complete("pixel", office.id), /ZIP directory|Office document/);
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test("rejects wrong offsets, chunk hashes, whole-file hashes, and MIME spoofing", async () => {
  const directory = await mkdtemp(join(tmpdir(), "codex-attachments-invalid-"));
  try {
    const bytes = Buffer.from("not really a pdf", "utf8");
    const store = new AttachmentStore(directory);
    const created = await store.init("pixel", {
      name: "report.pdf",
      mimeType: "application/pdf",
      size: bytes.length,
      sha256: sha256(bytes),
    });
    await assert.rejects(() => store.writeChunk("pixel", created.id, 1, bytes.length, bytes, sha256(bytes)), /offset/);
    await assert.rejects(() => store.writeChunk("pixel", created.id, 0, bytes.length, bytes, "0".repeat(64)), /Chunk SHA/);
    await store.writeChunk("pixel", created.id, 0, bytes.length, bytes, sha256(bytes));
    await assert.rejects(() => store.complete("pixel", created.id), /PDF signature/);

    const declaredWrongHash = await store.init("pixel", {
      name: "notes.txt",
      mimeType: "text/plain",
      size: bytes.length,
      sha256: "0".repeat(64),
    });
    await store.writeChunk("pixel", declaredWrongHash.id, 0, bytes.length, bytes, sha256(bytes));
    await assert.rejects(() => store.complete("pixel", declaredWrongHash.id), /Attachment SHA/);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("enforces UTF-8 text, JSON validity, quotas, expiry, and deletion", async () => {
  const directory = await mkdtemp(join(tmpdir(), "codex-attachments-policy-"));
  let now = Date.UTC(2026, 0, 1);
  try {
    const binary = Buffer.from([0xff, 0x00]);
    const store = new AttachmentStore(directory, () => now, 1024, 64);
    const badText = await store.init("pixel", {
      name: "bad.txt", mimeType: "text/plain", size: binary.length, sha256: sha256(binary),
    });
    await store.writeChunk("pixel", badText.id, 0, binary.length, binary, sha256(binary));
    await assert.rejects(() => store.complete("pixel", badText.id), /binary data/);

    const invalidJson = Buffer.from("{nope}");
    const json = await store.init("pixel", {
      name: "data.json", mimeType: "application/json", size: invalidJson.length, sha256: sha256(invalidJson),
    });
    await store.writeChunk("pixel", json.id, 0, invalidJson.length, invalidJson, sha256(invalidJson));
    await assert.rejects(() => store.complete("pixel", json.id), /JSON attachment is invalid/);

    await assert.rejects(() => store.init("pixel", {
      name: "too-big.txt", mimeType: "text/plain", size: 60, sha256: "0".repeat(64),
    }), /quota/);

    const other = Buffer.from("ok");
    const expiring = await store.init("other", {
      name: "ok.py", mimeType: "text/x-python", size: other.length, sha256: sha256(other),
    });
    now += 24 * 60 * 60_000 + 1;
    assert.equal(await store.cleanupExpired() >= 1, true);
    await assert.rejects(() => store.status("other", expiring.id));
    assert.equal(await store.delete("other", expiring.id), false);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});


test("serializes quota reservations and recovers a crash between file promotion and metadata commit", async () => {
  const directory = await mkdtemp(join(tmpdir(), "codex-attachment-crash-"));
  try {
    const bytes = Buffer.from("recovery proof");
    const store = new AttachmentStore(directory, Date.now, 1024, bytes.length);
    const init = { name: "proof.txt", mimeType: "text/plain", size: bytes.length, sha256: sha256(bytes) };
    const reservations = await Promise.allSettled([store.init("pixel", init), store.init("pixel", init)]);
    assert.equal(reservations.filter(result => result.status === "fulfilled").length, 1);
    const created = reservations.find(result => result.status === "fulfilled")!;
    assert.equal(created.status, "fulfilled");
    if (created.status !== "fulfilled") throw new Error("Missing upload");
    const id = created.value.id;
    await store.writeChunk("pixel", id, 0, bytes.length, bytes, sha256(bytes));
    await mkdir(join(directory, "files"), { recursive: true });
    await rename(join(directory, "partial", `${id}.part`), join(directory, "files", `${id}-proof.txt`));
    const restarted = new AttachmentStore(directory);
    const [first, duplicate] = await Promise.all([restarted.complete("pixel", id), restarted.complete("pixel", id)]);
    assert.equal(first.status, "complete");
    assert.equal(duplicate.sha256, sha256(bytes));
    assert.equal(first.id, duplicate.id);
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test("initialization retries reuse the same device-owned resource before quota reservation", async () => {
  const directory = await mkdtemp(join(tmpdir(), "codex-upload-init-"));
  try {
    const bytes = Buffer.from("one upload");
    const store = new AttachmentStore(directory, Date.now, 1024, bytes.length);
    const params = { uploadKey: "local-draft-attachment", name: "proof.txt", mimeType: "text/plain", size: bytes.length, sha256: sha256(bytes) };
    const first = await store.init("pixel", params);
    const retried = await new AttachmentStore(directory, Date.now, 1024, bytes.length).init("pixel", params);
    assert.equal(first.id, retried.id);
    await assert.rejects(store.init("pixel", {...params, name:"changed.txt"}), /metadata mismatch/);
    assert.notEqual((await store.init("other-device", params)).id, first.id);
  } finally { await rm(directory, {recursive:true,force:true}); }
});

test("outbound attachment presentation keeps restricted IDs and legacy labels, never Host paths", () => {
  const store = new AttachmentStore("/private/attachment-test");
  const id = "12345678-1234-4234-8234-123456789abc";
  const image = store.redactOutbound({ type: "localImage", path: `/private/attachment-test/files/${id}-photo.png` }) as any;
  assert.deepEqual(image.remoteAttachment, { id, name: "photo.png", kind: "image" });
  assert.equal(image.text, "Attached image: photo.png");
  assert.equal(image.path, undefined);
  const name = Buffer.from("report.pdf").toString("base64url");
  const file = store.redactOutbound({ type: "text", text: `[[CODEX_REMOTE_ATTACHMENT:${name}]]Read /private/attachment-test/files/${id}-report.pdf[[/CODEX_REMOTE_ATTACHMENT]]` }) as any;
  assert.deepEqual(file.remoteAttachment, { id, name: "report.pdf", kind: "file" });
  assert.equal(file.text, "Attached file: report.pdf");
  assert.equal(JSON.stringify(file).includes("/private/"), false);
  assert.deepEqual(store.redactOutbound({ type: "text", text: "Attached file: user prose" }), { type: "text", text: "Attached file: user prose" });
  const markdown = `![Shapes](/private/attachment-test/files/${id}-photo.png)`;
  const reference = `![Shapes](remote-attachment://${id}-photo.png)`;
  assert.equal(store.redactOutbound(markdown), reference);
  assert.equal((store.redactOutbound(store.redactOutbound({ delta: markdown })) as any).delta, reference);
});

test("image previews require complete device-owned images and preserve verified bytes", async () => {
  const directory = await mkdtemp(join(tmpdir(), "codex-preview-"));
  try {
    const store = new AttachmentStore(directory);
    const bytes = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg==", "base64");
    const init = await store.init("pixel", { name: "pixel.png", mimeType: "image/png", size: bytes.length, sha256: sha256(bytes) });
    await assert.rejects(() => store.preview("pixel", init.id), /not complete/);
    await store.writeChunk("pixel", init.id, 0, bytes.length, bytes, sha256(bytes));
    await store.complete("pixel", init.id);
    await assert.rejects(() => store.preview("other-device", init.id), /does not belong/);
    const preview = await store.preview("pixel", init.id);
    const chunks: Buffer[] = [];
    for await (const chunk of preview.stream) chunks.push(Buffer.from(chunk));
    assert.deepEqual(Buffer.concat(chunks), bytes);
    assert.equal(preview.descriptor.path, undefined);
    const text = Buffer.from("hello");
    const txt = await store.init("pixel", { name: "notes.txt", mimeType: "text/plain", size: text.length, sha256: sha256(text) });
    await store.writeChunk("pixel", txt.id, 0, text.length, text, sha256(text));
    await store.complete("pixel", txt.id);
    await assert.rejects(() => store.preview("pixel", txt.id), /not an image/);
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test("ZIP transport accepts a complete archive and rejects a forged archive without extracting", async () => {
  const root = await mkdtemp(join(tmpdir(), "codex-zip-"));
  try {
    const store = new AttachmentStore(root);
    for (const valid of [true, false]) {
      const bytes = Buffer.alloc(22);
      bytes.writeUInt32LE(valid ? 0x06054b50 : 0x04034b50);
      const init = await store.init("pixel", { name: "session.zip", mimeType: "application/zip", size: bytes.length, sha256: sha256(bytes) });
      await store.writeChunk("pixel", init.id, 0, bytes.length, bytes, sha256(bytes));
      if (valid) assert.equal((await store.complete("pixel", init.id)).kind, "archive");
      else await assert.rejects(store.complete("pixel", init.id), /ZIP directory/);
    }
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('registered source MIME types complete without relaxing text validation or extension matching', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'source-mimes-'));
  const store = new AttachmentStore(directory);
  try {
    for (const [name, mimeType, source] of [
      ['source.js', 'application/javascript', 'const answer = 42;'],
      ['source.xml', 'application/xml', '<answer>42</answer>'],
      ['source.yaml', 'application/yaml', 'answer: 42'],
      ['source.yml', 'application/x-yaml', 'answer: 42'],
    ]) {
      const bytes = Buffer.from(source);
      const item = await store.init('pixel', {name, mimeType, size:bytes.length, sha256:sha256(bytes)});
      await store.writeChunk('pixel', item.id, 0, bytes.length, bytes, sha256(bytes));
      const complete = await store.complete('pixel', item.id);
      assert.equal(complete.kind, 'code');
      assert.equal(complete.status, 'complete');
    }
    await assert.rejects(store.init('pixel', {name:'source.exe', mimeType:'application/xml', size:1, sha256:sha256(Buffer.from('x'))}), /Unsupported/);
    await assert.rejects(store.init('pixel', {name:'source.js', mimeType:'application/pdf', size:1, sha256:sha256(Buffer.from('x'))}), /Unsupported/);
    const binary = Buffer.from([0, 0xff, 0x01]);
    const bad = await store.init('pixel', {name:'binary.xml', mimeType:'application/xml', size:binary.length, sha256:sha256(binary)});
    await store.writeChunk('pixel', bad.id, 0, binary.length, binary, sha256(binary));
    await assert.rejects(store.complete('pixel', bad.id), /binary data/);
  } finally { await rm(directory, {recursive:true, force:true}); }
});


test("all advertised image formats upload, resolve and return exact owned preview bytes", async () => {
  const directory = await mkdtemp(join(tmpdir(), "codex-image-formats-"));
  try {
    const store = new AttachmentStore(directory);
    for (const [extension, mimeType] of [["png", "image/png"], ["jpg", "image/jpeg"], ["gif", "image/gif"], ["webp", "image/webp"]]) {
      const bytes = await readFile(new URL(`./fixtures/attachment-images/color-proof.${extension}`, import.meta.url));
      const init = await store.init("pixel", { name: `color-proof.${extension}`, mimeType, size: bytes.length, sha256: sha256(bytes) });
      await store.writeChunk("pixel", init.id, 0, bytes.length, bytes, sha256(bytes));
      const complete = await store.complete("pixel", init.id);
      assert.equal(complete.kind, "image");
      assert.equal(complete.mimeType, mimeType);
      assert.deepEqual(await readFile((await store.resolveForTurn("pixel", init.id)).path), bytes);
      await assert.rejects(() => store.preview("other-device", init.id), /does not belong/);
      const preview = await store.preview("pixel", init.id);
      const chunks: Buffer[] = [];
      for await (const chunk of preview.stream) chunks.push(Buffer.from(chunk));
      assert.deepEqual(Buffer.concat(chunks), bytes);
      assert.equal(preview.descriptor.path, undefined);
      await store.delete("pixel", init.id);
    }
  } finally { await rm(directory, { recursive: true, force: true }); }
});
