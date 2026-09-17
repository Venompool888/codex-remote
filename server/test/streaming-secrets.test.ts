import assert from 'node:assert/strict';
import test from 'node:test';
import { StreamingSecrets } from '../src/streaming-secrets.js';

test('Bearer and API key secrets are removed at every split and character boundary', () => {
  const raw = 'Header: bEaReR \tsecret-token_123.abc==; key sk-proj-test_1234567890. Done';
  const expected = 'Header: Bearer [redacted]; key [redacted]. Done';
  for (let i=0;i<=raw.length;i++) {
    const stream = new StreamingSecrets();
    assert.equal(stream.push(raw.slice(0,i))+stream.push(raw.slice(i))+stream.finish(),expected);
  }
  const stream = new StreamingSecrets();
  assert.equal([...raw].map(c=>stream.push(c)).join('')+stream.finish(),expected);
});
test('ordinary prose and word boundaries are preserved, unbounded token bodies are discarded incrementally', () => {
  const stream = new StreamingSecrets();
  assert.equal(stream.push('Hello world!'), 'Hello world!');
  const ordinary = 'whisk-example bearers and Bear';
  assert.equal(stream.push(ordinary)+stream.finish(), ordinary);
  assert.equal(stream.push('Bearer '), 'Bearer [redacted]');
  for(let i=0;i<1000;i++) assert.equal(stream.push('x'.repeat(1000)), '');
  assert.equal(stream.push('; next!'), '; next!');
  assert.equal(stream.finish(), '');
});
