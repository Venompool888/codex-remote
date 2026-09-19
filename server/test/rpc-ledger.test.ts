import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdtemp, readdir, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { RpcLedger, rpcPayloadFingerprint } from '../src/rpc-ledger.js';

test('write outcome survives host restart and concurrent retries dispatch once', async () => {
  const root = await mkdtemp(join(tmpdir(), 'ledger-'));
  try {
    let count = 0;
    const ledger = new RpcLedger(root);
    const execute = async () => { count++; return { ok: true, result: { id: 'turn-once' } }; };
    const firstPayload = { threadId: 'thread-a', input: [{ text: 'hello', type: 'text' }] };
    const reorderedPayload = { input: [{ type: 'text', text: 'hello' }], threadId: 'thread-a' };
    const results = await Promise.all([
      ledger.run('device:turn:key', firstPayload, execute),
      ledger.run('device:turn:key', reorderedPayload, execute),
    ]);
    assert.equal(count, 1);
    assert.deepEqual(results[0], results[1]);
    assert.deepEqual(await new RpcLedger(root).run('device:turn:key', reorderedPayload, execute), results[0]);
    assert.equal(count, 1);
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('crash after dispatch is explicitly unconfirmed and never repeated', async () => {
  const root = await mkdtemp(join(tmpdir(), 'ledger-'));
  try {
    const ledger = new RpcLedger(root);
    await assert.rejects(ledger.run('pending', { setting: 'web_search', value: 'live' }, async () => { throw new Error('crash'); }));
    let executed = false;
    const result = await new RpcLedger(root).run('pending', { value: 'live', setting: 'web_search' }, async () => {
      executed = true;
      return { ok: true };
    });
    assert.equal(executed, false);
    assert.equal(result.ok, false);
    assert.match(result.error!, /will not be submitted twice/);
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('same key with different payload is rejected while active and after restart', async () => {
  const root = await mkdtemp(join(tmpdir(), 'ledger-'));
  try {
    let finish!: (outcome: {ok: boolean; result: {plugin: string}}) => void;
    let count = 0;
    const ledger = new RpcLedger(root);
    const first = ledger.run('device:plugin:key', { pluginName: 'first' }, () => {
      count++;
      return new Promise((resolve) => { finish = resolve; });
    });
    while (!finish) await new Promise((resolve) => setImmediate(resolve));
    await assert.rejects(
      ledger.run('device:plugin:key', { pluginName: 'second' }, async () => {
        count++;
        return { ok: true, result: { plugin: 'second' } };
      }),
      /different request parameters/,
    );
    finish({ ok: true, result: { plugin: 'first' } });
    await first;
    assert.equal(count, 1);

    await assert.rejects(
      new RpcLedger(root).run('device:plugin:key', { pluginName: 'second' }, async () => {
        count++;
        return { ok: true, result: { plugin: 'second' } };
      }),
      /different request parameters/,
    );
    assert.equal(count, 1);
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('legacy records without a payload fingerprint never replay or return stale success', async () => {
  const root = await mkdtemp(join(tmpdir(), 'ledger-'));
  try {
    const seed = new RpcLedger(root);
    await seed.run('legacy', { original: true }, async () => ({ ok: true, result: { stale: true } }));
    const [file] = await readdir(root);
    const record = JSON.parse(await readFile(join(root, file), 'utf8'));
    delete record.fingerprint;
    delete record.version;
    await writeFile(join(root, file), JSON.stringify(record));

    let executed = false;
    const result = await new RpcLedger(root).run('legacy', { different: true }, async () => {
      executed = true;
      return { ok: true };
    });
    assert.equal(executed, false);
    assert.equal(result.ok, false);
    assert.match(result.error!, /older host/);
    assert.doesNotMatch(result.error!, /stale/);
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('payload fingerprint sorts object keys recursively but preserves array order', () => {
  assert.equal(
    rpcPayloadFingerprint({ z: [{ b: 2, a: 1 }], a: true }),
    rpcPayloadFingerprint({ a: true, z: [{ a: 1, b: 2 }] }),
  );
  assert.notEqual(rpcPayloadFingerprint({ argv: ['a', 'b'] }), rpcPayloadFingerprint({ argv: ['b', 'a'] }));
  assert.throws(() => rpcPayloadFingerprint({ value: Number.NaN }), /non-finite/);
});
