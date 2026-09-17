import { artifactImageReference } from '../src/artifact-image-reference.js';
import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdtemp, writeFile, mkdir, rm, symlink, realpath, utimes, readdir, readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { ArtifactStore } from '../src/artifact-store.js';

test('artifacts are immutable device-owned snapshots of assistant-linked workspace outputs only', async () => {
  const root = await mkdtemp(join(tmpdir(), 'artifacts-'));
  const cwd = join(root, 'project');
  await mkdir(cwd);
  await writeFile(join(cwd, 'result.txt'), 'verified result');
  await writeFile(join(root, 'secret.txt'), 'private');
  await writeFile(join(cwd, '.env.txt'), 'private');
  await symlink(join(root, 'secret.txt'), join(cwd, 'escape.txt'));
  const store = new ArtifactStore(join(root, 'store'));
  const thread = { cwd, turns: [{ items: [
    { type: 'userMessage', text: '[secret](../secret.txt)' },
    { type: 'agentMessage', text: '[result](result.txt) [escape](escape.txt) [hidden](.env.txt) [outside](../secret.txt)' },
  ] }] };
  try {
    const result = await store.list('device-a', 'thread-a', thread);
    assert.equal(result.length, 1);
    assert.equal(result[0].name, 'result.txt');
    assert.equal(result[0].imageReference, artifactImageReference('result.txt'));
    const replay = await store.list('device-a', 'thread-a', thread);
    assert.equal(replay[0].imageReference, result[0].imageReference);
    for (const prefix of [':', '::']) {
      const cited = await store.list('device-a', 'thread-a', {cwd, turns:[{items:[{type:'agentMessage',text:`${prefix}codex-file-citation{path="${join(await realpath(cwd), 'result.txt')}" purpose="output"}`}]}]});
      assert.equal(cited[0].id, result[0].id);
    }
    assert.equal(JSON.stringify(result).includes(cwd), false);
    await assert.rejects(store.download('device-b', result[0].id), /unavailable/);
    await assert.rejects(store.download('device-a', '../secret.txt'), /Invalid/);
    await writeFile(join(cwd, 'result.txt'), 'changed after listing');
    const download = await store.download('device-a', result[0].id);
    const parts: Buffer[] = [];
    for await (const chunk of download.stream) parts.push(chunk as Buffer);
    assert.equal(Buffer.concat(parts).toString(), 'verified result');
    const other = await store.list('device-b', 'thread-a', thread);
    assert.notEqual(other[0].id, result[0].id);
  } finally { await rm(root, { recursive: true, force: true }); }
});


test('cleanup removes abandoned snapshots while preserving recent writes and valid outputs', async () => {
  const root = await mkdtemp(join(tmpdir(), 'artifact-cleanup-'));
  const directory = join(root, 'store');
  await mkdir(directory);
  const old = new Date(Date.now() - 25 * 60 * 60_000);
  const orphan = 'a'.repeat(64) + '.data';
  const broken = 'b'.repeat(64) + '.json';
  const recent = 'c'.repeat(64) + '.data';
  const temp = '12345678-1234-4234-8234-123456789abc.tmp';
  const validId = 'd'.repeat(64);
  const expiredId = 'e'.repeat(64);
  try {
    for (const name of [orphan, broken, temp]) {
      await writeFile(join(directory, name), 'interrupted');
      await utimes(join(directory, name), old, old);
    }
    await writeFile(join(directory, recent), 'currently being indexed');
    await writeFile(join(directory, validId + '.data'), 'valid output');
    await utimes(join(directory, validId + '.data'), old, old);
    await writeFile(join(directory, validId + '.json'), JSON.stringify({id:validId, expiresAt:new Date(Date.now()+86400000).toISOString()}));
    await writeFile(join(root, 'outside.data'), 'must survive');
    await writeFile(join(directory, expiredId + '.data'), 'expired');
    await writeFile(join(directory, expiredId + '.json'), JSON.stringify({id:'../outside', expiresAt:old.toISOString()}));
    await new ArtifactStore(directory).list('device', 'thread', {cwd:root, turns:[]});
    assert.deepEqual((await readdir(directory)).sort(), [recent, validId+'.data', validId+'.json'].sort());
    assert.equal(await readFile(join(root, 'outside.data'), 'utf8'), 'must survive');
  } finally { await rm(root, {recursive:true, force:true}); }
});
