import fs from 'node:fs'
import assert from 'node:assert/strict'
import Ajv2020 from '../../contracts/node_modules/ajv/dist/2020.js'
import addFormats from '../../contracts/node_modules/ajv-formats/dist/index.js'
const dir = 'target/rbac-offline-online'
const api = JSON.parse(fs.readFileSync(`${dir}/openapi.json`, 'utf8'))
const records = JSON.parse(fs.readFileSync(`${dir}/http.json`, 'utf8'))
const steps = JSON.parse(fs.readFileSync(`${dir}/steps.json`, 'utf8'))
const facts = JSON.parse(fs.readFileSync(`${dir}/facts.json`, 'utf8'))
const ajv = new Ajv2020({ strict: false, allErrors: true })
addFormats(ajv)
const operations = Object.entries(api.paths).flatMap(([path, item]) => Object.entries(item).filter(([method, op]) => ['get', 'post'].includes(method) && op.operationId && path.startsWith('/v1/admin/authorization/')).map(([method, op]) => ({
  method: method.toUpperCase(), op,
  match: new RegExp(`^${path.split(/(\{[^}]+\})/).map((s) => s.startsWith('{') ? '[^/]+' : s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('')}$`),
})))
const coverage = {}, phases = {}
let checked = 0, requests = 0
for (const row of records) {
  phases[row.phase] ??= { total: 0, statuses: {} }
  phases[row.phase].total++
  phases[row.phase].statuses[row.status] = (phases[row.phase].statuses[row.status] || 0) + 1
  if (!row.path.startsWith('/v1/admin/authorization/')) continue
  const found = operations.find((o) => o.method === row.method && o.match.test(row.path.split('?')[0]))
  assert(found, `${row.method} ${row.path}`)
  const response = found.op.responses[String(row.status)]
  assert(response, `${found.op.operationId}/${row.status}`)
  const media = row.status >= 400 ? 'application/problem+json' : 'application/json'
  assert.equal(row.cacheControl, 'no-store')
  assert(row.contentType.startsWith(media))
  let validate = ajv.compile(response.content[media].schema)
  assert(validate(row.body), `${found.op.operationId}: ${ajv.errorsText(validate.errors)}`)
  if (row.requestBody && row.status !== 400) {
    validate = ajv.compile(found.op.requestBody.content['application/json'].schema)
    assert(validate(row.requestBody), `request: ${ajv.errorsText(validate.errors)}`)
    requests++
  }
  if (row.status === 201) assert.equal(row.location, `/v1/admin/authorization/users/${row.body.assignment.userId}/role-assignments/${row.body.assignment.assignmentId}`)
  coverage[found.op.operationId] ??= { responses: 0, statuses: [], success: false }
  const c = coverage[found.op.operationId]
  c.responses++; if (!c.statuses.includes(row.status)) c.statuses.push(row.status)
  c.success ||= row.status === 200 || row.status === 201
  checked++
}
assert.equal(Object.keys(coverage).length, 9)
assert(Object.values(coverage).every((c) => c.success))
assert(steps.some((s) => s.label === 'COMPLETE'), 'Full sequence must complete')
const rootCodes = ['audit-log:read', 'authorization:assignment-manage', 'authorization:read', 'authorization:root-manage']
for (const r of records.filter((r) => r.phase === 'BOOTSTRAP_ONLINE' && r.path.endsWith('/me/capabilities') && r.status === 200)) {
  assert.deepEqual([...r.body.grants[0].permissionCodes].sort(), rootCodes)
  assert.equal(r.body.grants[0].namespace, 'AUTHORIZATION')
}
assert(records.some((r) => r.phase === 'SOURCE_SWITCH_WINDOW' && r.body.canUseAdmin === false))
assert.equal(records.filter((r) => r.phase === 'NEGATIVE_OFFLINE_AUDIT_REFERENCE' && r.status === 503).length, 4)
for (const a of facts.assignments) {
  const normal = facts.changes.find((c) => c.id === a.grant_change_id && c.action === 'GRANT' && c.assignment_id === a.id)
  const offline = facts.effects.find((e) => e.id === a.grant_change_id && e.assignment_id === a.id && String(e.user_id) === String(a.user_id))
  assert(Boolean(normal) !== Boolean(offline), 'A grant must resolve to exactly one genuine audit owner')
  if (a.revoked_at != null) assert(facts.changes.some((c) => c.id === a.revoke_change_id && c.action === 'REVOKE' && c.assignment_id === a.id))
}
const unknown = steps.find((s) => s.label === 'SOURCE_SWITCH UNKNOWN_OUTCOME')
assert(unknown)
assert.equal(facts.offlineOperations.filter((o) => o.id === unknown.value.operationId && o.kind === 'SOURCE_SWITCH').length, 1)
assert(facts.changes.every((c) => ['GRANT', 'REVOKE'].includes(c.action)))
const result = { boundary: 'Full application offline CLI -> real QR/Sa/Redis -> real HTTP/primary DB; external WeChat fixture only', totalHttp: records.length, checkedRbacResponses: checked, checkedRequests: requests, coverage, phases,
  references: { assignments: facts.assignments.length, changes: facts.changes.length, offlineOperations: facts.offlineOperations.length, offlineEffects: facts.effects.length, exclusiveAuditOwners: true, unknownSourceSwitchSingleCommit: true } }
fs.writeFileSync(`${dir}/wire-validation.json`, JSON.stringify(result, null, 2) + '\n')
console.log(JSON.stringify(result))
