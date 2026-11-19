# Reminder、Notification 与 Delivery 设计

本文是提醒意图、用户通知事实和外部投递状态的项目级权威来源。业务状态与产品规则仍归 [业务规则](domain.md)，执行、租约和重试机制归 [Async Processing Architecture](async-processing.md)，失败结果含义归 [Reliability Baseline](reliability.md)，微信协议归 [WeChat Integration](wechat-integration.md)。

本文维护设计语义，不据此宣称实现或部署完成。Activity Reminder V1 以 §2.1–2.2、§5、§6.1 为业务规则定稿；§11 是 2026-10-11 源码审查与待实施差异，交由人工审查后分工作区执行。表结构和数据演进以 Flyway 为准，接口表示以共享 contracts 为准；部署确认与排障见运维手册。渠道能力分别按 §7 判断。

对外通知类型、能力声明和消费者义务见 [Notification coordination](../../contracts/docs/coordination/notification-delivery.md#activity-reminder-v1-contract-边界审查)。Backend 拥有完整业务判定、接收者查询、Plan/Job/Delivery、事务、幂等、重试、恢复和观测；不得将内部字段或 SQL 复制为公共契约。

## 1. 概念与依赖方向

渠道边界由 Notify 的 `NotificationChannelAdapter`、`NotificationChannels` 与 `NotificationSendResult` 定义，微信应用适配器负责 provider 映射、快照解码和结果分类转换。Delivery 的冻结 JSON、所有权、状态、幂等和恢复规则保持既有协议。

业务内容记录与键归 `notify/payload/data`，`NotifyPayload.contentData` 提供渠道无关事实。持久 JSON 保留原 `wxData` 字段名；新内容使用 COMMENT、REPLY、AUDIT_RESULT、ACTIVITY_REMINDER、BROADCAST 类型标识，解码器同时只接受这五种已知记录原先的 `cn.jualn.miniapp.module.wx.notice.data.*` Java 类型名，不动态加载任意类。v1 Job 的 noticeType 仅保留为历史字符串，原 Handler 继续 UNKNOWN 收尾，不据此重发。上述类型表示为内部载荷兼容规则，公开通知响应与已冻结 Delivery V1 JSON 不变。


LearningTask V1 的来源事实和固定 T-24h 规则由 LearningTask owner 持有，不能套用 Activity 的订阅人群。`LearningTaskNotifications` 在原业务事务调用既有 `NotifyService.processNotificationPayload`，写入 Notification 和 inbox sequence；九种 LearningTask 类型只走 SYSTEM / IN_APP，旧投影不增加渠道偏好类别。历史 sourceKey 去重与统一已读保持；这证明本地收件箱受理，不表示真人收到或微信送达。未来提醒复用 `learning-task.reminder` Job 和通用 Worker；Job 是任务/分配事实的执行投影，执行时重新锁定来源并检查资格、deadlineRevision/固定补交窗口。完成/撤销/取消/关闭使候选失效，RUNNING 候选也必须重验；写收件箱与 Job ownership fence 同事务。已合法计划但延迟的候选在截止前可恢复，T-24h 已过才创建的任务不即时补发。实现和隔离/真实环境证据分别见 [LearningTask](learning-task.md)，不扩大本文的发布声明。

Points 的批准/驳回/冲正结果使用 `points.result-notification` Job，在账务事务内可靠受理，Worker 在独立本地事务复核当前收件账号及 ownership 后调用 NotifyService 写 SYSTEM/IN_APP。禁言不等于不可接收；封禁账号不投递、来源未知保留重试。sourceKey 分别为申请+决定或新冲正流水，重投不重复建通知；无 target、材料、签名或审核备注。生产开关默认关闭，已发布客户端兼容和实际外部依赖需独立验收，见 [Points](points.md)。

Notification owner 写入现有秒精度 `created_at` 前显式截断到秒，避免 MySQL 把小数秒舍入到未来、随后真实已读时间反早于创建。此为写入精度兼容修复，不改变已读规则或历史时间。

```text
Activity / PublicEvent + Timeline
             │
             ▼
      domain ReminderPolicy
             │ ReminderSpec
             ▼
        ReminderPlan ────── AsyncJob(plan fan-out)
             │
             ▼
        Notification ────── NotificationDelivery ────── AsyncJob(delivery)
                                                        │
                                                        ▼
                                                WeChat Integration
```

| 概念 | 持久含义 | 不代表 |
|---|---|---|
| Timeline | 平台已知的业务时间事实 | 提醒偏移、发送状态 |
| ReminderPolicy | 某一业务在当前状态下应该存在的提醒规则 | 队列、重试、微信模板 |
| ReminderPlan | 将来某时执行一次提醒 fan-out 的持久业务意图 | Worker 状态、用户已收到 |
| Notification | 每用户稳定的通知内容事实 | 已进入收件箱、任一外部渠道已送达 |
| NotificationDelivery | 一条通知在一个本地或外部渠道上的投递意图与结果 | Job 的 claim/lease/retry 状态 |
| AsyncJob | 执行上述持久意图的技术状态 | 业务通知、投递结果 |

依赖只能沿图向下。Reminder、Notification 或 WeChat 适配器不得读取 Activity/PublicEvent Mapper 或 Entity；业务模块通过公开 Service、业务 Factory 或稳定 DTO 提供需要的事实。

内部扩展边界由 NotificationSource、NotificationDeliveryEligibility 表达：通知 owner 将持久化计划转换为不可变 ReminderPlanBO，并为每次投递检查重新读取计划、生成 NotificationDeliveryBO。业务实现只校验所属主体与接收者，不查询通知 Mapper。显式计划引用缺失或损坏时不得回退到旧 startTime 快照；主动业务事件不读取提醒计划。历史 Activity 开始提醒去重由 NotifyServiceImpl 在原 fan-out 事务中完成，业务身份仍由 ReminderOccurrence 定义，不依赖计划 ID 或调度偏移。

## 2. Reminder Policy

Activity 与 PublicEvent 保持两个独立业务模型，也分别拥有 `ActivityReminderPolicy` 和 `PublicEventReminderPolicy`。两者可以复用 `ReminderSpec`、reconcile 和执行基础设施，但不得合并成一个依赖 `subjectType` 分支的大型业务策略。

Policy 的逻辑输入输出为：

```text
policy(current subject state, Timeline, now)
  -> List<ReminderSpec(ruleKey, timelineId, sendAt,
                       recipientScope, notificationType)>
```

规则：

- Timeline 是唯一业务时间来源；`sendAt` 只能由精确 Timeline 节点和已确认的产品偏移推导，不能回读 Activity/PublicEvent 的历史时间列。
- 只有 `PUBLISHED + ACTIVE` 的主体可以产生 ReminderSpec；草稿、下架、取消、结束均返回空集合。重新发布按当前 Timeline 重新计算。
- `ruleKey` 是业务规则的稳定名字，`timelineId` 指向产生该规则的时间事实；`sendAt` 是派生执行时刻，不属于逻辑身份。
- Activity 新一轮 reconcile 在 `sendAt <= now < anchor` 内可补当前必要计划；达到锚点不再创建。PublicEvent 不创建 `sendAt <= now` 的新计划。已经持久化后才逾期的计划由 Async Job 的恢复规则处理，不能被下一轮 reconcile 静默删除。
- offset 是 Rule Catalog 中可调整的参数，不属于 `ruleKey`、NotificationType 或计划逻辑身份。把 T-1h 调成 T-2h 只产生旧计划 CANCEL + 新计划 CREATE，不创造新业务类型。

### 2.1 Reminder Rule Matrix

Activity V1 的 `ACTIVITY_START` 接受 `EXACT_POINT.time` 或 `EXACT_RANGE.startTime`；`REGISTRATION_START/REGISTRATION_END` 只接受 `EXACT_POINT.time`，与既有报名窗口一致。`DATE_*`、`TEXT` 和展示节点不产生精确定时提醒；精度不能替代 semantic。PublicEvent 保持其既有独立规则，本次不随 Activity 扩张。

| Subject | Timeline semantic | Rule identity | Offset | Recipient scope | Notification type | Overdue behavior |
|---|---|---|---|---|---|---|
| Activity | `REGISTRATION_START` | `ACTIVITY_REGISTRATION_START_REMINDER`（新规则） | V1 T-1h | `SUBSCRIBERS`（S） | `ACTIVITY_REGISTRATION_START_REMINDER`（内部 code 23） | `sendAt <= now < anchor` 补当前必要提醒；合法已有计划按 §3 恢复 |
| Activity | `REGISTRATION_END` | `ACTIVITY_REGISTRATION_DEADLINE_REMINDER` | V1 T-24h | `SUBSCRIBERS`（S，包含已平台报名的订阅者） | `ACTIVITY_REGISTRATION_DEADLINE_REMINDER` | 同上；明确节点有效的外部报名活动也适用 |
| Activity | `ACTIVITY_START` | `ACTIVITY_START_REMINDER` | V1 T-1h | `SUBSCRIBERS_OR_REGISTERED_USERS`（S ∪ R） | `ACTIVITY_START_REMINDER` | 同上；EXACT_RANGE 使用起点 |
| PublicEvent | `PUBLIC_EVENT_START` | `PUBLIC_EVENT_START_REMINDER` | 默认 T-1h；由 PublicEvent Rule Catalog 独立配置 | `SUBSCRIBERS` | `PUBLIC_EVENT_START_REMINDER` | reconcile 时 `sendAt <= now` 不创建；已创建后逾期由 Job 恢复协议处理 |
| PublicEvent | `REGISTRATION_END` | `PUBLIC_EVENT_REGISTRATION_DEADLINE_REMINDER` | 默认 T-24h；由 PublicEvent Rule Catalog 独立配置 | `SUBSCRIBERS` | `PUBLIC_EVENT_REGISTRATION_DEADLINE_REMINDER` | 同上 |

真实模型映射边界：

- 既有 `node_type VARCHAR(32)` 承担受控业务 semantic，schedule/precision 表达时间结构，不新增重复的 `semantic_type`。`TimelineSemantic` 集中维护 code、读取兼容和主体适用性；Activity 写入只接受 `ACTIVITY_START`，PublicEvent 写入只接受 `PUBLIC_EVENT_START`，共享 semantic 保持兼容。canonical 写入拒绝未知或跨主体 START code；历史未知值读取为 `OTHER`，不自动改写原数据库值。不得把 `EXACT_POINT`、`OTHER`、label、displayOrder、nodeKey 或卡片选中节点猜成开始节点。历史分类使用 [只读 preflight SQL](../src/main/resources/db/validation/timeline_semantic_preflight.sql)，没有可追溯的无歧义依据不自动回填。
- Activity V1 不再采用 S − R 的截止策略，不要求外部报名者证明第三方报名状态。`MINI_PROGRAM/EXTERNAL/MINI_PROGRAM_AND_EXTERNAL` 中有唯一、合法、精确标准节点时可产生报名提醒；`NONE` 不产生报名开始/截止提醒。缺节点、不精确、重复或开始不早于截止时，不猜另一边界或开放状态；外部活动不因缺节点被强制补齐或禁止保存。
- PublicEvent 当前 `EXAM`、`FINAL`、`MATERIAL_SUBMISSION` 等描述具体阶段，不能统一解释为事项开始或截止。`EXAM` 只有在生产数据分类证明它就是某类事项唯一开始节点且完成显式迁移后，才可映射到 `PUBLIC_EVENT_START`；不在运行时靠 type 分支猜测。
- `MATERIAL_SUBMISSION` 本身不能区分开始还是截止。未来需要提醒时新增明确 deadline semantic 和独立 rule，而不是匹配标题文本。

### 2.2 Activity Reminder V1 业务关系与主动变化

以下是已确认的 V1 业务规则；实现入口与验收边界见 §11.2。

- S 是主动活动订阅；R 是平台当前有效报名事实，两者独立。报名不创建订阅，取消报名不退订，退订不取消报名。同一通知的 S ∪ R 按 userId 去重。退订后仍属于 R，或取消报名后仍属于 S 的用户，继续具备活动开始和适用主动通知资格。
- canonical 报名当前只有 `SUBMITTED/CANCELLED`。R 对应持久化成功且当前 SUBMITTED；CANCELLED、内部 INVALIDATED、失败、未提交、未知状态均不属于 R。当前没有报名待审核/审核通过状态机，不把资料审核当报名审批。混合报名 SUBMITTED 仅证明平台提交，不证明外部步骤完成或官方录取；V1 依据真实平台事实提醒，不声称最终资格认证。
- 外部报名不伪造 R：只依据真实 S 与其他权威业务事实。点击链接、查看入口、进入群聊不能当第三方报名成功；当前没有可验证第三方报名事实来源，不预建同步协议。
- 定时提醒只覆盖 §2.1 三种 Activity semantic。普通阶段、比赛项目、OTHER 即使精确也不自动提醒，不从标题、排序、卡片或任意范围推导开始/截止。
- 已发布且 ACTIVE 的重要节点限于 REGISTRATION_START、REGISTRATION_END、ACTIVITY_START：待定变确定、确定时间有效变化、已公布时间撤回/变待定，使用 `ACTIVITY_TIME_CHANGED`。精确锚点按 §2.1 解释；ACTIVITY_START 的起点、范围终点和原日期精度都参与比较，类型转换但有效业务时间相同不自动算时间变化。OTHER、仅标题/排序/文字措辞变化、无实际业务变化不触发。节点不存在或 TEXT 不提供精确锚点，快照须明确表达“原待定/已撤回/现待定”，不得补造时刻。
- Activity 日期确定/变化/撤回及主范围终点变化属于重要时间通知；保留原精度，DATE 仍不能生成精确定时提醒。重复重要 semantic 不猜唯一节点；PublicEvent 行为不随本次扩张。
- 整体 primaryLocation 真实有效变化使用 `ACTIVITY_LOCATION_CHANGED`；保留既有 ACTIVITY_START 节点真实行动地点变化语义。空值↔有效地点也属于变化，明确表达原待定、现待定或已撤回，不编造地点。
- 活动取消使用 `ACTIVITY_CANCELLED`；显式提前结束使用 `ACTIVITY_ENDED_EARLY`。自然到时或普通结束不推导提前结束。接收者 S ∪ R；取消/提前结束不得套用 ACTIVE 过滤，也不得先删关系再 fan-out。
- 同次保存按提交后的真实差异判断，同类多节点变化聚合为一个类型的快照。相关时间/地点变化尽量一次打扰；跨类型合并方式待审，不新增泛化 UPDATED 类型。取消/提前结束优先表达生命周期事实；不同保存的真实变化保持独立身份。
- 任务生成与真实发送分离。迟发布、迟加入、重报、恢复订阅及改期在 `sendAt <= now < anchor` 内补当前必要规则；合法已有任务仍在业务锚点前按 §3–5 恢复。每批及外部调用前重查当前关系、提醒意图、主体/节点有效性、渠道资格。旧计划取消、时间变化或用户不再属于该规则集合时，未发送 Delivery 不得继续。预检后在途调用按 §7 记录权威结果或 UNKNOWN，不承诺撤回。
- 业务资格与 Category × Channel 偏好分开；活动订阅不等于微信授权。IN_APP、小程序、服务号分别判断身份、许可、能力和结果。IN_APP 保持 D1：关闭只影响未来规划，已有收件箱消息和 readAt 不撤销；微信调用前重查当前偏好、身份、许可、模板。计划完成、Job 成功、站内已读不代表微信送达。

## 3. Reconcile 与计划生命周期

业务创建、编辑、发布状态变化、生命周期变化或 Timeline 替换后，在同一业务事务中执行 reconcile：

```text
rules    = current valid rules and exact business nodes (before future-time filtering)
current  = PENDING / PROCESSING plans for subject

current still matches rule, node and reminder generation -> KEEP (including overdue)
current intent invalidated by business change             -> CANCEL
new intent with sendAt > now and no existing generation   -> CREATE
Activity due intent with sendAt <= now < anchor            -> CREATE current catch-up
changed intent                                           -> CANCEL old; CREATE if future or valid Activity catch-up
COMPLETED / CANCELLED generation                          -> never reactivate
```

计划的概念逻辑身份是：

```text
(sourceType, sourceId, timelineId, ruleKey)
```

`generation` 表示提醒意图的代次，不直接等同于主体每次保存的并发版本或内容 schema version。创建代次取主体版本与同一逻辑身份历史最大 generation + 1 的较大值；generation 不参与 Notification occurrence。重复 reconcile 必须幂等；时间、offset、recipient scope 或规则资格变化时取消旧意图，新 sendAt 在未来或处于 Activity 有效补发窗口才建立新意图。普通标题、介绍、附件编辑不因主体版本递增而重建计划；通知内容在实际创建 Notification 时冻结。目标唯一性可以使用 `(source_type, source_id, timeline_id, rule_key, generation)`，无需模拟 partial unique index。reconcile 复用业务主体写锁或版本条件串行化同一主体，不以“先查再写”承担并发正确性。

Timeline 整体替换时，业务模块须按已确认的唯一 semantic 与精确时间识别未变化的提醒意图，不能仅因物理 timelineId 换号重发；保留原意图身份或显式建立替换映射，具体持久化方案由实现交接验证。真实时间变化形成新意图，旧计划不可原地改 sendAt；重新发布只创建当前有效的新意图，不复活历史计划。Activity 当前补发允许另建执行代次，仍按主体/类型/实际锚点/用户 occurrence 吸收已有通知，不能因新 Job/Plan 重复历史事实。

### 3.1 业务有效期

已接受的 Reminder 在 `sendAt <= now < 对应精确业务节点时刻` 且主体、规则仍有效时允许恢复；达到开始/报名截止节点后，该类“开始前/截止前提醒”已失效。每批 fan-out 和每次外部调用前均由业务 owner 提供资格判断，不能只看 Job 到期或主体 ACTIVE。失效的未完成计划转 CANCELLED，未调用 provider 的 Delivery 转 SKIPPED；已有 Notification 和 IN_APP 阅读事实不追溯撤销。此边界不引入任意分钟级 TTL。

不得把提醒的节点有效期套到取消/提前结束通知；主动通知按 §6.2 处理。远程调用已经开始时，即使随后过期，也不能用 SKIPPED 覆盖可能已发生的效果，必须记录权威结果或 UNKNOWN。

计划目标状态为：

```text
PENDING -> PROCESSING -> COMPLETED
   │           │
   └───────────┴──────> CANCELLED
```

`COMPLETED` 只表示所有目标用户的 Notification fan-out 已完成或被幂等吸收，不表示任何外部渠道送达。历史计划不可重新激活。

## 4. ReminderPlan 与 AsyncJob

ReminderPlan 先表达“为什么和何时提醒”，AsyncJob 再表达“由哪个 Worker 执行、执行到第几次、何时重试”。两者不能合表，也不能让 Job payload 成为计划事实。

- CREATE 计划时，在同一 MySQL 事务创建唯一 `notification.plan.fanout` Job；当前 `JobService.create` 的 `MANDATORY` 事务约束与此方向一致。
- `next_run_at` 初始等于计划 `send_at`；Job dedupe key 使用 `plan:{planId}:fanout`。
- 取消先将计划条件更新为 `CANCELLED`，再 best-effort CAS 取消仍为 `PENDING` 的 Job。已经 RUNNING 或迟到的 Job 必须重新读取计划并安全 no-op。
- Outbox 不用于本地计划到本地 Job 的双写，因为二者可以处于同一数据库事务；只有无法由一个本地事务持久化、又必须可靠传播 committed fact 的边界才使用 Outbox。

### 4.1 cancel 与 Worker 竞态

普通取消保证是“取消提交后，不再开始新的 fan-out 批次”，而不是承诺已经在执行中的单个短批次零新增：

1. Handler 开始时读取并条件取得可执行计划；
2. 每批最多 100 个接收者，每批开始前重新验证计划状态、业务 `PUBLISHED + ACTIVE` 和 generation；
3. 取消可把 `PENDING/PROCESSING` 计划改为 `CANCELLED`；后续批次停止；
4. 旧 Job 重启、lease recovery 或重复执行时看到 `CANCELLED` 即成功 no-op；
5. Notification 的唯一 source key 吸收已完成批次的重复遍历。

这保证旧 Worker 不会无限生成过期提醒，并把竞态限制在一个短批次内。如果产品将来要求“取消接口返回后绝对不能新增一条 Notification”，必须另行引入 cancellation barrier 或逐批共享锁协议，并评估长锁和吞吐成本；当前不提前建设。

## 5. 接收者与 fan-out 恢复

计划是否存在只取决于业务状态、Timeline 和 Policy，不取决于 reconcile 当时是否已有订阅者。到期执行时再解析当前接收者：

- Activity 订阅来自 `activity_enrollment` 的订阅语义；正式报名来自 `activity_registration`，两者不能混用。
- PublicEvent 订阅来自 `exam_subscription`；当前包名和部分类型仍叫 exam，不改变业务上 PublicEvent 的独立性。
- Activity V1 新计划只用 `SUBSCRIBERS`、`SUBSCRIBERS_OR_REGISTERED_USERS`。旧 `SUBSCRIBERS_NOT_REGISTERED` 仅为既有计划/兼容解码的内部 scope，不进入 OpenAPI。旧策略转换由 reconcile 判断，不借恢复补建已过 sendAt 的新计划；合法且意图未变的 overdue 计划保持恢复语义。
- S ∪ R 按 userId 去重，不改变原始关系。RecipientResolver 支持批量稳定分页，不逐 receiver 查报名。报名与订阅不互相写入或取消，不以“报名自动关注”绕过并集资格。
- fan-out 失败后从头扫描，不持久化 cursor；在当前规模下，稳定排序、每批上限和 Notification 唯一键已经提供正确性，cursor 仅是未来性能优化。

### 5.1 批次集合与执行收尾

第一版使用执行时当前有效关系，不承诺事件时刻的全量接收者快照。一次扫描按 userId 稳定 keyset 分页，在扫描开始取得有界上限，后续批次不无限追逐新关系；定时 Plan 首次执行将上界持久化到 recipient_upper_user_id，恢复复用同一上界从头扫描当前集合，已通知用户由 source key 去重。批次读取后发生的订阅/报名变化允许落在当前短批次竞态内，不撤销已生成通知。扫描完成后不因新订阅再次执行同一计划；零接收者也是成功完成。

执行所有权与长 fan-out 的协议见 [Async §7.2.1](async-processing.md#721-业务执行所有权与通知收尾)。每批短事务不等于整个 Handler 短；不得以每批 100 人推断整个 Job 不会超过租约。

计划正常遍历完成才转 COMPLETED。技术故障耗尽预算时保留非完成计划与 DEAD Job，运维组合表示为“部分/未完成，待处理”，不得伪造 COMPLETED 或业务 CANCELLED；普通扫描不得自行新建替代 Job。受控 retry 复用同一计划身份与 source key，业务仍有效时从头扫描；过期/取消则关闭剩余工作，保留已经生成的通知。

## 6. Notification

Notification 是每用户、可长期存在的稳定内容事实；Notification 与任何一个渠道 Delivery 都不等价。第一版把 `IN_APP` 也作为正式渠道：本地 `IN_APP` Delivery 生效后，Notification 才出现在收件箱并参与未读计数。它可以在某个微信渠道未配置、身份未绑定、权限不足、用户关闭该渠道或发送失败时独立存在，其他渠道结果互不回滚。

Notification 创建时冻结用户可见快照：

- 保留现有 `title`、`content`、`target_type`、`target_id` 和 `sender_id`；点击详情时按 `target_type + target_id` 读取主体最新状态。
- 目标演进可增加 `content_schema_version` 与有界 `content_payload JSON`，保存渠道无关的稳定字段，例如 `subjectTitle`、`eventTime`、`location`；不保存整个 Activity/PublicEvent JSON。
- 页面路径、templateId、touser、openid、provider error 等渠道字段不得进入 Notification。
- Reminder 到期时由所属业务模块的 Notification Factory 基于当前有效主体和 Timeline 生成渠道无关 Draft；Factory 可以通过本模块持久化层读取自己的数据，但 WeChat、notify 通用组件不得跨模块读 Mapper。

新协议（type 8–11、23）Reminder fan-out 复用 `notification.source_key` 唯一约束，以冻结的实际业务节点时间确定 occurrence：

```text
reminder:{sourceType}:{sourceId}:{notifyType}:{businessAnchorInstant}:user:{userId}
```

`planId` 仍是调度执行身份及 Job dedupe 的依据，不参与新 Notification 业务身份；offset、generation、接收原因与计划重建不改变同一 occurrence。真实业务节点时间改变则形成新 occurrence。业务时间按项目 Asia/Shanghai 转换为 instant，不能用 sendAt 替代。新协议缺少冻结 anchor 时停止执行并要求 reconcile，不能降级为 planId 去重。V28 仅补 pending、当前代次、可靠 Timeline 关联的 anchor；processing/completed 历史计划不从当前主体伪造历史时间。

旧 type 1–7 计划保留 `plan:{planId}:user:{userId}` 兼容身份，不重写历史 source key。历史 type 8 已冻结、可精确对应同一节点的 startTime 可用于抑制迁移前后的重复；无可靠 anchor 的历史消息不能根据文案猜 occurrence。其他业务通知必须由所属业务定义稳定 source key，不能使用 title/content 哈希作为长期身份。

### 6.0 Notification Box 与阅读事实

`read_at` 是唯一阅读事实；legacy `isRead` 从其非空状态投影。数据库 `is_read` 仅作为当前写入同步的兼容 shadow，不参与查询或裁决。V27 的历史已读使用迁移确认时间，不声称恢复真实历史阅读时间。

R 是当前用户拥有的 Notification，GET by ID 与 batch-read 使用 R；V 是 R 中首次进入 IN_APP Box 的集合，list、summary、Badge、read-through 使用 V。external-only 行仍可由本人恢复/阅读，但不计入 Badge。V 通过 nullable `inbox_seq` 物化已有 LEGACY / 生效 IN_APP Delivery 边界，偏好变更不重排历史。

首次进入 V 的事务锁定该用户 `notification_inbox_counter`，分配下一 sequence 并保有锁直至提交；其他用户独立。sequence 一旦分配不改变，重复投递不重新分配。structured list 按 sequence 递减；legacy 保留原分页。head/through 是无 category 的全局边界，page cursor 绑定筛选；游标认证、用户隔离、24 小时有效。部署须配置至少 32 字符的稳定 `notification.cursor-secret`（环境变量 `NOTIFICATION_CURSOR_SECRET`），多实例共享同一值；未配置时仅进程内有效，重启后旧游标失效。

summary 在单个一致性快照内读取 head、V unread 与新增数量；没有 afterCursor 时 newCount 为 0。batch-read 去重、整批 ownership 校验、原子且幂等；read-through 只消费 sequence 不超过提交 head 的 V。readAt 只在首次阅读写入。Redis 是 V unread 的短期 projection，以 head/read_version 隔离迟到重建；MySQL 是裁决依据。

structured 与 legacy 使用同一 row/ID/readAt。`content_payload` 冻结 presentation、actor、subject 与可靠 target，不在读取时回查业务对象；时间/地点变更在业务变更事务中冻结 before/after。历史缺少变更快照时退化 SYSTEM 类型展示，不能从当前主体补 before。Notification 存在与目标可访问性分离，目标详情失败不会删除通知。NORMAL / MENTOR_QA Post 及其评论不再生产新点赞通知，历史通知保留；导师正式回答只在首次真正公开产生 POST_COMMENTED，普通二级向可信具体目标产生 COMMENT_REPLIED，待检查、回放和恢复不重复首次通知。其他既有 POST/COMMENT 的成功新增 like fact 仍按原领域产生通知，取消不产生通知，重新点赞复用首次 actor/recipient/source 身份；self-action 排除，不扫描伪造历史 like。

普通社区评论/回复仅在正式公开时产生通知；`POST_CONTENT_HANDLED` / `POST_REPORT_RESOLVED` 属 SYSTEM、仅 IN_APP，跳转本人帖子/评论/举报资源。处置以版本与实际效果身份去重，举报以 reportId 与固定结案身份去重；业务效果、管理历史与通知事实同事务接受。正常安全通过不新增审核成功通知，不扩展微信模板。

展示快照：presentation 的可选纯文本 `subjectTitle` 是关联帖子/活动/公共事项的创建时标题，`quote` 是原评论/原内容摘要，回复的 `body` 仍是新回复。保留旧 `context` 的已有含义和值，不能用它猜新增字段。评论/回复与点赞在创建时冻结可靠 actor；头像可用时保存 avatarUrl，无头像时保留 actor 并省略 avatarUrl。历史缺新增字段/actor 的消息保持缺省，不在读取时用当前实体或用户资料回填。新消费者的字段优先级与 context 去重由共享契约承接；站内模型/read/target/Delivery 身份不变。JSON payload 扩展无需新表或历史数据迁移。

业务事件通知直接创建 Notification 并按 Preference/Capability 规划各渠道 Delivery；它们不是定时提醒，不经过 ReminderPlan。不能从任意字段变化自动推导一个通用 UPDATED 通知。

### 6.1 Business Notification Matrix

| Business change | 触发边界 | Recipient scope | Notification type | Preference category |
|---|---|---|---|---|
| Activity 被取消 | 已发布 Activity 的 lifecycle 明确变为 CANCELLED | `SUBSCRIBERS_OR_REGISTERED_USERS` | `ACTIVITY_CANCELLED` | `ACTIVITY` |
| Activity 行动时间变化 | 已发布且 ACTIVE；§2.2 重要节点的确定、有效变更、撤回或变待定 | `SUBSCRIBERS_OR_REGISTERED_USERS` | `ACTIVITY_TIME_CHANGED` | `ACTIVITY` |
| Activity 行动地点变化 | 已发布且 ACTIVE；`primaryLocation` 或 `ACTIVITY_START` 节点 location 的有效值实际变化 | `SUBSCRIBERS_OR_REGISTERED_USERS` | `ACTIVITY_LOCATION_CHANGED` | `ACTIVITY` |
| Activity 显式提前结束 | 运维执行具有“提前结束”语义的 lifecycle action；自然到时不触发 | `SUBSCRIBERS_OR_REGISTERED_USERS` | `ACTIVITY_ENDED_EARLY` | `ACTIVITY` |
| PublicEvent 被取消 | 已发布 PublicEvent 的 lifecycle 明确变为 CANCELLED | `SUBSCRIBERS` | `PUBLIC_EVENT_CANCELLED` | `PUBLIC_EVENT` |
| PublicEvent 行动时间变化 | 已发布且 ACTIVE；`PUBLIC_EVENT_START`、`REGISTRATION_END` 或以后明确加入矩阵的 deadline semantic 精确时刻发生实际变化 | `SUBSCRIBERS` | `PUBLIC_EVENT_TIME_CHANGED` | `PUBLIC_EVENT` |
| PublicEvent 行动地点变化 | 已发布且 ACTIVE；上述行动节点真实存在 location 且有效值实际变化 | `SUBSCRIBERS` | `PUBLIC_EVENT_LOCATION_CHANGED` | `PUBLIC_EVENT` |

第一版不发送 Activity 报名成功/取消、任意字段更新、PublicEvent 普通 END 或无法证明为提前结束的事件。当前 PublicEvent lifecycle 只有通用 `END`，没有 `endedEarly` event/reason；增加明确语义前不定义 `PUBLIC_EVENT_ENDED_EARLY`。每个 business event 必须拥有稳定 event identity/version，逐用户 source key 使用 `business event identity + receiverId`，同一接收者即使同时订阅并报名也只产生一条 Notification。

系统广播当前不建立新产品能力；未来 System/Admin producer 可以复用 Notification → Channel Delivery，但当前不新增 broadcast table、管理 API、recipient engine 或 UI。

### 6.2 主动事件的时点与稳定内容

业务事务在确认实际变化时生成稳定 event identity、事件时刻、主体版本及最小渠道无关内容快照，并与本地 fan-out Job 或必要的 Outbox 一致提交。重复请求未产生新业务变化时不创建新事件；恢复不重新生成 event identity。连续两次真实变化是两个事实，不合并成笼统 UPDATED，不承诺异步到达顺序；文案表达事件发生时的变化，点击读取最新主体。

主动 fan-out 同样采用 §5.1 的执行时集合：执行前已退订/取消报名者按当前规则排除，执行前新建立有效关系者可能收到；不宣称覆盖事件发生时所有用户。取消/提前结束流程不得先删除用于 fan-out 的业务关系。固定接收者的评论、回复、审核通知由事件记录 receiverId，不在执行时重新推导另一位接收者。

取消/提前结束事件以已提交的对应事实为资格，不因主体不再 ACTIVE 被吞掉；时间/地点事件保留已发生变更的快照，不在恢复时替换成后续版本。普通 ACTIVE 校验仅适用于 Reminder，不能成为所有 Notification 的统一过滤器。删除、访问控制及当前 provider 资格仍适用；无法证明事件身份或内容时应停为待处理，不猜测发送。

### 6.3 既有通知目录与设置归属

| Producer / 触发 | 接收者与抑制 | 稳定来源 | 设置及渠道边界 |
|---|---|---|---|
| 评论审核通过 callback | 内容所有者；发送者本人不通知 | 既有 commentId + receiver + approved | 原互动设置 owner；不归入 ACTIVITY/PUBLIC_EVENT |
| 回复审核通过 callback | 被回复用户；发送者本人不通知 | 既有 reply commentId + receiver + approved | 同上 |
| 用户资料审核拒绝 callback | 资料所属用户；不扩大为所有审核成功通知 | 既有 auditLogId + profile-reject | 原审核结果设置 owner；不臆造 mandatory |
| Activity/PublicEvent Reminder | §2.1 与 §5 | business anchor occurrence + userId（旧 type 1–7 保留 planId） | 六组合 Category × Channel |
| Activity/PublicEvent 主动事件 | §6.1–6.2 | event identity + receiverId | 六组合 Category × Channel |
| 历史系统通知/广播 | 仅保留已有 producer/旧计划的明确接收范围 | 保持既有稳定身份；无身份项先 preflight | 原系统设置 owner；不新增广播产品 |

评论/回复/资料审核等既有路径的设置当前控制外部投递，不能无声扩展成删除或隐藏站内事实。它们沿兼容路径保留；迁移到新的类型/渠道策略前核对现有调用与设置含义，不把新三渠道 Planner 的默认值自动套给未归类类型。具体历史 key 拼写保持原实现，不为统一表格改名。外部发送同样遵守真实 capability/permission 与 UNKNOWN 边界。

## 7. NotificationDelivery

渠道投递是 Notification 的派生意图与结果。`IN_APP` 是本地渠道，两个微信渠道是独立外部渠道；它们不能复用笼统 `WECHAT`。V20 已落 `notification_delivery` 基础表；V21 增加 sparse preference 和明确的旧/新收件箱世代分类；V22 增加 `LEGACY_MIGRATION` 来源与逐用户 owner。它们不制造历史 Delivery。D1 已确认关闭 IN_APP 只影响未来规划，已有消息、已读状态和已规划投递保持，重开不补发。源码已有 canonical IN_APP、按有效绑定规划的服务号渠道，以及小程序独立 Adapter/分派；小程序按独立渠道开关和有效模板接入，微信响应判定该次资格；下列设计与目标环境部署/兼容切换证据分别判断：

| 字段 | 类型/可空方向 | 含义 |
|---|---|---|
| `id` | `BIGINT UNSIGNED NOT NULL` | 自增主键 |
| `notification_id` | `BIGINT UNSIGNED NOT NULL` | 所属 Notification |
| `channel` | `VARCHAR(32) NOT NULL` | `IN_APP/WECHAT_MINI_PROGRAM/WECHAT_OFFICIAL_ACCOUNT`；不使用笼统 `WECHAT` |
| `status` | `VARCHAR(16) NOT NULL` | `PENDING/PROCESSING/DELIVERED/FAILED/UNKNOWN/SKIPPED` |
| `attempt_count` | `INT UNSIGNED NOT NULL DEFAULT 0` | provider delivery attempt 数，不是 Job attempt |
| `provider_message_id` | `VARCHAR(128) NULL` | provider 返回时保存 |
| `result_category` | `VARCHAR(24) NULL` | 既有 Observability error category |
| `provider_error_code` | `VARCHAR(64) NULL` | provider 稳定 code，不保存敏感 payload |
| `last_error_message` | `VARCHAR(512) NULL` | 截断、脱敏诊断信息 |
| `last_attempt_at` | `DATETIME(3) NULL` | 最近一次 provider attempt 开始时刻 |
| `delivered_at` | `DATETIME(3) NULL` | 权威成功时刻 |
| `created_at/updated_at` | `DATETIME(3) NOT NULL` | 本地生命周期时间 |

约束和索引方向：

- `UNIQUE(notification_id, channel)` 是同一通知/渠道只有一个投递事实的并发仲裁。
- `INDEX(status, updated_at, id)` 支持超龄 PROCESSING/UNKNOWN 运维扫描和有界 cleanup；普通到期调度仍由 `async_job` 索引负责。
- 使用 `FOREIGN KEY(notification_id) REFERENCES notification(id) ON DELETE CASCADE`。Delivery 没有脱离 Notification 的独立业务价值；删除 Notification 时同步删除 terminal Delivery。Notification cleanup 必须跳过仍为 `PENDING/PROCESSING/UNKNOWN` 的行，避免级联抹掉待处理或待判定证据。
- terminal `DELIVERED/FAILED/SKIPPED` 随 Notification 使用同一 retention；`UNKNOWN` 保留到 operator/reconciliation 给出结论，并至少覆盖事故审计窗口。初始具体天数应结合现有 Notification 产品保留规则和目标数据量确认，不在缺少证据时写死。

Notification 与所有初始 Channel Delivery 在同一 MySQL 事务规划。`IN_APP` 在本地事务内直接成为 `DELIVERED`，不创建远程 Job，也不增加 provider attempt；每个微信 Delivery 与其唯一 delivery Job 同事务创建，建议 Job dedupe key 为 `delivery:{deliveryId}`，payload 只带 `deliveryId`。

初始规划与 Notification 插入是一次原子决定，即使全部渠道关闭也保留去重所需的 Notification。重复 source key 只读取已有结果，不按后来偏好/配置补建缺失渠道，不重置 FAILED/SKIPPED/UNKNOWN，不重写内容；修复真实事务不变量破坏需受控处理，不能借事件重放实施补发。IN_APP 偏好按 D1 仅影响初始规划；微信在调用前再次检查当前有效偏好，关闭则 SKIPPED，重开不会复活该终局。偏好检查后的在途调用不承诺可撤回。


微信模板 V1 的新外部投递在本事务中冻结已渲染字段及完整模板/绑定版本到 `notification_delivery.template_snapshot`（schema version 1），Job 只携带 deliveryId。配置更新不能修改重试内容；当前禁用、身份、偏好与业务资格仍在发送前复核。channel 明确分派：服务号沿用 bizsend，小程序使用独立 subscribe/send Adapter。小程序渠道关闭或模板缺失时不规划；已有小程序投递在渠道关闭后安全 SKIPPED，不能送到服务号接口。V34 的旧 NULL 快照 PENDING → SKIPPED（legacy_template_unfrozen），PROCESSING → UNKNOWN；不伪造历史模板。细节和离线示例见 [微信集成 §16.6](wechat-integration.md#166-wechat-notification-template-v1-实施边界)。不新增队列、Job 类型或 Delivery 状态。

五类定时 Notification 在创建时由 producer 的 ReminderPlan anchor 冻结 `businessTime` 和相应 `registrationStartTime/registrationDeadlineTime/publicEventStartTime/startTime`，同时保存已知 location/targetId。公共事项截止沿用 registrationDeadlineTime 的业务含义；不以 sendAt 或主体当前时间补旧通知。取消、时间/地点变化、提前结束等继续使用既有结构化事件快照，不扩展无关业务时间。

### 7.1 DeliveryPlanner

DeliveryPlanner 根据 NotificationType、Category、mandatory policy、用户 preference、渠道 capability、provider identity/permission 和当前业务相关性生成候选 Delivery：

canonical 规划按下面条件执行。服务号适用路径采用 [发送时校验授权](wechat-integration.md#51-调用前-eligibility)；`PROVIDER_VERIFIED_AT_SEND` 表示应用无法预知本次订阅资格，不宣称 GRANTED，也不追溯历史终局。适用类型与未接通渠道见微信文档 §17。

- `USER_CONFIGURABLE` 且用户关闭某渠道时，不创建该渠道 Delivery；`MANDATORY` 忽略用户 preference，但仍须满足渠道 capability、身份和 provider permission。
- 规划时已知 `enabled=false`、templateId 缺失或 Adapter/permission capability 不存在时，不创建该渠道 Delivery，并产生低基数 planning reason/metric；不能故意调用 provider 等其拒绝。
- 已创建 Delivery 后，执行时发现 capability 被关闭、身份解绑、permission 失效或主体不再相关，转 `SKIPPED` 并记录稳定 reason。
- capability 已确认可用但冻结 payload 违反既定模板 contract，属于实现/数据错误，记 `FAILED`；不要把“根本未配置渠道”伪装成 provider failure。
- Notification 与 Delivery 的创建事务不执行远程调用。

Eligibility 至少是以下条件的交集：

```text
business recipient
+ notification policy (USER_CONFIGURABLE / MANDATORY)
+ Category × Channel preference
+ channel enabled
+ provider identity
+ provider permission
+ template/adapter capability
```

用户愿意接收某渠道和 provider 当前允许发送是两个独立事实，不能互相代替。

### 7.2 Channel Capability Matrix

| Notification / Category | IN_APP | WECHAT_MINI_PROGRAM | WECHAT_OFFICIAL_ACCOUNT | Template requirement | Provider permission requirement |
|---|---|---|---|---|---|
| Activity Reminder / Direct (`ACTIVITY`) | 支持；本地事务完成 | 独立渠道开关及模板映射；微信响应判定该次资格 | 按有效显式绑定进入通用投递；活动开始保留 `activity_start` 兼容回退，无配置类型 unavailable | 微信两渠道各自 templateId、字段 mapping、jump target；不能共享 ID | 两个渠道分别由各自 provider 发送响应校验资格，不维护本地次数，不表示永久 GRANTED |
| PublicEvent Reminder / Direct (`PUBLIC_EVENT`) | 支持；本地事务完成 | 独立渠道开关及模板映射；微信响应判定该次资格 | 按有效显式绑定进入通用投递；未配置或未核验模板默认 unavailable | 同上；具体 ID 均为“配置缺失”，不得编造 | 同上 |
| 未来 System/Admin producer | 基础能力可复用，当前不新增产品 producer | 不预建未使用模板 | 既有 SYSTEM_NOTICE 可作为历史能力证据，不等于新广播产品已迁移 | 只有真实 producer/类型启用时配置 | 按该类型的 mandatory/configurable policy 与 provider 权限判断 |

`WECHAT_MINI_PROGRAM` 与 `WECHAT_OFFICIAL_ACCOUNT` 各自拥有 provider identity、authorization/permission、template registry、field mapping、jump target、request API 和 error classifier；只共享 token/HTTP/client foundation 与通用错误模型。当前仓库的 `openid` 是小程序身份，`mp_openid` 是服务号身份；两个发送器分别调用各自产品的 API，不以改名或失败后切换替代独立渠道。

### 7.3 Preference Model

- 第一版粒度为 `NotificationCategory × Channel`，Category 先启用 `ACTIVITY`、`PUBLIC_EVENT`，Channel 为三种正式渠道。不为每个 NotificationType 建独立开关。
- 使用 sparse override：没有 row 时取版本化 system default，只有用户修改时持久化 override。第一版 `ACTIVITY/PUBLIC_EVENT × IN_APP` 默认开启；两个微信渠道默认关闭，用户明确开启 preference 后仍需独立满足 provider permission。唯一约束语义为 `(user_id, category, channel)`，实际结构以 Flyway 为准；同时保留适合批量读取的 `(user_id, category, channel)` 覆盖索引/主键布局。
- Activity/PublicEvent 第一版都是 `USER_CONFIGURABLE`。当前代码虽有 `notifySystem/notifyAuditResult` 开关，但没有已确认且不可关闭的系统级 NotificationType；因此当前不臆造 mandatory 类型。未来账号安全/风控通知若确认为 `MANDATORY`，必须由 NotificationType policy catalog 明确标记，不能归入 ACTIVITY/PUBLIC_EVENT，也不能受其关闭开关影响。
- fan-out 每批先取得 receiverIds，再一次批量加载该批 Category preference、相关 provider identities 和 permissions，在内存中规划 Eligibility；禁止 1000 receivers 触发 1000 preference + 1000 identity 查询。
- 当前规模第一版直接使用有索引的 MySQL 批量读取；现有按 userId Redis Setting cache 不能用于 fan-out N+1。只有测量证明数据库读取成为瓶颈后，才增加按 category/channel 可批量失效的 cache，并明确 update invalidation 和 TTL；当前不设计复杂 Redis Preference Cache。
- 旧 `user_setting.notifyActivityRemind/notifyExamRemind` 的兼容映射仅影响对应服务号组合；历史导入来源为 `LEGACY_MIGRATION`，不表示微信授权。IN_APP/小程序保持各自默认或 override，不把旧一个布尔值解释为三个渠道的永久选择。逐用户 owner 与回填语义见数据库说明。

### 7.4 Delivery 状态与结果

```text
PENDING -> PROCESSING -> DELIVERED
                       -> FAILED
                       -> UNKNOWN
                       -> SKIPPED
```

- Known retryable failure：确认 provider 未接受且重复安全时，Delivery 回到 `PENDING` 并由 Job 持久退避；attempt_count 增加。
- Known permanent failure：Delivery 为 `FAILED`，本次 Job 可以成功结束，因为该渠道已有明确终局。
- Unknown Outcome：Delivery 为 `UNKNOWN`，Job 停止普通自动重试并进入人工/专用 reconciliation；不得把 timeout 当 Known Failure 盲重发。
- Known Success：Delivery 为 `DELIVERED`；重复 Handler 读取该状态后成功 no-op。
- 不再满足投递资格：Delivery 为 `SKIPPED`；这是业务/渠道资格终局，不是 provider 失败。

Job attempt 由 `async_job.attempt` 记录 Worker 的执行次数；Delivery attempt 只在即将发起一次 provider 调用时增加。解析 payload、预检或 claim 失败可以消耗 Job attempt，但不能伪造一次 provider delivery attempt。进程在远程调用后、结果落库前崩溃时，恢复必须保守地进入 `UNKNOWN`，除非 provider 提供幂等键或状态查询。

### 7.5 终止与人工结论

| 场景 | Delivery 结果 | 执行收尾 |
|---|---|---|
| 权威成功 | DELIVERED | Job 成功；重复执行 no-op |
| 明确永久失败 | FAILED | Job 可成功，表示分类完成，不表示送达 |
| 明确未接受、可安全重试且有预算 | PENDING | Job 持久退避 |
| 已知失败耗尽预算，或确认未开始调用即无法继续 | FAILED | Job DEAD，保留具体失败证据 |
| PROCESSING 后 crash / 结果未能落库 | UNKNOWN，除非有权威证据 | 停止普通 retry；不得依靠下一次发送探测结果 |
| 调用前失去业务/渠道资格 | SKIPPED | Job 成功 no-op；不增加 provider attempt |

Worker、lease recovery 与受控 repair 必须覆盖“最后一次 attempt 崩溃，没有下一次 Handler”的状态修正。DB 不可用时不能假称已完成收尾；DB 恢复后有界扫描 Job/Delivery 不一致组合，按调用证据分类，所有更新以当前状态/执行所有权为条件。既有 UNKNOWN、DELIVERED 不得被通用 DEAD 回调覆盖。机制见 Async §7.2.1。

人工 retry 只适用于已知失败、重复安全、当前仍有效的同一 Delivery，记录 operator、依据和操作结论；UNKNOWN 没有 provider 幂等/查询证据时不允许普通 retry。人工接受 best-effort 仅表示停止追查，不能制造 DELIVERED；UNKNOWN 事实及处置证据仍保留。当前没有完整操作入口，见 Runbook，属于启用外部渠道前的实现义务，不由本节宣称已具备恢复能力。

### 7.6 清理与去重窗口

Notification 的 source key 删除后将失去去重证据。因此清理不仅检查 Delivery 终局，还须证明关联计划/事件/Job 不再允许正常或事故重放，并覆盖对应的 Outbox/Job 恢复窗口；存在积压、DEAD 未处理、UNKNOWN 未结论或进行中恢复时跳过。站内保留规则现为 createdAt 起 90×24 小时；统一可见性与物理清理按下文 §12 执行，未决证据不授予继续读取权限。

超出已保留事实窗口的历史重发不是普通 retry，不能由通用 replay 工具绕过；需单独确定业务身份与影响范围。不建设内容冷归档；物理处置后仅保留必要的来源摘要回执，见 §12。人工 accept 不立即删除 UNKNOWN，仍需满足事故证据保留条件。

## 8. 数据演进边界

产品侧确认没有重要历史 Delivery 需要强行保留。迁移目标是从新 producer 切换后开始记录真实 Delivery，而不是制造历史：

1. 保留 V20 expand 结果；V21 已补 Category × Channel sparse preference、共同可见性查询所需索引和 `LEGACY/CANONICAL` 内部世代；V22 补偏好来源和逐用户 owner。旧 writer 仍可能存在期间默认归入 `LEGACY`；只有完成 D2 preflight 与 producer switch 后，新 writer 才显式写 `CANONICAL` 并在同一事务创建真实 `IN_APP` Delivery。
2. 历史 Notification 不批量制造 fake Delivery；历史 `notify_plan.status=1`、`async_job.SUCCEEDED` 或旧日志都不能推导 `DELIVERED`，也不增加无证据的 LEGACY 状态。
3. 在真实 DB 执行只读 preflight，分别统计历史 notification、notify_plan，以及 `notification.wechat-deliver` v1 的 PENDING/RUNNING/SUCCEEDED/DEAD/CANCELLED。若 pending/running/dead 均为 0，保留一个 release window 的兼容 reader 后即可删除，不建设复杂 drain/reconciliation 系统；若不为 0，再按实际行制定 drain/人工结论。
4. 系统广播当前不新增产品实现。旧 sourceType=3 仅按 preflight 结果决定兼容保留/后置迁移；不能为了架构完整新增 broadcast 表/API/UI，也不能阻塞新的 System/Admin producer 以后接入 Notification → Delivery。
5. Rollout 保持 `expand → compatible code → switch producer → inspect/drain old v1 jobs → later contract`。即使历史数据预计接近零，也必须完成 empty DB Flyway replay、当前 production-like 版本升级、唯一约束和 preflight 对账。
6. contract migration 只能在新 producer 稳定、旧 v1 Job 已证实为零或有明确终局、nullable 历史行已分类后执行；rollback 只能回到仍能读取新 schema 的兼容应用，不能通过伪造 Delivery 或删除 UNKNOWN 证据实现。

## 9. Observability 接入

本能力必须在既有 [Observability Source of Truth](observability.md) 下提供以下低基数结果证据，不在本文另造 meter 命名、MDC 字段或 error category：

- Reminder created / kept / cancelled / due / completed；
- fan-out recipient count、batch count、停止原因；
- Notification created / duplicate suppressed；
- Delivery created / delivered / failed / unknown / skipped；
- 对应 Job retry / dead 与 reconciliation/operator 结果。

允许的聚合维度限于已注册的 subject type、rule key、notification type、channel、result 和 error category；planId、notificationId、deliveryId、userId、openid 等具体实例只进结构化日志关联字段，不作 metric tag。正常逐条成功不打 INFO，最终 owner 负责 terminal ERROR。

## 10. 明确不采用

- 不合并 Activity 与 PublicEvent，不建立统一 Event 主表或通用规则引擎。
- 不把 ReminderPlan、Notification、Delivery、AsyncJob 合成一张状态表。
- 不让微信模板读取业务 Mapper，也不把 provider 字段写进 Notification。
- 不为每个本地事务内可持久化的 Job 强制经过 Outbox。
- 不使用 Redis ZSet/List 重新承载计划或可靠任务，不在当前单机规模引入 Kafka、RabbitMQ、Quartz 或分布式调度平台。
- 不用全局 processed-message 表替代业务唯一键，不为 fan-out 默认建设 cursor/分片/复杂工作流引擎。

## 11. Activity Reminder V1 实现与兼容边界

### 11.1 待人工审查的业务细则与未知事实

- **D1 边界冲突：** 若“修改偏好后实际发送资格正确”被理解为取消已规划 IN_APP 或隐藏历史消息，会违反已确认 D1 和 canonical InboxNotification。V1 当前保留 D1，仅微信调用前重查偏好；改变它须单独兼容评审，不作为本次可靠性修复。
- **已发布平台报名窗口撤回：** [PlatformRegistrationTimeline](../../contracts/api/schemas/activity.yaml) 约束发布/在线编辑/已发布 Detail：END 必须恰好一个 EXACT_POINT，存在的 START 也须 EXACT_POINT。[ActivityLifecycleService.validateAdminSubmission](../src/main/java/cn/jualn/miniapp/module/activity/service/ActivityLifecycleService.java) 在线保存校验该窗口。因此 ACTIVITY_START/外部报名的合法撤回可复用既有保存表示，但 MINI_PROGRAM/MIXED 的 END 撤回/变 TEXT，以及保留 START 却变 TEXT，当前不能提交。不能把拒绝保存算成“无实际变化无需通知”而遗漏 V1。须决定是否允许活动保持发布但关闭报名、如何表达 participationAvailability、保留 R 与历史答案及取消能力，先 C4 再 provider/consumer；本轮不放宽该 Contract。可选 START 的删除仍是现有合法请求，需区分撤回原公告与无时间下限语义。
- **R 的业务含义：** 当前 SUBMITTED 不等于官方录取，混合报名不等于外部完成。本文将其作为现有平台参与提醒事实。若业务要求“最终审核成功才属于 R”，须先设计真实报名状态机及 Contract，不能把未实现审核视为已有功能。
- **旧单活动开关：** S 对外读取与发送 SQL 的 notify_enable 条件不同，且有 updateNotifyEnable 兼容实现。源码保留兼容 updateNotifyEnable；本次保留 S 的 notify_enable 条件，R 不受该开关过滤。历史 false 的目标环境分布仍未读取，不删除旧开关、不回填用户意愿。
- **同次保存跨类型去扰：** 同类型多节点可复用 changes 聚合；时间+地点目前两个可见类型。需明确合并为既有哪个类型、仅微信合并或保持两条但限制打扰。不得未经审查丢掉一种变化或新增 UPDATED。若改变公开分类/类型含义，先 C2 兼容评审。
- **恢复切换：** Activity 当前有效期内的补发与旧合法任务保留均成立；具体上线转换基于旧任务真实状态和可证明锚点，不能取消全部 overdue、绕过去重或补过锚点的提醒。
- **平台/环境未知：** 未读取账号后台、DB、生产配置或部署日志，不能确认模板、长期/一次性资格、启用开关和真实收件。官方协议入口继续由 [WeChat Integration](wechat-integration.md#9-能力启用与验收边界) 维护；上线前补当前官方协议与目标账号证据。

上述待决细则仍需人工审查；实施只覆盖已确认规则，不据此扩张保存状态、通知类型或微信授权。


### 11.2 Activity Reminder V1 当前实施入口与兼容边界

Activity V1 临近补发：ActivityReminderCatchUp 在关系变更事务内写 `notification.reminder.catch-up` Job；发布/改期复用 reconcile。Job 按单用户重查主体、当前规则、关系及有效期，再与普通调度共享 ReminderOccurrence 去重；已完成扇出的扫描上界不限制随后加入的小 userId。新计划只是执行证据，不能按新 Job 身份重发。投递前仍沿现有资格与渠道检查。

2026-10-11 对齐 Contracts 最新 notification schema/handoff：新增 ACTIVITY_REGISTRATION_START_REMINDER（内部 code 23，不改变旧 1–22 编码），返回 12×3 完整能力集合。代码实施不代表环境已部署或微信已接通。

| 规则 / 缺陷 | 当前入口 | 实现和兼容含义 |
|---|---|---|
| 三条定时规则 | ActivityReminderPolicy | START T-1h / END T-24h / ACTIVITY_START T-1h；报名只读 POINT，活动开始可 RANGE 起点。NONE 不生成报名提醒，内部窗口保持精确校验，外部不推断 R。 |
| S/R 与旧开关 | ActivityEnrollmentMapper；NotifyServiceImpl.queryUserIds | S 保留 status=1 且 notify_enable=1；R 只读 activity_registration.status=1。UNION 按 userId 去重；不删除或改写历史 false，不互写两张关系表。修正无 script 注解中的 &gt;/&lt; SQL。 |
| 定时执行资格 | ActivityReminderSubjectStatus；ReminderExecution | 比较真实规则、唯一 semantic、精度、timelineId、冻结 anchor、sendAt 与 PUBLISHED/ACTIVE，不用主体内容版本相等代替意图判断。每批短事务先锁 Activity owner，再复查 Plan 和本批接收者；远程调用不在该事务内。 |
| reconcile / 内容 | ReminderReconcileService；NotifyPlanMapper | 时间/offset/未来 scope 变化取消旧计划，新意图在未来或 Activity 当前补发窗口 CREATE。相同规则/anchor/sendAt 保留合法逾期计划；标题/地点刷新 Plan 展示字段，同锚物理节点换号保留 Activity 意图。Activity 补发可新建执行代次，同 occurrence 已有通知仍吸收，不重发。 |
| 旧 scope 切换 | ReminderReconcileService.sameIntent | 未来 S→并集 / S−R→S 取消旧计划并建 V1。当前 Activity 在锚点前按现行 scope 协调，scope 不匹配时取消旧意图并建立当前补发；保留可证明的原 occurrence 去重证据。 |
| generation | ReminderReconcileService；NotifyPlanMapper.maxGeneration | 新意图代次在同一业务写锁下单调分配，避免同主体版本下旧 Plan 已取消后插入撞唯一键。主体标题版本变化不导致旧意图失效。 |
| 有界遍历 / 重放 | NotifyServiceImpl.broadcastPlanFanOut；V33 | 每批最多 100，首次冻结 receiver userId 上界并持久保存；恢复从头查询当前资格并由业务 occurrence 唯一键吸收已生成效果。零人完成；批次资格失效取消，不冒充完成。 |
| 主动时间通知 | ActionableTimelineChangeDetector.activityTimeChanges；ActivityLifecycleService.enqueueActivityActionableChanges | 三种重要 semantic 的 TEXT/不存在→精确、精确变化、精确→TEXT/删除；同类型聚合，冻结纯文本 changes。日期确定/变化/撤回和主范围终点变化适用；重复语义、普通 OTHER 和展示文字变化不猜测。PublicEvent detector 原行为保持。 |
| 主动地点/生命周期 | ActivityLifecycleService；ActivityReminderSubjectStatus.lockBusinessEventEligible | 有效地点之间及未知↔有效地点、取消、显式提前结束沿既有类型与事件版本去重。时间/地点快照不因随后结束而替换或撤销；取消/提前结束校验对应事实，不套 ACTIVE。 |
| 外部发送复核 | ActivityDeliveryEligibility；NotificationDeliveryServiceImpl.execute | 新通知内部保存 reminderPlanId，公共 projection 不暴露。旧 plan:user key 读原 Plan；旧冻结 startTime 可验证当前 start occurrence，不能从 sendAt/文案猜。Plan 已取消、锚点变化、关系丢失、过节点或渠道失效时 SKIPPED，不删除 Notification。provider callback 在身份/模板渲染后、调用前再次复核，实际调用前才增加 attempt。 |
| D1 / 旧消息 / UNKNOWN | 原 inbox reader、LegacyNotificationDeliveryJobHandler、Delivery 状态机 | 既有站内消息及 readAt 不撤销，不制造历史 Delivery，不补发；旧 v1 Job 缺发送历史仍 UNKNOWN/待人工处理。PROCESSING crash 保守 UNKNOWN，权威 -1 才持久重试；类型扩展不改变恢复规则。 |
| 类别 / 偏好 / 跳转 / 能力 | NotifyType；NotificationPreferences；NotificationCapabilityQueries；NotificationCenterServiceImpl；NotificationSnapshotProjection；NotificationMapper | 新类型属于 ACTIVITY，沿六组合偏好、稳定 ACTIVITY_DETAIL、原列表/恢复/已读边界。完整 36 项，不把小程序或无专用模板类型报告为 available。 |

V33 是 expand：Timeline label 120、description 2000、time_description 500，Plan/Notification title 200 保持合法 Activity 标题；新增 nullable 扫描上界，不回填通知、不迁移旧开关、不改变业务关系。迁移前必须停止旧 fan-out/writer 并盘点旧任务；新 Mapper 依赖 V33，旧 JAR 不理解 V1 接收规则及 code 23，不能将恢复旧 JAR当作语义回滚。真实环境资格、旧 false 数据盘点及旧 Job 分布仍需部署前只读证据。

调度、claim/retry/lease/retention 和 MyBatis 时间填充统一使用 Asia/Shanghai 的 LocalDateTime；不修改 JVM 默认时区，不将历史本地时间列批量移位。JDBC、DB session、旧写入历史的实际时间区须在发布时核对，见 Async 的时间口径与只读 preflight。

验证入口为 commands 的 Activity Reminder V1 专项。源码/单元/Mapper/隔离 MySQL 及迁移证据分别报告；真实微信模板、许可、终端收件、目标环境切换和性能负载测试不从本地通过推断。


验证方法见 [Activity Reminder 专项](../governance/commands.md#activity-reminder-v1-focused-validation)；执行结果留在专项 target/Surefire，目标环境切换按 [runbook](operations/runbook.md#activity-reminder-切换) 核对。


## 12. 通用创建、个人删除与内容生命周期

跨端语义跟随 Contracts `notification-inbox-lifecycle-2026-10-29` 当前工作树；接收文件 SHA-256 与执行结果留在 `target/notification-foundation/`，不是新的长期协议。90 天是 breaking 可见性变化；所有受支持消费者完成窗口、404、缓存和迟到响应适配后才可对外切换，不从本地隔离验收推导发布许可。

- 新内部 producer 使用 `NotificationCreator.create(NotifyPayload)`，稳定 sourceKey、接收账号、类型和非空标题必填。结果为 CREATED / DUPLICATE / SKIPPED；非法输入及来源/冻结内容冲突抛异常并回滚。业务需要原子性时在原业务事务调用。`allowedChannels` 只收窄渠道，不能覆盖偏好、身份和实际 capability；`inAppOnly` 保留兼容。新入口明确生成 CANONICAL 渠道事实，旧 producer 经 `NotifyService` 的 legacy adapter 保留原分类、偏好与快照含义。重复不补建渠道、不改原内容、不复活收件箱。
- `NotifyServiceImpl` 保留通用计划/事件执行与历史 facade；`NotificationCreationService` 拥有受理、去重、持久化和渠道规划。`NotificationSource` 由 Activity/PublicEvent owner 提供分批接收者、提醒 payload 与业务内容字段。`NotificationDeliveryEligibility` 由业务 owner 实现；notify 的 registry 不依赖 Activity 实现。学习、积分继续使用各自业务封装和既有 Job，不强制经过 ReminderPlan。
- DELETE 只对当前本人 V 内项设置 deleted_at，不设置 readAt；合法 ID 不存在/他人/R-only/已到期/重复删除均为 204 且无额外效果。列表、旧 adapter、R 恢复、summary、计数和已读 SQL 使用同一删除/到期条件。批量已读混入隐藏项整体失败；read-through/旧 read-all 跳过隐藏项。用户删除不取消外部发送。
- 具有站内资格（LEGACY、已入箱或存在 IN_APP Delivery）的项，从原 createdAt 起满 90×24 小时即从 V/R 排除。R-only 不套用本轮站内期限。`NotificationObservation` 为同一次事务提供明确上海时区、秒精度观察边界，与 notification.created_at 精度一致；日期边界不按自然日推导。计数均读同一数据库投影，旧 Redis unread projection 不再被读取，纯时间流逝不依赖失效消息。
- 入箱序号和 global head 永不回退/重用；迟到入箱在同一用户锁下检查真实删除和期限。到期 PENDING Delivery 受控转 SKIPPED；provider claim 的条件更新也检查期限。PROCESSING 在途结果继续记录，UNKNOWN 不自动重发。旧活动 Job 的 owned handler 传递 execution context，每批和写入前检查 ownership。
- `NotificationLifecycleService` 每分钟可重建扫描，每批最多 100 项，每轮最多 10 批/5 秒（单批开始前预算，不是抢占数据库调用），逐项短事务。先收尾到期待发送，再清理安全内容；不为清理另建 Job。未决 PROCESSING/UNKNOWN 阻止物理级联删除；超过 180 天且没有有效运行租约的 PROCESSING 收敛 UNKNOWN。180 天后去除站内正文、展示/目标/发送者快照和 UNKNOWN 模板正文，仅保留追查所需元数据；legacy 无 JSON 正文也适用。清理失败下轮继续，超过期限的内容始终不可读取。
- `notification_source_receipt` 仅存 SHA-256 来源/身份/可选内容摘要、原 notificationId/创建时刻及处置时刻，不保存原 sourceKey、账号、业务目标或正文。创建与 receipt 同事务，来源行锁协调并发与内容清理。旧适配器校验身份、保留首次冻结内容；严格新入口还校验冻结内容与渠道限制。旧来源缺少原始事件时间及可信 replay cutoff，**摘要不能按 90 天盲删**；只要源仍允许重放就保留最小屏障。退役这些摘要须先由 source owner 建立并证明停止旧源受理的边界，不能把有限内容保留误当有限去重保证。它不是用户删除墓碑或内容归档。
- `NotificationDeliveryResolutionService` 是内部恢复应用服务，不新增 HTTP/权限目录。复用 BusinessAuthorizationLease 的当前 ROOT、READ_COMMITTED 治理锁及提交前复核；必须提供受限证据引用。CONFIRMED_DELIVERED / CONFIRMED_FAILED 需权威证据；ACCEPT_UNKNOWN 只记录停止追查，不改 UNKNOWN、不发送。相同结论幂等、不同结论拒绝。接受 UNKNOWN 后至少保留 30 天处置窗口才允许内容清理，处置审计独立保留。Job cleanup 不先删除未收尾 Delivery、未完成 Plan 或旧 v1 的唯一恢复依据。

- 历史来源未分类是启用阻断，不能按期限推定安全：NULL/纯空白 sourceKey 在候选 LIMIT 前排除，既不物理删除也不自动脱敏正文或 UNKNOWN 模板。直接清理 SQL 同样要求有效来源，以及摘要、notificationId、原 createdAt 一致且未处置的 receipt；来源证据与删除在同一事务提交。不能从账号、文案、时间或行摘要合成 sourceKey。该保护是待 source owner 给出可信归属/停止重放边界的临时例外，不是允许无限保留完整历史内容的正常策略；可见性仍满 90 天即关闭。UNKNOWN 的人工结论不能替代来源分类，未分类积压必须单独观测和处置。

真实独占 MySQL/Redis/HTTP 与旧调用回归的证据边界由本次测试报告给出；微信 provider、真人认证、设备 UI 和已发布消费者兼容须独立验收。
