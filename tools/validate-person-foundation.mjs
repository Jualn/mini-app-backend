// Read-only canonical and actual HTTP evidence check. Outputs only Backend/target.
import fs from 'node:fs';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { resolve } from 'node:path';
const read=p=>JSON.parse(fs.readFileSync(p,'utf8'));
const hash=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const handoff=read('../contracts/docs/coordination/identity-organization-v1/handoff.json');
assert.equal(handoff.revision,'identity-organization-v1-r3');
for(const file of handoff.files) assert.equal(hash(`../contracts/${file.path}`),file.sha256,file.path);
for(const [canonical,local] of [['rbac-v1','rbac/rbac-v1'],['person-foundation-v1','person/person-foundation-v1']])
  assert.equal(hash(`../contracts/api/catalogs/${canonical}.json`),hash(`src/main/resources/${local}.json`));
const paths=read('../contracts/api/paths/person-foundation.yaml');
const source=fs.readFileSync('src/main/java/cn/jualn/miniapp/module/person/controller/PersonController.java','utf8');
const registered=new Set([...source.matchAll(/@(Get|Post|Put|Patch)Mapping\("([^"]+)"\)/g)].map(m=>`${m[1].toLowerCase()} ${m[2]}`));
const operations=Object.entries(paths).flatMap(([path,item])=>Object.entries(item).filter(([method])=>['get','post','put','patch'].includes(method)).map(([method,op])=>({path,method,op})));
assert.equal(operations.length,20);assert.equal(registered.size,20);
for(const op of operations) assert(registered.has(`${op.method} ${op.path}`));
const require=createRequire(resolve('../contracts/package.json'));
const Ajv=require('ajv/dist/2020.js'),formats=require('ajv-formats');
const ajv=new Ajv.default({strict:false,allErrors:true});formats.default(ajv);
const bundle=read('target/person-foundation/openapi.json'),rows=read('target/person-foundation/http.json'),coverage={};
const escape=s=>s.replace(/[.*+?^${}()|[\]\\]/g,'\\$&');
for(const row of rows.filter(r=>!r.path.startsWith('/v1/admin/authorization/'))) {
  const operation=operations.find(o=>o.method===row.method.toLowerCase() && new RegExp('^'+o.path.split(/(\{[^}]+\})/).map(s=>s.startsWith('{')?'[^/]+':escape(s)).join('')+'$').test(row.path.split('?')[0]));
  assert(operation,`${row.method} ${row.path}`);
  const response=bundle.paths[operation.path][operation.method].responses[row.status];
  assert(response,`undeclared status ${operation.op.operationId}/${row.status}`);
  const media=row.status<400?'application/json':'application/problem+json';
  const validate=ajv.compile(response.content[media].schema);
  assert(validate(row.body),`${operation.op.operationId}/${row.status}: ${ajv.errorsText(validate.errors)}`);
  if(row.status<400) {
    coverage[operation.op.operationId]=(coverage[operation.op.operationId]??0)+1;
    if(!operation.op.operationId.startsWith('list')) assert.match(row.etag,/^"[^"\r\n]+"$/);
    if(row.status===201) assert(row.location?.startsWith('/v1/admin/'));
  }
  assert.equal(row.cacheControl,'no-store');assert(row.contentType.startsWith(media));
}
assert.equal(Object.keys(coverage).length,20);
const readerSchemas=read('../contracts/docs/coordination/identity-organization-v1/readers.schema.json');
const readerRows=read('target/person-foundation/readers.json');
ajv.addSchema(readerSchemas);
for(const row of readerRows) {
  const validate=ajv.getSchema(`${readerSchemas.$id}#/$defs/${row.schema}`);
  assert(validate(row.body),`${row.schema}: ${ajv.errorsText(validate.errors)}`);
}
assert(readerRows.length>=10);
const result={status:'PASS',revision:handoff.revision,manifestFiles:handoff.files.length,operations:20,responses:rows.length,coverage,
 readerResults:readerRows.length,
 boundary:'Real loopback HTTP/MySQL/owner services. Session and capability adapter are synthetic. Candidate stage confirmation is never production evidence.'};
fs.writeFileSync('target/person-foundation/validation.json',JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
