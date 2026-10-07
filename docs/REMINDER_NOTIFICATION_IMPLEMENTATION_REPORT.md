# Reminder / Notification / Delivery 实施报告

更新日期：2026-09-28

本报告累计记录 `IMPLEMENTATION_PROMPT.md` 第一轮通知/异步实施、2026-09-27 canonical HTTP compatible foundation，以及 2026-09-28 第二轮正常通知与基本恢复实现。长期设计已在 `reminder-notification.md`、`wechat-integration.md` 和 `IMPLEMENTATION_HANDOFF.md` 收口；每项状态仍以本报告列出的实际代码和验证证据为限，不代表生产已经发布。

2026-09-28 后续文档审查先补齐提醒意图/有效期、执行时接收集合、租约所有权、一次渠道规划、terminal/repair 与清理窗口；随后本轮按这些边界完成下述代码与定向验证。真实数据库 migration/preflight、生产 cohort/producer switch、真实微信和部署仍未执行，不能把本地 PASS 扩大为生产就绪。

## 0. 2026-09-28 第二轮结果

### 已实现

- Reminder reconcile 现在先按完整有效规则匹配既有计划：已接受且仅发送时刻逾期的计划不会被普通编辑取消；新计划仍只在 `sendAt > now` 时创建。Timeline 节点物理 ID、rule、sendAt、scope 与 type 未变化时，即使主体并发版本递增也 KEEP；真实变化才 CANCEL + CREATE。到期 fan-out 每批重新验证主体和对应精确节点仍未到，失效计划转 `CANCELLED`。
- `JobExecutionContext` 把 `jobId + leaseOwner` 作为 attempt ownership，批次前用数据库时钟续租；Notification/Delivery 写事务先 `FOR UPDATE` 校验 `RUNNING + token + lease 未过期`。Job success/retry/dead CAS 同样要求未过期 lease；90 秒单次执行预算小于 120 秒 lease，预算耗尽交回 durable retry。
- canonical Category 通知对已切 owner 用户显式写 `notification.inbox_generation=CANONICAL`，按批量读取的 sparse preference 规划渠道；`IN_APP` 在 Notification 本地事务内写 `DELIVERED`，关闭时仍保留内容事实但不进入收件箱。重复 `source_key` 直接 no-op，不因重放或重开偏好补建渠道。未切 owner 用户保留 legacy writer 行为，等待 D3 cohort 切换。
- 两个微信渠道继续因 capability/template/permission 证据不足而不创建 canonical Delivery；没有用旧服务号模板冒充新类型。既有评论、回复、审核等 1–7 类型仍走原 owner 和旧服务号兼容路径。
- Activity 取消、显式提前结束、已发布 ACTIVE 下已确认精确行动时间变化与有效地点变化，均创建冻结事件内容的 durable business fan-out Job；接收者为当前有效订阅者与有效平台报名者的去重并集。PublicEvent 已实现取消、已确认精确行动时间与行动节点有效地点变化，接收者为当前有效订阅者。扫描开始取得 userId 上界，每批最多 100；新增/退出关系遵守执行时集合及一批竞态。
- Delivery provider 调用前在受 ownership 保护的短事务中把 `PENDING -> PROCESSING` 并增加 attempt，网络调用在事务外；结果提交再次校验相同 token。`PROCESSING` 恢复为 `UNKNOWN`，不会盲重发。新增有界 repair 扫描：DEAD Job 对应 `PROCESSING -> UNKNOWN`，有证据未开始的 `PENDING -> FAILED`。
- 待定→精确、精确→待定、节点删除、地点清空仍按交接保持未定义，不触发群发；PublicEvent 普通 END、Activity 报名成功/取消、通用 UPDATED 仍未增加。

### canonical operationId 对应

| operationId | Controller / Service | Mapper / Migration | 本轮状态 |
|---|---|---|---|
| `getMyNotificationPreferences` / `updateMyNotificationPreferences` / `batchUpdateMyNotificationPreferences` | `CanonicalNotificationController` → `CanonicalNotificationServiceImpl` | `NotificationPreferenceMapper`，V21/V22 | PATCH 与原生客户端 POST 别名共用同一更新用例；Planner 已消费同一 sparse preference |
| `getMyNotificationChannelCapabilities` | 同上 | 无新增表；能力来自当前适配证据 | `IN_APP` available；两个微信渠道 unavailable |
| `listMyNotifications` / `getMyNotificationUnreadCount` | 同上 | `NotificationMapper.xml` 共同可见集合 | 新 canonical producer 已写真实 `IN_APP/DELIVERED`，可进入同一集合 |
| `markMyNotificationRead` / `markAllMyNotificationsRead` | 同上 | 同一 `canonicalVisibility` predicate | 与列表/计数保持相同授权和可见集合 |

