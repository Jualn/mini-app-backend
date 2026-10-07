# Activity / PublicEvent / Reminder / Notification Architecture Review

> 性质：2026-09-24 的静态代码与迁移历史审查报告。本文记录“为何做出当前设计”，不是业务规则、数据库结构或运行环境的权威来源。第二轮产品矩阵已在同日收口，长期规则以 `domain.md`、`reminder-notification.md`、`wechat-integration.md`、`async-processing.md`、`reliability.md` 和 Flyway 为准。

## 1. Verified Current State

### Activity 与 PublicEvent

- Activity 由 `module/activity` 拥有，PublicEvent 的表已经是 `public_event`，但代码仍由 `module/exam`、`ExamInfo`、`TargetType.EXAM` 承载；两者没有统一 Event 主表。
- 两者共享 `eventcontent`、媒体和 Timeline 能力。V12 后业务时间只来自 Timeline；主页公共事项倒计时直接查询最近未来 `EXACT_POINT` 节点，是同步读模型，不是 push ReminderPlan。
- 发布状态和生命周期是独立维度。Activity 与 PublicEvent 的管理写入都会调用 reminder refresh，但当前传入 `sendAt=null`，实际效果是取消旧 plan，不创建新 plan。
- Activity 的订阅事实位于 `activity_enrollment`，正式平台报名位于 `activity_registration`；PublicEvent 订阅位于 `exam_subscription`。

### Reminder、Notification 与 Delivery

- `notify_plan` 当前同时表达活动/公共事项计划和系统广播；状态 `0/1/2` 只足以表达待 fan-out、fan-out 完成、取消，不能表达部分 fan-out 或渠道送达。
- `notify_plan` 与 `notification.plan.fanout` AsyncJob 可在同一事务创建；`JobService.create` 要求 `MANDATORY` 事务，Job 通过 dedupe key、claim/lease、retry/dead 和取消 CAS 执行。
- Activity canonical 生命周期通过 `cancelActivityPlans` 同时取消 plan 并 best-effort 取消 PENDING Job；PublicEvent canonical 生命周期只通过 `replaceEventReminder(..., null)` 取消 plan，旧 PENDING Job 会保留到期后由 plan 状态 no-op。两者都不能替代 RUNNING Worker 的批次再校验。
- fan-out 目前只在入口读取一次 plan，随后循环全部批次；取消发生在入口检查后时，旧 Worker 可继续生成通知。Notification 的 `source_key` 已有唯一约束，可吸收重复 fan-out，但不能停止失效 fan-out。
- Notification 已是 MySQL 收件箱事实，并与 `notification.wechat-deliver` Job 同事务创建；没有 `notification_delivery` 表，因此无法独立记录渠道状态、provider attempt、UNKNOWN 或 SKIPPED。
- 现有 plan fan-out source key 已使用 `plan:{planId}:user:{userId}`。直接活动订阅者通知仍使用 title/content 哈希，不能作为长期业务身份。

### Async 与 WeChat

- 旧 Redis List/BRPOP、Reminder ZSet/body、startup rebuild 和通用 QueueMessage 生产代码已删除；当前 Durable Job 由 MySQL direct claim，Outbox -> Redis Stream 只用于必须传播的 committed event。
- Job attempt 已由 `async_job.attempt` 记录，但没有独立 delivery attempt。当前 WeChat delivery 把 `ExternalServiceException/WebClientException` 一律转 Unknown Outcome，Worker 直接 DEAD；分类过粗但没有盲重试。
- `WxClient` 基本保持 provider-level，并为 token 失效执行一次刷新；通知模板链路中的 `NotifyPlanNoticeDataFactory` 却直接依赖 ActivityMapper，Activity 时间字段为 null，PublicEvent 模板仍未实现。
- 现有身份链路同时包含小程序和服务号能力，说明 Mini Program openid 与 Official Account openid 不能继续被当成一个抽象身份。

以上是仓库静态事实，不证明目标数据库已经执行 V17–V19，也不证明线上 JAR、Redis 或微信配置与当前 checkout 一致。

## 2. Audit Differences

与输入审计基线相比，重新验证后的差异/澄清为：

1. 旧 Redis List/ZSet 路径在当前生产代码中已删除，不再只是“准备迁移”；部署环境旧 key 是否删除仍未知。
2. Activity/PublicEvent 当前不是“能生成错误时间的提醒”，而是刷新时显式传 null，只取消旧 plan、不产生新 plan。
3. PublicEvent 主页 reminder 是用户读取页面时从未来精确 Timeline 节点计算的倒计时投影，不属于 Reminder/Notification/Delivery 链路；当前实现没有主体开始时间兜底。
4. 审查当时 `notification.source_key` 与 MySQL AsyncJob 已由 V17 落入迁移历史，而 `notification_delivery` 尚不存在；随后第一轮实施已由 V20 加入基础表，当前实施事实见独立报告。
5. Activity 取消的历史入口曾包含订阅者通知，但 canonical 管理生命周期入口目前只取消 plan/cache，不应把旧方法行为当现行契约。PublicEvent canonical 入口同样未创建业务事件 Notification。

