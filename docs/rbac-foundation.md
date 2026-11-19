# RBAC 授权基础与迁移边界

当前受控目录为 `rbac-v1-unified-final-r8` / `unified-duty-v1-final-r9`（79 Permission、28 Role、36 Duty）。V93 同步已知目录摘要，登记 `HOME/home-banner:manage` 与 `HOME_BANNER_MANAGER_V1`，并开放该 namespace 的存储约束。V94 将仍处于 V93 初始状态的首页权限和角色发布为 `ENABLED / READY`。当前有效 ROOT 随即获得首页管理 owner 能力，普通管理员通过显式 `PLATFORM` 的 `HOME_BANNER_MANAGE` 同源授权获得访问。迁移保留现有 Authority、ROOT、任命与就绪状态。下文历史迁移/旧角色效果对照仅用于审计，不再提供 LEGACY 在线授权。

## 源码职责与内部边界

authorization 按已有职责组织：`web` 承担认证入口、请求/响应与错误表示，`service` 承担来源分派、当前事实读取、目录规则、治理事务及离线执行，`model` 定义既有决策、快照和投影，`mapper` 仅含应用运行 Mapper，`support` 提供时间与错误表示，`bootstrap` 承担启动覆盖检查。HTTP 错误工厂不再借用 UnifiedAuthorizationService 的内部静态方法。

治理、目录、当前快照读取、离线证明和显式离线执行器保留在同一 service 包，维持 `readCurrent`、受控目录构造器、快照 JSON 和证明校验的包内可见性；不为目录拆分开放无事务的快照读取。RbacOfflineTool / RbacInitialMigration / RbacRootOperations 仍不是应用 Bean；RbacOfflineMapper 保留包内接口且不带 `@Mapper`，只由显式离线上下文登记，不能放进应用 Mapper 扫描目录。应用扫描读取 `mapper` 下的 RbacMapper / UnifiedMapper，XML namespace/resultType 与 Java 类型同步。账号来源覆盖和初始授权来源的投影归 RbacModels，运行 Mapper 不依赖 Service 内嵌结果类型。

Java 类型迁移不改变 SQL、UTC 微秒、ROOT mutex → 排序账号锁、现有事务传播、固定收据及指纹格式。包结构和编译不是目标环境的授权、迁移、目录启用或离线操作批准；验证边界按实际测试报告。

## ROOT 平台所有者与职责来源

用户已批准 ROOT 作为平台所有者，而非仅治理授权。Reader 在同一主库快照核验当前账号、真实 ROOT、目录成员与 ROOT 就绪状态后，按运行支持清单和 Permission ENABLED/READY 派生 ownerCapabilities；人员/组织各项同样必须有实现支持及运行 ENABLED/READY，不保留白名单例外；派生 Owner 不要求其他业务 Role 可授予。每项 rootAssignmentId 绑定原 ROOT，namespace/GLOBAL/permissionCodes 明确，不产生业务 Assignment，不修改旧角色或真实授权。Decision.allows、粗权限候选、内容列表 SQL 范围与 admin capabilities 使用同一派生结果。ROOT 治理、自改禁止和最后有效 ROOT 保护保持原真实 grant 检查。人员/组织目标及独立范围治理见[人员基础](person-foundation.md)。

缺失支持、NOT_READY、停用、账号限制及撤权不会被 ROOT 绕过；组织事实/动作范围、私有附件、内容安全和业务状态仍归各 owner 裁决。已实现能力的运行支持不再受 profile 限制；ROOT 仍不会获得未支持或未就绪的业务能力。功能上线须完成对应 Provider 验收和受控目录发布，不批量开启候选角色。

需要先应用 V52、V55 及 V62 同步当前目录，再部署匹配二进制；不重新执行人员首次迁移，不改任何现有 Assignment。这些迁移均保留既有目录 readiness/grantable。新旧目录摘要不能混跑，停用入口可保留全部事实；不要回退摘要掩盖不一致。HTTP 增量字段由 Contracts 定义；旧消费者忽略新增字段会保守隐藏新入口，新消费者对旧 Provider 缺失字段按空能力处理。

`rbac-v1-unified-r1` 的 V55 新增只读 `POINTS_READER_V1`，仍为 NOT_READY / 不可授予；不将旧 Points 人员范围转换为新职责。`RbacCatalog.rolePermissions(roleCode, namespace)` 是 Reader 与后续职责解释复用的确定性交集入口；角色名称不构成动作。完整角色的所有登记成员必须全部就绪才可发布，不能用某个 namespace 的部分可用性绕过整角色门禁。V57 复用既有 Assignment 和授权变更审计，Person owner 保存逐职责范围，新增 ROOT 签发的一层委派上限、不可变来源、五分钟预览及固定幂等收据。统一 `/v1/admin/authorization` 七个操作由 `UnifiedAuthorizationController` 执行；`me/duty-options` 已是实际查询。逐职责使用当前角色×namespace、整角色就绪和 Provider 支持计算，GLOBAL 不推导 PLATFORM。禁止自授、ROOT 复合操作及继续委派；正常组织任命绑定签发者、持有人及下游受任人的原成员周期。上级撤销/到期/账号或周期失效即时停止依赖分支，独立来源保留。复合命令在同一 MySQL 事务提交 Assignment、Person 范围、审计及永久收据；无法保证时503，不伪造部分成功。真实 ROOT 维护仍走原治理 writer 和最后 ROOT 保护。