请求到落库到读取语义为：认证用户的 owner 与六组合 preference → business/reminder fan-out 批量规划 → `Notification(CANONICAL)` 与启用的 `IN_APP/DELIVERED` 同事务落库 → canonical/legacy reader 共同集合 → 同集合未读和已读更新。V21/V22 提供 schema，V24 已补历史偏好与 owner 的受保护回填；V24 仍未在真实目标数据库执行。

### 验证与剩余门槛

- PASS：JDK 17 主代码编译，704 个 main source；保留 26 个既有 MapStruct unmapped warning。
- PASS：`tools/validate-async-processing.ps1`，62 tests，0 failures/errors/skipped；覆盖 Policy/reconcile、ownership token/续租、canonical IN_APP 规划与关闭、Delivery 状态、provider 分类、已确认时间/地点变化检测及既有异步回归。
- PASS：`tools/validate-event-information.ps1 -TestNames ActivityServiceImplTest,ExamServiceImplTest`，16 tests，0 failures/errors/skipped；覆盖 Activity/PublicEvent 服务及统一 durable business notification 生产者接线。
- BLOCKED：普通 Maven 定向测试生命周期仍会先编译全仓测试；既有 `InteractServiceImplTest` 与旧微信事件测试和当前主代码接口不一致。本轮使用仓库登记的 testIncludes 专项脚本取得隔离证据，没有修改这些无关 fixture。
- BLOCKED：已尝试真实 MySQL 集成，`root@localhost` 无密码访问被拒，因而空库 V1–V22 replay、版本升级、唯一约束/锁/repair SQL 未取得数据库证据。
- NOT RUN：Redis 集成；真实微信；目标环境 D2/D3/preflight、历史 cohort、producer switch、v1 drain；部署、生产迁移和客户端 E2E。

## 1. 批次状态

| 批次 | 状态 | 已完成 | 剩余项 |
|---|---|---|---|
| A. Database expand | PARTIAL | V20 扩展 `notify_plan`、`notification` 并新增 `notification_delivery`；V21 新增 sparse preference、明确 `LEGACY/CANONICAL` 收件箱世代与共同查询索引；V22 新增偏好来源和逐用户 owner；V24 回填历史偏好并切 owner | V1–V24 已在临时 MySQL 8.0.40 重放并验证 V24 样本与冲突保护；目标环境 V24、D2 分类和后续 producer/contract 切换尚未执行 |
| B. Reminder Policy / reconcile | PASS（代码/定向测试） | Activity/PublicEvent 独立 Rule Catalog 与 Policy 已按四条矩阵实现；只读取显式 semantic，支持精确 point/range 起点、T-1h/T-24h、当前 generation reconcile；Activity deadline fan-out 使用批量 `SUBSCRIBERS_NOT_REGISTERED` 差集 | 目标数据库样本与 production-like plan/job 并发仍 BLOCKED；旧数据缺失 START 时按设计少发，不启发式补齐 |
| C. fan-out / cancel | PASS（代码/定向测试） | attempt token、条件续租、每批 ownership 锁定、100 人上限、执行时集合上界、source key 幂等、取消/失效停止后续批次已实现 | 真实 MySQL 并发、长批次 lease recovery 与取消竞态未运行 |
| D. Business Event Notification | PASS（已确认矩阵的代码/定向测试） | Activity 取消/提前结束/精确时间/有效地点与 PublicEvent 取消/精确时间/行动节点有效地点 producer 已实现；Activity 使用订阅者和有效平台报名者并集；互动/审核保持原 owner | 待定/删除/清空等未定义产品分支继续 BLOCKED；报名成功、@ 提及和 PublicEvent 普通 END 明确不启用 |
| E. Delivery / WeChat | PARTIAL（IN_APP PASS，微信 BLOCKED） | canonical sparse preference 已接管 migrated-owner Planner；`CANONICAL + IN_APP/DELIVERED` 同事务；provider attempt、token 保护、UNKNOWN 与 DEAD repair 已实现 | 两个微信渠道因真实模板、Adapter、identity/permission 证据和真实请求均缺失，继续 unavailable / NOT RUN |
| F. Compatibility / rollout | PARTIAL | 保留 v1 reader；V21/V22/V24、八个隔离 API、共同可见集合、owner bridge 和 canonical producer code 已贯通；目标执行 V24 前未切 owner 用户保持 legacy writer | D2 preflight、目标环境 V24、生产 producer switch、v1 drain、contract 收缩和部署未执行 |

## 1.1 2026-09-27 canonical HTTP 扩展

