import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';

const root=process.cwd();
const contracts=path.resolve(root,'../contracts');
const hash=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const json=p=>JSON.parse(fs.readFileSync(p,'utf8').replace(/^\uFEFF/,''));
const catalogPath=path.join(contracts,'api/catalogs/rbac-v1.json');
const catalog=json(catalogPath), schemas=json(path.join(contracts,'api/schemas/rbac.yaml'));
assert.equal(hash(catalogPath),hash('src/main/resources/rbac/rbac-v1.json'));
assert.equal(catalog.permissions.length,77);assert.equal(catalog.roles.length,22);
assert.ok([...catalog.permissions,...catalog.roles].every(x=>!x.grantable && x.readiness==='NOT_READY'));
const permissions=new Map(catalog.permissions.map(p=>[p.code,p]));
assert.equal(permissions.size,77);assert.ok(!permissions.has('*'));
const intersections=[];
for(const role of catalog.roles) {
  assert.ok(role.permissionCodes.every(p=>permissions.has(p)));
  for(const namespace of role.namespaces) {
    const members=role.permissionCodes.filter(p=>permissions.get(p).namespaces.includes(namespace));
    assert.ok(members.length>0,`${role.code}/${namespace} empty`);
    assert.ok(schemas.RbacNamespace.enum.includes(namespace));
    intersections.push({role:role.code,namespace,permissionCodes:members});
  }
}
const operations=[];
for(const [route,methods] of Object.entries(json(path.join(contracts,'api/paths/rbac.yaml')))) {
  for(const method of ['get','post']) if(methods[method]) {
    const op=methods[method]; assert.deepEqual(op.security,[{adminBearerAuth:[]}]);
    operations.push({operationId:op.operationId,method:method.toUpperCase(),route,implementationStatus:op['x-implementation-status']});
  }
}
assert.equal(operations.length,9);
const inputIndex=json(path.join(contracts,'docs/coordination/rbac-v1-inputs.json'));
const historicalInputs=inputIndex.inputs.map(x=>({...x,currentSha256:hash(x.path),unchanged:hash(x.path)===x.sha256}));
assert.ok(historicalInputs.every(x=>x.unchanged),'Historical input changed; re-review required');
const phase2=path.dirname(historicalInputs.find(x=>x.path.endsWith('/role-presets-v1.json')).path);
const actions=json(path.join(phase2,'business-actions.json'));
const routes=json(path.join(phase2,'http-entry-action-map.json'));
const presets=json(path.join(phase2,'role-presets-v1.json'));
const migration=[];
for(const preset of presets.filter(p=>p.code.startsWith('LEGACY_'))) {
  assert.equal(preset.permissions.length,preset.code==='LEGACY_OPERATOR_V1'?29:34);
  for(const permission of preset.permissions) {
    const linked=actions.filter(a=>a.permissions.includes(permission));
    migration.push({candidate:preset.code,permission,
      actions:linked.map(a=>({id:a.id,action:a.action,branch:a.branch,state:a.state,evidence:a.evidence})),
      routes:routes.filter(r=>r.actions.some(id=>linked.some(a=>a.id===id))).map(r=>({id:r.id,http:r.http,controller:r.controller})),
      catalogLifecycle:permissions.get(permission)?.lifecycle,
      conclusion:permission==='user:role'?'EXCLUDE: legacy writer needs freeze or separately contracted ROOT adapter'
        :permission==='content:manage'?'LEGACY_ONLY: split final effects; never copy coarse code into new grants'
        :'CANDIDATE_ONLY: exact old routes, parameter effects and trusted namespaces must be approved before switching'});
  }
}
assert.equal(migration.length,63);
function files(dir){return fs.readdirSync(dir,{withFileTypes:true}).flatMap(e=>e.isDirectory()?files(path.join(dir,e.name)):[path.join(dir,e.name)]);}
const legacyConsumers=[];
const mainFiles=files('src/main/java');
for(const file of [...mainFiles,...files('src/main/resources/mapper')].filter(p=>/\.(java|xml)$/.test(p)
    && !p.includes(`${path.sep}rbac${path.sep}`) && !p.endsWith('RbacMapper.xml') && !p.endsWith('DeepSeekClient.java'))) {
  const lines=fs.readFileSync(file,'utf8').split(/\r?\n/);
  const hits=lines.flatMap((line,index)=>/\brole\b|\bUserRole\b|(?:get|set)\w*Role\(|authorRole|userAuthProfile|USER_AUTH_PROFILE/.test(line)?[{line:index+1,text:line.trim()}]:[]);
  if(hits.length) legacyConsumers.push({file:file.replaceAll('\\','/'),sha256:hash(file),hits});
}
const delegatingConsumers=[];
for(const file of mainFiles.filter(p=>p.endsWith('.java') && !p.includes(`${path.sep}rbac${path.sep}`))) {
  const hits=fs.readFileSync(file,'utf8').split(/\r?\n/).flatMap((line,index)=>/permissionPolicy|AdminPermissionPolicy|AdminTokenService|getUserAuthInfo\(/.test(line)?[{line:index+1,text:line.trim()}]:[]);
  if(hits.length) delegatingConsumers.push({file:file.replaceAll('\\','/'),sha256:hash(file),hits});
}
for(const candidate of migration) for(const route of candidate.routes) {
  const [controller,method]=route.controller.split('.');
  const file=mainFiles.find(p=>path.basename(p)===controller+'.java');
  route.currentControllerMethodPresent=!!file && new RegExp(`\\b${method}\\s*\\(`).test(fs.readFileSync(file,'utf8'));
  route.currentSourceSha256=file?hash(file):null;
}
assert.ok(migration.flatMap(x=>x.routes).every(r=>r.currentControllerMethodPresent),'Traced legacy controller changed; D07 review required');
const pinnedFiles=['docs/coordination/rbac-v1.md','api/schemas/rbac.yaml','api/paths/rbac.yaml','api/catalogs/rbac-v1.json','api/examples/rbac-v1-http.json','docs/coordination/rbac-v1-validation.md'];
const result={date:'2026-10-17',boundary:'STATIC ONLY; not migration approval or Provider readiness',catalogSha256:hash(catalogPath),
  contracts:pinnedFiles.map(p=>({path:p,sha256:hash(path.join(contracts,p))})),historicalInputs,operations,intersections,migration,legacyConsumers,delegatingConsumers};
fs.mkdirSync('target',{recursive:true});fs.writeFileSync('target/rbac-g0-evidence.json',JSON.stringify(result,null,2)+'\n');
const quote=v=>'"'+String(v).replaceAll('"','""')+'"';
fs.writeFileSync('target/rbac-d07-candidates.csv','candidate,permission,lifecycle,actions,routes,conclusion\n'+migration.map(x=>[x.candidate,x.permission,x.catalogLifecycle,x.actions.map(a=>a.id).join(';'),x.routes.map(r=>r.http).join(';'),x.conclusion].map(quote).join(',')).join('\n')+'\n');
console.log(JSON.stringify({permissions:77,roles:22,operations:operations.length,namespaceIntersections:intersections.length,d07Candidates:migration.length,legacyConsumerFiles:legacyConsumers.length,delegatingConsumerFiles:delegatingConsumers.length,
  missingTracedControllerMethods:migration.flatMap(x=>x.routes).filter(r=>!r.currentControllerMethodPresent).length,historicalInputsUnchanged:true,artifactMatchesCanonical:true}));
