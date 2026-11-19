// Validate actual provider HTTP evidence; no dependency on temporary handoff directories.
import fs from 'node:fs';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {resolve} from 'node:path';
const read = path => JSON.parse(fs.readFileSync(path, 'utf8'));
const require = createRequire(resolve('../contracts/package.json'));
const Ajv = require('ajv/dist/2020.js').default;
const formats = require('ajv-formats').default;
const ajv = new Ajv({strict: false, allErrors: true});
formats(ajv);
const bundle = read('target/query-optimization/openapi.json');
const declarations = Object.keys(read('../contracts/api/paths/admin-query.yaml'));
const parents = ['/v1/admin/activities', '/v1/admin/activities/{activityId}',
    '/v1/admin/public-events', '/v1/admin/public-events/{publicEventId}'];
const success = new Set();
let validated = 0, parentProjections = 0;
for (const row of read('target/points-integration/http.json')) {
    const actual = row.path.split('?')[0];
    const path = [...declarations, ...parents].find(path =>
        new RegExp('^' + path.replace(/\{[^}]+\}/g, '[^/]+') + '$').test(actual));
    if (!path) continue;
    const operation = bundle.paths[path]?.[row.method.toLowerCase()];
    if (!operation) continue;
    const response = operation.responses[row.status];
    assert(response, `${row.method} ${path}: undeclared ${row.status}`);
    const media = row.status < 400 ? 'application/json' : 'application/problem+json';
    const validate = ajv.compile(response.content[media].schema);
    assert(validate(row.body), `${operation.operationId}/${row.status}: ${ajv.errorsText(validate.errors)}`);
    assert.equal(row.cacheControl, 'no-store', operation.operationId);
    assert(row.contentType.startsWith(media));
    validated++;
    if (row.status >= 400) continue;
    if (declarations.includes(path)) success.add(operation.operationId);
    if (parents.includes(path) && ['GET', 'POST', 'PUT'].includes(row.method) && row.body.draft) {
        const references = row.body.draft.attachments.slice().sort((a, b) =>
            a.displayOrder - b.displayOrder || (BigInt(a.attachmentId) < BigInt(b.attachmentId) ? -1 : 1));
        assert.deepEqual(row.body.attachmentDetails.items.map(item => item.attachmentId),
            [...new Set(references.map(item => item.attachmentId))]);
        parentProjections++;
    }
    if (operation.operationId === 'getAdminAttachments') {
        const ids = new URL(row.path, 'http://localhost').searchParams.getAll('attachmentId');
        assert.deepEqual(row.body.items.map(item => item.attachmentId), ids);
    }
    for (const item of row.body.items ?? row.body.attachmentDetails?.items ?? []) {
        if (item.attachment) assert.equal(item.attachment.attachmentId, item.attachmentId);
    }
}
assert.equal(success.size, 5, 'All five operations need successful actual HTTP evidence');
assert(parentProjections > 0, 'Missing parent projection evidence');
const result = {status: 'PASS', boundary: 'Local real HTTP/Sa/Redis/MySQL; synthetic duties and external Provider doubles',
    validated, successfulOperations: [...success].sort(), parentProjections,
    deployment: 'NOT_VERIFIED', realProvider: 'NOT_RUN'};
fs.mkdirSync('target/query-optimization', {recursive: true});
fs.writeFileSync('target/query-optimization/wire-validation.json', JSON.stringify(result, null, 2) + '\n');
console.log(JSON.stringify(result));
