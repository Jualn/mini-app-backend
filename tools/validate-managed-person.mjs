import fs from 'node:fs';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {resolve} from 'node:path';
const require = createRequire(resolve('../contracts/package.json'));
const Ajv = require('ajv/dist/2020.js').default;
const formats = require('ajv-formats').default;
const ajv = new Ajv({strict: false, allErrors: true}); formats(ajv);
const read = p => JSON.parse(fs.readFileSync(p, 'utf8').replace(/^\uFEFF/, ''));
const hash = p => crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const revisions = [['unified-v1','unified-v1-r4'],['identity-organization-v1','identity-organization-v1-r5'],['points-v1','points-v1-r5']];
let manifest;
for (const [name, revision] of revisions) {
  const handoff = read(`../contracts/docs/coordination/${name}/handoff.json`);
  assert.equal(handoff.revision, revision);
  if (manifest) assert.deepEqual(handoff.files, manifest);
  else manifest = handoff.files;
  for (const file of handoff.files) assert.equal(hash(`../contracts/${file.path}`), file.sha256, file.path);
}
const bundle = read('target/independent-person/openapi.json');
const rows = read('target/independent-person/http.json');
const coverage = {};
const escape = s => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
for (const row of rows) {
  const path = Object.keys(bundle.paths).find(p => new RegExp('^' + p.split(/(\{[^}]+\})/).map(s => s.startsWith('{') ? '[^/]+' : escape(s)).join('') + '$').test(row.path.split('?')[0]) && bundle.paths[p][row.method.toLowerCase()]);
  assert(path, `${row.method} ${row.path}`);
  const op = bundle.paths[path][row.method.toLowerCase()];
  const response = op.responses[row.status]; assert(response, `${op.operationId}/${row.status}`);
  const media = row.status < 400 ? 'application/json' : 'application/problem+json';
  const validate = ajv.compile(response.content[media].schema);
  assert(validate(row.body), `${op.operationId}/${row.status}: ${ajv.errorsText(validate.errors)}`);
  assert.equal(row.cacheControl, 'no-store'); assert(row.contentType.startsWith(media));
  if (row.status === 201) assert(row.location.startsWith('/v1/admin/'));
  if (row.status < 400) coverage[op.operationId] = (coverage[op.operationId] ?? 0) + 1;
}
assert.equal(Object.keys(coverage).length, 20);
const result = {status: 'PASS', revision: 'identity-organization-v1-r5', responses: rows.length, coverage,
  boundary: '20 independent aggregate Admin operations through real loopback HTTP/MySQL. Synthetic Admin identity/capability adapter. This file covers only core aggregate representations; business chains and authorization races have separate JUnit evidence, not inferred from wire validation.'};
fs.writeFileSync('target/independent-person/wire-validation.json', JSON.stringify(result, null, 2));
console.log(JSON.stringify(result));

if (process.argv.includes('--full')) {
const businessRows = read('target/points-integration/http.json');
const selected=businessRows.filter(r=>/\/points\//.test(r.path)||/\/learning-task/.test(r.path)||r.path.includes('/notifications?representation=structured'));
let count=0;const coverage={},statuses={};
for(const r of selected){ if(typeof r.body === "string" && r.body.includes("redacted")) continue;
const path=Object.keys(bundle.paths).find(p=>new RegExp('^'+p.split(/(\{[^}]+\})/).map(s=>s.startsWith('{')?'[^/]+':s.replace(/[.*+?^${}()|[\]\\]/g,'\\$&')).join('')+'$').test(r.path.split('?')[0])&&bundle.paths[p][r.method.toLowerCase()]);
assert(path,`${r.method} ${r.path}`);const op=bundle.paths[path][r.method.toLowerCase()],res=op.responses[r.status];assert(res,`${op.operationId}/${r.status}`);
const content=res.content;let schema=content?.[r.status>=400?'application/problem+json':'application/json']?.schema;
assert(schema,op.operationId);const validate=ajv.compile(schema);assert(validate(r.body),`${op.operationId}/${r.status}: ${ajv.errorsText(validate.errors)}`);assert.equal(r.cacheControl,'no-store');coverage[op.operationId]=(coverage[op.operationId]??0)+1;statuses[`${op.operationId}/${r.status}`]=(statuses[`${op.operationId}/${r.status}`]??0)+1;count++;
}
for (const expected of ['createPointApplication/201','reviewPointApplication/200','getMyPointSummary/200','publishLearningTask/200','confirmLearningTask/200','listMyNotifications/200']) assert(statuses[expected],`Missing actual success: ${expected}`);
fs.writeFileSync('target/independent-person/v66/full-wire-validation.json',JSON.stringify({status:'PASS',responses:count,coverage,statuses,boundary:'Actual isolated full Spring app HTTP, Sa/Redis/RBAC and business owners; external WeChat/COS/safety doubles. Redacted credential responses excluded. Reverse is denied by existing capabilities, not a successful reverse acceptance. No production/device acceptance.'},null,2));console.log(`PASS ${count} business HTTP responses`);

}
