# Reminder、Notification 与 Delivery 设计

本文是提醒意图、用户通知事实和外部投递状态的项目级权威来源。业务状态与产品规则仍归 [业务规则](domain.md)，执行、租约和重试机制归 [Async Processing Architecture](async-processing.md)，失败结果含义归 [Reliability Baseline](reliability.md)，微信协议归 [WeChat Integration](wechat-integration.md)。

本文维护 Reminder、Notification 与 Delivery 的当前语义。表结构和数据演进以 Flyway 为准，接口表示以共享 contracts 为准；部署确认与排障见运维手册，不在本文维护阶段进度。站内渠道、服务号适用类型与小程序通知渠道分别按 §7 的能力边界判断。

## 1. 概念与依赖方向

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
- 新一轮 reconcile 不创建 `sendAt <= now` 的计划。已经持久化后才逾期的计划由 Async Job 的恢复规则处理，不能被下一轮 reconcile 静默删除。
- offset 是 Rule Catalog 中可调整的参数，不属于 `ruleKey`、NotificationType 或计划逻辑身份。把 T-1h 调成 T-2h 只产生旧计划 CANCEL + 新计划 CREATE，不创造新业务类型。

### 2.1 Reminder Rule Matrix

所有自动 Reminder 只接受 `EXACT_POINT` 或 `EXACT_RANGE` 的精确起点；`DATE_*`、`TEXT` 和仅有展示意义的节点不产生自动提醒。`EXACT_*` 只是时间精度，不能替代 Timeline semantic。

| Subject | Timeline semantic | Rule identity | Offset | Recipient scope | Notification type | Overdue behavior |
|---|---|---|---|---|---|---|
| Activity | `ACTIVITY_START` | `ACTIVITY_START_REMINDER` | 默认 T-1h；由 Activity Rule Catalog 配置 | `SUBSCRIBERS` | `ACTIVITY_START_REMINDER` | reconcile 时 `sendAt <= now` 不创建；已创建后逾期由 Job 恢复协议处理 |
| Activity | `REGISTRATION_END` | `ACTIVITY_REGISTRATION_DEADLINE_REMINDER` | 默认 T-24h；由 Activity Rule Catalog 配置 | `SUBSCRIBERS_NOT_REGISTERED` | `ACTIVITY_REGISTRATION_DEADLINE_REMINDER` | 同上；只有平台能权威判断有效报名状态时启用差集 |
| PublicEvent | `PUBLIC_EVENT_START` | `PUBLIC_EVENT_START_REMINDER` | 默认 T-1h；由 PublicEvent Rule Catalog 独立配置 | `SUBSCRIBERS` | `PUBLIC_EVENT_START_REMINDER` | reconcile 时 `sendAt <= now` 不创建；已创建后逾期由 Job 恢复协议处理 |
| PublicEvent | `REGISTRATION_END` | `PUBLIC_EVENT_REGISTRATION_DEADLINE_REMINDER` | 默认 T-24h；由 PublicEvent Rule Catalog 独立配置 | `SUBSCRIBERS` | `PUBLIC_EVENT_REGISTRATION_DEADLINE_REMINDER` | 同上 |

真实模型映射边界：

