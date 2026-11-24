// Actual wire evidence against the fixed canonical handoff. No sibling mutations.
import fs from 'node:fs';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import {createRequire} from 'node:module';
import {resolve} from 'node:path';
const read=p=>JSON.parse(fs.readFileSync(p,'utf8'));
const handoff=read('../contracts/docs/coordination/unified-v1/handoff.json');
for(const f of handoff.files) assert.equal(crypto.createHash('sha256').update(fs.readFileSync('../contracts/'+f.path)).digest('hex'),f.sha256,f.path);
const require=createRequire(resolve('../contracts/package.json'));
const Ajv=require('ajv/dist/2020.js').default,formats=require('ajv-formats').default;
const ajv=new Ajv({strict:false,allErrors:true});formats(ajv);
const bundle=read('target/person-foundation/openapi.json');
const groups=[['person','person-foundation','target/person-foundation/http.json'],['unified','unified-authorization','target/person-foundation/http.json'],['points','points','target/points-integration/http.json'],['learning','learning-task','target/points-integration/http.json']];
const results=[];
for(const [name,file,evidence] of groups) {
 const paths=read('../contracts/api/paths/'+file+'.yaml'),ops=new Map(),success=new Set();let count=0,redacted=0;
 for(const [path,methods] of Object.entries(paths)) for(const [method,op] of Object.entries(methods)) if(['get','post','put','patch'].includes(method))ops.set(method+' '+path,op.operationId);
 for(const row of read(evidence)) {
  const path=Object.keys(paths).find(p=>paths[p][row.method.toLowerCase()] && new RegExp('^'+p.replace(/\{[^}]+\}/g,'[^/]+')+'$').test(row.path.split('?')[0]));
  if(!path || !paths[path][row.method.toLowerCase()])continue;
  const op=paths[path][row.method.toLowerCase()],r=bundle.paths[path][row.method.toLowerCase()].responses[row.status];
  assert(r,`${name} undeclared ${row.method} ${row.path} ${row.status}`);assert.equal(row.cacheControl,'no-store');
  if(row.body==='<credentials redacted>'){redacted++;continue;}
  const media=row.status<400?'application/json':'application/problem+json',schema=r.content?.[media]?.schema;
  assert(schema,`${op.operationId}/${row.status} missing schema`);const validate=ajv.compile(schema);
  assert(validate(row.body),`${op.operationId}/${row.status}: ${ajv.errorsText(validate.errors)}`);count++;
  if(row.status<400)success.add(op.operationId);
 }
 if(name==='unified')assert.equal(success.size,7);
 if(name==='person')assert.equal(success.size,19);
 assert(count>0,name+' missing actual wire evidence');
 results.push({module:name,declared:ops.size,validated:count,redacted,successfulOperations:[...success].sort(),unvalidatedSuccessOperations:[...ops.values()].filter(id=>!success.has(id))});
}
fs.mkdirSync('target/unified-v1',{recursive:true});fs.writeFileSync('target/unified-v1/wire-validation.json',JSON.stringify({status:'PASS',manifestFiles:handoff.files.length,results},null,2)+'\n');console.log(JSON.stringify(results));
