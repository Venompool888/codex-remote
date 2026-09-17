import assert from 'node:assert/strict';
import test from 'node:test';
import { publicPayload } from '../src/public-payload.js';

test('non-routing source, detail and summary redact private paths without altering provenance labels', () => {
  const input = {source:'Installed on host',detail:'Missing /var/lib/private-owner/dependency',
    nested:{source:'/home/private-owner/plugins/sample',summary:'See /etc/private/config'},cwd:'/var/lib/private-owner/work'};
  const value=publicPayload(input) as typeof input;
  assert.equal(value.source,'Installed on host');
  assert.equal(value.detail,'Missing [private host path]');
  assert.deepEqual(value.nested,{source:'[private host path]',summary:'See [private host path]'});
  assert.equal(value.cwd,input.cwd); // Opaque routing is a separate protocol change.
  assert.equal(input.detail,'Missing /var/lib/private-owner/dependency');
});

test('outbound text redacts private paths and secrets while preserving required routing metadata', () => {
  const value = publicPayload({ cwd: '/Users/test/work', text: 'Get [report](</Users/test/private folder/report.pdf>). Read /root/.ssh/id_rsa',
    command: 'Authorization: Bearer private-token-123', api_key: 'secret' }) as any;
  assert.equal(value.cwd, '/Users/test/work');
  assert.equal(value.text, 'Get [report](remote-artifact://available). Read [private host path]');
  assert.equal(value.command.includes('private-token'), false);
  assert.equal(value.api_key, '[redacted]');
});

test('file citation variants become a safe artifact action', () => {
  for (const prefix of [':', '::']) {
    assert.equal(publicPayload(`Created ${prefix}codex-file-citation{path="/Users/test/private folder/proof.pdf" purpose="output"}`, 'text'),
      'Created [View artifact](remote-artifact://available)');
  }
});

test('service diagnostics redact Linux service paths without mutating upstream commands', () => {
  const command = 'python3 /var/lib/codex-remote-public/workspace/proof.py --config /etc/private/config.json';
  const input = { command, error: { message: 'EACCES /var/log/private/server.log' },
    output: ['/opt/private/bin/tool', '/srv/private/report.txt', '/var/cache/private/state'],
    reason: 'Review /var/lib/private/state', arguments: '--file=/var/lib/private/report.txt' };
  const safe = publicPayload(input) as typeof input;
  assert.equal(safe.command, 'python3 [private host path] --config [private host path]');
  assert.equal(safe.error.message, 'EACCES [private host path]');
  assert.deepEqual(safe.output, Array(3).fill('[private host path]'));
  assert.equal(safe.reason, 'Review [private host path]');
  assert.equal(safe.arguments, '--file=[private host path]');
  assert.equal(input.command, command);
});

test('service diagnostic correction preserves legacy image routing and ordinary text', () => {
  const text = '![Preview](/var/lib/codex-remote-public/workspace/preview.png)';
  const value = publicPayload({ text, cwd: '/var/lib/codex-remote-public/workspace', command: 'echo hello' }) as any;
  assert.equal(value.text, text);
  assert.equal(value.cwd, '/var/lib/codex-remote-public/workspace');
  assert.equal(value.command, 'echo hello');
});