- 已按 contracts 新增八个隔离的 `/v1/users/me/...` 操作，成功响应不使用旧 `Result/PageResult`；偏好 GET/PATCH 与 POST 写入别名设置 `Cache-Control: no-store`，读写身份只来自当前认证上下文。
- 已实现六组合 sparse preference 的完整读取、局部原子更新、`null` reset、重复组合整体拒绝；PATCH 与 `POST ...:batch-update` 共用同一 Service。owner 未切换时三个偏好操作均返回 `/problems/notification-preferences-unavailable` 503，写请求不产生写入。来源可返回 `SYSTEM_DEFAULT/USER_OVERRIDE/LEGACY_MIGRATION`。
- 已实现 33 项能力快照；`IN_APP` 报告本地可用，两个微信渠道在缺少逐类型模板、Adapter 与可靠 permission 证据时保守返回 unavailable，不伪造授权。
- 已实现共同收件箱集合查询基础、稳定字符串 ID、冻结内容读取、绑定身份与过滤条件的游标、同集合未读统计和幂等已读；集合当前纳入明确 `LEGACY` 行或已有 `IN_APP/DELIVERED` 的后续 `CANONICAL` 行。
- 2026-09-28 新增逐用户原子迁移入口：锁定旧设置行，保存 activity/exam 有效值为服务号 `LEGACY_MIGRATION`，随后切 owner；已切换重跑 no-op，存在无法解释的新偏好时停止。旧 GET/PUT 在切换后只桥接服务号，canonical PATCH 同步失效旧设置缓存；新用户创建时直接建立 canonical owner。
- 旧 `/v1/notify` 的列表、未读、单条已读、全部已读及未读缓存已改用 canonical 共同可见集合，避免新旧 API 对同一用户读出不同集合。D1 按新契约保持历史消息与已读状态，不用当前偏好回溯过滤。
- 本轮没有把现有 producer 改写为 `CANONICAL`，没有创建历史 fake Delivery，也没有实际迁移历史 cohort 或启用微信发送；因此这些新增接口是 compatible code/expand，不是生产切换声明。
- 验证：Java 17 主代码编译 PASS（697 个 main source）。按本轮交接要求，专项/全仓测试、真实 MySQL migration/preflight、微信、部署与 CI/CD 均 NOT RUN。

## 2. 已迁移与未启用场景

### 定时 Reminder

Activity/PublicEvent 两套 Policy 已按已确认矩阵接入 canonical 保存/发布/生命周期事务，计划与唯一 fan-out Job 由 reconcile 同事务创建。规则只认 `ACTIVITY_START`、`PUBLIC_EVENT_START`、`REGISTRATION_END`；label、排序、`EXAM`、`OTHER` 和“第一个精确节点”均无效。目标数据库样本尚未核验，因此不能声明生产启用或历史主体覆盖完整。

### 主动 Business Event Notification

已迁移：

- 顶级评论审核通过：内容所有者，发送者本人不通知；稳定 source key 保持 commentId + receiver + approved。
- 回复审核通过：被回复用户，发送者本人不通知；稳定 source key 保持 reply commentId + receiver + approved。
- 用户资料审核拒绝：被审核用户；稳定 source key 使用 auditLogId + result。
- Activity 取消/提前结束/已确认的精确时间与有效地点变化：执行时有效订阅者与有效平台报名者并集，关系事实不互相转换；canonical 与旧生命周期入口写同一种 durable fan-out Job，逐用户 source key 为 event identity + receiver。
- PublicEvent 取消/已确认的精确时间与行动节点有效地点变化：执行时有效订阅者；普通 END 不解释为提前结束。

部分兼容、尚未完成目标迁移：

- 系统广播：旧 `notify_plan.source_type=3` 可继续排空，但当前 checkout 没有独立的新广播 producer，因此不能宣称已迁出 ReminderPlan。

明确未启用：待定与精确之间转换、节点删除、地点清空等未确认变化分支；PublicEvent 普通 END；Activity 报名成功/取消；评论 @ 提及；把报名关系自动改造成订阅关系。

## 3. 持久化与失败语义

- Notification 是站内收件箱事实；微信 Delivery 的缺失、SKIPPED、FAILED 或 UNKNOWN 不回滚 Notification。
- `(notification_id, channel)` 唯一约束保证每个渠道只有一个 Delivery intent；业务 `notification.source_key` 吸收 fan-out/事件重放。
- v2 Delivery Job 只携带 `deliveryId`，执行时读取冻结的 Notification content payload；WeChat 层不再读取 Activity/PublicEvent Mapper 或 Entity。
- `async_job.attempt` 在 Worker claim 时增加；`notification_delivery.attempt_count` 只在 provider 调用前增加。
- provider 明确永久失败记 FAILED；网络异常/响应丢失记 UNKNOWN 并停止普通自动重试；provider 明确可重试码保留 PENDING 并交回 Job retry。
- Worker 取得 PROCESSING Delivery 后崩溃，恢复执行会将其转为 UNKNOWN，不进行可能重复发送的第二次 provider 写。

