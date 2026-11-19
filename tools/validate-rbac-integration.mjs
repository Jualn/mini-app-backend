import './validate-rbac-foundation.mjs';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';

// Read-only cross-workspace inputs. Output is evidence, never a migration instruction.
const json=p=>JSON.parse(fs.readFileSync(p,'utf8').replace(/^\uFEFF/,''));
const evidence=json('target/rbac-g0-evidence.json');
const base=path.dirname(evidence.historicalInputs.find(x=>x.path.endsWith('/role-presets-v1.json')).path);
const actions=json(path.join(base,'business-actions.json'));
const routes=json(path.join(base,'http-entry-action-map.json'));
const catalog=json('../contracts/api/catalogs/rbac-v1.json');
const permissions=new Map(catalog.permissions.map(p=>[p.code,p]));
const owners={AUTH:'admin/auth/service/impl/AdminQrLoginServiceImpl.java',USR:'user/service/impl/UserServiceImpl.java',
 POST:'post/service/PostGovernanceService.java',COM:'comment/service/CommentGovernanceService.java',CON:'content/service/impl/AdminContentServiceImpl.java',
 OFF:'officialcontent/service/impl/OfficialContentServiceImpl.java',ACT:'activity/service/impl/ActivityServiceImpl.java',PE:'publicevent/service/impl/PublicEventServiceImpl.java',
 REV:'audit/service/impl/AdminReviewServiceImpl.java',REPORT:'report/service/impl/AdminReportCaseServiceImpl.java',
 NOT:'notify/service/impl/AdminNotificationPlanServiceImpl.java',MED:'media/service/impl/MediaServiceImpl.java',
 DOC:'admin/documentimport/support/DocumentImportAuthorizer.java',SYS:'admin/system/service/impl/AdminSystemServiceImpl.java'};
