import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, stat, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test, { type TestContext } from 'node:test';
import { RoutingReferences } from '../src/routing-references.js';

async function fixture(t: TestContext) {
  const directory = await mkdtemp(join(tmpdir(), 'routing-refs-'));
  t.after(() => rm(directory, {recursive:true,force:true}));
  const file = join(directory,'private','references.json');
  return {directory,file,store:new RoutingReferences(file)};
}

test('references are opaque, stable under concurrent issue and recover after restart', async t => {
  const {file,store} = await fixture(t);
  const target='/var/lib/private-owner/workspace';
  const refs=await Promise.all(Array.from({length:20},()=>store.issue('device-a','workspace',target)));
  assert.equal(new Set(refs.map(r=>r.reference)).size,1);
  assert.match(refs[0].reference,/^remote-workspace:\/\/[a-f0-9]{64}$/);
  assert.equal(JSON.stringify(refs).includes('private-owner'),false);
  assert.equal(refs[0].name,'workspace');
  const restarted=new RoutingReferences(file);
  assert.equal(await restarted.resolve('device-a','workspace',refs[0].reference),target);
  assert.deepEqual(await restarted.issue('device-a','workspace',target),refs[0]);
  assert.equal((await stat(file)).mode & 0o777,0o600);
  assert.equal(JSON.parse(await readFile(file,'utf8')).entries.length,1);
});

test('device, reference kind and tampered identifiers cannot cross scope', async t => {
  const {store}=await fixture(t);
  const a=await store.issue('a','workspace','/home/private/project');
  const b=await store.issue('b','workspace','/home/private/project');
  assert.notEqual(a.reference,b.reference);
  for (const [device,kind,reference] of [
    ['b','workspace',a.reference],['a','path',a.reference],['a','workspace',a.reference+'/../../etc'],
    ['a','workspace','/home/private/project'],['a','workspace','remote-workspace://'+'0'.repeat(64)],
  ] as const) await assert.rejects(store.resolve(device,kind,reference),/Routing reference unavailable/);
  const path=await store.issue('a','path','/home/private/project');
  assert.equal(await store.resolve('a','path',path.reference),'/home/private/project');
});

test('revocation removes one device persistently without invalidating another', async t => {
  const {store,file}=await fixture(t);
  const a=await store.issue('a','workspace','/srv/project');
  const b=await store.issue('b','workspace','/srv/project');
  await store.revokeDevice('a');
  const restarted=new RoutingReferences(file);
  await assert.rejects(restarted.resolve('a','workspace',a.reference),/unavailable/);
  await assert.rejects(restarted.issue('a','workspace','/srv/project'),/revoked/);
  await restarted.revokeDevice('a');
  assert.equal(await restarted.resolve('b','workspace',b.reference),'/srv/project');
});

test('corrupt storage fails closed and never exposes its path in errors', async t => {
  const {store,file}=await fixture(t);
  await store.issue('a','workspace','/srv/project');
  await writeFile(file,'{"version":1,"entries":[{"path":"/private/secret"}]}');
  await assert.rejects(new RoutingReferences(file).issue('a','workspace','/srv/new'),error=>{
    assert.equal((error as Error).message,'Routing reference store unavailable; repair it on the host');return true;
  });
});

test('failed writes do not issue ephemeral IDs or leak private paths', async t => {
  const {directory}=await fixture(t);
  const parent=join(directory,'not-a-directory');await writeFile(parent,'fixture');
  await assert.rejects(new RoutingReferences(join(parent,'refs.json')).issue('a','workspace','/srv/private'),
    /Routing reference store unavailable/);
  const valid=new RoutingReferences(join(directory,'valid.json'));
  for (const path of ['relative/path','/tmp/bad\u0000path','/tmp/new\nline'])
    await assert.rejects(valid.issue('a','workspace',path),/Invalid routing target/);
});