- 既有 `node_type VARCHAR(32)` 承担受控业务 semantic，schedule/precision 表达时间结构，不新增重复的 `semantic_type`。`TimelineSemantic` 集中维护 code、读取兼容和主体适用性；Activity 写入只接受 `ACTIVITY_START`，PublicEvent 写入只接受 `PUBLIC_EVENT_START`，共享 semantic 保持兼容。canonical 写入拒绝未知或跨主体 START code；历史未知值读取为 `OTHER`，不自动改写原数据库值。不得把 `EXACT_POINT`、`OTHER`、label、displayOrder、nodeKey 或卡片选中节点猜成开始节点。历史分类使用 [只读 preflight SQL](../src/main/resources/db/validation/timeline_semantic_preflight.sql)，没有可追溯的无歧义依据不自动回填。
- 当前 `REGISTRATION_END` 已有明确“报名截止”语义，可作为两类业务的截止节点；它必须具有精确时刻。Activity 的 `SUBSCRIBERS_NOT_REGISTERED` 以 `activity_enrollment` 的有效订阅者减去 `activity_registration` 的有效报名者。外部报名无法权威判定，纯外部报名 Activity 第一版不启用该差集规则，除非未来引入可验证报名事实。
- PublicEvent 当前 `EXAM`、`FINAL`、`MATERIAL_SUBMISSION` 等描述具体阶段，不能统一解释为事项开始或截止。`EXAM` 只有在生产数据分类证明它就是某类事项唯一开始节点且完成显式迁移后，才可映射到 `PUBLIC_EVENT_START`；不在运行时靠 type 分支猜测。
- `MATERIAL_SUBMISSION` 本身不能区分开始还是截止。未来需要提醒时新增明确 deadline semantic 和独立 rule，而不是匹配标题文本。

## 3. Reconcile 与计划生命周期

业务创建、编辑、发布状态变化、生命周期变化或 Timeline 替换后，在同一业务事务中执行 reconcile：

```text
rules    = current valid rules and exact business nodes (before future-time filtering)
current  = PENDING / PROCESSING plans for subject

current still matches rule, node and reminder generation -> KEEP (including overdue)
current intent invalidated by business change             -> CANCEL
new intent with sendAt > now and no existing generation   -> CREATE
changed intent                                           -> CANCEL old; CREATE only if future
COMPLETED / CANCELLED generation                          -> never reactivate
```

计划的概念逻辑身份是：

```text
(sourceType, sourceId, timelineId, ruleKey)
```

`generation` 表示提醒意图的代次，不直接等同于主体每次保存的并发版本或内容 schema version。同一 generation 内重复 reconcile 必须幂等；时间、offset、recipient scope 或规则资格变化时取消旧意图，只有新 sendAt 在未来才建立新意图。普通标题、介绍、附件编辑不因主体版本递增而重建计划；通知内容在实际创建 Notification 时冻结。目标唯一性可以使用 `(source_type, source_id, timeline_id, rule_key, generation)`，无需模拟 partial unique index。reconcile 复用业务主体写锁或版本条件串行化同一主体，不以“先查再写”承担并发正确性。

Timeline 整体替换时，业务模块须按已确认的唯一 semantic 与精确时间识别未变化的提醒意图，不能仅因物理 timelineId 换号重发；保留原意图身份或显式建立替换映射，具体持久化方案由实现交接验证。真实时间变化形成新意图，旧计划不可原地改 sendAt；重新发布只创建当前有效且未来的新意图，不恢复历史计划。已 COMPLETED 的同一意图不能因普通保存再次产生计划。

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
- 第一版只启用规则矩阵实际需要的 `SUBSCRIBERS`、`SUBSCRIBERS_NOT_REGISTERED` 和 `SUBSCRIBERS_OR_REGISTERED_USERS`；不要为未来假设提前扩张 scope 枚举。
- `SUBSCRIBERS_NOT_REGISTERED` 是有效订阅者集合减去有效平台报名者集合；`SUBSCRIBERS_OR_REGISTERED_USERS` 是两者并集。两种集合运算都以 userId 去重，不改变任何原始业务关系。
- RecipientResolver 必须支持批量、稳定分页读取，不能逐 receiver 查询报名状态。订阅不自动产生报名，报名也不自动产生订阅；未来“报名自动关注”只能作为独立业务 Policy 实现。
- fan-out 失败后从头扫描，不持久化 cursor；在当前规模下，稳定排序、每批上限和 Notification 唯一键已经提供正确性，cursor 仅是未来性能优化。

### 5.1 批次集合与执行收尾

第一版使用执行时当前有效关系，不承诺事件时刻的全量接收者快照。一次扫描按 userId 稳定 keyset 分页，在扫描开始取得有界上限，后续批次不无限追逐新关系；恢复从头扫描当前集合，已通知用户由 source key 去重。批次读取后发生的订阅/报名变化允许落在当前短批次竞态内，不撤销已生成通知。扫描完成后不因新订阅再次执行同一计划；零接收者也是成功完成。