## 3. Final Target Architecture

最终依赖方向为：

```text
Activity ── ActivityReminderPolicy ─┐
                                    ├─ Reminder reconcile ─ ReminderPlan ─ AsyncJob
PublicEvent ─ PublicEventPolicy ────┘                         │
                                                              ▼
Business Notification Factory ───────────────────────── Notification
                                                              │
                                                      DeliveryPlanner
                                                              │
                                                NotificationDelivery ─ AsyncJob
                                                              │
                                                      WeChat Integration
                                                              │
                                                           WxClient
```

Timeline 是业务时间事实；ReminderPlan 是未来业务意图；Notification 是用户事实；Delivery 是渠道状态；Job 是执行状态；WeChat 是外部副作用。详细状态、幂等和 schema 方向见 [Reminder、Notification 与 Delivery 设计](reminder-notification.md)。

### Contract boundary

| 层次 | 本设计中的内容 | 治理方式 |
|---|---|---|
| External Contract | 用户可调用的通知偏好、收件箱、Delivery 运维/恢复 API（只有真实新增或改变时） | 先更新 `contracts`/OpenAPI，再实现 Controller/DTO/错误与客户端 |
| Internal durable contract | Flyway schema、状态枚举、source/dedupe key、Job type + schemaVersion + payload、NotificationContent/DeliveryData 的持久化兼容 | 由本文链接的能力/Async/DB 权威维护，变更要有兼容、backfill、drain 和恢复策略 |
| 普通 Java DTO | 同一进程、同一版本内 Controller/Service/Mapper 之间的 BO/DTO | 遵循项目架构即可；未跨持久化/队列/应用边界时不建立额外版本协议 |

当前设计本身不改变已接受的 Activity/PublicEvent HTTP Contract，因此不为“证明审查完成”制造 OpenAPI diff。

## 4. Final Decisions

- Activity 与 PublicEvent 分开建模、分开 Policy，只共享 Timeline、ReminderSpec、reconcile、Notification 和 Async 基础设施。
- Policy 只从 Timeline 推导，`PUBLISHED + ACTIVE` 才有输出；第二轮已确认 Activity/PublicEvent start 默认 T-1h、明确 deadline 默认 T-24h，offset 不进入稳定 identity。semantic preflight 决定复用 `node_type VARCHAR(32)` 并补 `ACTIVITY_START/PUBLIC_EVENT_START` code，不能从精度或标题猜测。
- reconcile 使用 KEEP/CANCEL/CREATE；时间变化取消旧 plan 再建新 plan。逻辑身份不含 sendAt，以 subject generation 保证同版本幂等。
- 计划与 fan-out Job、Notification/Delivery 与 delivery Job分别同事务创建；本地同库边界不增加 Outbox。
- fan-out 从头重扫、Notification UNIQUE 去重；每批重新验证 plan/subject，把取消竞态限制在一个短批次。
- Notification 是稳定内容事实，独立于任何单一 Channel；`IN_APP`、`WECHAT_MINI_PROGRAM`、`WECHAT_OFFICIAL_ACCOUNT` 分别规划 Delivery，任一微信失败不回滚其他渠道。
- 新增持久 `notification_delivery`，明确 `DELIVERED/FAILED/UNKNOWN/SKIPPED`，区分 Job attempt 与 provider attempt。
- WeChat 消费稳定业务快照，不再读 Activity/PublicEvent persistence；`WxClient` 保持 provider-level。
- Unknown Outcome 不盲重试；没有 provider reconciliation 能力时转人工/专用恢复。
- 本轮不建设新的 Broadcast 产品能力；既有系统通知仅在 producer/recipient/source identity 清晰且风险小时后置接入，不阻塞未来 System/Admin producer 复用 Notification → Delivery。

## 5. Rejected Alternatives

| 方案 | 拒绝理由 |
|---|---|
| 合并 Activity/PublicEvent 为通用 Event | 业务状态、订阅/报名和规则不同，会形成 type-switch 模型 |
| 一个通用 ReminderPolicy | 把业务规则推入基础设施，后续只能不断加分支 |
| 原地更新 plan.sendAt | 抹去旧意图和竞态证据，难以判断迟到 Job 是否仍有效 |
| ReminderPlan 与 AsyncJob 合表 | 混合业务意图和 Worker lifecycle，取消/重试/保留语义冲突 |
| Notification 等于微信消息 | 无微信时无法保留站内事实，provider 字段污染业务模型 |
| 只用 delivery Job、不建 Delivery | DEAD/完成不能表达渠道终局、Unknown 或人工恢复证据 |
| WeChat Factory 直接查业务 Mapper | 反转依赖、复制业务读取规则、无法冻结一致快照 |
| 所有异步都走 Outbox | 同库本地事务多绕一层 transport，增加无价值故障边界 |
| 每个 fan-out 持久 cursor | 当前规模下 UNIQUE + 从头扫已正确，cursor 增加恢复状态 |
| 当前引入 Kafka/Quartz/工作流引擎 | 单机低/中频任务没有吞吐或组织证据支持复杂度 |

