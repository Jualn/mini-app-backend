# RBAC 离线治理事实与执行边界

## 1. 审计与公共边界

本文件维护离线 operation/effect 与执行器的内部机制。v1 双签工具只允许专用可销毁合成库；v2 指定首次 ROOT 与 v3 更正是独立入口，按 §8 的清单批准执行。历史测试批准和结果不构成新环境授权。发布与恢复条件见 [runbook](operations/runbook.md#rbac-发布与恢复)。

现有六表不能把无 ROOT 的运维主体记成普通授权 actor：V41 的 Change 必须有真实 actor User、Assignment，action 只有 GRANT/REVOKE；不能填假 User 或伪造成功。最小向前迁移新增独立 operation/effect 两张表，不修改 V41/V42、旧成功行或普通授权审计语义。

Contract `RbacAssignment` 将 grantChangeId/revokeChangeId 定义为不透明“审计引用”，未承诺它们必须来自 listAuthorizationChanges；协调 §5 明确要求 Bootstrap/来源切换运维审计另外保留。因此离线 ROOT 的 grantChangeId 指向同事务持久化的**真实 offline effect ID**，普通授予/撤销继续指向原 Change。公开字段、类型、状态、九路由、错误和 GRANT/REVOKE 列表不变；不增加公共运维枚举或假装普通 HTTP GRANT。运维事实只经受控离线核对。若未来要求在管理端直接导航/统一展示运维事实，必须先补跨端契约，不能直接混入现有列表。

审计引用统一规则是“真实授权效果事实 ID”，而非占位 UUID。沿现有正常 writer 的做法，跨事实表的联合引用由事务 owner 校验；不伪造 MySQL 可延迟的环形 FK。离线 effect 有真实 operation、User、可选新 Assignment 外键；Assignment 写入和 effect 插入必须共同提交。普通后续 revoke 保留原 grantChangeId，新增真实普通 revokeChangeId。旧历史数据不回写成新格式。

在线Reader与QueryService在各自当前主库事务中通过`RbacOfflineAudit`核对已知离线origin：既查effect的Assignment/引用关联，也查operation固定收据保留的Assignment身份。因此单独丢失effect不会被当成普通授权；缺行、错引用、不可解释快照、未知版本或收据/不可变授予字段不一致返回已有503 source-unavailable。历史查询只比对不可变授予字段，不把后来的正常撤销/版本变化误判成原离线事实损坏；正常Change仍由原事务owner维护，不混入离线审计。此读取依赖V43；没有新增公开字段或枚举。

这不是对任意DBA篡改/整体历史丢失的证明。operation和effect两条独立origin证据都被删掉时，仅凭现有不透明grantChangeId不能还原来源；当前检查未把所有历史普通Grant改造成全局强制审计解析器。全库恢复仍须先跑preflight并对照独立故障域最新事实；无法解释的引用不能靠补占位行或放宽Reader恢复服务。

证明读取按一次调用的Assignment引用批量执行，每批最多200个不同的(id,grantChangeId)；同批历史before/after共享查询结果，但每个快照仍分别核对不可变授予字段。分页只核验实际返回行，Reader核验当前全部Assignment。单引用保持原常量参数SQL，多引用以绑定参数CTE和一次Operation收据扫描保留缺Effect检测；普通Change即使完整，也不能独自排除另一份冲突离线收据，因此没有以Change命中或Effect缺失为条件跳过扫描。批量关联回执保留对异常大小写引用的拒绝检测，不能用更严格匹配隐藏原先可定位的损坏来源。复用只限本次调用/主库快照，不跨事务、请求或治理重检；没有Redis/JVM正向授权缓存。普通单条决策的历史扫描成本仍存在，不能把批量优化当作与历史规模无关。

## 2. 事务所有者与不变量

离线执行器拥有显式 REQUIRES_NEW / READ_COMMITTED 事务，未注册为应用 Bean、HTTP、启动任务或生产 CLI 命令。批准主体是环境外的运维执行身份 executorRef，以及环境预置信任的两把独立 Ed25519 批准公钥；不是尚不存在的 ROOT User。签名绑定整个命令及批准证据引用/摘要。SOURCE_SWITCH 另外要求现存 ROOT sponsor 的当前资格与预期 Authority 版本；该 sponsor 不冒充运维 executor。

| 操作 | 前提/目标 | 原子效果 |
|---|---|---|
| BOOTSTRAP | 恰好一个明确目标；无未撤销 ROOT 且从未有 ROOT Assignment/首次引导事实；目标有显式 LEGACY/ASSIGNMENT、正常未删、Authority/账号快照版本匹配、没有任何 shadow Assignment | 该目标建立/确认 ASSIGNMENT、新 Authority 版本、新 ROOT/AUTHORIZATION/GLOBAL/无期限；各自独立 effect；同一 operation/固定回执 |
| SOURCE_SWITCH | 一个目标；合法当前 ROOT sponsor，禁止 sponsor 自切；目标 LEGACY、资格和版本匹配、没有任何历史或 shadow Assignment | 只切来源和版本，不授任何角色，随后按普通治理另授；旧 role 保留但不能放权 |
| RECOVERY | 恰好一个明确目标；无未撤销 ROOT、有真实 ROOT 历史；ROOT完整目录仍可用；目标当前正常未删且版本匹配；LEGACY必须无shadow，ASSIGNMENT只能有已撤销历史 | 只新增一份 ROOT；旧Assignment/Change/收据、revokedAt和撤销引用完全保留。不是恢复备份或清除用户封禁，也不能借目录停用触发重启用 |

锁顺序：ROOT Role 行 mutex → sponsor/target User 按 ID 升序锁（已有 lockSubject 同时锁相关 Authority）→ 当前目录/ROOT/资格/版本与全部Assignment核对 → operation、source、新Assignment及effect、固定回执原子提交。账号处置、普通治理和离线操作共享同一个 mutex；离线入口不能拿普通ROOT guard伪造actor。没有删除/反向切LEGACY/重置旧role能力。

同 operationId 全环境唯一；请求字段顺序采用固定 record JSON，目标顺序规范化按User排序，签名也使用同一规范化字节。现行版本仅 v1；不对原因/证据引用做语义trim。同 ID 同语义且签名有效恢复原成功回执，不重新执行，不因当前撤权/旧预期版本而重授；同 ID 异语义拒绝。读不到提交事实是未知结果，不自动重试、换ID或判失败。查询/重放仍验证独立环境与双签名信任，回执只是历史成功，须另inspect当前事实。

## 3. 两张向前表

operation：operationId、环境、操作类型、executorRef、批准证据引用/摘要、规范化请求/指纹、批准签名、snapshotVersion=1、固定回执、DB UTC提交时刻。仅成功事务保存；失败输出受控错误，不伪造成功行。

effect：effectId、operationId、目标User、新Assignment（SOURCE_SWITCH为空）、操作前/后Subject及完整Assignment快照。唯一(operationId,userId)，真实外键RESTRICT；effectId用于新ROOT的grantChangeId。不可变快照仍保留旧撤销历史。没有TTL、cleanup、改写/删除服务，也未决定法定保留期。

迁移只建结构，不建立批准公钥/测试环境标记、不启用目录、不产生人员授权。现有六表和公共converter原样可读；旧role-only二进制依然不能接ASSIGNMENT流量。部署这两张表本身不等于生产运维已可执行。

V45 向前扩展 operation 的两个 CHECK：原 snapshot_version=1 仍要求两条批准记录；version=2 只允许 BOOTSTRAP 和一条真实运维清单批准记录。未伪造第二名批准人；这不是 v1 双人签名，也不是新增公开授权操作。v2 收据可含多名显式普通用户的切源效果，但仅一名用户有 ROOT Assignment；其余不得产生任何角色。新 Reader 识别两种内部版本，旧二进制不能读取 v2，切换前须准备新构建。V45 本身不选人、不启用目录、不改 User/Authority。

## 4. 执行保护

v1 双签 CLI 在建立连接之前只接受 `jdbc:mysql://127.0.0.1:<显式端口>/rbac_offline_test`。写入还需显式 `--execute`、MySQL 8.4、测试库专有guard的环境/执行身份及两把不同公钥摘要匹配，并验证两个不同批准者对规范化命令的签名。guard仅由合成fixture创建，不在Flyway或应用中创建；任意自带公钥、缺guard、错环境/篡改请求均拒绝。普通应用 RbacCatalog 的支持清单见 [RBAC 基础](rbac-foundation.md#实现支持与运行可用性)，不由该合成工具发布。

这不是生产授权系统：能管理测试MySQL的DBA仍可改guard。生产入口硬禁用，不能靠换URL/flag/配置转成生产恢复器。正式执行器还需独立发布批准、主机身份与凭据保管/文件ACL、真实批准身份托管、维护窗口、备份水位和恢复证据完整性保护。生产灾难恢复不能只调用RECOVERY重建ROOT：必须先恢复/核对最新撤权、来源和幂等事实；不确定则保持维护。

## 5. 支持目录准备

普通应用按 RbacCatalog.implementedSupport 提供已实现能力，独立于 dev/prod 或 learning-task-integration profile。运行支持不自动发布目录或授予人员；完整角色的所有 Permission 必须满足当前运行状态。受控目录字节、实现支持、数据库可用状态与真实 Assignment 分别核对。

v1 合成工具仍要求 guard、固定环境、双签和完整目录。v2 首次迁移的固定十码为 ROOT 四码及 LEARNING_MANAGER 六码；这只是该工具的一次性发布组合，不是应用全部支持清单，不包含审核/媒体/特殊撤销。只创建指定 ROOT；后续业务授权由正常治理承担。ROOT ownerCapabilities 的当前派生规则见 RBAC 基础，不再沿用“ROOT 永无业务能力”的旧切片假设。

不能裁剪角色成员、复制夹具状态到生产或修改已应用迁移来激活权限。生产发布须固定支持制品、目录哈希、成员/域验收和批准目标；停用角色不原地重启以复活旧授权。

## 6. 签名命令与结果核对

签名文件是`SignedRequest { request, approvals }`，全部字段由`RbacOfflineTool`中的具名record定义，未知字段按默认严格Jackson绑定拒绝。request版本固定1，包含operationId(UUID)、environmentId、kind、executorRef、approvalRef/approvalSha256、approvedUntil(UTC Instant)、expectedCatalogVersion、sponsorUserId/expectedSponsorVersion及显式targets；每个target含既有userId、expectedAuthorizationVersion和expectedSubjectFingerprint。后者由inspect从当前Subject全部资格/来源字段计算，不是profile展示版本。SOURCE_SWITCH必须有当前ROOT sponsor，其余两种不接受虚假sponsor。

先inspect，再组装命令/dry-run，由两名环境预置信任者分别对`signingBytes(request)`作Ed25519签名。该方法按User排序targets，以固定record JSON编码；approvals各含approverRef、Base64 X509公钥及签名。请求中的公钥必须匹配guard里各自摘要，不能自带两把钥匙即获准。approvedUntil只限制新执行；同一已提交操作仍可在当前信任/环境校验下恢复历史收据。批准证据本身由批准者核对并签署其摘要，工具不凭URL自动信任外部内容。私钥托管/签批产品及真实执行身份认证不在本轮生产能力范围。

默认dry-run不会落库、不会生成成功审计，不代替签批。未知提交须保留原签名文件和operationId，显式执行同一请求时，先校验环境/签名、持mutex，找到成功事实即复核固定回执与全部effect并返回；不能因为过期或已撤而再次授予。缺effect、损坏快照/回执或同ID异语义拒绝，不修造成功。随后inspect分别读取现在的状态；历史收据不表示现在仍有权。没有独立“把未知判失败”或无签名恢复写入口。

## 7. 验证入口与证据边界

使用 [离线治理专项](../governance/commands.md#rbac-offline-governance-validation) 和 [离线到在线专项](../governance/commands.md#rbac-offline-to-online-integration-validation) 分别验证真实 MySQL/事务/CLI、审计损坏拒绝、原收据恢复和 Sa/Redis/HTTP。模拟 commit 确认丢失不等于真实断电；隔离角色与 Provider 替身不证明生产可用。生产恢复仍需独立故障域证据和授权，不能用测试 PASS 开放工具。

## 8. 指定初始 ROOT 的运维迁移

`RbacInitialMigration` 是独立 CLI，无 Bean、HTTP、启动自动迁移或 ROOT 通配。只在 operator 所在主机的显式 loopback host/port/database 执行，数据库凭据来自临时环境变量，不在参数/清单中。工具不创建假身份、不改旧 role、不清空历史。环境、ROOT 和普通用户集合必须来自本批有效批准清单，不在长期文档固定真实账号 ID。以后线上必须重新指定环境、真实 User、批准及备份，不能复制本次 plan、ID、server UUID 或测试 profile 当作生产批准。

1. 停止受影响流量，准备含 v2 Reader 的新构建、V45 及当前匹配目录迁移和 RBAC/用户角色快照备份。目标无 Flyway history 时，不自动 baseline/repair；显式记录本次 DDL 摘要与执行事实，后续另按数据库说明接管历史。V101 等目录迁移不回填人员；若历史用户缺 Authority，先按批准目标执行显式 LEGACY 回填并核对 `missing=0/corrupt=0`，不能把缺行直接当 ASSIGNMENT。
2. Inspect 读取当前全部有 Authority 的账号（最多 1000 人），固定环境 ID、主库 server UUID/schema/URL、ROOT ID、当前 User/Authority/版本及无 shadow Assignment、完整目录摘要和状态、批准引用/文件摘要、30 分钟有效期和唯一 operationId。计划明确每个其他账号仅切 ASSIGNMENT、无角色；普通用户登录/学习归属仍用原 User。不做全体新管理授权，也不重置旧 role。当前 V101/r13 目录仍允许 snapshot v2 BOOTSTRAP；兼容性不代表放宽无 ROOT 历史、无普通 Assignment 和 ROOT/LEARNING_MANAGER 原始登记状态这些前提。
3. 默认 DryRun 只验证，不写。Execute 必须显式给出已复核 plan 文件的精确 SHA-256。该批准方式是现有主机运维身份 + 文件清单摘要/外部批准引用，**不声称密码学双人审批或远程批准人认证**；保管与审批由运维流程承担。
4. 事务取 ROOT mutex → 本批 User 按 ID 顺序锁 → 当前账号、Authority、目录、历史、批准期限及账号集合重验。只有正常未删、显式 LEGACY/ASSIGNMENT、无历史/当前 ROOT 和无任何 shadow Assignment 的首次迁移可执行。当前其他账号的资格/限制原样保留，不通过迁移解除限制；本批任何状态/版本/集合变化均拒绝。
5. 一个 READ_COMMITTED 事务启用完整 ROOT/LEARNING_MANAGER 目录，只给指定 User 建 ROOT/AUTHORIZATION/GLOBAL/无期限，其他本批 User 只切来源且无管理角色；operation、每个 User 的 before/after effect、固定回执和真实 Assignment 审计引用同事务提交。真实 Reader 必须当场判定 ROOT 有效，否则全回滚。
6. 同 plan/operationId 重放校验原请求、收据、全部 effect 及引用，返回历史成功，不重复授予；后来撤权也不恢复。提交未知先保留原 plan，再同 plan 对账；不得换批次重授或改造恢复为首次。新批次见已有 ROOT 历史拒绝。后续正常授权仍由 ROOT 对其他 ASSIGNMENT User 走九个正式接口，自授/最后 ROOT 保护不变。

本次不为未来新注册账号重写兼容初始化策略；新账号由 User owner 同事务建立 ASSIGNMENT Authority 且无 Grant；首次 ROOT 可沿显式离线清单建立，普通管理职责由统一授权治理授予，不能改旧 role 放权。首次清单不超过 1000 人；更大生产存量需独立批次方案。生产还需批准相应支持制品、真实操作身份、备份/恢复和发布窗口；应用默认实现支持不代表该运维执行器已经获准生产使用。

### 8.1 已部署环境的 ROOT 生命周期

使用 [运维手册的 ROOT 工具](operations/runbook.md#root-运维与旧任命退出) 初始化、交接或应急恢复。历史 v3 初始更正记录和签发依据仍可核验。
