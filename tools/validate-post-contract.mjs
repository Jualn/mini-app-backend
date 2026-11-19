import {readFileSync} from 'node:fs';
import {createRequire} from 'node:module';
import {resolve} from 'node:path';
const require=createRequire(resolve('../contracts/package.json'));
const Ajv=require('ajv/dist/2020').default;
const ajv=new Ajv({strict:false,allErrors:true});
require('ajv-formats')(ajv);
const api=JSON.parse(readFileSync('../contracts/.validation/openapi.json','utf8'));
const responses=JSON.parse(readFileSync(process.argv[2] ?? 'target/post-contract-responses.json','utf8'));
if(responses.length<7) throw new Error('Required real MVC/MySQL representations are missing');
for(const response of responses) {
  const schema=api.components.schemas[response.schema];
  if(!schema) throw new Error(`Canonical schema missing: ${response.schema}`);
  const valid=ajv.compile(schema);
  if(!valid(response.body)) throw new Error(`${response.schema}: ${JSON.stringify(valid.errors)}`);
}
console.log(`PASS: ${responses.length} real MVC/MySQL representations conform to canonical Post schemas. Identity, safety and media providers remain doubles.`);
