import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { link, mkdtemp, mkdir, readFile, readdir, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import {
  WORKSPACE_MUTATION_METHODS,
  WORKSPACE_MUTATION_WRITES,
  WORKSPACE_TEXT_WRITES_SUPPORTED,
  WorkspaceMutations,
} from '../src/workspace-mutations.js';

class FakeCodex {
  calls: Array<{method: string; params: unknown}> = [];
  handlers = new Map<string, (params: any) => unknown>();
  async call(method: string, params: unknown): Promise<unknown> {
    this.calls.push({ method, params });
    return this.handlers.get(method)?.(params) ?? {};
  }
}

const digest = (value: string) => createHash('sha256').update(value, 'utf8').digest('hex');
const confirm = (action: string, subject: string) => ({ action, subject });

test('typed method registry exposes only bounded workspace and task operations', () => {
  assert.deepEqual([...WORKSPACE_MUTATION_METHODS], [
    'host/workspace/text/save', 'host/workspace/text/create', 'host/memory/reset',
    'host/thread/backgroundTerminals/list', 'host/thread/backgroundTerminals/terminate',
    'host/thread/backgroundTerminals/clean',
  ]);
  assert.equal(WORKSPACE_MUTATION_WRITES.has('host/thread/backgroundTerminals/list'), false);
  assert.equal(WORKSPACE_MUTATION_WRITES.has('host/workspace/text/save'), true);
});

test('unsupported platforms fail workspace text writes closed', { skip: WORKSPACE_TEXT_WRITES_SUPPORTED }, async () => {
  const root = await mkdtemp(join(tmpdir(), 'workspace-mutations-'));
  try {
    await mkdir(join(root, 'notes'));
    const mutations = new WorkspaceMutations(new FakeCodex() as any, async () => root);
    await assert.rejects(mutations.call('host/workspace/text/create', { cwd: root, path: 'notes/today.txt', text: 'first',
      confirmation: confirm('create_workspace_text', 'notes/today.txt') }), /unavailable on this Host/);
    await assert.rejects(readFile(join(root, 'notes/today.txt')), /ENOENT/);
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('create and save publish bounded UTF-8 text with exact confirmation and optimistic hash',
  { skip: !WORKSPACE_TEXT_WRITES_SUPPORTED }, async () => {
  const root = await mkdtemp(join(tmpdir(), 'workspace-mutations-'));
  try {
    await mkdir(join(root, 'notes'));
    const mutations = new WorkspaceMutations(new FakeCodex() as any, async () => root);
    const created = await mutations.call('host/workspace/text/create', {
      cwd: root, path: 'notes/today.txt', text: 'first',
      confirmation: confirm('create_workspace_text', 'notes/today.txt'),
    });
    assert.deepEqual(created, { path: 'notes/today.txt', size: 5, sha256: digest('first') });
    assert.equal(await readFile(join(root, 'notes/today.txt'), 'utf8'), 'first');

    const saved = await mutations.call('host/workspace/text/save', {
      cwd: root, path: 'notes/../notes/today.txt', text: 'second', expectedSha256: digest('first'),
      confirmation: confirm('save_workspace_text', 'notes/today.txt'),
    });
    assert.deepEqual(saved, { path: 'notes/today.txt', size: 6, sha256: digest('second') });
    assert.equal(await readFile(join(root, 'notes/today.txt'), 'utf8'), 'second');

    await assert.rejects(mutations.call('host/workspace/text/save', {
      cwd: root, path: 'notes/today.txt', text: 'stale overwrite', expectedSha256: digest('first'),
      confirmation: confirm('save_workspace_text', 'notes/today.txt'),
    }), /changed since it was read/);
    assert.equal(await readFile(join(root, 'notes/today.txt'), 'utf8'), 'second');
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('create never replaces existing files and symlinks cannot escape the workspace',
  { skip: !WORKSPACE_TEXT_WRITES_SUPPORTED }, async () => {
  const root = await mkdtemp(join(tmpdir(), 'workspace-mutations-'));
  const outside = await mkdtemp(join(tmpdir(), 'workspace-outside-'));
  try {
    await mkdir(join(root, 'safe'));
    await writeFile(join(root, 'safe', 'exists.txt'), 'keep');
    await symlink(outside, join(root, 'escape'));
    const mutations = new WorkspaceMutations(new FakeCodex() as any, async () => root);

    await assert.rejects(mutations.call('host/workspace/text/create', {
      cwd: root, path: 'safe/exists.txt', text: 'replace',
      confirmation: confirm('create_workspace_text', 'safe/exists.txt'),
    }), /already exists/);
    assert.equal(await readFile(join(root, 'safe', 'exists.txt'), 'utf8'), 'keep');
    assert.equal((await readdir(join(root, 'safe'))).some((name) => name.startsWith('.codex-')), false);

    await assert.rejects(mutations.call('host/workspace/text/create', {
      cwd: root, path: 'escape/private.txt', text: 'leak',
      confirmation: confirm('create_workspace_text', 'escape/private.txt'),
    }), /parent directory is unavailable or contains a symbolic link/);
    await assert.rejects(readFile(join(outside, 'private.txt')), /ENOENT/);

    await writeFile(join(outside, 'target.txt'), 'outside');
    await symlink(join(outside, 'target.txt'), join(root, 'safe', 'linked.txt'));
    await assert.rejects(mutations.call('host/workspace/text/save', { cwd: root, path: 'safe/linked.txt', text: 'replace',
      expectedSha256: digest('outside'), confirmation: confirm('save_workspace_text', 'safe/linked.txt') }), /does not exist|regular file/);
    assert.equal(await readFile(join(outside, 'target.txt'), 'utf8'), 'outside');

    await assert.rejects(mutations.call('host/workspace/text/create', {
      cwd: root, path: 'safe/big.txt', text: '😀'.repeat(131_073),
      confirmation: confirm('create_workspace_text', 'safe/big.txt'),
    }), /exceeds/);
  } finally {
    await rm(root, { recursive: true, force: true });
    await rm(outside, { recursive: true, force: true });
  }
});

test('save rejects multiply linked files that could modify content outside the workspace',
  { skip: !WORKSPACE_TEXT_WRITES_SUPPORTED }, async () => {
    const root = await mkdtemp(join(tmpdir(), 'workspace-mutations-'));
    const outside = await mkdtemp(join(tmpdir(), 'workspace-outside-'));
    try {
      await writeFile(join(root, 'shared.txt'), 'keep');
      await link(join(root, 'shared.txt'), join(outside, 'shared.txt'));
      const mutations = new WorkspaceMutations(new FakeCodex() as any, async () => root);
      await assert.rejects(mutations.call('host/workspace/text/save', { cwd: root, path: 'shared.txt', text: 'replace',
        expectedSha256: digest('keep'), confirmation: confirm('save_workspace_text', 'shared.txt') }), /one link/);
      assert.equal(await readFile(join(outside, 'shared.txt'), 'utf8'), 'keep');
    } finally { await rm(root, { recursive: true, force: true }); await rm(outside, { recursive: true, force: true }); }
  });

test('destructive memory and background terminal wrappers require exact confirmation', async () => {
  const codex = new FakeCodex();
  codex.handlers.set('thread/backgroundTerminals/list', () => ({
    data: [{ itemId: 'item-1', processId: 'process-1', command: 'npm test', cwd: '/private/project',
      osPid: 42, cpuPercent: 1.5, rssKb: 2048, privateToken: 'hidden' }],
    nextCursor: 'opaque cursor',
  }));
  codex.handlers.set('thread/backgroundTerminals/terminate', () => ({ terminated: true }));
  const mutations = new WorkspaceMutations(codex as any, async (value) => String(value));

  assert.deepEqual(await mutations.call('host/thread/backgroundTerminals/list', {
    threadId: 'thread-1', limit: 10, cursor: 'opaque cursor',
  }), { data: [{ itemId: 'item-1', processId: 'process-1', command: 'npm test', cwdName: 'project',
    osPid: 42, cpuPercent: 1.5, rssKb: 2048 }], nextCursor: 'opaque cursor' });
  assert.deepEqual(codex.calls.at(-1), { method: 'thread/backgroundTerminals/list',
    params: { threadId: 'thread-1', limit: 10, cursor: 'opaque cursor' } });

  await assert.rejects(mutations.call('host/thread/backgroundTerminals/terminate', {
    threadId: 'thread-1', processId: 'process-1',
    confirmation: confirm('terminate_background_terminal', 'process-1'),
  }), /confirmation/);
  assert.equal(codex.calls.filter((call) => call.method === 'thread/backgroundTerminals/terminate').length, 0);

  assert.deepEqual(await mutations.call('host/thread/backgroundTerminals/terminate', {
    threadId: 'thread-1', processId: 'process-1',
    confirmation: confirm('terminate_background_terminal', 'thread-1:process-1'),
  }), { terminated: true });
  await mutations.call('host/thread/backgroundTerminals/clean', {
    threadId: 'thread-1', confirmation: confirm('clean_background_terminals', 'thread-1'),
  });
  await mutations.call('host/memory/reset', {
    confirmation: confirm('reset_memory', 'Codex memory'),
  });
  assert.deepEqual(codex.calls.slice(-3).map((call) => call.method), [
    'thread/backgroundTerminals/terminate', 'thread/backgroundTerminals/clean', 'memory/reset',
  ]);
  assert.equal(codex.calls.at(-1)?.params, undefined);
});
