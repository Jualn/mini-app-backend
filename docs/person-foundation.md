# 人员身份与组织基础

协议权威是当前本地 Contracts 的 `unified-v1-r4`、Person `identity-organization-v1-r5`、Points `points-v1-r5` 三份 handoff 及其同一份135文件 SHA 清单。输入校验见 `tools/validate-managed-person.mjs`。没有 accepted compatibility baseline；独立主体、数据与副作用切换是 breaking。目录版本单独记录：RBAC `rbac-v1-unified-r2`、Duty `unified-duty-v1-r3`、本地 Person 政策目录 `identity-organization-v1-r3`；不能将契约修订当作目录版本。Role/Permission 与31项 Duty 定义未扩权。Backend 为 `0.0.1-SNAPSHOT` 加当前源码；Admin/Mini 的版本和验收独立记录，本地材料不代表其他机器已有输入。

## 独立主体切换状态

Person UUID 是业务主体；User BIGINT 仅表示账号、操作者和独立账号职责。真实姓名只由 `person_profile` 提供，普通账号无需建档。`ManagedPersonService`、标识服务、绑定服务及对应 Controller/Mapper 承接创建、读取、更名、生命周期、历史、专业班级登记和绑定。V65 建立独立聚合，V66 替换仍按 User 定义的事实、成员和名单/导师存储，并切换 Points 与 LearningTask。迁移边界、预检与恢复见[数据库说明](../src/main/resources/db/README.md#独立-person-旧结构清理-v66)。

Person 生命周期、当前身份、组织成员、来源证据、完整性、RBAC Assignment、管理范围和业务资格是分别维护的事实，互不自动提升。归档结束当前绑定；恢复不复活旧 bindingId。名单、姓名或 User 昵称不能推断身份、合并人员或搬迁业务。

## 档案、专业班级与标识

专业班级是独立共享登记。Merge Patch 省略表示不改、字符串表示设置、null 表示清除，空白字符串拒绝；保存来源、核实引用、实际操作 User 与时间，采用独立版本。来源快照不覆盖既有现值。当前组织已核实成员的字段读取/维护与整人权限分开；未指定组织的整人入口只接受明确 PERSON/PLATFORM 或合格 ROOT。其他组织的私有来源引用不进入共享字段响应或公开历史。

标识按固定技术命名空间登记、更正、撤销。默认学号命名空间为 `CAMPUS_STUDENT_NUMBER`，可通过 `people-qualification.student-number-namespace` 和 `student-number-display-name` 显式配置。学号仅 trim，保留大小写、前导零与内部字符。更正、撤销均保留旧值永久占用；数据库唯一键裁决并发。首期不允许跨人转移、自动合并或业务搬迁。

HMAC 匹配与 AES-GCM 加密沿用 `PersonPrivateData` 的安全 owner 和独立密钥配置；缺失/错误密钥失败关闭。完整原值只在独立授权的受控核对渠道解密，不进入普通响应、URL、日志。党建人员目录列表和详情在当前 `party-personnel:manage` 及精确组织范围内作为受控核对渠道，通过 `studentNumber` 返回当前已核实完整学号，同时保留 `studentNumberMasked`；普通来源、历史与错误仍不得返回完整学号。不能直接替换密钥导致历史密文不可读或摘要无法匹配。

## 绑定与治理

管理关联、解绑和原子重新关联同时检查 Person 当前唯一槽、User 当前唯一槽、档案版本及双方槽版本。一个命令结束旧绑定并创建新 bindingId，事件与永久收据同事务提交。当前本人绑定来自权威读取；自维护限制检查当前或历史关联，不比较两个 ID 空间。

依赖本人组织周期的任命固定保存 personId/bindingId/membershipId；解绑、重绑或周期结束后失效，重新绑定同一人员也不复活旧证明。独立批准的账号职责不自动转移，也不因解绑一概删除。每份来源须独立完整覆盖目标，不能拼接多个不足的来源。READ 不蕴含 MAINTAIN/REVIEW/REVERSE。

所有跨模块最终写校验使用共同 authority gate 与治理锁，并在提交前重验当前授权、期限、绑定及所需事实。正式答疑、账务、任务和文件访问按各自业务许可读取固定证明；不因关联变动改写历史归属。该串行保护尚无生产吞吐验收。

联合事务入口为 `TransactionalAuthorization.begin(PersonAuthorityGate.Access, long... users)`，以 MANDATORY 加入调用者事务，按 Person gate → RBAC ROOT 治理 mutex → 去重排序后的 User/Authority 顺序加锁，并返回 Person epoch。Person 拥有 gate/epoch 和事实；Authorization 拥有治理锁、账号/授权来源锁及当前权限目录。SHARED 用于稳定读取与业务资格校验，EXCLUSIVE 用于改变 Person 事实或范围的治理操作。Person 内部的导师权限就绪检查通过 Authorization 读取，不能直接使用 RbacMapper。

RR 用例在首个一致性读取前由 `PersonFactReaders.open` 建立联合锁和 `PersonReadContext`；该上下文绑定事务与线程，挂起、新事务或完成后均不能复用。RC 业务短写由 `BusinessAuthorizationLease` 使用同一联合入口，并在 beforeCommit 重读主库授权。Person 的事实、绑定、资格写继续在原事务内复核对应权限、时点和证明；失败连同业务状态、事件和成功收据一起回滚。资格候选检查与最终写入各自取得相应事务保护。

服务目录位于 `module/person/service`，按实际职责组织，仍共用同一 Person 聚合的表、事件、收据和事务：

| 目录 | 职责与入口 |
|---|---|
| `profile` | 档案、姓名、专业班级、标识与受控姓名读取；ManagedPersonService、ManagedPersonIdentifierService |
| `binding` | 账号关联、解绑、重新关联及内部绑定状态转换；ManagedPersonBindingService、PersonBindingTransitions，归档复用相同结束绑定逻辑 |
| `facts` | 身份、组织、成员、范围、事务上下文与完整性；PersonFoundationService、PersonFactReaders |
| `roster` | 党建名单、上传登记/解析/完成、来源匹配与申请；PartyRosterService、RosterUploadCommands、RosterUploadService |
| `qualification` | 导师资料、资格维护与当前资格；MentorManagementService、PersistentMentorQualification |
| `access` | 身份接入、Person gate 和面向业务的事实/范围能力；PersonAuthentication、PersonAuthorityGate、PersonBusinessScopes |
| `support` | Person 内部的加密、命令值、表示与事件/收据协作；PersonPrivateData、PersonnelValues、PersonnelReads、PersonnelJournal、ManagedPersonJournal |
| `bootstrap` | 非应用 Bean 的离线 PersonInitialAuthority |

HTTP、BO 与 Mapper 保持现有分层与协议。跨职责内部协作中的 Java public 可见性不构成跨模块 API；support 与 PersonBindingTransitions 仅供 Person 内部使用，由 ArchitectureBoundaryTest 约束。

## Person 事实与组织

`PersonFoundationService`、`PersonFactReaders`、`PersonBusinessScopes` 和 `PersonMapper` 统一以独立 personId 读取身份、完整成员集、局部成员及 PERSON 范围。缺 head 保持 UNKNOWN；明确 NONE、核实空集合必须由显式维护建立。组织类型是受控字段，不从名称推断；旧组织升级为 UNCLASSIFIED，须显式更正后才能用作党建组织。

`GET /v1/admin/organizations/{organizationId}/members` 从当前已核实局部周期列出人员，包含未关联账号的 Person。REGISTERED_CURRENT_MEMBERS 不证明完整花名册。局部 JOIN/VERIFY_PRESENT/REMOVE/VERIFY_ABSENT 仅作用于本组织，完整归属 UNKNOWN 不妨碍可靠局部正向事实；不提升完整成员集/花名册。整人 `GET /v1/admin/persons/{personId}/memberships` 另以 `knownMemberships + knownMembershipsCoverage` 返回当前已知有效登记，明确可能不完整；`currentMemberships` 仍只在完整 VERIFIED 时提供。完整替换和局部写共用人员事实版本，重入创建新 membershipId。

Points 的 CURRENT 组织统计及 LearningTask 按组织展开全体成员另需独立完整花名册证明。候选搜索、单条成员周期判权和普通党建成员维护不以完整花名册为前置。`PersonFoundationService` 提供组织完整性读取、CONFIRM/REVOKE 和历史，沿现有 organization:read/map-manage、精确组织范围与 Duty/ROOT 校验；不要求整人身份权限。空集合也须显式确认，增量导入与局部成员列表不自动证明完整。党建工作台的完整名单核对逐项处理输入差异及平台独有成员，核对实际成员周期集合后在同一提交记载新的证明。日常可信成员变更接续仍然有效的证明；没有可接续证明时继续正式成员操作，不制造新证明。

`PersonBusinessScopes.requireRoster` 检查确认事件与成员生命周期版本。版本纳入不可变成员周期、已跨过的开始/到期边界及组织停用记录；退出再重入、停用再恢复不能复活旧确认。读取完整性或历史时将已失效确认追加为 INVALIDATE；业务读取即时按版本拒绝过期证明，不等待该记录追加。确认/撤销、审计与永久命令收据同事务，集合变化使原计划409、旧ETag412，ETag不包含读取时钟。V87 将缺少核实人与确认历史的旧合成完整性记录降为 UNCONFIRMED，不把旧摘要提升为正式确认。

整人组织派生授权仍要求一份来源完整覆盖已核实非空成员集；UNKNOWN 不能由空集合真值放行。ROOT、显式 PERSON/PLATFORM 与受限控制来源分别校验，既有独立 OPERATE/ADMINISTER 控制和 ROOT_OWNER 来源语义保留。组织停用不删除成员、历史或批准。

## 党建名单

`PartyRosterService` 负责单组织 XLSX 上传、四字段映射、创建者完整学号核对预览、全批提交及更正。来源文件加密，不进入公开附件或 AI；不执行公式。专业班级单列、可为空，空白 Excel 单元格不会清除既有共享登记。学号文本保留原值；数值学号的格式/精度校验由 `RosterWorkbook` 实施，不能猜前导零。

未提交文件有效24小时。预览生成 planId、到期时间和依赖摘要；计划最多10分钟并受文件期限限制。提交重验当前组织类型/状态、来源版本、权限、Person epoch、绑定与成员依赖，批内重复学号整批阻断。Person、标识、本组织成员与来源全批原子提交；只有新 Person 初始化正式阶段，既有人员保留共享资料及正式阶段。LOCAL_ONLY 只返回本组织动作，不泄露外部命中、personId、创建/复用或阶段差异。

Mini `createMyPartyIdentityApplication` 只接受正式档案姓名和当前已核实学号，在Provider固定命名空间内精确匹配已有ACTIVE Person；不要求组织、有效名单或阶段，不新建Person。唯一账号/Person绑定、限流、永久幂等与历史保留；解绑再关联创建新bindingId和申请记录，历史APPROVED不是当前绑定。`getMyPartyIdentity` 从当前绑定读取，区分 UNBOUND、PERSON_UNAVAILABLE、UNKNOWN 与已核实事实。正式导入事实来源为 ROSTER_IMPORT，精确领取证明为 ROSTER_EXACT_MATCH；后者不是强实名认证。

同号异名及本组织来源快照冲突在预览阻断，提交使用相同判定并重验；重复导入保留既有Person、阶段和有效membershipId，相同来源行通过批次追溯事件复用。来源表示返回 `sourceEffect=TRACE_ONLY`。原文件及可恢复完整单元格的预览按固定期限清理：未提交为expiresAt后24小时、已提交为提交后24小时；清理只移除密文和临时计划，文件读取410 roster-file-unavailable，正式事实、来源行、映射、摘要、历史与成功收据保留。

上传命令沿用短事务收据登记、事务外解析、短事务原子完成的恢复边界；未知结果保留原 body/key，只读查询后恢复。成功收据不随预览过期删除。来源更正不能跨人转移标识或自动覆盖 Person 姓名。

## 导师与正式答疑

`MentorManagementService` 按 Person 建立资料和核准，允许无账号；姓名实时取档案，资料、业务资格、账号状态与 RBAC 分开。公开按 User 的认证入口仅投影当前绑定；正式回答及当前认证按 A3 方案 A 额外要求 ADMIN_MANUAL，须有创建时有权管理员、来源凭据、原子绑定事件与永久收据。ROSTER_EXACT_MATCH 不授予正式作答能力。已有自助关联可由有权管理员核实后通过同组合原子重关联建立新 bindingId，不原地升级历史，不延长资格。正式 Comment 保留原作者 User，并保存当时 personId/bindingId/qualificationId/version 证明；撤销、解绑、期限与最终写入通过共同 gate 裁决。历史回答从既有 Comment 证据查询，不建平行答疑表。

资料/资格使用共同强版本，禁止自核准；公开认证还检查当前账号可用性。V64 已有导师目录发布语义保留，V66 不自动核准导师、不授权真人、不改变角色或职责定义。

## Points 与材料

申请、流水、余额锁和汇总归 Person；保留原 applicantUserId、submittedBindingId、受理组织/周期及实际操作 User。当前绑定决定本人业务读取；历史误绑不自动迁移。自审/冲正检查原提交账号和该 Person 的当前/历史关联，换号不能规避。新申请及批准重验正式阶段与受理依据；资格失去不自动撤单或改历史金额。

已提交材料经对应申请的业务授权读取；原账号草稿仍由原 User 读取。此许可不迁移所有文件、账号通知或其他媒体。审核权限不能冲正；既有目录没有授予调整/批准角色时，冲正继续关闭，不能为演示链路扩权。

## LearningTask 与通知

受众明确区分 PERSON 和 ACCOUNT，分别授权并按责任去重。PERSON 需要可用的当前绑定账号，普通账号选择无需建档；预览保留 NO_BINDING、账号不可用及重复责任阻断项，依赖版本变化必须重新预览。既有两类责任在之后绑定时保留两份 Assignment，不自动吞并。

本人按 assignmentId 读取、确认和提交。PERSON 责任保留 personId；ACCOUNT 保留所选 User。确认/材料记录实际 User，人员责任同时记录当时 bindingId。任务与责任不随重绑改写，旧投递账号失去人员责任访问，当前绑定账号按原 assignmentId 继续。

新通知冻结 assignmentId 及实际投递账号；使用通知 structured 表示读取业务跳转目标。重绑不搬迁旧通知、不改投递人。缺 assignmentId 的历史通知保留文字和已读状态、关闭不可靠跳转；待发提醒核对原绑定，不自动重定向新账号。

## 持久化与恢复

事实、不可变事件、核实来源、实际操作者和成功命令收据同事务提交；CAS/唯一约束失败全部回滚。账号、人员与来源版本不能互代。有效期采用右开区间，最终检查使用当前时间；响应 no-store，ID 为字符串，时间带明确偏移。

同 key 成功重放先重验当前权限/范围/来源，再返回原业务状态、表示、ETag 和 Location；名单文件的可用性与保留期限按当前批次投影，重放不延长或恢复文件；不同语义返回409。超时不证明失败，创建命令支持原 key 恢复；禁止以新 key 重做未知结果。独立 Person 历史只含本聚合事件，身份、成员及导师业务历史保留各自投影。

`PersonIntegrityService` 以 Person 检查当前事实、完整性、事件与收据；UNKNOWN 不是损坏，不触发自动回填。来源损坏、依赖未知仍失败关闭。没有 accepted compatibility baseline，不提供旧 User 主体双读桥。

## 本地验证与交付证据

当前验证入口见[commands](../governance/commands.md#独立person聚合专项验证)。`ManagedPersonDatabaseHttpTest` 在全新 schema 运行真实 MySQL/Flyway/MyBatis/Spring 事务与 loopback HTTP；Admin身份/capability、外部安全及业务副作用中的替身须分别标记。实际 Sa/Redis/RBAC 的两条业务链路由 `PointsIntegrationHttpTest#v66*` 单独执行；微信、COS和内容安全仍使用显式外部替身。

证据分别保留于 `target/independent-person/`、`target/independent-person/v66/` 和 Surefire；固定输入与20个核心管理操作表示由 `validate-managed-person.mjs` 校验。历史现状矩阵与早期验收是静态历史快照，不构成本轮契约验收。源码、编译、迁移文件、Mock、隔离数据库及完整应用验证分别记录，均不代表本地业务库、服务器或客户端已部署。

## 关联使用边界

ACTIVE 且未结束、有可追溯来源的 ADMIN_MANUAL / ROSTER_EXACT_MATCH 绑定均可正常使用 Person。关联来源只追溯，不另设本人业务能力等级；导师正式回答及当前认证是 ADMIN_MANUAL 的明确例外；身份、积分、材料、PERSON 学习任务和绑定周期仍分别校验当前账号、Person生命周期、业务资格与当前bindingId。解绑、归档、周期变化不复活历史任命、旧绑定或旧资格。不授予 RBAC / Duty，不改专业班级共享维护规则。
