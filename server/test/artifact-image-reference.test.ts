import { currentRemoteCapabilities } from '../src/protocol.js';
import assert from 'node:assert/strict';
import test from 'node:test';
import { artifactImageReference, rewriteArtifactImages, StreamingArtifactImages } from '../src/artifact-image-reference.js';
import { publicPayload } from '../src/public-payload.js';

test('generated images use selectors without exposing paths; external and uploaded references stay intact', () => {
  const source = '/var/lib/private/workspace/my preview.png';
  const expected = `![Preview](remote-artifact-image://${artifactImageReference(source)})`;
  assert.equal(rewriteArtifactImages(`![Preview](<${source}>)`), expected);
  assert.equal(publicPayload({ text: `![Preview](<${source}>)` }, '', true) &&
    (publicPayload({ text: `![Preview](<${source}>)` }, '', true) as any).text, expected);
  for (const source of ['https://example.com/a.png', 'remote-attachment://id', 'remote-artifact-image://' + 'a'.repeat(64)]) {
    const text = `![Image](${source})`; assert.equal(rewriteArtifactImages(text), text);
  }
});

test('image source is never published in partial chunks or at interrupted completion', () => {
  const raw = 'Before ![Image](/var/lib/private/workspace/preview.png) after!';
  const expected = rewriteArtifactImages(raw);
  for (let index = 0; index <= raw.length; index++) {
    const stream = new StreamingArtifactImages();
    assert.equal(stream.push(raw.slice(0,index)) + stream.push(raw.slice(index)) + stream.finish(), expected);
  }
  const stream = new StreamingArtifactImages();
  assert.equal([...raw].map(char => stream.push(char)).join('') + stream.finish(), expected);
  assert.equal(stream.push('![Image](/var/lib/private/') + stream.finish(), '[Image reference unavailable]');
  assert.equal(stream.push('![' + 'x'.repeat(100_000) + ') next') + stream.finish(), '[Image reference unavailable] next');
});


test('artifact-enabled hosts advertise restricted previews instead of path-based image reads', () => {
  const modern = currentRemoteCapabilities({ artifacts: true });
  assert.equal(modern.attachments.restrictedArtifactImages, true);
  assert.equal(modern.rpcMethods.includes('host/image/read'), false);
  assert.equal(modern.rpcMethods.includes('host/artifacts/list'), true);
  const legacy = currentRemoteCapabilities();
  assert.equal(legacy.attachments.restrictedArtifactImages, false);
  assert.equal(legacy.rpcMethods.includes('host/image/read'), true);
});


test('user instructions quoting relative image Markdown remain readable', () => {
  const text = 'Reply with ![Preview](preview.png)';
  const user = {type:'userMessage',content:[{type:'inputText',text}]};
  assert.deepEqual(publicPayload(user,'',true), user);
});
