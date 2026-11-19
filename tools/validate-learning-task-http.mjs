import {readFileSync} from 'node:fs';
import {createRequire} from 'node:module';
import {resolve} from 'node:path';

// Canonical sources/dependencies are read-only; the fresh Redocly bundle and all outputs live in Backend target/.
const require = createRequire(resolve('../contracts/package.json'));
const Ajv = require('ajv/dist/2020').default;
const addFormats = require('ajv-formats');
const ajv = new Ajv({strict: false, allErrors: true});
addFormats(ajv);
const [fixturePath = 'target/learning-task-b2-http-responses.json', contractPath = 'target/learning-task-b2-contract.json'] = process.argv.slice(2);
const api = JSON.parse(readFileSync(contractPath, 'utf8'));
const fixtures = JSON.parse(readFileSync(fixturePath, 'utf8'));
if (!Array.isArray(fixtures) || fixtures.length < (process.argv[2] ? 1 : 35)) throw new Error('Expected recorded response fixtures; the default historical suite requires at least 35.');
const validators = new Map();
for (const fixture of fixtures) {
    if (!validators.has(fixture.schema)) {
        const schema = api.components.schemas[fixture.schema];
        if (!schema) throw new Error('Missing canonical schema: ' + fixture.schema);
        validators.set(fixture.schema, ajv.compile(schema));
    }
    const validate = validators.get(fixture.schema);
    if (!validate(fixture.body)) {
        throw new Error(fixture.schema + ' HTTP ' + fixture.status + ': ' + JSON.stringify(validate.errors));
    }
}
console.log('PASS: ' + fixtures.length + ' recorded responses; ' + validators.size + ' canonical schemas. Runtime/identity/provider coverage is limited to the fixture producer; schema validation is not deployment proof.');