本文件维护 Backend 数据、内部服务和正式HTTP的当前边界。对外语义归 [RBAC V1 Contract](../../contracts/docs/coordination/rbac-v1.md)，唯一受控目录归 [catalog](../../contracts/api/catalogs/rbac-v1.json)。实现位于 `module/authorization`。发布条件、审计保留待决项与恢复顺序见 [runbook](operations/runbook.md#rbac-发布与恢复)。这不是实际人员的初始化或来源切换批准。

### 实现支持与运行可用性

已实现管理能力默认纳入运行支持，无独立启用开关，不依赖 dev/prod、loopback 或测试库名称。基础清单读取 `src/main/resources/rbac/local-admin-test.json`，再由 `RbacCatalog.implementedSupport` 补充明确实现的 person-scope、mentor 资格管理及 Points 能力；文件名为既有工具引用。导师资格管理已纳入实现支持；规则管理及其他未实现能力不自动纳入。管理资格不产生本人导师资格。支持清单仅表示代码具备实现；数据库目录 lifecycle/readiness、完整 Role、真实 Assignment、ROOT 派生规则和人员范围仍按既有契约校验，不自动发布目录或修改账号授权。

## A. 协议适配与裁决入口

本人职责门控摘要由 `UnifiedAuthorizationService.dutySummary` 在一次主库 REPEATABLE_READ 事务内读取当前目录、账号版本及请求职责的完整来源，复用 `UnifiedDutyReader.reason`，不从权限并集推导职责。`refreshAfter` 至多 60 秒，并收紧至请求相关来源、委派、账号限制或成员周期的已知下一边界；计算完成时边界已过则 503。摘要不提供目标/受众，不是业务授权凭证；旧 effective-duties 的分页和 no-store 不变。

五个 canonical 管理查询在身份拦截与上下文拦截两处均只要求有效管理会话；业务 owner 独立判权。仪表盘按账号、审核、举报、通知分别读取摘要，使用同一个上海自然日区间；账号/组织精简名称沿原详情读取资格及目标范围，不借职责或审计可见性扩权。新读取与错误统一 no-store，协议见 [Admin query operations](../../contracts/api/paths/admin-query.yaml)。本地专项命令见 [查询优化验证](../governance/commands.md#admin-query-focused-validation)；代码支持不代表部署或真实目录验收。

RBAC HTTP 保留目录、角色任命、当前能力和授权变更查询。普通业务授权统一由 UnifiedAuthorizationService 写入 Duty 和关联任命。ROOT 生命周期使用独立运维工具。

| Contract 语义 | 当前实现入口 / 数据责任 | 验证与剩余责任 |
|---|---|---|
| listAuthorizationPermissions | QueryService当前ROOT → 受控元数据 + DB可用性 | 完整78项、状态/版本、wire/no-store/503；非ROOT先403 |
| listAuthorizationRoles | 同一快照内Catalog + roles/members | 完整27个固定组合及未就绪成员，不裁剪或扩权 |
| getAuthorizationRole | 当前ROOT后精确code | 公开Role投影，未知404；不暴露内部表键 |
| listUserRoleAssignments | 当前ROOT → target Authority + 有界assignmentPage | evaluatedAt固定，grantedAt/id高水位与seek，actor/target/过滤/版本绑定cursor |
| getUserRoleAssignment | 当前ROOT → 同user的历史Assignment | 最新版本/当前状态，已撤/已到期仍可读；跨user统一404 |
| getMyAdminCapabilities | 独立认证 → 同一主库快照Decision | ASSIGNMENT/grants/refreshAfter；无grant为200 false/[]，异常或 LEGACY 来源拒绝，不因此撤Token |
| listAuthorizationChanges | 当前ROOT + audit-log:read → Change安全投影 | V42内部发布序号高水位、时间/id seek、AND筛选、当前逐页授权；无key/指纹/完整snapshot |

历史基线核验（V41–V62，不代表当前目录或环境验收）：

1. 当前受控目录77项Permission包含72项REGISTERED及5项DISABLED（delegation-manage、role-manage、content:manage、post:restore、user:role），全部NOT_READY且不可授予。业务已存在不等于新Permission已执行；应用运行支持以当前已实现能力清单为准，数据库目录状态保持独立。
2. 25个Role均是不可变组合；V41登记原18项，V48增量登记PERSON_*_V1三项，V55增量登记POINTS_READER_V1，V62增量登记两类字典维护与帖子运营三个窄角色。受控元数据保持NOT_READY/grantable=false；实际目标环境的启用与人员授权必须另有证据。登记迁移不启用任何角色、不创建Assignment、不改ROOT或旧组合。人员范围不是RBAC Assignment，见[人员基础](person-foundation.md)。
3. 多空间Role不是Role×Permission的笛卡尔积；每个Assignment只携带成员中允许其namespace的交集。当前43个交集非空且合法。Role整体须全部成员就绪，不能裁剪成员冒充已发布组合。
4. GLOBAL始终绑定显式namespace；非GLOBAL、缺失/未知scope分别拒绝，任何路径不补GLOBAL。ROOT固定AUTHORIZATION且无expiresAt。
5. actor+key跨所有授权命令唯一；重放先复核当前ROOT及目标可读，再恢复固定收据；新执行才检查目标来源、版本、可授予状态与期限。不同语义冲突，不能重复续期。
6. ROOT 为平台所有者，基于真实 ROOT Assignment 派生当前环境支持且已就绪的管理能力；禁止自授、自撤、自改。ROOT互撤与使用统一guard的账号处置串行化，并复核当前有效ROOT。
7. QR新表示只改变响应选择，不能改变Authority或Token信任链。省略表示头默认 RBAC_V1，显式 LEGACY 在读取/消费凭据前拒绝；仅 ASSIGNMENT profile，不返回旧 scalar 角色或 *。
8. 旧role保留原义，不同步成ADMIN/OPR以激活新角色；在线只接受 ASSIGNMENT；缺 Authority 不默认任何来源。
9. Assignment的ACTIVE/EXPIRED/REVOKED与Role/Permission可用性分开；ACTIVE不保证授权有效。已知停用/不可授予409、登记未接入503、来源故障503已在正式HTTP验证；不改变旧入口的错误映射。
10. expiresAt公开输入保留原字符串至精度校验，0–6位后无损规范化为UTC，DATETIME(6)和安全响应保留微秒，输出Z且不要求固定6位。UTC转换后须在MySQL的1000–9999年范围。幂等指纹仅含规范化期限；新执行才以主库UTC_TIMESTAMP(6)检查未来时间，过期/撤销后同key仍恢复原成功收据，不重授。审计查询的任意小数秒边界继续按原精度比较及ceil谓词（after含、before不含），未把expiresAt规则扩大到查询或其他日期字段。

“先复核可读性再查收据”的文字与“认证→收据→目标”的执行顺序不构成放权冲突：先按actor/key定位收据，命中也必须在返回前核对原目标可读；不重新执行状态/版本检查。本实现按此保留存在性保护及原结果恢复。

## B. 审计存储与旧授权迁移边界

### 审计与收据存储

| 项目 | 内部存储与实现 | 边界 |
|---|---|---|
| User ID | 现有BIGINT UNSIGNED / Java Long | 仅表示账号与操作者；独立 Person UUID 见人员专题 |
| Permission / Role code | VARCHAR(96) / VARCHAR(64)，utf8mb4_0900_bin（NO PAD） | 大小写及尾空格精确比较；未知码拒绝，不截断、不trim、不glob |
| namespace / scope | VARCHAR(32) / VARCHAR(16)，受控namespace及GLOBAL CHECK | Role/Permission的空间组合由受控manifest再验证 |
| 原因 | 1–500 Unicode code points，保留原文，按JSON Schema的ECMAScript `\S`拒绝全空白 | 沿Contract提案；500个补充平面字符真实落库；NBSP/BOM/窄不换行空格边界测试，不使用语义不同的Java isBlank |
| 幂等key | 16–128 ASCII，`[A-Za-z0-9][A-Za-z0-9._:-]{15,127}`；VARBINARY(128) | 不大小写折叠，跨target/命令actor域唯一；不得存Token |
| 请求指纹 | SHA-256；独立RbacJson固定action+Command JSON，保留微秒，包含版本/原因/期限 | 不受全局旧HTTP秒级格式影响；JSON字段顺序/Unicode转义不改变语义；不trim原因；旧v1编码的key另以原编码hash及不可变持久期限核对，不重写原收据或误认同秒内不同期限 |
| 版本 | 服务端生成UUID字符串；只能相等比较；User和Assignment各有版本 | 不公开可递增含义；Role/Permission当前可用状态另参与不透明catalogVersion |
| 内部快照 | snapshot_version=1；before/after + 固定Receipt JSON；新写使用ISO本地UTC并保留微秒 | 兼容读取既有v1秒级编码，保留其原事实而不伪造丢失精度；HTTP仅由安全converter投影；未知版本拒绝 |
| 时间 | RBAC DATETIME(6)写UTC、以DB UTC_TIMESTAMP(6)判断 | 旧User限制时间沿UserService的JVM本地时间语义，比较前显式转换；不改旧表含义 |
| 成功审计/收据保留 | 无TTL、无cleanup、无DELETE写接口，永久保留key/指纹/原收据 | 生产具体期限、归档访问、墓碑机制及法定要求仍待D06批准；不得先短周期清理 |
| 失败审计 | 内部事务拒绝/存储失败只写受控服务日志的operatorId/targetUserId，不写key、原因、Token或完整快照 | 日志访问及保留沿既有Observability/runbook；失败日志查询HTTP未开放 |

旧admin_operation_log的before/after各255字符且无幂等域，不作为新授权成功收据。没有修改它或引入异步审计、Outbox。Bootstrap/来源切换使用[V43离线运维事实](rbac-offline-governance.md)，不能伪装成普通GRANT/REVOKE成功；真实执行仍需独立批准。

D06 expiresAt公共精度按2026-10-16正式冻结同步：最多6位、明确offset、UTC微秒、等价瞬间同指纹；时间精度子项CLOSED。原因/key按现行Schema实现；**生产审计/幂等保留、归档访问/恢复及日志范围仍待批准，全部D06未DONE**。成功收据的历史身份/内容不改写。应用数据库权限仅给授权owner必要读写，无成员编辑/角色启停服务或HTTP。DB高权限运维不是公开编辑接口。

### 旧授权逐效果对照（仅历史盘点，在线映射已退役）

两份旧候选合计63行，按34个不同码覆盖以下全部组；脚本输出每行原动作、分支和HTTP追溯。这里的“对应”只说明旧效果来源，**不是新grant批准**。

| 候选码（组内数量） | 真实旧入口 / 参数效果 | 迁移风险及收口 |
|---|---|---|
| activity:read（1） | AdminActivityController列表/详情 | 只限ACTIVITY，非报名明细许可 |
| activity:edit/publish/unpublish/lifecycle（4） | 同Controller创建/替换及CanonicalAdminActivityLifecycleController；Service按publish/unpublish/cancel/end分支 | 旧edit包含这些效果；仅复制新edit会丢权，给所有新editor发布又会扩权 |
| activity:registration:read/export（2） | AdminActivityRegistrationController明细、CSV/XLSX导出 | export不从read推导；未给旧候选追加未实现的registration:dispose |
| public-event:read（1） | AdminPublicEventController列表/详情 | 仅PUBLIC_EVENT |
| public-event:edit/publish/unpublish/lifecycle（4） | 同Controller及CanonicalAdminPublicEventLifecycleController、ExamServiceImpl分支 | 与Activity分别授权，不按跨模块Role扩大媒体/其他空间 |
| official-content:read/edit/publish/unpublish（4） | OfficialContentServiceImpl以CONTENT_READ/MANAGE检查；create强制DRAFT，replace的publishStatus可发布/撤下 | 不能只按PUT=edit；已发布正文修改仍有发布效果；不扩展ARTICLE |
| content:read（1） | AdminContentController / AdminContentServiceImpl，仅Post/Comment聚合 | 必须沿可信Comment主体解析space；不是全目录内容读取 |
| content:manage（1） | 旧粗入口动作及M01/媒体适配 | 新目录DISABLED/LEGACY_ONLY；不能直接变成Assignment成员，也不初始化LEGACY组合 |
| post:curate/dispose（2） | AdminContentAction pin/unpin/feature/unfeature/take-down；Post旧非作者delete | curate与dispose分开；post restore仍拒绝，不能因有dispose擅自增加恢复能力 |
| comment:dispose（1） | Comment旧非作者删除；管理take-down/restore | 必须按target的POST/ACTIVITY/PUBLIC_EVENT/ARTICLE等可信主体，保留审核/根线程及导师门禁；不可将当前OPR旧路由能力扩到新Article业务 |
| media:read/write（2） | AdminMediaController不同用途的CONTENT_MANAGE/ACTIVITY_EDIT/PUBLIC_EVENT_EDIT；CanonicalAttachmentController登记 | namespace来自服务端用途/绑定owner，不是所有媒体或LearningTask私有文件许可；OPR原无直接全媒体浏览API，不能为补新read码开放它 |
| review:read/decide（2） | AdminReviewController，审核owner按目标/决定/版本/幂等执行 | 不自动新增LearningTask/Article审核能力 |
| report:read/handle（2） | 旧ReportService按OPR/ADMIN；AdminReportCaseServiceImpl结案的VIOLATION_HANDLED可能调用内容下架 | 新旧路径效果不同，必须按最终目标叠加dispose；不能只迁移report码 |
| notice:read（1） | AdminNotificationPlanController列表/详情 | 无广播创建/发布、无微信许可 |
| user:read（1） | AdminUserController列表/详情 | 无政治身份/组织/导师/积分写权 |
| post-dictionary:read/manage（2，ADMIN额外） | AdminPostDictionaryController | OPR原14码不含这两项，不得给OPR补上 |
| system:read（1，ADMIN额外） | AdminSystemController overview | 不包含async retry/repair自由运维入口 |
| user:manage（1，ADMIN额外） | AdminUserController mute/ban/restore → UserService；当前仅旧role=USER目标可处置 | ROOT也可能旧role=USER，启用来源前所有限制/恢复/注销owner必须先接统一guard，不能沿旧role保护ROOT |
| user:role（1，ADMIN额外） | AdminUserController PUT role → changeUserRole，旧最后ADMIN锁 | **排除新业务grant**；D07 已批准全局冻结旧单 role 写，历史入口由 Service 拒绝409；只能使用正式 Assignment 治理，不能靠它授/撤ROOT或覆盖独立Assignment |

前29项是OPR候选，额外5项得到ADMIN的34项；其中content:manage只是旧兼容占位，user:role明确排除新业务兼容grant，**不是可以直接插入的29/34个有效新Permission**。目录也没有LEGACY_OPERATOR_V1/LEGACY_ADMIN_V1。ArticlePublisherBootstrap 已退役，不作为启动初始化入口；旧 Publisher 映射与独立 Article gate 见 Article 专题。Security Foundation V1已收紧普通Token管理效果：非作者处分、非公开管理查询和旧举报管理统一拒绝，改走既有独立Admin接口。逐动作矩阵包括旧角色/接口/效果、新Permission/namespace、组合授权、保留/扩大/缩小风险、客户端及切换测试；静态对照不等于全部动作端到端通过。任何目标人员迁移仍须逐效果核验与明确批准，不从静态对照推导放行。

## C. 数据结构与初始化

[V41](../src/main/resources/db/migration/V41__add_rbac_foundation.sql)只新增六张表和目录登记：

- permission_definition：code PK、允许空间、当前状态和受控制品digest。
- role：不可变code/空间组合的预设定义及当前可用状态。
- role_permission：联合PK及两个RESTRICT FK；Mapper不提供成员编辑。
- user_role_assignment：User/Role FK；namespace/GLOBAL CHECK、时间顺序、ROOT无期限、撤销事实配对；唯一当前槽包含user/role/namespace/scope/current_slot。
- authorization_authority：User PK/FK、LEGACY/ASSIGNMENT CHECK、不透明version；V41不回填。新 User 同事务显式初始化 ASSIGNMENT 且不创建任何管理 Grant；历史用户来源变化只能使用获准离线计划，不存在缺行默认来源。
- authorization_change：User及Assignment FK、actor+binary key唯一、operation_id唯一、snapshot_version=1、成功操作/快照/原因/key长度约束。

过期即时失效，无需Job；重授时在同一治理事务释放过期槽并建新Assignment。撤销释放槽、保留旧行。历史行通过NULL槽兼容重授。引用不cascade删除。grantChangeId/revokeChangeId由事务owner与成功Change原子维护；MySQL不支持可延迟的环形FK，未伪造双向立即FK，应用事务及故障回滚测试承担该一致性。

V41 seed与应用资源 `rbac/rbac-v1.json` 固定同一canonical SHA-256；应用每次ASSIGNMENT决策核对全部受控码、空间、digest及成员集合。不同制品或非法DB改动拒绝；不是“数据库有一行就承认支持”。初始化无人员数据，目录登记不触发业务就绪。

V42只给authorization_change添加内部AUTO_INCREMENT publication_order及唯一索引。UUID公共ID、历史行、六表职责和成功事务不变；ROOT mutex序列化的既有writer自动取得发布序号。审计首屏固定已提交序号高水位，后续页同时按occurredAt/id seek，不能把新提交插入当前遍历；时间戳/随机UUID本身不承担提交高水位。迁移不启用目录、不改人员或Authority。

V43只新增rbac_offline_operation/effect两张独立运维事实表。离线ROOT的grantChangeId指向同事务真实effect，普通GRANT/REVOKE继续指向Change；公共审计列表不混入运维操作。专用合成执行器不注册为应用Bean/HTTP/启动任务，只允许固定loopback测试schema、环境guard和独立双签批准；生产执行硬禁用。锁序、版本、原收据恢复与保留旧撤销事实见[离线治理](rbac-offline-governance.md)。V41/V42及既有成功行不改写。

Reader和QueryService现于同一主库快照核验已知离线grant的effect、operation固定收据及不可变授予字段；effect单独缺失仍由收据origin定位，当前能力及历史投影均拒绝不完整事实。正常撤销不会改变授予引用，也不要求历史Assignment保留原当前version/未撤状态。完整应用/真实QR、Redis、九HTTP消费及修复负例证据归[2026-10-17闭环报告](C:/Users/ACER/Documents/Codex/rbac-offline-online-2026-10-17/rbac-offline-online.md)，不扩成全库恢复或生产批准。

Assignment分页首屏固定evaluatedAt及首行grantedAt/id高水位；状态按该时刻派生。cursor绑定actor、目标、完整筛选/页大小、当前actor/target授权版本和catalogVersion，版本变化返回400 validation-error / CURSOR_STALE。审计cursor绑定actor/筛选和发布高水位，每页重新判ROOT。局部RbacCursorCodec使用进程内随机HMAC键及15分钟窗口，不含会话秘密；重启/切换节点后的cursor拒绝并从首屏刷新，生产若要求跨节点续页需另行受控共享键/路由策略。不能把cursor当许可。

## D. 当前授权读取

`RbacAuthorizationReader.read`使用新的REPEATABLE_READ事务，从当前单一主库取得一致决策；不会加入外层旧MVCC快照。当前应用没有新增读库路由，后续引入读库必须保持该主库承诺。Mapper显式清理一级查询缓存、禁用二级缓存，治理重检不复用旧实体。

Reader当前Assignment、QueryService返回页和授权审计快照均按调用批量核对已知离线证明，去重仅覆盖相同引用的查询，不省略每个历史快照的字段核验。不同事务或READ_COMMITTED治理等待后的重检仍独立读主库；Controller的独立判权事务不因性能合并。查询边界与保留的普通授权历史扫描成本见[离线治理](rbac-offline-governance.md#1-审计与公共边界)。不新增Schema、支持目录或来源模型。

在线 Reader 只接受 ASSIGNMENT；LEGACY、未知或缺失来源返回来源不可确认，不再产生旧角色/通配符权限。历史来源及收据仍保留解码。`Decision.allows(permission, namespace)`只接受ASSIGNMENT证据，不将旧`*`解释成新授权。ASSIGNMENT不再要求旧role为2/3；它逐User资格、Role、Permission、namespace、GLOBAL、有效期、受控应用支持及完整Role就绪检查，并保留Assignment来源。来源丢失、非法、目录破坏、主库不可读拒绝；无有效grant是已知false，不是来源异常。

`CurrentAuthorization`是唯一来源分派入口，已接入SaPermissionProvider、AdminTokenService、QR和旧业务owner。普通Token旧管理分支统一拒绝；readLegacyEntry也不保留MUTED管理例外，LEGACY/ASSIGNMENT均要求当前有效账号。旧Session role和旧auth Redis正向缓存不参与放权。Sa返回候选及兼容粗别名只预筛选；Service始终检查真实namespace/动作，别名不进入Decision.grants。

AdminTokenService分开requireAuthenticatedSession（独立admin Sa映射、revocation、QR activation、当前User资格）、requireAdminAccess（再看当前来源许可）、requireValidLogin（旧表示已关闭，改用 capabilities）及diagnoseCurrent。九个`/v1/admin/authorization/**`入口经RbacHttpAccess认证后再由Service判ROOT；本人capabilities无需grant。ASSIGNMENT撤至无grant后业务401，正式诊断仍200 false/[]且不撤有效Token；LEGACY 在线来源拒绝，旧 /me 不再返回 scalar 角色。没有匿名例外。Reader不替代owner的可见性、Ownership、导师资格和状态检查。

## E. 授权事务与ROOT保护

`RbacGovernanceService.grant/revoke`拥有独立READ_COMMITTED事务：锁ROOT Role行作为治理mutex → 按User ID顺序锁actor/target → 当前ROOT → 同key收据恢复或新执行 → target来源/版本/目录/时间/当前槽 → 当前ROOT复核 → Assignment/Authority/Change共同提交。失败完整回滚，无自动重试。成功重放不重新计算原状态，不因为后来撤销/过期重新授予。

ROOT只以当前正常未删账号、ASSIGNMENT来源、启用并完整支持的受控ROOT、AUTHORIZATION/GLOBAL、有效Assignment计数。有效ROOT用户按人去重。没有自动到期ROOT或普通Role停用/重新启用API。

`protectAccountChange`是MANDATORY事务guard，要求owner显式READ_COMMITTED（拒绝默认隔离或旧RR快照），不能独立提交。UserService禁言/封禁/恢复在拿业务User锁前调用：ROOT行mutex→按ID排序锁actor/target User及Authority→复核当前有效ROOT、禁止自改和最后主体→user:manage和目标业务限制→同事务写入/原操作日志。LEGACY shadow ROOT不构成当前ROOT；来源缺失/损坏拒绝。最后主体保护仅拦截目标本身仍是最后有效ROOT的变更，不把恢复另一个已封禁ROOT误判为移除最后主体；恢复仍要求有效ROOT治理者及user:manage。真实MySQL两个ROOT并发封禁只成功一个，LEGACY ADMIN不能封禁role=USER的有效ROOT。Guard不授予普通业务权。

源码没有账号删除/注销或生产Authority切换的实际owner，未虚构公开操作；隔离离线SOURCE_SWITCH只在现有ROOT sponsor及双签、空shadow和版本条件下切来源，不授角色，使用同一mutex/User锁序。限制到期的既有状态归一化只压实已经生效的到期事实，不创建新授权、不恢复已撤Token。旧user:role 已全局冻结，Service 对所有来源返回409 authorization-source-migration-required，不写旧 role、Authority、Assignment 或成功审计；旧角色写入和配套 Authority version writer、最后旧 ADMIN 查询已移除。账号限制/恢复继续统一 ROOT guard；没有role同步或自动来源转换。

## F. G2 旧管理入口、兼容与协调项

| 责任 owner | 当前统一裁决 | 保留与限制 |
|---|---|---|
| PostService / PostQueries / PostGovernanceService | 作者删除保留，非作者普通Token处分拒绝；普通Token非公开管理查询拒绝；治理检查下架post:dispose、置顶精选post:curate及审核review:decide | 本人Ownership、当前公开资格/P1、导师门禁保持；恢复仍原有拒绝 |
| CommentService / CommentGovernanceService | 作者删除入口保留非作者comment:dispose检查；治理检查下架/恢复comment:dispose；从持久关联主体解析namespace，审核同理 | 不信任传入targetType；本人删除/根线程/导师业务检查保持；ASSIGNMENT ARTICLE独立拒绝 |
| ReportServiceImpl | 旧普通Token管理列表/处理拒绝，独立Admin举报入口保持 | 单条只改report，不偷偷改变内容；修正旧selectById传入Wrapper导致合法处理失败；真实HTTP结案已测 |
| AdminReportCaseServiceImpl | 聚合read/handle@REPORT；实际TAKE_DOWN之前额外dispose@真实内容域 | 正常结案不要求dispose；无处分权时report和内容均不提交；违规结案与单条旧语义保持差异 |
| AdminContentServiceImpl | 列表及summary在SQL按允许namespace过滤；详情/操作按实际Post或Comment主体 | REPORT受控投影沿report:read；不能以content:read代替dispose；ARTICLE不接入 |
| ActivityServiceImpl / ExamServiceImpl | read/edit/publish/unpublish/lifecycle分别匹配ACTIVITY/PUBLIC_EVENT；编辑公开表示同时要求publish | 不通过HTTP Method推断效果；文档导入按实际目标域edit；业务状态/附件约束保持 |
| ActivityRegistrationServiceImpl | read/export分开；内部作废使用registration:dispose；所有旧内部admin helper同样经过dispatcher | 未新增公开作废HTTP、不启用尚未就绪权限 |
| OfficialContentServiceImpl | read/edit/publish/unpublish@OFFICIAL_CONTENT，候选公开正文变化检查publish | 不开放Article |
| AdminReviewServiceImpl | POST/ACTIVITY及混合Comment队列按持久主体解析域；列表、cursor分页、计数、summary在SQL过滤；READ独立，DECIDE同时要求同域read+decide，写入仍委托owner | 评论支持POST/ACTIVITY/PUBLIC_EVENT；未知/孤立主体拒绝，ARTICLE通用审核对两种来源均拒绝，保留独立正式门禁；USER人工资料审核无合法review namespace，不能用user:read/manage代替 |
| MediaServiceImpl / AttachmentReadServiceImpl | 管理上传/登记media:write；可信绑定主体media:read；未绑定已登记附件仅上传者可沿精确对象/登记记录的原业务用途读取，另需当前同域media:read | 限ACTIVITY/PUBLIC_EVENT/OFFICIAL_CONTENT；非上传者、缺登记/清理中/用途不符/历史未知对象拒绝；外部URL登记ASSIGNMENT503；Article/私有成果独立gate；不信任客户端targetId |
| AdminNotificationPlanService / AdminSystemService | notice:read@NOTIFICATION / system:read@SYSTEM | 无广播/配置/修复新能力 |
| UserServiceImpl / PostDictionaryAuthorization | user:read/manage@USER；旧role写全局冻结；字典read/manage@POST | 不同步旧role；ROOT 依据当前就绪能力派生业务权，字典写入actor与admin身份一致 |
| AuthServiceImpl / UserConverter / Mapper | 普通登录仍写role快照；公开platformOperator、作者role展示、列表筛选、旧角色审计记录仍保留 | 都不作为ASSIGNMENT放权来源；getUserAuthInfo保持P1主库读取，旧Redis key只evict |

需要Contract/产品协调的迁移差异：USER人工资料审核；外部媒体无可信归属的登记/复用；旧/me及菜单能力读取。混合评论队列不需要新请求字段，服务端以授权域收窄既有响应，不能由前端隐藏全局数据。本人资料沿User owner安全检查后原子生效；USER_NICKNAME/AVATAR/BIO/BACKGROUND的历史事实不在当前人工队列（scene仅1/2/4），applyTargetDecision也不支持USER；迟到callback不发布或撤回有效资料。账号治理的user:manage不是资料审核。新人工资料审核需Contract先定义权限、namespace、决定效果与历史读取，不能直接启用已有历史记录。

普通 `/v1/users/me` 的 role、公开 platformOperator 及作者 role 保留兼容展示含义；当前管理资格来自 Authority/Assignment，不能反推。旧 user:role 写入全局冻结，不建设旧角色到 Assignment 的自动适配。正式管理只接受 ASSIGNMENT，普通 Token 不获得管理效果；收紧边界由当前Contracts定义。

Article 自有管理接口已由 ArticleAuthorization 映射正式 ARTICLE 权限，已发布编辑额外复核发布权，媒体读写分开；默认支持清单和人员授权不自动开放，分类管理由 ARTICLE_CATEGORY_MAINTAIN / ARTICLE_CATEGORY_STEWARD_V1 承接，旧 Publisher 管理仍无固定角色归属，见 [Article](article.md#授权配置和启用条件)。通用内容列表、评论处分、审核的 ARTICLE 门禁，以及 LearningTask、导师资格、政治身份/组织、党员积分的其他门禁不因本次映射改变。缺失归属不是GLOBAL，上述LEGACY兼容不代表可切换人员来源。

## G. Authority 建立、部署顺序与验证

新User创建：UserService.getUserInfo的原事务内insert User→AuthorizationAuthorityService.initializeNewUser（MANDATORY）→事件；任何一步失败均不提交User。并发创建仍由 User 唯一性及 Authority PK/FK 仲裁，不提供在线批量回填。没有自动来源切换。

新账号由 `AuthorizationAuthorityService.initializeNewUser` 在 User 创建事务内登记 ASSIGNMENT，不附加 Grant。`backfillLegacy` 已拒绝执行；旧来源、影子任命和缺行必须先只读盘点，不能用批量回填绕过当前切换前提。

发布顺序：核实目标实例/库/迁移历史与来源覆盖、全部 ROOT 及依赖 → 明确唯一合法 ROOT → 验证该账号实际扫码登录及当前治理 → 完成匹配 Provider、Admin Web/Mini 与必要迁移 → 按已验收切片发布目录。`AuthorizationReadinessGate` 要求无缺失/损坏/LEGACY 来源且只存在一个未撤销 ROOT 主体。在线授予第二 ROOT 返回 `authorization-root-singleton-protected`；自改和最后 ROOT 保护保留。多 ROOT、无 ROOT 或异常来源不自动选择、撤销、晋升旧 ADMIN 或改写历史依赖。

受控初始引导/恢复沿既有离线入口；新操作只接受一个 ROOT 目标，仍检查环境身份、批准凭据、版本、历史及审计；旧双 ROOT 收据只保留验证/读取能力，不再次执行双 ROOT 引导。数据库 CHECK 继续容纳历史来源，不将历史清理混入 V91。

## Article 精确组织职责

ARTICLE_EDIT / ARTICLE_PUBLISH 使用既有 CONTENT_EDITOR / CONTENT_PUBLISHER × ARTICLE 的完整权限交集，仅接受精确 ORGANIZATION，无受众或层级继承。复用当前统一预览、提交、委派、有效来源、撤销与 ROOT Guard，不新建 Permission/Role 或管理接口。模板副本保持 canonical NOT_READY/grantable=false；实现支持不自动改变运行目录事实或授予真人。ArticleOrganizationPolicy 在 Person gate / RBAC owner 锁内读取同源当前职责，普通管理员不能用旧GLOBAL、成员或负责人代替业务组织范围；当前ROOT凭已开放owner capability覆盖全部真实组织。快照、历史只读与迁移边界见 [Article](article.md)。


## 统一业务职责与 Admin Web 消费边界

Person/Points/统一授权使用 [人员专题](person-foundation.md) 声明的同一份固定输入清单；目录版本与 handoff 修订分别校验，禁止在本文另存过期版本快照。Backend 两份目录按字节同步，V62 只增加三个角色及成员并更新全目录 digest，保留旧生命周期、READY/grantable、成员、Assignment、来源和审计。自动候选媒体场景 11 仅用于官方宣传安全证据，不进入人工审核队列；迁移先检查场景编号碰撞。

`UnifiedDutyCatalog.supported` 表示实现支持；可授予投影统一检查目标环境 Role 和全部成员 Permission 的 ENABLED/READY/grantable。canonical 静态模板 NOT_READY/grantable=false 记录登记时证据，不是独立运行硬门禁；不等待 Contracts 改成 READY。目标、来源、期限、独立 ADMINISTER、可信 Provider 和业务状态门禁继续执行。下面矩阵表示 owning policy 消费边界。

每个 UNIFIED Assignment 在投影前核不可变命令、原 scope、准确 duty/role/namespace、期限、当前人员/来源及上级依赖；PLATFORM 模块映射还由 `PlatformDutyPolicy` 固定核完整权限交集与唯一平台目标。业务 owner 继续解析真实资源和具体动作。GLOBAL 只是 RBAC namespace scope，不代替业务目标。新三个窄角色禁止 Standalone 写入及读取放行；旧合法 Standalone/LEGACY 保持原边界，无隐式迁移。ROOT 仍走原治理保护及既有受控 owner 能力，不是新工作的通配来源。

业务短写事务由 `BusinessAuthorizationLease` 调用 `TransactionalAuthorization.begin(SHARED, actor)`，取得 Person 共享 gate → ROOT 治理 mutex → actor，使用显式 READ_COMMITTED 在同一事务初检和 beforeCommit 重验。没有额外 REQUIRES_NEW 连接或成功权限缓存；中途到期使业务更新与审计一同回滚。既有 Article 组织职责的 owner/锁和 Person/Points/Learning 事务仍由原 owning policy 执行。并发死锁或资源失败不自动重试写请求。二维码会话继续依实时账号和授权准入，ASSIGNMENT 的普通 scalar USER 无需变成数值管理员；撤销唯一职责后旧 Token 下一请求拒绝，独立 authority/工作仍可使会话准入，具体业务再判其自身来源。

RBAC 运行版本现绑定 RBAC bytes/支持与数据库可用状态、Duty bytes、逐职责支持集和 owning-policy revision；Duty-only 变化同样使旧 preview/cursor 失效。不得混跑不同目录摘要的二进制。

### 31 Duty 消费矩阵

所有职责按目标环境当前 options 判断可授；静态模板状态不覆盖运行目录事实。本地发布工具的清单及允许目标见 commands；工具清单、代码支持和数据库当前状态分别核对。运行可授不自动创建真人授权。业务名称和允许目标以 canonical/options 为准。

| Duty | Role / Namespace | 业务目标 | owning policy / 执行边界 |
|---|---|---|---|
| PERSON_IDENTITY_MAINTAIN | PERSON_FACT_STEWARD_V1 / IDENTITY | PERSON, ORGANIZATION, PLATFORM | PersonFactReaders / PersonMapper：身份 READ、MAINTAIN；完整人员单源，禁止自维护提权 |
| ORG_MEMBER_MAINTAIN | PERSON_FACT_STEWARD_V1 / ORGANIZATION | ORGANIZATION | PersonFoundationService.localAccess：局部成员读写，不证明完整集合/花名册 |
| POINTS_REVIEW | POINTS_REVIEWER / POINTS | ORGANIZATION, PLATFORM | PointsAcceptancePolicy.application：原受理组织与周期，申请读/审核 |
| POINTS_PERSON_LEDGER | POINTS_READER_V1 / POINTS | PERSON, ORGANIZATION, PLATFORM | PointsAcceptancePolicy.ledger / PointsR3Mapper.fullPerson：详情/列表/COUNT/个人统计/流水/冲正及材料的完整人员单源 |
| POINTS_STATS_CURRENT | POINTS_READER_V1 / POINTS | ORGANIZATION, PLATFORM | PointsAcceptancePolicy.statistics(false)：当前组织聚合，可信完整花名册及每人的完整成员事实；不加个人账务职责 |
| POINTS_STATS_ACCEPTING | POINTS_READER_V1 / POINTS | ORGANIZATION, PLATFORM | PointsAcceptancePolicy.statistics(true)：流水原受理组织聚合，不随人员转移 |
| LEARNING_MANAGE | LEARNING_MANAGER / LEARNING_TASK | ORGANIZATION, PLATFORM | LearningTaskScopePolicy：任务归属目标与接收对象上限分开 |
| LEARNING_REVIEW | LEARNING_REVIEWER / LEARNING_TASK | ORGANIZATION, PLATFORM | LearningTaskScopePolicy：审核队列/成果详情/材料/决定上下文；无完整受众或普通管理入口 |
| ARTICLE_EDIT | CONTENT_EDITOR / ARTICLE | ORGANIZATION | ArticleOrganizationPolicy：精确组织范围、保存及在线编辑的组合能力 |
| ARTICLE_PUBLISH | CONTENT_PUBLISHER / ARTICLE | ORGANIZATION | ArticleOrganizationPolicy：精确组织发布/撤下及历史署名，不借栏目 PLATFORM |
| ARTICLE_CATEGORY_MAINTAIN | ARTICLE_CATEGORY_STEWARD_V1 / ARTICLE | PLATFORM | ArticleAuthorization / ArticleWriter.saveCategory：全局目录读建改/排序/启停；层级、100项、ETag/安全/历史引用保留 |
| POST_DICTIONARY_MAINTAIN | POST_DICTIONARY_STEWARD_V1 / POST | PLATFORM | PostDictionaryAuthorization / PostOrganizationService：管理列表与分类/话题写；公共选项查询保留原边界 |
| POST_MODERATE | POST_MODERATOR_V1 / POST | PLATFORM | AdminContentService / PostService / CommentService：帖子读取/精选/下架与 POST 评论；无字典、无审核，帖子 restore 关闭 |
| OFFICIAL_CONTENT_EDIT | CONTENT_EDITOR / OFFICIAL_CONTENT | PLATFORM | OfficialContentService：草稿与旧 PUT；公开效果仍另需发布/撤下，媒体写独立 |
| OFFICIAL_CONTENT_PUBLISH | CONTENT_PUBLISHER / OFFICIAL_CONTENT | PLATFORM | OfficialContentService.publication / OfficialPublicationWriter：独立状态与媒体只读，无正文编辑 |
| ACTIVITY_MANAGE | EVENT_OPERATOR / ACTIVITY | PLATFORM | ActivityService：生命周期/在线编辑/媒体；DocumentImportService 服务边界与 SSE 输出重验；不推组织管理归属 |
| PUBLIC_EVENT_MANAGE | EVENT_OPERATOR / PUBLIC_EVENT | PLATFORM | ExamService：PUBLIC_EVENT 生命周期/媒体/导入；与 ACTIVITY 严格分域 |
| ACTIVITY_REGISTRATION_READ | REGISTRATION_READER / ACTIVITY | PLATFORM | ActivityRegistrationService.pageAdmin/pageAdminContract：报名读，不附活动编辑 |
| ACTIVITY_REGISTRATION_EXPORT | REGISTRATION_EXPORTER / ACTIVITY | PLATFORM | ActivityRegistrationService.exportCsv/exportXlsx：独立导出与完整快照、公式防护；作废仍关闭 |
| PERSON_IDENTITY_READ | PERSON_FACT_READER_V1 / IDENTITY | PERSON, ORGANIZATION, PLATFORM | PersonFactReaders：仅身份 READ，不满足组织域 |
| ORG_MEMBER_READ | PERSON_FACT_READER_V1 / ORGANIZATION | ORGANIZATION | PersonFoundationService.localAccess：仅精确组织局部读，无维护或完整性证明 |
| PERSON_ORGANIZATION_READ | PERSON_FACT_READER_V1 / ORGANIZATION | PERSON, ORGANIZATION, PLATFORM | PersonFactReaders / PersonMapper：完整个人组织 READ，单源覆盖 |
| USER_ACCOUNT_GOVERN | USER_GOVERNOR / USER | PLATFORM | UserService / RbacGovernanceService：只处置普通 scalar USER；自身、真实 ROOT/受保护来源仍保护 |
| REPORT_HANDLE | REPORT_HANDLER / REPORT | PLATFORM | AdminReportCaseService：事项读/结案；要求下架另核真实主体 dispose，同事务零部分结案 |
| NOTICE_MONITOR | NOTICE_MONITOR / NOTIFICATION | PLATFORM | AdminNotificationPlanService：仅通知计划读，不授发送/恢复 |
| SYSTEM_MONITOR | NOTICE_MONITOR / SYSTEM | PLATFORM | AdminSystemService：系统/依赖监测读，不授配置/恢复/授权审计 |
| POST_SAFETY_REVIEW | CONTENT_REVIEWER / POST | PLATFORM | AdminReview / Post owner：POST 风险任务与 POST 评论；同源 read+decide |
| ACTIVITY_SAFETY_REVIEW | CONTENT_REVIEWER / ACTIVITY | PLATFORM | AdminReview / Activity / Comment owner：已有 ACTIVITY 风险任务与 ACTIVITY 评论；同源 read+decide |
| PUBLIC_EVENT_COMMENT_REVIEW | CONTENT_REVIEWER / PUBLIC_EVENT | PLATFORM | AdminReview / Comment owner：仅 PUBLIC_EVENT 下评论；EXAM 主体查询为空，无主体决定 |
| ACTIVITY_COMMENT_MODERATE | CONTENT_OPERATOR / ACTIVITY | PLATFORM | AdminContent / CommentService：真实关联 ACTIVITY 评论；合法恢复子集 |
| PUBLIC_EVENT_COMMENT_MODERATE | CONTENT_OPERATOR / PUBLIC_EVENT | PLATFORM | AdminContent / CommentService：真实关联 PUBLIC_EVENT 评论；不能借此处置 POST |

### 25 Role 消费矩阵

固定角色成员保持 canonical；同一宽角色的其他 namespace 不自动获得任何新业务目标。没有日常 Duty 不代表删除规划或允许其他模板代替。

| Role | 对应日常 Duty / 其他边界 |
|---|---|
| ROOT | 独立高级授权治理、最后 ROOT/自授保护；无日常 Duty |
| USER_GOVERNOR | USER_ACCOUNT_GOVERN |
| CONTENT_EDITOR | ARTICLE_EDIT, OFFICIAL_CONTENT_EDIT |
| CONTENT_PUBLISHER | ARTICLE_PUBLISH, OFFICIAL_CONTENT_PUBLISH |
| CONTENT_REVIEWER | POST_SAFETY_REVIEW, ACTIVITY_SAFETY_REVIEW, PUBLIC_EVENT_COMMENT_REVIEW |
| CONTENT_OPERATOR | ACTIVITY_COMMENT_MODERATE, PUBLIC_EVENT_COMMENT_MODERATE |
| EVENT_OPERATOR | ACTIVITY_MANAGE, PUBLIC_EVENT_MANAGE |
| REGISTRATION_READER | ACTIVITY_REGISTRATION_READ |
| REGISTRATION_EXPORTER | ACTIVITY_REGISTRATION_EXPORT |
| LEARNING_MANAGER | LEARNING_MANAGE |
| LEARNING_REVIEWER | LEARNING_REVIEW |
| REPORT_HANDLER | REPORT_HANDLE |
| NOTICE_MONITOR | NOTICE_MONITOR, SYSTEM_MONITOR |
| IDENTITY_ORG_STEWARD | 保留旧组合；不是可信事实或 Person 范围来源 |
| MENTOR_REGISTRAR | MentorManagementService 持久管理资料/核准；V64 发布实现目录，实际授权仍读运行目录。管理角色不授本人导师资格 |
| POINTS_MANAGER | BLOCKED：规则/任意调整/批准责任未定；不混入审核 |
| POINTS_REVIEWER | POINTS_REVIEW |
| RECOVERY_OPERATOR | BLOCKED：异步恢复高风险操作独立，不由监测 Duty 代替 |
| PERSON_FACT_READER_V1 | PERSON_IDENTITY_READ, ORG_MEMBER_READ, PERSON_ORGANIZATION_READ |
| PERSON_FACT_STEWARD_V1 | PERSON_IDENTITY_MAINTAIN, ORG_MEMBER_MAINTAIN |
| PERSON_SCOPE_ADMIN_V1 | 独立 ADMINISTER/批准范围管理；无日常 Duty，不从事实维护推导 |
| POINTS_READER_V1 | POINTS_PERSON_LEDGER, POINTS_STATS_CURRENT, POINTS_STATS_ACCEPTING |
| ARTICLE_CATEGORY_STEWARD_V1 | ARTICLE_CATEGORY_MAINTAIN |
| POST_DICTIONARY_STEWARD_V1 | POST_DICTIONARY_MAINTAIN |
| POST_MODERATOR_V1 | POST_MODERATE |

### Admin Web 实际调用与错误处理

七个统一入口均在 `/v1/admin/authorization`：GET `me/duty-options`；GET `users/{userId}/effective-duties`；GET/POST `delegation-authorities`；POST `duty-previews`；GET `duty-previews/{previewId}`；POST `duty-changes`。GET 使用服务端分页游标，不自行合并不同 option 的上限。普通负责人只展示当前 options，目录中31项不等于当前31项可授。

提交顺序为 options → preview → commit → effective-duties 刷新。POST 保留同一原始请求与 Idempotency-Key；previewId、intent 和提交者必须一致。服务器返回 current catalogVersion/evaluatedAt、option 的完整 authorityId/目标/期限，preview 返回源影响，receipt 返回固定提交结果及 Assignment/scope IDs，effective 投影解释 effective/reason/source。字段完整定义以 canonical schema 为准，不增加 Web 猜测 Role→Permission 的协议。

下面仅帮助定位已有请求形状；字段以 canonical Schema 为准，示例职责未出现在当前 options 时必须禁止提交：

```json
{"kind":"GRANT","granteeUserId":"123","authorityId":"服务端提供的完整来源ID","items":[{"dutyCode":"ARTICLE_CATEGORY_MAINTAIN","target":{"type":"PLATFORM"},"expiresAt":"2026-12-01T00:00:00Z"}],"reason":"安排栏目维护工作"}
```

POST duty-changes 的 body 为 `{"previewId":"预览ID","intent":原始intent}`。一层委派期限不超过 authority 及 selection 剩余期限；SAME_ORGANIZATION_MEMBERS 仅组织目标并绑定原周期，PLATFORM 只能通过明确 INDEPENDENT_APPROVAL。交接先授新来源、逐项重授下游，再撤旧来源；紧急撤销立即阻断依赖分支，不能改旧 sourceId 复活它们。独立合法来源不连带撤销。

官方宣传新增 POST `/v1/admin/official-contents/{id}:publish`、`:unpublish`，无 body，只接受当前强 If-Match。发布岗可先 GET 列表/详情/媒体，再执行状态操作；不回退 PUT、不补 edit。DRAFT 撤下409；已完成同动作只有当前 ETag 才返回当前表示，旧 ETag 仍412；首次 publishedAt 使用数据库秒精度且保留首次值。保存正文和引用不变，revision/状态/审计原子。原 PUT 的编辑+公开效果组合继续保留。真实自动文本/图片安全与媒体可读证明失败503，零发布；外部 LINK 视频当前缺可信内容安全证明，独立发布安全关闭，不把可播放链接当安全结论。候选日志可留存，不是业务已发布证明。

文章 `/articles/settings` 应直接按 article-category 能力进入分类接口，不能先要求 article:read/content:read。帖子字典与运营入口分别消费独立能力。学习纯审核员使用 `/v1/admin/learning-task-submissions` 与 `/v1/admin/learning-task-submissions/{id}` 的审核上下文，不转普通任务管理详情；不读取全部 audience、他人材料或隐藏文章正文。安全审核走 `/v1/admin/reviews`，积分审核走 `/v1/admin/points/applications`，三者不互相替代。

其他实际入口：账号 GET `/v1/admin/users`、`/{id}` 与 POST `/{id}/mute|ban|restore`；举报 GET `/v1/admin/report-cases`、`/{targetType}/{targetId}` 与 POST `/{targetType}/{targetId}/decision`；通知 GET `/v1/admin/notification-plans`、`/{id}`；系统 GET `/v1/admin/system/overview`。这些既有 wire 的 Result、字符串 Long、lowercase/数字枚举及本地时间保持，不能机械添加 ETag。举报处置按钮仍单独检查对应主体 dispose。

401 为会话不再满足准入；403 当前动作无权；404 目标不可见/不可用；409 重读预览/业务状态；412 保留草稿取新 ETag；428 补条件；503 表示事实/授权/Provider 暂不可确认，不能转空集合或改走 LEGACY。响应未知时保留原 key/body，先查收据或权威状态，不能自动用新 key 重授。PLATFORM 只表示本业务目录/模块；除 Article、Person、Points、Learning 已有明确组织语义外，不宣称 Activity/PublicEvent 组织级管理已支持。

### 安全切换与未决项

Person/Points 完整个人数据改为逐独立来源完整覆盖，再合并合法人员。A、B 两个来源即使同 authority/授予者也不能覆盖 A+B；显式 PERSON/本业务 PLATFORM 可独立覆盖。详情、列表、COUNT、个人统计与完整流水共用相同谓词。身份 READ 与完整组织 READ 仍按人员目录两域交集；ORG_MEMBER_READ 不进入完整目录。局部 A 受理申请、A 当前成员聚合和学习受众交集维持各自原语义；UNKNOWN 不变空，Points 即使 PERSON 直授仍要求完整成员事实。

这属于安全语义收紧，不是兼容增量。上线前在明确目标只读执行 `db/validation/unified_v1_preflight.sql`，候选计数不是授权证明；据真实批准依据显式新授完整来源，禁止自动扩权。专用测试库的结果不能替代目标环境盘点。保留原授权/墓碑/审计；不得回退允许跨源拼接的旧查询。先迁移 V62 再切匹配二进制，严格 digest 不允许混跑；按目标环境受控发布相应完整角色；缺少可信 Provider 的业务操作仍由 owner 拒绝。

导师资料/核准由人员 owner 的持久入口管理；报名作废、学习已参与特殊撤销、积分规则/任意人工调整与批准、异步恢复/广播/配置及统一待办，继续按各自契约、实现支持和运行目录判断，缺少依赖保持关闭；不创建虚假工作流或替代授权。导师管理角色不等于导师本人资格。实际目标迁移、真实微信扫码/COS/通知/AI、Web 浏览器与 Mini 设备不能由测试替身代替。

统一授权历史执行证据、逐场景状态及失败诊断曾输出于 `target/unified-authorization-implementation-2026-10-23/`，它是当前工作区测试产物，不是生产 READY 证明。


## ROOT 管理审计查询

`listAdminDutyHistory` 与 `listAdminOperationAudits` 使用独立管理身份及当前真实 ROOT；不从旧 ADMIN、SYSTEM_MONITOR 或单独 audit-log:read 推导权限。每页与返回前重查来源、账号和 ROOT，失败按新操作映射为 401/403/503，全部响应 no-store。既有 listAuthorizationChanges 保持原权限及成功角色授撤范围。

AdminAuditJournal 在业务 owner 原事务中接受闭合安全投影，失败使该事务回滚。角色/职责/委派、Person 事实、内容维护/治理、学习任务、积分 owner 保留原日志与收据；幂等回放通过原收据提前返回，不重复接受成功记录。新审计不读取请求原文、凭据、key、姓名、联系方式、材料或内部异常；自由文本原因整体隐藏，原原因及完整事实仍归源记录 owner。跨业务引用本身不授予源记录读取权。

V77 的 guard 仅串行化审计序号分配到提交，保证读到的高水位不存在更低序号的迟提交。游标有完整筛选/操作者绑定和签名，排序使用 UTC recordedAt 与稳定 eventId；每次窗口最多31天。历史来源未回填为新事实，coverage 明确 PARTIAL/UNAVAILABLE 和实际可用起点，不承诺无限历史或伪造 COMPLETE。没有审计清理或保留期缩短。

到期核对只消费此前固定记录的 expiresAt；绑定、成员周期和授权依赖失效沿已保存引用追加说明，不从当前绑定或目录重建历史，不复活原来源。AdminAuditLifecycle 使用现有 Spring 调度与本地事务，每分钟有界处理候选；effectiveAt 保留真实到期，recordedAt 为可靠记录时间。已确认业务目标的 HTTP 拒绝/失败由请求完成适配在独立事务追加；只有明确回滚才区分 REJECTED/FAILED，提交无法判定保留 UNKNOWN，已提交成功事实优先。认证前或无法确认业务目标的请求留在原安全日志边界；审计存储不可用会保留故障日志，不把拒绝记录伪装成成功。

契约定位为 contracts 的 admin-audit paths/schemas 与 coordination/admin-audit.md。实现、受控测试身份、实际登录来源、历史覆盖和部署状态分别验收；本地验证不代表 Admin Web 或部署完成。

## 账号应急停用与提交前保护

旧ADMIN/OPR、具有Assignment治理历史或当前委派权的目标账号仅真实ROOT可限制/恢复；普通user:manage不扩大为管理员停用权。旧role写冻结、来源互斥、自改禁止和最后ROOT保护保持。账号封禁事务推进User凭证版本，恢复不复活旧凭证或旧授权。Article保留REPEATABLE_READ和组织职责owner，在原末尾检查之外注册beforeCommit当前时间/来源/范围复核，审计写入等待跨到期时整体回滚；不强套只支持READ_COMMITTED的BusinessAuthorizationLease。所有旧普通Token管理分支默认拒绝，不回退MUTED例外。

账号普通治理使用CurrentAuthorization.requirePlatform复核同一USER_ACCOUNT_GOVERN/PLATFORM来源及user:read+user:manage，或当前真实ROOT派生owner capability；不能由独立GLOBAL Role拼接目标。LEGACY保留有限原效果，不叠加Assignment。Source=LEGACY的shadow Assignment仍不构成实际管理资格或ROOT，实际管理员目标保护只按当前来源解释。


ROOT管理边界：当前有效ROOT无需额外普通Duty/Scope即可管理所有已开放能力支持的真实资源。ownerCapabilities仍由同一有效ROOT、账号、精确namespace/permission、代码支持及运行目录裁决；普通Duty/Assignment不扩权。Article组织署名、学习任务管理及受众、积分管理按各owner使用该ROOT来源覆盖全范围，保留真实组织/人员/资源、本人资格、功能关闭、业务状态、可信来源、审计与提交前重验。effective-duties不伪造ROOT工作；duty-summary的effective也可表示当前ROOT对应已开放管理入口可用，具体动作仍逐项精确判权。ROOT撤销或账号失效后派生管理范围立即结束。

## 受限授权候选读取

`me/target-options` 与 `me/recipient-options` 绑定当前 ROOT 任命或一份有效委派来源及 Duty，复用现有 Person gate、ROOT mutex 和不可变证明。Person owner 的 `DutyCandidateDirectory` 按上限/目标/当前成员先过滤，再按姓名、昵称或可见组织名搜索及分页；不要求全局 user/identity/organization 目录权限，不返回总数或完整标识。未绑定 Person 显示不可授，独立批准可授 ACCOUNT_ONLY；同名依组织类型/稳定标识区分。

游标绑定 actor、来源、意图、目录/授权版本及 Person epoch，并在下一成员/账号期限边界前到期；版本改变返回 invalid-cursor，来源未知返回 unified-authorization-unavailable。候选不是授权凭证，preview/commit 继续重验完整同源范围、原成员/绑定周期及期限。

`MENTOR_MANAGE` 复用 MENTOR_REGISTRAR；`ACTIVITY_REGISTRATION_DISPOSE` 使用 REGISTRATION_DISPOSER_V1 的 read/dispose，旧名单读取/导出/活动管理角色不扩成员。代码支持不等于目标环境发布。导师资格管理仍禁止本人/历史本人关联的核准，管理职责不产生导师资格。
