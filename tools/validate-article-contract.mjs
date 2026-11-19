import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { resolve } from 'node:path';

// Reads canonical tooling and schemas; never writes to the Contracts sources.
const require = createRequire(resolve('../contracts/package.json'));
const Ajv = require('ajv/dist/2020').default;
const addFormats = require('ajv-formats');
const ajv = new Ajv({strict: false, allErrors: true});
addFormats(ajv);
const fixedContent = process.argv.includes('--article-content');
const api = JSON.parse(readFileSync(fixedContent ? 'target/article-content-v1-r1/openapi.json' : '../contracts/.validation/openapi.json', 'utf8'));
const responses = ['target/article-contract-responses.json', ...(process.argv.includes('--organization') ? ['target/article-org-http-responses.json'] : []), ...(fixedContent ? ['target/article-content-http-responses.json'] : [])].flatMap(path => JSON.parse(readFileSync(path, 'utf8')));
const validators = new Map();
for (const response of responses) {
  if (!validators.has(response.schema)) {
    const schema = api.components.schemas[response.schema];
    if (!schema) throw new Error(`Canonical schema is missing: ${response.schema}`);
    validators.set(response.schema, ajv.compile(schema));
  }
  const validate = validators.get(response.schema);
  if (!validate(response.body)) throw new Error(`${response.schema} HTTP ${response.status}: ${JSON.stringify(validate.errors)}`);
}
console.log(`PASS: ${responses.length} real MVC/MySQL response representations, ${validators.size} canonical schemas. Auth/safety/COS/Redis remain test doubles.`);