const files=[];
function walk(dir){for(const x of fs.readdirSync(dir,{withFileTypes:true})){const p=path.join(dir,x.name);if(x.isDirectory())walk(p);else if(p.endsWith('.java'))files.push(p);}}
walk('src/main/java');
const planned=new Set(['PLANNED','BLOCKED']);
function details(a) {
 const p=a.permissions;
 let namespaces=[...new Set(p.flatMap(code=>permissions.get(code)?.namespaces??[]))];
 let composite='按动作列出的全部 Permission 和同一可信 namespace；仅 GLOBAL；保留 owner 资格/状态';
 let risk='不能复制整个多域 Role；仅按已批准动作/namespace 初始化，不自动迁移';
 let compatibility='LEGACY 原协议/当前有限映射保持；ASSIGNMENT 需新能力客户端';
 let status=p.length?'SERVICE_BRIDGED_STATIC':'OWNER_UNCHANGED';
 let tests='当前账号、namespace拒绝、撤权、期限、状态、参数分支的专用环境端到端验收';
 if(planned.has(a.state)){status='EXISTING_GATE_CLOSED';risk='保持原门禁；Contract 登记不等于能力启用';}
 if(a.id==='USR07'){status='FROZEN_LEGACY_WRITE';namespaces=[];composite='所有来源由Service拒绝409 authorization-source-migration-required；不写role/Authority/Assignment/成功审计';risk='本轮已批准冻结旧角色写；保留历史字段和读取；不构建ROOT适配';tests='UserServiceImplRoleChangeTest + P1SecurityDatabaseHttpTest真实HTTP/DB拒绝及持久事实不变';}
 if(a.id==='CON01'){composite='content:read@各行真实主体namespace；SQL列表及summary同域过滤；无域不查询';risk='旧全域聚合不可复制成任意GLOBAL；ARTICLE/未知域拒绝';}
 if(a.id==='REV01'||a.id==='REV02'){status='PARTIAL_CONTRACT_GAP';risk='混合Comment列表/分页/计数/summary按持久主体域SQL过滤；USER资料人工审核仍缺Catalog语义；ARTICLE独立拒绝；未知/无主体关系拒绝';composite='READ=review:read；DECIDE=review:read+review:decide@同一可信主体域；处分独立，owner再次检查';tests='AdminReviewAuthorizationTest + P1SecurityDatabaseHttpTest混合队列/跨域决定/撤权/持久分页和summary';}
 if(a.id==='REPORT03'){composite='旧单条仅report:handle@REPORT且只改report；聚合handle+read；仅实际TAKE_DOWN再加post:dispose/comment:dispose@真实内容namespace';risk='迁移只有report:handle会失去真实下架；不能把下架权自动授予正常结案者';tests='P1真实HTTP单条正常处理、聚合正常/违规、无dispose原子拒绝/跨域拒绝/有dispose提交';}
 if(a.id==='POST06'||a.id==='COM06'||a.id==='REPORT02'||a.id==='REPORT03') compatibility='保留旧普通Token管理入口；LEGACY OPR/ADMIN（含旧禁言行为），不强制改admin Token；ASSIGNMENT当前主库裁决';
 if(a.id==='MED02'){risk='可信绑定域，或无绑定时当前上传者+精确对象/登记记录的ACTIVITY/PUBLIC_EVENT/OFFICIAL_CONTENT用途；其他未知对象拒绝；Article独立';tests='MediaAuthorizationTest + P1SecurityDatabaseHttpTest精确上传者/用途/登记及清理状态';}
 if(a.id==='MED04'){status='PARTIAL_CONTRACT_GAP';risk='登记使用上传记录可信namespace；外部未绑定URL没有可信业务域，ASSIGNMENT503；不能猜GLOBAL';composite='上传/登记media:write@可信业务域；内容写入另外检查edit及必要publish；无请求上下文的后台履约不推断权限';}
 if(a.id==='ACT04'||a.id==='ACT05'||a.id==='PE04'||a.id==='PE05'||a.id==='OFF03'||a.id==='OFF04') composite='编辑edit；实际修改公开表示另加publish；撤下unpublish；旧同一HTTP的参数分支分别核对';
 if(a.id==='ACT11'){status='PARTIAL_NO_PUBLIC_ENABLE';risk='内部作废owner已接registration:dispose，但无新增HTTP且Permission仍未就绪';}
 if(a.id==='USR06'){composite='先ROOT mutex→排序User/Authority锁→ROOT资格/最后主体→user:manage@USER→原目标限制→写入同事务';tests='P1真实UserService两个ROOT并发封禁/LEGACY拒绝/影子ROOT隔离 + RbacDatabase并发撤权';}
 if(a.id==='AUTH04'){status='REPRESENTATION_COORDINATION';risk='旧/me只返回LEGACY，ASSIGNMENT409；内部diagnoseCurrent不撤Token且只读本人；正式capabilities HTTP已实施，逐grant/namespace读取；设备与发布环境独立验收';}
 if(a.id==='AUTH02'||a.id==='AUTH03'){tests='P1真实HTTP/DB/Redis LEGACY与RBAC_V1固定结果、错误表示不消费、激活/撤权；QR Redis并发/崩溃恢复';}
 if(a.id.startsWith('DOC')) namespaces=[a.id==='DOC01'?'ACTIVITY':'PUBLIC_EVENT'];
 return {newNamespaces:namespaces,composite,permissionExpansion:'未批准；粗Sa别名只预筛选，不能作为资源许可',unplannedShrinkRisk:risk,
  legacyPreserved:planned.has(a.state)?'既有门禁保持':'保持旧owner行为，未切换真实用户',clientImpact:compatibility,implementationStatus:status,preSwitchTests:tests};
}
const matrix=actions.map(a=>{
 const prefix=a.id.replace(/\d+$/,'');let owner=owners[prefix];
 if(prefix==='ACT'&&Number(a.id.slice(3))>=8)owner='activity/service/impl/ActivityRegistrationServiceImpl.java';
 if(a.id==='POST09'||a.id==='POST10')owner='post/service/PostDictionaryAuthorization.java';
 const ownerFile=owner?files.find(f=>path.basename(f)===path.basename(owner)):null;
 return {actionId:a.id,module:a.module,oldEffect:a.action,branch:a.branch,originalState:a.state,
  oldRoles:evidence.migration.filter(c=>c.actions.some(x=>x.id===a.id)).map(c=>c.candidate==='LEGACY_OPERATOR_V1'?'OPR':'ADMIN').filter((v,i,all)=>all.indexOf(v)===i),
  oldRoutes:routes.filter(r=>r.actions.includes(a.id)).map(r=>({id:r.id,http:r.http,controller:r.controller})),
  newPermissions:a.permissions,ownerFile:ownerFile?.replaceAll('\\','/'),ownerSha256:ownerFile?crypto.createHash('sha256').update(fs.readFileSync(ownerFile)).digest('hex'):null,
  ...details(a),runtimeCoverage:'参见具名测试证据；本静态矩阵不证明本行端到端通过'};
});
assert.equal(matrix.length,132);assert.equal(routes.length,211);
const coarseActions=['POST06','POST07','COM06','OFF02','OFF03','OFF04','OFF05','MED02','MED04'];
const candidateRows=evidence.migration.flatMap(c=>(c.permission==='content:manage'
 ? coarseActions.map(id=>({id})):c.actions).map(a=>({candidate:c.candidate,permission:c.permission,
 ...matrix.find(row=>row.actionId===a.id),candidateStatus:c.permission==='content:manage'?'DISABLED_COARSE_SPLIT_NEVER_GRANT':'CANDIDATE_ONLY'})));
assert.equal(new Set(candidateRows.map(r=>r.candidate+'/'+r.permission)).size,63);
const q=v=>'"'+String(Array.isArray(v)?v.map(x=>typeof x==='object'?JSON.stringify(x):x).join(';'):v??'').replaceAll('"','""')+'"';
const cols=['candidate','permission','actionId','oldRoles','oldRoutes','oldEffect','branch','newPermissions','newNamespaces','composite','legacyPreserved','permissionExpansion','unplannedShrinkRisk','clientImpact','preSwitchTests','implementationStatus','ownerFile','runtimeCoverage'];
fs.writeFileSync('target/rbac-d07-integration.csv',cols.join(',')+'\n'+candidateRows.map(r=>cols.map(c=>q(r[c])).join(',')).join('\n')+'\n');
fs.writeFileSync('target/rbac-g2-evidence.json',JSON.stringify({date:'2026-10-17',boundary:'STATIC; NO PERSON MIGRATION APPROVAL',httpEntries:211,actions:matrix,candidateRows,
 directRoleConsumers:evidence.legacyConsumers,delegatingConsumers:evidence.delegatingConsumers},null,2)+'\n');
console.log(JSON.stringify({httpEntries:211,actions:matrix.length,candidates:63,candidateActionRows:candidateRows.length,
 explicitGaps:matrix.filter(a=>['PARTIAL_CONTRACT_GAP','REPRESENTATION_COORDINATION'].includes(a.implementationStatus)).map(a=>a.actionId)}));
