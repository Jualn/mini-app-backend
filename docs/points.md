# 积分申请与账务

当前本地固定输入为 `points-v1-r6` / `identity-organization-v1-r5` / `unified-v1-r4`；Points 以当前 canonical schemas/paths 与两份配套授权目录为输入。独立主体切换是 breaking，没有 accepted compatibility baseline；目录版本与契约修订分开记录，见[人员基础](person-foundation.md)。

## 实现与约束

申请、追加式流水、余额锁和汇总以 Person 为主体，原 applicantUserId、submittedBindingId、受理组织/成员周期以及实际操作 User 不随重绑改写。独立人员与账号绑定只决定当前本人访问；历史误绑不能自动搬迁账务。正常核分为1～10整数，reviewNote可省略，提供时不得为空白或null；历史核定值不改写。批准与唯一原始 GRANT 同事务提交，驳回不记账。申请返回最小人员姓名、原受理组织名称及与状态同快照的 accounting（NONE/EFFECTIVE/REVOKED）；awardedPoints/grantLedgerId始终表示原审核事实。

改分通过 `ledger/{ledgerId}:correct` 原子追加 REVERSAL 与独立 CORRECTION_GRANT，两笔共用 correctionId/originalLedgerId、原因及依据；再次改分沿当前未冲正正向节点，最终全额撤销不复活。撤销和改分必须提供 reason/basis。账、累计越界检查、审计、通知意图及原 key 回执同事务提交，回放先重验当前授权；原审核、Person、提交绑定和组织周期不改写。Person锁内对冲正存在性及写入累计使用数据库当前读，数据库保留原GRANT唯一及每个正向节点最多一次REVERSAL约束。

新申请和批准要求当前已核实 ACTIVE 正式阶段、有效受理组织/同一成员周期及当前账号条件。局部正向成员证据只证明本组织，不证明完整候选列表、完整个人成员集或花名册。申请按原受理依据授权；全人账务与 CURRENT/ACCEPTING 统计分别使用一份来源完整覆盖规则，不能将多份不足来源拼接。

CurrentPointsDependencies 在共同 Person gate、User/RBAC 与账务锁内校验真实会话、动作范围、当前绑定、期限和业务依据，并提交前再验。审核只需既有读取/审核能力；纠错复用 POINTS_MANAGER 的读取/发起调整/批准调整组合，通过独立 POINTS_CORRECTION 的 PERSON / 原受理 ORGANIZATION / PLATFORM 目标授权。禁止原提交账号或该 Person 当前/历史关联账号自审、冲正。纠错不依赖申请人当前成员、阶段、绑定、账号或档案ACTIVE，也不因原组织停用失效；管理员自身会话、来源、任命和期限仍须当前有效。只持纠错职责可读范围内已批准单申请、单申请账务链及不可变纠错整项结果，不能读完整个人账本、材料或统计。本人流水可按 applicationId 筛选，先验证当前本人归属。