执行所有权与长 fan-out 的协议见 [Async §7.2.1](async-processing.md#721-业务执行所有权与通知收尾)。每批短事务不等于整个 Handler 短；不得以每批 100 人推断整个 Job 不会超过租约。

计划正常遍历完成才转 COMPLETED。技术故障耗尽预算时保留非完成计划与 DEAD Job，运维组合表示为“部分/未完成，待处理”，不得伪造 COMPLETED 或业务 CANCELLED；普通扫描不得自行新建替代 Job。受控 retry 复用同一计划身份与 source key，业务仍有效时从头扫描；过期/取消则关闭剩余工作，保留已经生成的通知。

## 6. Notification

Notification 是每用户、可长期存在的稳定内容事实；Notification 与任何一个渠道 Delivery 都不等价。第一版把 `IN_APP` 也作为正式渠道：本地 `IN_APP` Delivery 生效后，Notification 才出现在收件箱并参与未读计数。它可以在某个微信渠道未配置、身份未绑定、权限不足、用户关闭该渠道或发送失败时独立存在，其他渠道结果互不回滚。

Notification 创建时冻结用户可见快照：

- 保留现有 `title`、`content`、`target_type`、`target_id` 和 `sender_id`；点击详情时按 `target_type + target_id` 读取主体最新状态。
- 目标演进可增加 `content_schema_version` 与有界 `content_payload JSON`，保存渠道无关的稳定字段，例如 `subjectTitle`、`eventTime`、`location`；不保存整个 Activity/PublicEvent JSON。
- 页面路径、templateId、touser、openid、provider error 等渠道字段不得进入 Notification。
- Reminder 到期时由所属业务模块的 Notification Factory 基于当前有效主体和 Timeline 生成渠道无关 Draft；Factory 可以通过本模块持久化层读取自己的数据，但 WeChat、notify 通用组件不得跨模块读 Mapper。

新协议（type 8–11）Reminder fan-out 复用 `notification.source_key` 唯一约束，以冻结的实际业务节点时间确定 occurrence：

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

structured 与 legacy 使用同一 row/ID/readAt。`content_payload` 冻结 presentation、actor、subject 与可靠 target，不在读取时回查业务对象；时间/地点变更在业务变更事务中冻结 before/after。历史缺少变更快照时退化 SYSTEM 类型展示，不能从当前主体补 before。Notification 存在与目标可访问性分离，目标详情失败不会删除通知。POST/COMMENT 的成功新增 like fact 产生通知，取消不产生通知，重新点赞复用首次 actor/recipient/source 身份；self-action 排除，不扫描伪造历史 like。

展示快照：presentation 的可选纯文本 `subjectTitle` 是关联帖子/活动/公共事项的创建时标题，`quote` 是原评论/原内容摘要，回复的 `body` 仍是新回复。保留旧 `context` 的已有含义和值，不能用它猜新增字段。评论/回复与点赞在创建时冻结可靠 actor；头像可用时保存 avatarUrl，无头像时保留 actor 并省略 avatarUrl。历史缺新增字段/actor 的消息保持缺省，不在读取时用当前实体或用户资料回填。新消费者的字段优先级与 context 去重由共享契约承接；站内模型/read/target/Delivery 身份不变。JSON payload 扩展无需新表或历史数据迁移。

业务事件通知直接创建 Notification 并按 Preference/Capability 规划各渠道 Delivery；它们不是定时提醒，不经过 ReminderPlan。不能从任意字段变化自动推导一个通用 UPDATED 通知。

### 6.1 Business Notification Matrix

| Business change | 触发边界 | Recipient scope | Notification type | Preference category |
|---|---|---|---|---|
| Activity 被取消 | 已发布 Activity 的 lifecycle 明确变为 CANCELLED | `SUBSCRIBERS_OR_REGISTERED_USERS` | `ACTIVITY_CANCELLED` | `ACTIVITY` |
| Activity 行动时间变化 | 已发布且 ACTIVE；`ACTIVITY_START` 或 `REGISTRATION_END` 的精确时刻发生实际变化 | `SUBSCRIBERS_OR_REGISTERED_USERS` | `ACTIVITY_TIME_CHANGED` | `ACTIVITY` |
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
| Activity/PublicEvent Reminder | §2.1 与 §5 | planId + userId | 六组合 Category × Channel |
| Activity/PublicEvent 主动事件 | §6.1–6.2 | event identity + receiverId | 六组合 Category × Channel |
| 历史系统通知/广播 | 仅保留已有 producer/旧计划的明确接收范围 | 保持既有稳定身份；无身份项先 preflight | 原系统设置 owner；不新增广播产品 |

评论/回复/资料审核等既有路径的设置当前控制外部投递，不能无声扩展成删除或隐藏站内事实。它们沿兼容路径保留；迁移到新的类型/渠道策略前核对现有调用与设置含义，不把新三渠道 Planner 的默认值自动套给未归类类型。具体历史 key 拼写保持原实现，不为统一表格改名。外部发送同样遵守真实 capability/permission 与 UNKNOWN 边界。

## 7. NotificationDelivery

渠道投递是 Notification 的派生意图与结果。`IN_APP` 是本地渠道，两个微信渠道是独立外部渠道；它们不能复用笼统 `WECHAT`。V20 已落 `notification_delivery` 基础表；V21 增加 sparse preference 和明确的旧/新收件箱世代分类；V22 增加 `LEGACY_MIGRATION` 来源与逐用户 owner。它们不制造历史 Delivery。D1 已确认关闭 IN_APP 只影响未来规划，已有消息、已读状态和已规划投递保持，重开不补发。当前 producer 仍只覆盖既有服务号路径，下面的三渠道目标仍需后续兼容切换：

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
| Activity Reminder / Direct (`ACTIVITY`) | 支持；本地事务完成 | 目标支持；当前 Adapter、模板注册和权限事实缺失，默认 unavailable | `ACTIVITY_START_REMINDER` 已接既有 `activity_start` 模板；其余类型默认 unavailable | 微信两渠道各自 templateId、字段 mapping、jump target；不能共享 ID | Mini Program 需要对应小程序订阅消息授权事实；Official Account 当前由 provider 在发送时权威校验，不表示永久 GRANTED |
| PublicEvent Reminder / Direct (`PUBLIC_EVENT`) | 支持；本地事务完成 | 目标支持；当前 unavailable | provider 基础存在，但 PublicEvent 专用模板 mapping 尚未验证，默认 unavailable | 同上；具体 ID 均为“配置缺失”，不得编造 | 同上 |
| 未来 System/Admin producer | 基础能力可复用，当前不新增产品 producer | 不预建未使用模板 | 既有 SYSTEM_NOTICE 可作为历史能力证据，不等于新广播产品已迁移 | 只有真实 producer/类型启用时配置 | 按该类型的 mandatory/configurable policy 与 provider 权限判断 |

`WECHAT_MINI_PROGRAM` 与 `WECHAT_OFFICIAL_ACCOUNT` 各自拥有 provider identity、authorization/permission、template registry、field mapping、jump target、request API 和 error classifier；只共享 token/HTTP/client foundation 与通用错误模型。当前仓库的 `openid` 是小程序身份，`mp_openid` 是服务号身份；现有通知发送服务调用的是服务号订阅通知 API，不能改名后冒充小程序渠道。

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

Notification 的 source key 删除后将失去去重证据。因此清理不仅检查 Delivery 终局，还须证明关联计划/事件/Job 不再允许正常或事故重放，并覆盖对应的 Outbox/Job 恢复窗口；存在积压、DEAD 未处理、UNKNOWN 未结论或进行中恢复时跳过。没有确定保留规则前不新增自动 Notification 清理。

超出已保留事实窗口的历史重发不是普通 retry，不能由通用 replay 工具绕过；需单独确定业务身份与影响范围。当前不建设永久去重表或冷归档。人工 accept 不立即删除 UNKNOWN，仍需满足事故证据保留条件。

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
