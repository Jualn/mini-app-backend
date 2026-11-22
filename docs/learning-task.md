# LearningTask Backend

当前实现使用本地固定 `unified-v1-r4` / Person `identity-organization-v1-r5` 输入，V66 支持独立 PERSON 与 ACCOUNT 两种责任。受众 persons/personIds 与 accounts/userIds 分别授权；人员责任需当前可用绑定，普通账号选择不强制建档。预览保留 NO_BINDING/账号不可用/重复责任阻断，依赖变化要求重新预览。绑定后两类既有责任不合并。

任务固定唯一 managementTarget；管理归属与完整受众上限须来自同一有效来源，组织筛选仍需独立完整花名册证据。本人以 assignmentId 寻址；确认和材料保留实际 User，PERSON 同时固定发生时 Binding。换号继续同一人员责任，旧投递账号失去其访问；ACCOUNT 责任仍归所选账号。通知冻结 assignmentId 和实际投递账号，重绑不搬迁历史通知。旧任务明确账号责任由 V66 保留为 ACCOUNT，旧筛选受众不得猜测转换为人员。

当前隔离完整应用和专项目录见[commands](../governance/commands.md#独立person聚合专项验证)；历史真机记录仅保留原切片证据含义。固定 identityIds 仍缺可信 owner 映射，返回503。私有文件链由 Media owner 实现，能力查询以实际配置与检查为准；未配置 LearningTask 私有桶时返回零能力，未知依赖返回503，不能据通用 COS 配置开放附件要求。契约、源码、SQL、数据库、真实外部 Provider 与客户端分别验收，不以协调修改声称向后兼容。

协议权威：[正式 operations](../../contracts/api/paths/learning-task.yaml)、[schemas](../../contracts/api/schemas/learning-task.yaml)、[协调语义](../../contracts/docs/coordination/learning-task.md)、[完成事实与验收](../../contracts/docs/coordination/learning-task-v1-closeout.md)。本文件维护当前领域实现与依赖边界，不新建业务框架或另改 Contract。

## 所有权与执行入口

Controller/Converter 只处理正式请求和表示；`LearningTaskServiceImpl` 拥有任务、Assignment、Submission 与请求收据事务；`LearningTaskSubmissionQueries` 承接成果读取和审核上下文；`LearningTaskScopePolicy` 拥有任务管理归属与受众上限。User/Person、Article、私有媒体、安全检查、通知和异步调度保留各自 owner，不建立平行身份或审批模型。

`LearningTaskConfigurationQueries` 在原任务READ授权和REPEATABLE_READ事务内提供配置显示摘要；负责方组织名称和保存的组织分组名称必须由同源读取来源覆盖。Article owner 的 `publicTitle` 只查公开可读文章标题；不扩展通用组织／文章管理权限。摘要独立no-store且不参与编辑ETag。`LearningTaskClosure` 由摘要查询与原持锁close操作共同使用，按全部有效分配判断截止、待审和补交窗口；该查询不授予生命周期操作权。依赖不可用保留真实错误，缺失可选名称不伪造ID名称；无数据库迁移。

## 候选准备与安全检查

`LearningTaskServiceImpl.create/edit` 显式 NOT_SUPPORTED，短事务准备候选 → 事务外 `LearningTaskTextSafety` → 短事务重取锁并复核授权、状态、版本、候选全文、期限及条件资源。安全等待期间不持 task/request 行锁。创建首先查原收据，当前授权通过后恢复原结果，不因安全依赖随后故障而重新执行已成功事实；并发首次请求仍在最终事务竞争唯一请求身份。

Audit owner 的 `LearningTaskTextSafetyCheckService` 复用 WxClient 和 UserService 可信 openid，scene=1（provider 已定义的资料类），按 Unicode code point 分块；只接受原始 JSON 验证的完整 pass。仅 `dev & learning-task-integration & !prod` 的已批准独立测试环境兼容已观察的 keyword 空明细：顶层数字 errcode=0、errmsg=ok、有效 trace_id，综合 suggest=pass/label=100，至少一条 content_model 明确成功且 pass/label=100；keyword 空项必须恰好只有 strategy=keyword 与数字 errcode=0。显式 null、风险字段、未知策略/字段、模型缺失、非零错误、risky/review/未知结果、冲突、非法类型及重复 JSON 键均不放行。其他环境不启用空项兼容。risky/reject 为 canonical 400 validation-error；缺 openid、错误/不确定响应、异常为 CONTENT_SAFETY 503，无自动重试。NEVER 阻止事务内调用；材料提交与创建/编辑一样，在事务外检查不可变候选，最终短事务重新验证，不在持锁事务中执行远程检查。

无 Article 请求不调用材料准入。按 article-content-v1-r1，独立管理身份和 LearningTask 自身目标范围内可选择、引用跨发布组织的公开 Article，不要求 Article 管理职责；新增/改变非空引用及首次任务发布在写事务内重验 PUBLISHED、未删除及可信公开表示，并与文章撤下/删除共用 Article guard。此为原无条件 resource-unavailable 门禁的 breaking 授权修订。未改变引用的标题/说明更正、完成/提交/审核、期限、关闭/取消及发布后增补分配不重验材料公开性；材料可用性变化仍参与原表示和 ETag。DTO 严格拒绝未定义字段；成功创建的重放保留原 201、Location、body、ETag 和微秒时间。

## 受众预览与发布

PERSON 与 ACCOUNT 的解析按各自 owner 和当前同源 LEARNING_MANAGE 来源执行。组织筛选/范围展开要求可信完整花名册；identityIds 缺可信映射时返回 503。不能以空列表、默认全员或多个不足来源拼接替代完整授权。

预览绑定操作者、任务、用途、Task ETag、完整依赖快照与五分钟期限；发布/追加重新解析并比较，再重验当前授权、成员周期、绑定和账号资格。V66 保存完整 Subject、绑定、可用性和既有责任分类，人数从冻结成员计算；应用、过期或依赖变化后仍读取原名单，当前可执行性另行投影，不重新解释旧命令收据。

发布/追加只新增从未分配的对应责任；Task、Assignment、请求收据和站内通知受理在同一事务提交，通知失败全部回滚。PERSON 与 ACCOUNT 唯一性、绑定证明及 V66 转换以迁移和人员专题为准，不能继续套用旧 task/user 唯一语义。

## 本人责任、进度与成果

本人列表、详情、确认及成果接口从可信普通会话解析当前责任，按 assignmentId 寻址；其他人的责任隐藏为 404。Admin Token 不替代 Mini Token。纯确认原子更新 Assignment，不建空 Submission；首次 confirmedAt/completedAt 由服务端采样。客户端不得注入主体、状态、完成时刻或积分。

Assignment ETag 同时约束 Task 表示和 displayStatus；期限或父表示变化不能复用旧标签。相同 key 先按当前权限恢复原 body/ETag，新命令才按当前状态、窗口和条件标签判断。锁顺序保持 task → assignment → submission；统计按完整有效 Assignment 分母与统一 asOf 计算，不从一页人数或成果次数推完成率。

文字提交先短事务检查当前主体、本人分配、规则、窗口与幂等原收据，再在无写事务/行锁期间调用现有微信安全检查，最终短事务重验。安全通过的不可变候选直接落库，不接受客户端身份、状态或时间。无需审核有效提交完成；需审核只进入 PENDING_REVIEW，审核通过才完成。驳回建立固定 max(firstDeadline, rejectedAt+72h)，再次驳回不延长，补交新增 Submission，旧决定保留。提交/审核/取消/撤销竞争、截止、授权撤回、安全期间父状态改变、通知故障整体回滚与原 key 恢复须由对应定向测试验证。正式通知枚举没有“成果已提交”类型，未发明该通知；现有审核结果、补交提醒复用原 Notification/Job/Worker。

## 通知与提醒

`LearningTaskNotifications.accept` 在原业务事务内调用通知 owner，稳定 sourceKey 与 inbox_seq 保证站内受理；冻结 assignmentId、实际投递 User、标题和变化信息，不复制私密材料。既有九类 SYSTEM/IN_APP 通知不因此开放微信渠道，未定义的“成果已提交”通知不自行增加。

提醒复用既有 Job/Worker。首次 T-24h 绑定 deadlineRevision，补交 T-24h 绑定固定 supplementDeadline；创建时已过 T-24h 不即时补发。执行重新锁 task/assignment，复核当前责任、原绑定、账号、进度、期限、版本与 Job ownership；完成/撤销/取消/关闭使候选失效，不自动将原提醒改投新绑定账号。已合法计划但延迟的候选可在截止前恢复。

## 外部依赖与启用边界

| 依赖 | 当前保护 |
|---|---|
| Article 引用 | 由 ArticleReferenceReadService 提供公开材料准入与事务锁；选择器按 LearningTask 自身 read + edit、LEARNING_MANAGE 目标授权。当前不公开/已删除返回 resource-unavailable，依赖或公开证据未知返回 503；全部保留引用阻断 Article 删除，提示不披露任务清单。具体机制及已确认 ROOT 差异见 [Article](article.md#统一内容文件与保留) |
| identityIds | 缺可信 owner 映射返回 503，不解释为无匹配人员 |
| 私有附件 | LearningTaskFiles / LearningMaterialService 提供私有上传、登记、接管和受控读取；未配置专用桶为零能力，未知依赖503；能力不足时拒绝新建/设置附件要求与首次发布，不影响未改要求的标题更正及历史读取 |
| 审核/例外撤销 | 审核要求独立 read AND review；已参与责任的例外撤销要求独立能力，不由普通 manager 推导 |
| 运行目录 | 实现支持、Role 完整成员的运行可用性、真实授权和业务范围分别核对，不能复制测试目录启用真人 |

## 验证入口与证据边界

当前业务增量见 [LearningTask business V1](../governance/commands.md#learningtask-business-v1-validation)；历史基础命令见 [LearningTask 专项](../governance/commands.md#learningtask-focused-validation) 和 [独立 Person 专项](../governance/commands.md#独立person聚合专项验证)。数据库事务、loopback HTTP、实际 Sa/Redis/RBAC 与外部替身分别说明；规范化响应校验不代表真实 Provider 验收。

历史文字/纯确认切片的原始结果见 [live-result.json](operations/learning-task-integration/live-result.json)，首次迁移与更正事实见 [migration-result.json](operations/learning-task-integration/migration-result.json) / [root-correction-result.json](operations/learning-task-integration/root-correction-result.json)。这些是 2026-10-17 至 18 日的旧 User 责任切片，不能证明 V66 的 PERSON/ACCOUNT、当前账号授权或生产状态。新主体的证据目录与替身边界见 [人员专题](person-foundation.md#本地验证与交付证据)。

目标环境先核对实际 schema/history、目录、真实账号与绑定、安全 Provider、客户端版本和常驻 Worker，再分别验收无附件与文件切片。准备工具见 commands，迁移/恢复见 [数据库说明](../src/main/resources/db/README.md) 和 [runbook](operations/runbook.md#rbac-发布与恢复)。不从旧交接中的端口、PID、个人 ID 或批准推导当前执行授权。


## 审核提交人最小姓名摘要

审核队列每项与审核详情返回 submitterSummary；仅合法 LEARNING_REVIEW 即可读取，无额外人员目录/任务管理前置。PERSON 只使用本次 Submission 的实际 submittedByUserId、固定 personId/bindingId，并核对提交瞬间处于该绑定的 UTC 周期；不从现绑定、责任人或昵称推断姓名。提供当前获准姓名，不声明历史姓名快照。当前账号没有独立实名来源，ACCOUNT 返回 UNAVAILABLE，不强制绑定 Person。姓名源缺失/故障不阻断原审核读取；本人 Submission 不扩展姓名字段。

详情与 ETag 使用同一授权读取快照，姓名摘要变化纳入强 ETag；审核时仍重新核对当前任务/分配/提交和同一摘要表示。客户端从新的详情取得不透明 ETag 后再提交决定；原幂等收据回放优先级保持。


## 受众选项与冻结预览

`LearningTaskRecipientOptions` 以任务或新建 managementTarget、edit/assign 及当前 LEARNING_MANAGE 同源上限提供有界候选，不借 person:read/user:read。Person owner 的 `LearningRecipientReader` 提供最小姓名、关联定位及当前获准的专业班级辨识；组织展示限本次获准范围，未知阶段省略，不能把昵称当实名。SQL 先做组织并集与阶段交集、稳定游标分页，再按可信 Person 事实复核；最终完整受众仍由 ScopePolicy 验证一个来源可以覆盖全部对象，选项并集不等于职责拼接授权。

组织批量固定选择先通过 `completeOrganization` 验证组织当前完整名单，再逐页读取可选及不可选成员；普通候选搜索无需完整名单证明。`selectableOnly` 与排除名单在形成候选页前处理，长排除名单复用同一 owning query 的只读 POST。创建页只提交明确 personIds；organizationSelections 仅为其子集分组，保存和发布不按分组重新展开组织。旧 filters 的解析与整体阻断继续有效；新增分组随原 audience JSON 保存，无新增表或迁移。

材料候选由 Article owner 查询公开文章，支持两层分类筛选及双向发布时间游标；分类目录只提供最小辨识投影，不要求 Article 管理权限。字段与参数以 canonical 为准。

冻结 members 是预览分类事实，阻断优先，新增、已分配、阻断互斥。过期、任务或依赖变化仍可读取原名单，当前 canApply 与强 ETag 一同变化；原预览 key 回放也重算可执行投影。失权仍拒绝，apply 始终重验当前事实。

逐项核对已保存选择使用 `listLearningTaskSelectedRecipients`。它沿用管理详情的 `learning-task:read` 与当前管理范围，按当前同源受众上限逐条复核披露资格。只分页读取保存的 Person 和账号标识，脱敏项保留原位置。签名游标绑定操作者、任务版本、授权和页大小，载荷只使用页位置来定位条目。

## 私有成果与安全接管

`LearningTaskFiles` 先校验本人 Assignment 或独立成果读取职责，再调用 Media 的 `LearningMaterialService`。Media 独立保存 allocation、file、原登记收据；上传和登记使用原始 Idempotency-Key 精确大小写摘要作为存储身份，不受数据库默认排序规则折叠；不把公开 Attachment 或 Points application 当学习成果。签发、复制、技术核验、图片安全等待和取短期签名均在事务外，正式 Submission 与文件 BOUND 接管加入同一业务事务。任务/分配锁先于文件锁；回收只竞争未接管候选，正式历史不随重交、撤销、关闭、取消或通知到期删除。

候选在成功登记后固定保留24小时，响应返回 unboundExpiresAt；上传凭证上限15分钟，不能代替候选期限。服务端保留不可由客户端凭证覆盖的独立快照。图片使用独立 LEARNING_TASK_MATERIAL 安全场景，单次预留后未知结果不自动重发；PDF/Word复用受限被动解析，拒绝危险内容结构。VIDEO最大200MiB，必须取得 MP4/H.264及可选AAC的完整 ffprobe 核验证据；不沿用 Article/Points 的可选文件头降级。无文字且 minimumTextLength=0 的附件主成果沿同一提交/审核流程。

`LEARNING_TASK_PRIVATE_BUCKET` 显式绑定 app.learning-task.private-bucket，默认空，不回落公开桶，也不因其他业务私有桶已配置而启用。复用 COS 精确对象、private ACL、禁止覆盖及服务端快照机制；图片/PDF/Word每文件10MiB，最多9个。能力查询探测可信私有链；新建、重新设置 requirements、首次发布先在事务外检查，再在最终本地事务复核当前要求，未改要求的标题/说明更正和历史读取不受当前能力失效阻断。已发布规则不降级。

本人访问要求当前责任/绑定；管理员沿 File → Submission → Assignment → Task 独立成果范围读取，管理任务权限不能替代。正式文件校验真实历史 fileIds，临时文件还需原上传账号、有效候选和活动分配；跨用户/任务、失效或已回收候选404。每次 access 重新鉴权，只返回单对象最长5分钟HTTPS签名；云端 Range/客户端播放须另行验证。

## 自审、通知追溯与 Article 保护

审核在写收据和业务副作用前拒绝原 submittedByUserId，以及 PERSON 对应人员当前换绑账号；不比较姓名。独立 LEARNING_REVIEW 的授权入口保持不变。

V85 的 learning_task_notice 在同一业务事务留存 occurrence、责任、可确定 User、binding 与可用性。失效接收人跳过实际投递，不能传空 Notice；可靠受理失败仍回滚整个管理动作。已受理通知继续沿平台 createdAt 起90天可见窗口，溯源事实与任务/成果历史不由 inbox 到期清理。

Article 普通撤下检查任一 PUBLISHED 任务引用，截止经过或全部完成不解除；安全停展需显式 SAFETY、原因、原撤下权限/范围和强版本，原因与变更同事务留存。最小 usage 提示不返回任务标识。安全停展不等待任务处置，保留任务与全部引用；任一保留引用继续阻止删除。部署前须核对支持的管理端和旧草稿，停止可绕过保护的旧 writer；隔离验证不代表生产已启用。
