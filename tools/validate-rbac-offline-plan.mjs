// Read-only source checks; writes evidence only under target, never executes SQL or enables a role.
import fs from 'node:fs'
import crypto from 'node:crypto'
import assert from 'node:assert/strict'
const hash = (path) => crypto.createHash('sha256').update(fs.readFileSync(path)).digest('hex')
const canonical = '../contracts/api/catalogs/rbac-v1.json'
const catalog = JSON.parse(fs.readFileSync(canonical, 'utf8'))
assert.equal(hash(canonical), hash('src/main/resources/rbac/rbac-v1.json'))
assert.equal(hash(canonical), 'cf6ac904e3c78a93a07f61fa7e7624ce21f1a4390d8ad2030fba76b808b8165c')
assert.equal(hash('src/main/resources/db/migration/V41__add_rbac_foundation.sql'), '23fc7a6230ccbfc792312b41739ad1532ab32fb47d8a24248b741e193ef131b3')
const expected = {
  ROOT: ['audit-log:read', 'authorization:assignment-manage', 'authorization:read', 'authorization:root-manage'],
  NOTICE_MONITOR: ['notice:read', 'system:read'],
}
const roles = Object.entries(expected).map(([code, members]) => {
  const role = catalog.roles.find((r) => r.code === code)
  assert.deepEqual([...role.permissionCodes].sort(), [...members].sort())
  assert.equal(role.grantable, false)
  return {
    code, completeMembers: role.permissionCodes, namespaces: role.namespaces,
    intersections: role.namespaces.map((namespace) => ({ namespace, scope: 'GLOBAL', permissions: members.filter((p) => catalog.permissions.find((x) => x.code === p).namespaces.includes(namespace)) })),
    productionApproval: 'NOT_APPROVED', productionEnabled: false,
  }
})
const source = fs.readFileSync('src/main/java/cn/jualn/miniapp/module/authorization/service/RbacCatalog.java', 'utf8')
assert.match(source, /public RbacCatalog\(\)\s*\{\s*this\(implementedSupport\(\)\)/)
const tool = fs.readFileSync('src/main/java/cn/jualn/miniapp/module/authorization/service/RbacOfflineTool.java', 'utf8')
assert.doesNotMatch(tool, /@(Component|Service|RestController)|implements\s+(ApplicationRunner|CommandLineRunner)/)
const migration = fs.readFileSync('src/main/resources/db/migration/V43__add_rbac_offline_governance_facts.sql', 'utf8')
assert.equal((migration.match(/CREATE TABLE/g) || []).length, 2)
assert.doesNotMatch(migration, /\b(INSERT|UPDATE|DELETE|ALTER|DROP)\s/i)
const providers = [
  'src/main/java/cn/jualn/miniapp/module/authorization/web/RbacController.java',
  'src/main/java/cn/jualn/miniapp/module/authorization/service/RbacGovernanceService.java',
  'src/main/java/cn/jualn/miniapp/module/authorization/service/RbacQueryService.java',
  'src/main/java/cn/jualn/miniapp/module/notify/service/impl/AdminNotificationPlanServiceImpl.java',
  'src/main/java/cn/jualn/miniapp/module/admin/system/service/impl/AdminSystemServiceImpl.java',
].map((path) => ({ path, sha256: hash(path) }))
const d07 = 'docs/operations/rbac-v1-release-review/d07-reviewed.csv'
fs.mkdirSync('target', { recursive: true })
fs.writeFileSync('target/rbac-offline-support-plan.json', JSON.stringify({
  catalogSha256: hash(canonical), d07Sha256: hash(d07), roles, providers,
  runtimeSupport: [...JSON.parse(fs.readFileSync('src/main/resources/rbac/local-admin-test.json', 'utf8')).permissionCodes,
    'person-scope:read', 'person-scope:manage', 'points:read', 'points:review', 'points:adjust', 'points:adjust-approve'], status: 'PREPARATION_ONLY',
  releaseConditions: ['Complete members and every declared namespace owner verification', 'Explicit application support release', 'Reviewed forward state migration; no V41 edit or fixture promotion', 'No re-enable of disabled historical roles', 'D03/D06/D07 and target-environment approvals'],
  notProven: ['Production provider readiness', 'True WeChat/device', 'All D07 business effects', 'Production deployment or recovery'],
}, null, 2) + '\n')
console.log('PASS: complete ROOT/NOTICE_MONITOR plan, namespace intersections, implemented runtime support and unchanged V41; no SQL executed')