材料仍归媒体 owner：独立 POINTS 私有用途、不可变留存、File→Application 绑定及审计不变。已提交文件通过申请的业务授权供当前 Person 读取；原账号未提交草稿不随重绑开放。不能用该许可迁移所有文件或账号通知。实际 COS ACL/STS/CORS 与安全供应商仍需独立验收。 存储按[架构 §2.2](architecture.md#22-cos-环境与访问类别隔离)划分生产/测试与公开/私有四桶，`COS_PRIVATE_BUCKET` 显式绑定积分私有桶，禁止回落到 `COS_BUCKET`。当前 Points 要求与公开媒体不同桶、同地域且 ACL 可核实；WechatPointMaterialSafety 使用微信图片异步检查及既有回调；图片须明确通过才可登记，待定/未知返回503 CONTENT_SAFETY，明确拒绝返回400 points-file-unavailable。PDF/Word/VIDEO 按当前业务决定暂不做内容审核，继续复核格式、大小、所有权和私有ACL，不宣称内容安全通过。V69 为不可变留存文件关联唯一自动审核记录（scene=12，targetId为原上传User）；记录先提交再调用微信，重试只读原结果，超时/崩溃不自动重新送审。短期私有签名仅向微信传递，不入业务历史、Job或日志。回调不登记文件，必须由后续登记命令重新鉴权。真实微信与私有对象下载/回调仍需环境验收。旧混用桶按[运维手册](operations/runbook.md#141-cos-环境隔离与混用桶迁移)逐项核对后迁移，不以目录或年龄推断环境。

结果通知与终审/撤销/改分同事务可靠受理，意图保存发生时 Person、当前可信 Binding/User。既有 worker 投递前复核同一绑定和可接收账号，换绑/解绑或账号明确不可用留跳过依据，依赖故障进入重试。改分仅产生一条 POINT_GRANT_CORRECTED/correctionId，不为内部冲正另发通知。Job schema v2 不解释旧无绑定证明的载荷；不将历史通知搬到新账号。可靠受理不等于外部投递成功；不含私密备注、材料或签名。

## 受理组织管理

独立 `POINTS_ACCEPTANCE_MANAGE` / `POINTS_ACCEPTANCE_STEWARD_V1` 职责只含 `points:acceptance-manage`，使用 PLATFORM 目标。ROOT 通过原统一授权中心授予专人；旧审核员和积分规则管理员不自动取得资格管理权。组织分类不推导受理资格。管理列表显式区分 UNKNOWN / ENABLED / DISABLED；启用限 ACTIVE 组织，停用可处理已停用组织。

`/v1/admin/points/accepting-organizations` 提供设置列表、详情及 decisions 历史/写入。写入要求原因、原始 If-Match 与 Idempotency-Key；永久命令回执、追加式证据及当前指针同事务，持有 Person authority exclusive gate 与 User/RBAC gate，并在提交前再验当前会话和职责。未知结果先读取当前设置，原 body/key/If-Match 保留用于同 key 恢复。启停不产生积分、不转移账务、不修改成员周期；真实组织仍需逐一批准，不自动启用。

CURRENT 组织统计在数据库中按参与者组织过滤后分页及计数；个人申请数按 PENDING/APPROVED/REJECTED/CLOSED 精确计数并必返 closedCount。所有积分汇总包含 CORRECTION_GRANT 与 REVERSAL，受理统计沿原组织且按申请去重，CURRENT 重叠行不能求和作为平台总额。

## 数据演进

V88 扩展追加式纠错、独立撤销依据、可选审核说明、通知投递结果及配套目录发布；不创建人员职责或组织资格。原始授予唯一索引保留，正向流水的冲正唯一约束与纠错两种kind唯一约束分开。已批准未上线切片直接替换，不提供旧积分数据、旧客户端或旧Job载荷桥接，不执行共享/生产数据操作；存在不满足新约束的旧测试记录时应保留现场并按切换范围处理，不能伪造依据。迁移文件存在不代表目标环境已发布。


V67 新增独立受理资格管理定义与永久审计/幂等回执，不创建 Assignment 或组织资格。V68 将已知旧定义的 catalog_digest 同步到新目录，修复 V67 遗漏导致的全局登录目录校验失败；不修改启停状态或授权。目标环境须完整执行 V67–V68 并部署配套目录/Provider 后再使用。

V66 为新主体建立数据库约束与提交证明，拒绝猜测转换已有旧 Points 申请/流水/命令，不自动回填 User→Person。旧资料清理、保留的职责/账户任务与手动执行恢复见[数据库说明](../src/main/resources/db/README.md#独立-person-旧结构清理-v66)。V1–V65 不修改；主开发库与服务器执行状态分别核验，源码中有 SQL 不表示已部署。

## 验证入口与证据边界

当前命令见[独立 Person 专项](../governance/commands.md#独立person聚合专项验证)。ManagedPersonDatabaseHttpTest 的真实 MySQL 专项覆盖 Person 所有权、换号、自审/冲正拒绝及不可变提交证明，业务授权/材料/通知 adapter 的替身边界单独记录。

PointsIntegrationHttpTest#v66* 的完整应用链路使用实际 Spring、Sa/QR、独占 Redis、MySQL、RBAC、Person、Points 和媒体 owner：创建、同 key 重放、独立审核、重绑、原提交账号失去申请/已提交材料访问、新绑定账号继续读取、旧草稿不迁移，以及目录拒绝冲正。外部微信、COS、内容安全是显式替身；这不是正式 Provider 或设备验收。新账户同时有任务两类责任的链路另核对 assignmentId 与实际投递账号。

实际响应和数据库证据位于 `target/points-integration/http.json`、`target/independent-person/v66/` 与 Surefire。`validate-managed-person.mjs --full` 复核固定输入，并按当前 canonical 校验核心及已捕获的业务响应；凭证已脱敏的响应不作为 schema 成功证据。旧 `target/points-phase-b/` 等记录仅为历史快照，不作为新主体验收。

r6 聚焦证据位于 `target/points-r6/`：真实MySQL事务/迁移/统计、实际MVC schema、独立Unified授权链及通知worker按各自替身边界分别记录。命令见[积分纠错专项](../governance/commands.md#积分纠错-r6-验证)，不以旧HTTP文件证明新kind或真实Provider验收。

## 剩余环境验收

真实受理组织与人员批准、正式私有桶/ACL/STS/CORS、微信与安全供应商、Admin/Mini 设备消费、目标库执行及部署仍需各自真实证据。尚无可信 identityIds owner 映射或 Learning 私有附件 Provider 的场景继续按所属模块关闭；不通过测试 profile、库名或伪造来源打开实际功能。
