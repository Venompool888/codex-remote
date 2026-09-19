import assert from 'node:assert/strict';
import {mkdtemp,rm,writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import test from 'node:test';
import {RoutingReferences} from '../src/routing-references.js';
import {WorkspaceRouting} from '../src/workspace-routing.js';
import {requiredScopeForMethod} from '../src/protocol.js';

test('structured skill inputs resolve only their own device path references', async t => {
 const directory=await mkdtemp(join(tmpdir(),'skill-reference-'));
 t.after(()=>rm(directory,{recursive:true,force:true}));
 const refs=new RoutingReferences(join(directory,'refs.json'));
 const a=new WorkspaceRouting(refs,'a');
 const path='/srv/private/skill/SKILL.md';
 const ref=await refs.issue('a','path',path);
 const input=[{type:'skill',name:'Skill',path:ref.reference},
   {type:'text',text:ref.reference},{type:'mention',path:'plugin://plugin'},
   {type:'remoteCapability',capabilityId:'catalog-id'}];
 const resolved=await a.skillInputs(input) as any[];
 assert.equal(resolved[0].path,path);
 assert.deepEqual(resolved.slice(1),input.slice(1));
 assert.equal(input[0].path,ref.reference);
 await assert.rejects(new WorkspaceRouting(refs,'b').skillInputs(input),/unavailable/);
 await assert.rejects(a.skillInputs([{type:'skill',path}]),/unavailable/);
 const workspace=await refs.issue('a','workspace',path);
 await assert.rejects(a.skillInputs([{type:'skill',path:workspace.reference}]),/unavailable/);
});

test('opaque projection filters private roots in unknown fields and patches without altering relative code', async t => {
 const directory=await mkdtemp(join(tmpdir(),'unknown-path-fields-'));
 t.after(()=>rm(directory,{recursive:true,force:true}));
 const routing=new WorkspaceRouting(new RoutingReferences(join(directory,'refs.json')),'a');
 const source={newDiagnosticField:'File /srv/private-owner/secret.txt could not be opened',
   diff:'--- /home/private-owner/code.py\n+++ src/code.py\n+print("ok")',
   nested:{'/var/lib/private-owner/config':'value'}, model:'gpt-model', path:'/srv/private-owner/report.pdf'};
 const projected=await routing.outbound(source) as any;
 assert.equal(JSON.stringify(projected).includes('private-owner'),false);
 assert.equal(projected.diff.includes('+++ src/code.py\n+print("ok")'),true);
 assert.match(projected.path,/^remote-path:\/\/[a-f0-9]{64}$/);
 assert.equal(projected.pathName,'report.pdf');
 assert.equal(projected.model,'gpt-model');
 assert.equal(source.path,'/srv/private-owner/report.pdf');
 await assert.rejects(routing.outbound({'/home/a/file':1,'/home/b/file':2}),/cannot be displayed safely/);
 await assert.rejects(routing.outbound({'/home/a/file':1,'[private host path]':2}),/cannot be displayed safely/);
});

test('workspace migration is bounded, indexed and returns no private path for failed entries', async t => {
 const directory=await mkdtemp(join(tmpdir(),'workspace-migration-'));
 t.after(()=>rm(directory,{recursive:true,force:true}));
 await writeFile(join(directory,'file.txt'),'fixture');
 const refs=new RoutingReferences(join(directory,'refs.json'));
 const routing=new WorkspaceRouting(refs,'a');
 const paths=[directory,join(directory,'missing'),join(directory,'file.txt'),directory];
 const result=await routing.migrate(paths) as any[];
 assert.equal(result.length,4);
 assert.deepEqual(result.map(row=>row.index),[0,1,2,3]);
 assert.equal(JSON.stringify(result).includes(directory),false);
 assert.equal(result[0].cwd,result[3].cwd);
 assert.equal(await refs.resolve('a','workspace',result[0].cwd),directory);
 assert.deepEqual(result[1],{index:1,available:false,reason:'missing'});
 assert.deepEqual(result[2],{index:2,available:false,reason:'not_directory'});
 await assert.rejects(refs.resolve('b','workspace',result[0].cwd),/unavailable/);
 for(const invalid of ['wrong',Array(101).fill(directory),['relative'],[42],['/bad\npath']])
   await assert.rejects(routing.migrate(invalid),/Invalid/);
 assert.equal(requiredScopeForMethod('host/workspace/migrate'),'rpc:write');
});

test('workspace validation resolves scoped arrays and returns the same reference keys', async t => {
 const directory=await mkdtemp(join(tmpdir(),'workspace-validation-wire-'));
 t.after(()=>rm(directory,{recursive:true,force:true}));
 const refs=new RoutingReferences(join(directory,'refs.json'));
 const routing=new WorkspaceRouting(refs,'device-a');
 const known=await refs.issue('device-a','workspace','/srv/private/work');
 const missing=await refs.issue('device-a','workspace','/srv/private/missing');
 const request={paths:[known.reference,missing.reference]};
 assert.deepEqual(await routing.inbound(request,true),{paths:['/srv/private/work','/srv/private/missing']});
 const result=await routing.outbound({workspaces:[{path:'/srv/private/work',available:true},
   {path:'/srv/private/missing',available:false,reason:'missing'}]},true) as any;
 assert.equal(result.workspaces[0].path,known.reference);
 assert.equal(result.workspaces[1].path,missing.reference);
 assert.equal(result.workspaces[0].pathName,'work');
 assert.equal(JSON.stringify(result).includes('/srv/private'),false);
 assert.deepEqual(request,{paths:[known.reference,missing.reference]});
 await assert.rejects(new WorkspaceRouting(refs,'device-b').inbound(request,true),/unavailable/);
 await assert.rejects(routing.inbound({paths:['/srv/private/work']},true),/unavailable/);
 await assert.rejects(routing.inbound({paths:'not-an-array'},true),/Invalid workspace references/);
});

test('typed file paths have device-bound references and readable names without resolving prose', async t => {
 const directory=await mkdtemp(join(tmpdir(),'file-path-wire-'));
 t.after(()=>rm(directory,{recursive:true,force:true}));
 const refs=new RoutingReferences(join(directory,'refs.json'));
 const routing=new WorkspaceRouting(refs,'device-a');
 const original={cwd:'/srv/private/work', changes:[{path:'/srv/private/work/report.pdf',
   kind:{move_path:'/srv/private/work/final.pdf'}}],
   image:{savedPath:'/srv/private/work/image.png'}, grantRoot:'/srv/private/work',
   relative:{path:'src/main.ts'}, pathName:'untrusted display name'};
 const projected=await routing.outbound(original) as any;
 assert.equal(JSON.stringify(projected).includes('/srv/private'),false);
 assert.equal(projected.changes[0].pathName,'report.pdf');
 assert.equal(projected.changes[0].kind.move_pathName,'final.pdf');
 assert.equal(projected.image.savedPathName,'image.png');
 assert.deepEqual(projected.relative,{path:'src/main.ts'});
 assert.notEqual(projected.cwd,projected.grantRoot);
 assert.equal(await refs.resolve('device-a','path',projected.changes[0].path),original.changes[0].path);
 await assert.rejects(refs.resolve('device-b','path',projected.changes[0].path),/unavailable/);
 await assert.rejects(routing.inbound({cwd:projected.grantRoot}),/unavailable/);
 const prose={text:projected.changes[0].path, answers:{question:{answers:[projected.cwd]}}};
 assert.deepEqual(await routing.inbound(prose),prose);
 assert.equal(original.changes[0].path,'/srv/private/work/report.pdf');
 for (const value of [{pathName:'spoof',path:original.changes[0].path},
   {path:original.changes[0].path,pathName:'spoof'}]) {
   assert.equal((await routing.outbound(value) as any).pathName,'report.pdf');
 }
});

test('permission replies approve only the projected pending grant and restore canonical host values', async t => {
 const directory=await mkdtemp(join(tmpdir(),'permission-wire-'));
 t.after(()=>rm(directory,{recursive:true,force:true}));
 const refs=new RoutingReferences(join(directory,'refs.json'));
 const a=new WorkspaceRouting(refs,'a'), b=new WorkspaceRouting(refs,'b');
 const params={permissions:{fileSystem:{read:['/srv/private/project'],write:['/srv/private/project/out']},network:{enabled:true}}};
 const visible=await a.outbound(params) as any;
 assert.equal(JSON.stringify(visible).includes('/srv/private'),false);
 const result={permissions:visible.permissions,scope:'turn'};
 assert.deepEqual(await a.permissionReply(params,result),{permissions:params.permissions,scope:'turn'});
 assert.deepEqual(await a.permissionReply(params,{permissions:{},scope:'turn'}),{permissions:{},scope:'turn'});
 await assert.rejects(b.permissionReply(params,result),/exceeds the requested permissions/);
 await assert.rejects(a.permissionReply(params,{permissions:params.permissions,scope:'turn'}),/exceeds the requested permissions/);
 await assert.rejects(a.permissionReply(params,{...result,scope:'session'}),/turn/);
 const other=await a.outbound({permissions:{fileSystem:{read:['/srv/private/other'],write:['/srv/private/project/out']},network:{enabled:true}}}) as any;
 await assert.rejects(a.permissionReply(params,{permissions:other.permissions,scope:'turn'}),/exceeds the requested permissions/);
 assert.deepEqual((await a.permissionReply(params,{...result,extra:'ignored'})),{permissions:params.permissions,scope:'turn'});
 const keyed={permissions:{fileSystem:{'/srv/private/first/report':true,'/srv/private/second/report':false}},
   permissionsPathNames:{spoof:'bad'}};
 const projected=await a.outbound(keyed) as any;
 assert.equal(JSON.stringify(projected).includes('/srv/private'),false);
 assert.deepEqual(Object.values(projected.permissionsPathNames),['report','report']);
 assert.equal(Object.keys(projected.permissions.fileSystem).length,2);
 assert.equal(projected.permissionsPathNames.spoof,undefined);
 assert.deepEqual(await a.permissionReply(keyed,{permissions:projected.permissions,scope:'turn'}),
   {permissions:keyed.permissions,scope:'turn'});
});
