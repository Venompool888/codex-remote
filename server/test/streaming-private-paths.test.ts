import assert from 'node:assert/strict';
import test from 'node:test';
import { StreamingPrivatePaths, redactPrivatePaths } from '../src/streaming-private-paths.js';
import { publicPayload } from '../src/public-payload.js';

test('all split boundaries and character streaming hide service, home and quoted paths', () => {
  for (const raw of ['/var/lib/service/workspace/proof.txt', '/Users/private/work/file', '/home/private/.ssh/key',
    '/root/.codex/auth.json', '/tmp/private/output.png', '/private/var/folders/private/file',
    '"/var/lib/private folder/secret file.txt"', "'/etc/private folder/config'", '`/opt/private folder/file`']) {
    const input = `Before ${raw} after`;
    const expected = `Before ${raw.startsWith('"') ? '"[private host path]"' : raw.startsWith("'") ? "'[private host path]'" : raw.startsWith('`') ? '`[private host path]`' : '[private host path]'} after`;
    for (let split=0;split<=input.length;split++) {
      const stream = new StreamingPrivatePaths();
      assert.equal(stream.push(input.slice(0,split))+stream.push(input.slice(split))+stream.finish(),expected);
    }
    const stream = new StreamingPrivatePaths();
    assert.equal([...input].map(char=>stream.push(char)).join('')+stream.finish(),expected);
  }
});

test('bounded path discard, interrupted prefixes, and independent reuse', () => {
  const stream = new StreamingPrivatePaths();
  assert.equal(stream.push('/var/lib/'+'x'.repeat(1_000_000)) + stream.finish(),'[private host path]');
  assert.equal(stream.push('/var/li') + stream.finish(),'[private host path]');
  for (const text of ['hello / world', 'a/b', 'remote-artifact-image://'+'a'.repeat(64), 'https://example.com/image.png']) {
    assert.equal(stream.push(text)+stream.finish(),text);
  }
});

test('restricted whole-message redaction matches streamed text and retains routing metadata', () => {
  const text = 'Read "/var/lib/private folder/proof.txt" then /srv/private/file';
  const result = publicPayload({type:'userMessage',text,cwd:'/var/lib/service/workspace'},'',true) as any;
  assert.equal(result.text,redactPrivatePaths(text));
  assert.equal(result.cwd,'/var/lib/service/workspace');
});


test('standalone diagnostics also hide quoted home paths before legacy filtering', () => {
  assert.equal(publicPayload('open "/home/private user/secret file" failed','message'), 'open "[private host path]" failed');
});
