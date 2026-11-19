// Read-only canonical verification; all evidence stays in Backend/target.
import {readFileSync, writeFileSync, readdirSync} from 'node:fs';
import {createRequire} from 'node:module';
import {resolve} from 'node:path';
import assert from 'node:assert/strict';

const require = createRequire(resolve('../contracts/package.json'));
const Ajv = require('ajv/dist/2020.js');
const formats = require('ajv-formats');
const option = name => process.argv.find(value => value.startsWith(`--${name}=`))?.split('=').slice(1).join('=');
const component = process.argv.includes('--component');
const bundle = JSON.parse(readFileSync(option('bundle') || 'target/points-v1/openapi.json', 'utf8'));
const paths = JSON.parse(readFileSync('../contracts/api/paths/points.yaml', 'utf8'));
const integration = process.argv[2];
const responses = JSON.parse(readFileSync(integration || 'target/points-v1/http-responses.json', 'utf8'));
if (!integration) responses.push(...JSON.parse(readFileSync('target/points-integration/http.json', 'utf8')).filter(r =>
    r.path === '/v1/users/me/points/accepting-organizations' || r.path.startsWith('/v1/admin/points/statistics/accepting-organizations') || /\/v1\/users\/me\/points\/applications\/[^/]+:close$/.test(r.path)));
const controllers = 'src/main/java/cn/jualn/miniapp/module/points/controller';
const registered = new Set();
for (const name of readdirSync(controllers).filter(name => name.endsWith('Controller.java'))) {
    const source = readFileSync(`${controllers}/${name}`, 'utf8');
    const prefix = source.match(/@RequestMapping\("([^" ]+)"\)/)?.[1] || '';
    for (const match of source.matchAll(/@(Get|Post)Mapping(?:\(([^)]*)\))?/g)) {
        const suffixes = match[2] ? [...match[2].matchAll(/"([^" ]+)"/g)].map(value => value[1]) : [''];
        for (const suffix of suffixes) registered.add(`${match[1].toLowerCase()} ${prefix}${suffix}`);
    }
}
let operations = 0;
for (const [path, item] of Object.entries(paths)) {
    for (const method of ['get', 'post']) if (item[method]) {
        assert.ok(registered.has(`${method} ${path}`), `Missing route: ${method} ${path}`);
        operations++;
    }
}
assert.equal(registered.size, operations);
const ajv = new Ajv.default({strict: false, allErrors: true, validateFormats: true});
formats.default(ajv);
let checked = 0;
let redacted = 0;
const exercised = new Set();
const escape = value => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
for (const response of responses) {
    const path = Object.keys(paths).find(template => paths[template][response.method.toLowerCase()] && new RegExp('^' + template.split(/(\{[^}]+\})/).map(part => part.startsWith('{') ? '[^/]+' : escape(part)).join('') + '$').test(response.path.split('?')[0]));
    if (!path && integration) continue;
    const method = response.method.toLowerCase();
    assert.ok(path && paths[path][method], `Unknown operation ${response.path}`);
    exercised.add(`${method} ${path}`);
    if (integration && response.body === '<credentials redacted>') {
        redacted++;
        continue;
    }
    const actual = bundle.paths[path][method].responses[String(response.status)];
    const schema = actual?.content?.[response.status >= 400 ? 'application/problem+json' : 'application/json']?.schema;
    assert.ok(schema, `Undeclared status/media type ${response.method} ${response.path} ${response.status}`);
    const validate = ajv.compile(schema);
    assert.ok(validate(response.body), `${response.method} ${response.path}: ${ajv.errorsText(validate.errors)}`);
    if (!integration && response.status === 201) assert.ok(response.location?.startsWith('/v1/'), 'Created response needs original Location');
    if (integration) assert.equal(response.cacheControl, 'no-store');
    checked++;
}
if (!integration) assert.equal(exercised.size, operations, 'Every operation requires executed success or explicit blocked-path evidence');
assert.ok(checked > 0);
const revision = JSON.parse(readFileSync('../contracts/docs/coordination/points-v1/handoff.json', 'utf8')).revision;
const result = {
    revision,
    routes: operations,
    operationsExercised: exercised.size,
    responsesChecked: checked,
    redactedBodiesNotSchemaChecked: redacted,
    status: 'PASS',
    boundary: component ? 'Real Spring MVC/Points service/MySQL with explicit authentication/authorization/admission/media/notification doubles. Real Unified grant and owning policy are checked separately; not full-app or external-provider acceptance.' : integration ? 'Real full-app HTTP/QR/Redis/RBAC/person/Points/media owners. External WeChat/COS/safety are explicit doubles; redacted credentials/signatures excluded from schema check.'
        : 'Original core HTTP/MySQL uses synthetic identity/scope/media owners; new r3 operations also use full-app HTTP evidence. External provider and consumer readiness is not established.'
};
writeFileSync(option('output') || (integration ? 'target/points-phase-b/integration-wire-validation.json' : 'target/points-v1/wire-validation.json'), JSON.stringify(result, null, 2) + '\n');
console.log(JSON.stringify(result));