## 4. 验证证据

| 验证对象 | 结果 | 证据范围 |
|---|---|---|
| 2026-09-28 owner/reader 增量 main compile | PASS | 使用 JDK 17 编译 697 个 main source；仅保留既有 MapStruct 警告；按交接要求未运行测试 |
| 2026-09-29 原生客户端偏好写入别名 | PARTIAL | Java 17 main compile PASS；隔离编译并执行 `CanonicalNotificationControllerTest`，1 test PASS，确认 POST 路由复用 canonical 更新并返回 no-store 完整表示 | 全仓 `testCompile` 被既有 `InteractServiceImplTest` 构造参数与 `RedisKeyConstant.viewCount` 漂移阻塞；真实 HTTP/目标部署 NOT RUN |
| Java 17 main compile | PASS | 2026-09-27 使用 JDK 17 编译 690 个 main source；仅保留既有 MapStruct 警告 |
| Timeline semantic / Policy / reconcile 定向测试 | PASS | 66 tests，0 failures/errors/skipped；覆盖显式 semantic、label/order/首个精确节点反例、两套 Policy、过期/歧义抑制、plan reconcile、Activity 未报名订阅者批量差集及 Delivery 回归 |
| async / notification 定向测试 | PASS | 49 tests，0 failures，0 errors，0 skipped；覆盖 reconcile、plan/job 创建、Delivery 状态/attempt/provider 分类及既有通知生产者 |
| Activity lifecycle 定向测试 | PASS | `ActivityServiceImplTest` 13 tests，0 failures/errors；覆盖 canonical/旧状态路径相关行为 |
| Activity / PublicEvent producer 定向测试 | PASS | `ActivityServiceImplTest,ExamServiceImplTest` 16 tests，0 failures/errors/skipped；覆盖统一 durable business notification 接线 |
| 第二轮 async / notification 定向测试 | PASS | 62 tests，0 failures/errors/skipped；覆盖 ownership、reconcile、canonical IN_APP、Delivery 状态、repair 与变化检测 |
| MySQL integration（含 V20） | BLOCKED | 已实际执行；`root@localhost` 无密码访问被拒，随机测试库未创建；同次 51 tests 中仅 MySQL integration 1 error、Redis integration 1 skipped，其余通过 |
| Redis integration | NOT RUN | 本次未提供 `-RedisIntegration` |
| 真实 WeChat provider | NOT RUN | 未使用真实服务号身份、模板和订阅消息额度 |
| 全仓测试 | NOT RUN | 工作区存在大量既有未提交修改，按最小相关范围验证 |
| 生产 migration / backfill / drain | NOT RUN | 未获生产操作授权 |

## 5. 下一轮必要输入与 rollout 顺序

1. 在目标数据库运行 Timeline semantic preflight，人工处理缺少显式 START 的已发布主体，并验证 plan/job 创建、取消与恢复；不得对旧记录做启发式 backfill。
2. 在目标环境执行历史/v1 Job preflight；确认旧设置写入静默后执行 V24，并核对 owner/偏好数量与来源。临时 MySQL 已完成 V1–V24 重放及 V23→V24 样本升级，但不替代目标数据核验；D2 仍不得从时间、ID 或 SUCCEEDED 推断边界/送达。
3. 核对旧设置写入来源与冲突用户后执行 D3 cohort 导入；使用逐用户原子迁移入口，不覆盖已存在的新偏好。D1 已明确，无需再等待产品决定。
4. 在隔离 MySQL 对已实现的 Activity 接收者并集、Activity/PublicEvent producer 做真实并发与去重验证，确认稳定 event/source identity；产品未定义的待定/删除/清空分支继续不启用。
5. canonical `CANONICAL + IN_APP/DELIVERED` producer 已实现；下一步只在逐类型微信 template/mapping/permission 取得真实证据后启用对应微信 Channel，缺失时不得调用 provider。
6. 为八个 canonical 操作补充 HTTP、事务、游标、越权、并发已读和真实 MySQL 可见性证据，再整组启用客户端列表/计数/已读。
7. preflight 证明 v1 待处理工作为零时简化 drain，否则按真实状态处理；本轮仍不建设 Broadcast 产品功能。若新增 Delivery 运维或 UNKNOWN 恢复公共 API，先更新 contracts/OpenAPI。
