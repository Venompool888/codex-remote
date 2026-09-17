import assert from 'node:assert/strict';
import test from 'node:test';
import { StreamingReplacement } from '../src/streaming-replacement.js';

const prefix = '/private/test host/attachments/files/';
const target = 'remote-attachment://';
test('attachment prefixes survive every possible two-chunk boundary and character streaming', () => {
  const raw = `Before ![Image](${prefix}123-photo.png) after ${prefix}456-other.png done.`;
  const expected = raw.split(prefix).join(target);
  for (let i = 0; i <= raw.length; i++) {
    const stream = new StreamingReplacement(prefix, target);
    assert.equal(stream.push(raw.slice(0, i)) + stream.push(raw.slice(i)) + stream.finish(), expected);
  }
  const stream = new StreamingReplacement(prefix, target);
  assert.equal([...raw].map(c => stream.push(c)).join('') + stream.finish(), expected);
});
test('ordinary words are not delayed, independent streams do not share prefixes, interrupted prefixes fail closed', () => {
  const a = new StreamingReplacement(prefix, target);
  const b = new StreamingReplacement(prefix, target);
  assert.equal(a.push('Hello world'), 'Hello world');
  assert.equal(a.push('/private/test '), '');
  assert.equal(b.push('Other task'), 'Other task');
  assert.equal(a.finish(), '[incomplete attachment reference]');
  assert.equal(a.push('new turn'), 'new turn');
  assert.equal(b.finish(), '');
});