## 6. Existing Governance Compatibility

### 直接复用

- `docs/domain.md`：Activity/PublicEvent 业务含义、状态、订阅与报名边界。
- `docs/architecture.md`：模块所有权、Service/Mapper 依赖方向和单体边界。
- `docs/async-processing.md`：Outbox、Job、Stream、lease、retry/dead 和 Worker 机制。
- `docs/reliability.md`：Known Failure、Unknown Outcome、幂等、远程写与 reconciliation。
- `docs/observability.md`：trace/operation/message/job ID、日志、错误分类和指标基数。
- Flyway 与 DB README：可执行结构、升级和 backfill；contracts：真实对外 HTTP 协议。

### 本次补充

- `docs/reminder-notification.md`：原治理中缺失的 ReminderPlan、Notification、Delivery 语义和相互边界。
- `docs/wechat-integration.md`：原治理中缺失的 provider adapter、身份和模板映射边界。
- `docs/domain.md`、`docs/architecture.md`、`docs/async-processing.md`、README 和 maintenance map 只增加路由/依赖，不复制上述详细规则。

### 应停止作为目标设计的历史事实

- Redis List/ZSet 提醒架构、`status=1` 等于已送达、NotifyService 一类承担所有通知职责、WeChat 直接读 ActivityMapper、系统广播与 ReminderPlan 共用一个业务模型。
- 这些是代码迁移对象，不通过修改旧 migration 或删除当前实现来“文档化完成”。

## 7. Migration Impact Preview

| 层 | 预计影响 | 本次状态 |
|---|---|---|
| DB | expand `notify_plan`；可选扩展 Notification 快照；新增 `notification_delivery`；受控 backfill/index/cleanup | NOT IMPLEMENTED |
| Activity/PublicEvent | 新增各自 Policy、业务 Factory；canonical write 同事务 reconcile | NOT IMPLEMENTED |
| Notify | 拆分 reconcile、fan-out、Notification、DeliveryPlanner；保留兼容入口后收缩 | NOT IMPLEMENTED |
| Async | Handler 改为按 plan/delivery ID 读取持久事实；补取消再校验和 Delivery unknown owner | NOT IMPLEMENTED |
| WeChat | 删除通知路径对业务 Mapper 的依赖；稳定模板 DTO；细分错误结果 | NOT IMPLEMENTED |
| Contracts | 当前没有必须改变的外部 HTTP 操作；若以后新增通知偏好/投递运维 API，再先更新 contracts | NO CHANGE |
| Operations | migration、backfill 报告、dead/unknown 管理、指标/告警和 rollout/rollback 证据 | FUTURE |

风险最高的迁移点是旧 pending plan/job 对应关系、`status=1` 无法证明微信送达、系统广播混用、取消与 RUNNING Job 竞态，以及未知结果没有 Delivery 承载。不得从历史 Job 成功反向回填 `DELIVERED`。

## 8. Implementation Order

1. 为 Activity/PublicEvent 增加明确 START semantic，并按已确认 Rule Matrix 更新契约/发布校验；PublicEvent 现有 `EXAM` 等节点只能在数据分类后显式迁移。
2. 设计并审核三渠道、Category × Channel sparse preference、兼容读写、历史数据 preflight 和 rollback 边界。
3. 落 `notification_delivery` 与 Notification 渠道无关快照；先建立状态和唯一性，不切流量。
4. 落两套业务 Policy/Factory 与 reconcile，在 canonical write 中以同事务创建 plan + Job。
5. 修改 plan Handler：执行前和每批再校验、从头扫描、稳定 source key、计划终态 CAS。
6. 修改 DeliveryPlanner/Handler/WeChat mapping：只用 deliveryId 和快照，区分 known/permanent/unknown。
7. preflight 核对旧 v1 工作；为零时简化 drain。系统广播不扩展产品功能，只在低风险时迁移既有 producer。
8. 完成 targeted DB/concurrency/provider-contract 测试、目标环境 migration、指标/恢复演练和 rollout 证据。

每一步都应按 [实施交接](IMPLEMENTATION_HANDOFF.md) 的验收边界独立完成；不得把“文档已定稿”报告成代码或数据库已实现。
