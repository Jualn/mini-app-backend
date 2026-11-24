// Focused provider evidence: local membership paths only, not unified authorization / Points / Learning readiness.
import fs from 'node:fs';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {resolve} from 'node:path';
const read=p=>JSON.parse(fs.readFileSync(p,'utf8'));
const hash=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const h=read('../contracts/docs/coordination/unified-v1/handoff.json');
assert.equal(h.revision,'unified-v1-r1');
for(const f of h.files) assert.equal(hash(`../contracts/${f.path}`),f.sha256,f.path);
assert.equal(hash('../contracts/api/catalogs/rbac-v1.json'),hash('src/main/resources/rbac/rbac-v1.json'));
const require=createRequire(resolve('../contracts/package.json'));
const Ajv=require('ajv/dist/2020.js').default,formats=require('ajv-formats').default;
const ajv=new Ajv({strict:false,allErrors:true});formats(ajv);
const bundle=read('target/person-foundation/openapi.json');
const paths=Object.entries(bundle.paths).filter(([p])=>p.startsWith('/v1/admin/organizations/{organizationId}/members/{userId}'));
assert.equal(paths.length,3);
const evidence=read('target/person-foundation/http.json').filter(r=>/^\/v1\/admin\/organizations\/[^/]+\/members\/[^/]+/.test(r.path));
const coverage=new Set();
for(const row of evidence) {
    const [path,methods]=paths.find(([p])=>new RegExp('^'+p.replace(/\{[^}]+\}/g,'[^/]+')+'$').test(row.path.split('?')[0]))??[];
    assert(path,`${row.method} ${row.path}`);
    const operation=methods[row.method.toLowerCase()];assert(operation);
    const response=operation.responses[row.status];assert(response,`undeclared ${row.status}`);
    const media=row.status<400?'application/json':'application/problem+json';
    const validate=ajv.compile(response.content[media].schema);
    assert(validate(row.body),`${operation.operationId}: ${ajv.errorsText(validate.errors)}`);
    assert.equal(row.cacheControl,'no-store');assert(row.contentType.startsWith(media));
    if(row.status<400) coverage.add(operation.operationId);
}
assert.equal(coverage.size,3);
const result={boundary:'LOCAL_MEMBERSHIP_ONLY; synthetic session/capability, real MySQL/HTTP/owner transactions',revision:h.revision,
    manifestFiles:h.files.length,responses:evidence.length,operations:[...coverage].sort(),status:'PASS',release:'NOT_VERIFIED'};
fs.mkdirSync('target/unified-v1',{recursive:true});
fs.writeFileSync('target/unified-v1/local-membership-wire.json',JSON.stringify(result,null,2)+'\n');
console.log(JSON.stringify(result));
