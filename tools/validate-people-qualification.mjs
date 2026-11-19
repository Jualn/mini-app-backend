// Validate actual Provider HTTP/MVC wire against the current canonical Contract.
import fs from 'node:fs';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {resolve} from 'node:path';
const require=createRequire(resolve('../contracts/package.json'));
const Ajv=require('ajv/dist/2020.js').default;
const formats=require('ajv-formats').default;
const ajv=new Ajv({strict:false,allErrors:true});formats(ajv);
const read=p=>JSON.parse(fs.readFileSync(p,'utf8'));
const paths=read('../contracts/api/paths/people-qualification.yaml');
const bundle=read('target/people-qualification/openapi.json');
const rows=read('target/people-qualification/http.json');
const operations=Object.entries(paths).flatMap(([path,item])=>Object.entries(item).map(([method,op])=>({path,method,op})));
const coverage=new Set();let validated=0;
const escape=s=>s.replace(/[.*+?^${}()|[\]\\]/g,'\\$&');
for(const row of rows) {
    const actual=row.path.split('?')[0];
    const operation=operations.find(o=>o.method===row.method.toLowerCase() && o.path===actual)
        ??operations.find(o=>o.method===row.method.toLowerCase() && new RegExp('^'+o.path.split(/(\{[^}]+\})/).map(s=>s.startsWith('{')?'[^/]+':escape(s)).join('')+'$').test(actual));
    assert(operation,`${row.method} ${row.path}`);
    const response=bundle.paths[operation.path][operation.method].responses[row.status];
    assert(response,`undeclared status ${operation.op.operationId}/${row.status}`);
    const media=row.status<400?'application/json':'application/problem+json';
    const validate=ajv.compile(response.content[media].schema);
    assert(validate(row.body),`${operation.op.operationId}/${row.status}: ${ajv.errorsText(validate.errors)}`);
    assert.equal(row.cacheControl,'no-store');assert(row.contentType.startsWith(media));
    if(row.status<400) {
        coverage.add(operation.op.operationId);
        if(response.headers?.ETag) assert.match(row.etag,/^"[^"\r\n]+"$/);
        if(response.headers?.Location) assert(row.location?.startsWith('/v1/'));
    }
    validated++;
}
assert.equal(coverage.size,operations.length,`missing: ${operations.filter(o=>!coverage.has(o.op.operationId)).map(o=>o.op.operationId)}`);
const readerSchema=read('../contracts/docs/coordination/identity-organization-v1/readers.schema.json');
ajv.addSchema(readerSchema);
const validateReader=ajv.getSchema(readerSchema.$id+'#/$defs/IdentityReaderResult');
const reader=read('target/people-qualification/reader.json');
assert.equal(reader.source,'ROSTER_EXACT_MATCH');
assert(validateReader(reader),ajv.errorsText(validateReader.errors));
const result={status:'PASS',operations:coverage.size,responses:validated,identityReader:'PASS: actual transaction-bound ROSTER_EXACT_MATCH result',authentication:'Synthetic admin/session adapter; Mini MVC Sa adapter and throttle doubles. No deployed client claim.'};
fs.writeFileSync('target/people-qualification/wire-validation.json',JSON.stringify(result,null,2));
console.log(JSON.stringify(result));
