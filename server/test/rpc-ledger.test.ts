import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { RpcLedger } from '../src/rpc-ledger.js';

test('write outcome survives host restart and concurrent retries dispatch once', async () => {
  const root = await mkdtemp(join(tmpdir(), 'ledger-'));
  try {
    let count = 0;
    const ledger = new RpcLedger(root);
    const execute = async () => { count++; return { ok: true, result: { id: 'turn-once' } }; };
    const results = await Promise.all([ledger.run('device:turn:key', execute), ledger.run('device:turn:key', execute)]);
    assert.equal(count, 1);
    assert.deepEqual(results[0], results[1]);
    assert.deepEqual(await new RpcLedger(root).run('device:turn:key', execute), results[0]);
    assert.equal(count, 1);
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('crash after dispatch is explicitly unconfirmed and never repeated', async () => {
  const root = await mkdtemp(join(tmpdir(), 'ledger-'));
  try {
    const ledger = new RpcLedger(root);
    await assert.rejects(ledger.run('pending', async () => { throw new Error('crash'); }));
    let executed = false;
    const result = await new RpcLedger(root).run('pending', async () => { executed = true; return { ok: true }; });
    assert.equal(executed, false);
    assert.equal(result.ok, false);
    assert.match(result.error!, /will not be submitted twice/);
  } finally { await rm(root, { recursive: true, force: true }); }
});
