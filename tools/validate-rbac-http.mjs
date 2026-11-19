import fs from 'node:fs';
import assert from 'node:assert/strict';
import Ajv2020 from '../../contracts/node_modules/ajv/dist/2020.js';
import addFormats from '../../contracts/node_modules/ajv-formats/dist/index.js';

// Input bundle is generated into Backend/target from the unmodified canonical entry.
const api = JSON.parse(fs.readFileSync('target/rbac-3c-openapi.json', 'utf8'));
const records = JSON.parse(fs.readFileSync(process.argv[2] ?? 'target/p1-security-http-responses.json', 'utf8'));
const ajv = new Ajv2020({strict: false, allErrors: true, validateFormats: true});
addFormats(ajv);
const routes = Object.entries(api.paths).filter(([p]) => p.startsWith('/v1/admin/authorization/')).sort(([a], [b]) => b.length - a.length);
const coverage = {};
let checked = 0, requestsChecked = 0;

function validate(schema, value, label) {
    const check = ajv.compile(schema);
    assert(check(value), `${label}: ${JSON.stringify(check.errors)}`);
}

for (const record of records.filter(r => r.path.startsWith('/v1/admin/authorization/'))) {
    const pathname = record.path.split('?')[0];
    const found = routes.find(([p]) => new RegExp('^' + p.replace(/[.*+?^$()|[\]\\]/g, '\\$&').replace(/\{[^}]+\}/g, '[^/]+') + '$').test(pathname));
    assert(found, `Unknown RBAC route ${pathname}`);
    const op = found[1][record.method.toLowerCase()];
    assert(op, `Unexpected method ${record.method}`);
    const response = op.responses[String(record.status)];
    assert(response, `${op.operationId}: unexpected ${record.status}`);
    const media = record.status >= 400 ? 'application/problem+json' : 'application/json';
    assert.equal(record.cacheControl, 'no-store', `${op.operationId} cache policy`);
    assert(record.contentType.startsWith(media), `${op.operationId} media type`);
    const body = JSON.parse(record.body);
    validate(response.content[media].schema, body, `${op.operationId}/${record.status}`);
    if (record.requestBody && record.status !== 400) {
        validate(op.requestBody.content['application/json'].schema, JSON.parse(record.requestBody), `${op.operationId} request`);
        requestsChecked++;
    }
    if (record.status === 201) assert.equal(record.location, `/v1/admin/authorization/users/${body.assignment.userId}/role-assignments/${body.assignment.assignmentId}`);
    const item = coverage[op.operationId] ??= {requests: 0, statuses: new Set(), success: false};
    item.requests++;
    item.statuses.add(record.status);
    item.success ||= record.status === 200 || record.status === 201;
    checked++;
}
assert.equal(Object.keys(coverage).length, 9, 'All nine operations must execute');
for (const [operation, c] of Object.entries(coverage)) assert(c.success, `${operation} needs real success evidence`);
const examples = JSON.parse(fs.readFileSync('../contracts/api/examples/rbac-v1-http.json', 'utf8'));
let exampleResponses = 0, exampleRequests = 0, negativeExampleRequests = 0;
for (const c of examples.cases) {
    const op = api.paths[c.operationPath]?.[c.request.method.toLowerCase()];
    assert(op, c.name);
    const media = c.response.status >= 400 ? 'application/problem+json' : 'application/json';
    validate(op.responses[String(c.response.status)].content[media].schema, c.response.body, c.name);
    exampleResponses++;
    if (c.request.body) {
        const valid = ajv.compile(op.requestBody.content['application/json'].schema)(c.request.body);
        assert.equal(valid, c.requestValid !== false, `${c.name}: canonical example request validity`);
        exampleRequests++;
        if (!valid) negativeExampleRequests++;
    }
}
const result = {
    boundary: 'Canonical wire schemas against recorded real HTTP, including normalized timed grants and expiry validation',
    checkedResponses: checked, checkedRequests: requestsChecked, canonicalExampleResponses: exampleResponses,
    canonicalExampleRequests: exampleRequests, negativeExampleRequests,
    operations: Object.fromEntries(Object.entries(coverage).map(([k, c]) => [k, {
        requests: c.requests,
        statuses: [...c.statuses].sort(),
        success: c.success
    }]))
};
fs.writeFileSync('target/rbac-3c-wire-validation.json', JSON.stringify(result, null, 2) + '\n');
console.log(JSON.stringify(result));
