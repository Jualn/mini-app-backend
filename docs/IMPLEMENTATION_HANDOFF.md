# Implementation Handoff: Reminder / Notification / Delivery Round 2

本交接记录 2026-09-24 第二轮设计收口后的实施边界，并同步至 2026-09-28 contracts 修订后的 compatible foundation。各轮代码和验证事实见 [实施报告](REMINDER_NOTIFICATION_IMPLEMENTATION_REPORT.md)；长期业务、渠道和可靠性规则分别以 [业务规则](domain.md)、[Reminder / Notification / Delivery](reminder-notification.md)、[WeChat Integration](wechat-integration.md)、[Async Processing](async-processing.md) 和 [Reliability](reliability.md) 为准。

本文不是生产已上线声明。当前已经新增 Java canonical API、V21/V22 schema migration、V24 历史偏好/owner 回填、共同收件箱 reader 和旧设置桥接代码，但没有在目标数据库执行 V24/preflight，没有切换 canonical producer 或启用真实微信渠道。D1 语义已确定；D2/D3 的生产数据切换仍未执行。

2026-09-28 本次仅完成正常运行与恢复的文档收口，没有修改代码、迁移或公开接口，也没有新增运行验证。下方旧编译/测试信息是历史证据，不覆盖新增规范。后续执行入口为 [执行 Prompt](IMPLEMENTATION_PROMPT.md)，验收范围以本文 §4 为准。

## 0. 当前实施检查点（2026-09-28）

- 已有八个隔离的 `/v1/users/me/...` canonical 操作：偏好 GET/PATCH、原生客户端 POST 写入别名、渠道能力、收件箱列表、未读数、单条已读和全部已读；两个偏好写入口共用同一 Service 与最终表示，旧 `/v1/notify`、`/v1/setting` 形状保留。
- V21 已定义 `notification_preference` sparse override 和 `notification.inbox_generation`；V22 增加 `USER_OVERRIDE/LEGACY_MIGRATION` 来源和逐用户 owner；V24 在旧设置写入静默的发布边界内批量导入历史有效值并切换 owner。默认 `LEGACY` 使尚未切换的旧 writer 明确归入旧集合；迁移不制造历史 Delivery。
- canonical 与旧 `/v1/notify` 的列表、计数和两种已读操作复用同一可见集合：明确 `LEGACY`，或存在 `IN_APP/DELIVERED` 的 `CANONICAL`。偏好 GET/PATCH 在 owner 未切换时返回 canonical 503；PATCH 原子修改提交组合，支持 `null` reset，并返回完整六组合表示。
- 历史 cohort 由 V24 在同一事务把旧 activity/exam 有效布尔值仅导入服务号组合并切 owner；迁移前若发现已有偏好但没有 owner 或非法旧值就失败，不覆盖。切换后旧 GET/PUT 经 adapter 读写同一 sparse preference；IN_APP/小程序不受旧字段影响。新用户创建时直接建立 canonical owner。
- D1 已确认：关闭 IN_APP 不隐藏或删除已有消息、不改已读、不撤销已规划投递；重开不补发。当前 reader 因而不按当前偏好过滤历史消息。
- capability 当前固定返回四种 Reminder 加七种 Direct Notification 共 33 项；`IN_APP` 本地可用，两个微信渠道因逐类型模板、Adapter/permission 证据不足保持 unavailable。
- Java 17 主代码编译已通过（697 个 main source）；专项/全仓测试、真实 MySQL、真实微信、部署和 CI/CD 未运行。以上只证明 compatible code/静态边界，不证明功能或生产切换完成。

## 1. 已确定设计，不得自行改写

### Reminder

- Timeline 是唯一 Business Time Truth；`EXACT_POINT/EXACT_RANGE` 只是精度，不能代替 semantic。
- Activity 与 PublicEvent 分别维护 Policy/Rule Catalog，不建立 UniversalReminderPolicy。
- Activity Start：`ACTIVITY_START_REMINDER`，默认 T-1h，`SUBSCRIBERS`。
- Activity Registration Deadline：`ACTIVITY_REGISTRATION_DEADLINE_REMINDER`，默认 T-24h，`SUBSCRIBERS_NOT_REGISTERED`；offset 可调整，不进入稳定 rule identity。
- PublicEvent Start：`PUBLIC_EVENT_START_REMINDER`，默认 T-1h，`SUBSCRIBERS`。
- PublicEvent `REGISTRATION_END`：`PUBLIC_EVENT_REGISTRATION_DEADLINE_REMINDER`，默认 T-24h，`SUBSCRIBERS`。
- 没有明确业务语义的 Timeline node 不产生 Reminder；`sendAt <= now` 不创建新 plan。
- Timeline semantic preflight 已确认复用既有 `node_type VARCHAR(32)`，不新增 `semantic_type`。code set 增加 `ACTIVITY_START/PUBLIC_EVENT_START`；不能从 `OTHER`、`EXACT_POINT`、displayOrder、标题或 card projection 猜测。
- `REGISTRATION_END` 是当前唯一已验证的通用截止 semantic。`MATERIAL_SUBMISSION` 等不能仅凭名字解释为 deadline。

### Direct Business Notification

| Business change | Recipient | NotificationType | Category |
|---|---|---|---|
| Activity cancelled | `SUBSCRIBERS_OR_REGISTERED_USERS`，userId 去重 | `ACTIVITY_CANCELLED` | `ACTIVITY` |
| Activity actionable time changed | 同上 | `ACTIVITY_TIME_CHANGED` | `ACTIVITY` |
| Activity actionable location changed | 同上 | `ACTIVITY_LOCATION_CHANGED` | `ACTIVITY` |
| Activity explicit ended early | 同上 | `ACTIVITY_ENDED_EARLY` | `ACTIVITY` |
| PublicEvent cancelled | `SUBSCRIBERS` | `PUBLIC_EVENT_CANCELLED` | `PUBLIC_EVENT` |
| PublicEvent actionable time changed | `SUBSCRIBERS` | `PUBLIC_EVENT_TIME_CHANGED` | `PUBLIC_EVENT` |
| PublicEvent actionable node location changed | `SUBSCRIBERS` | `PUBLIC_EVENT_LOCATION_CHANGED` | `PUBLIC_EVENT` |

- 不实现 Activity 报名成功/取消通知，不创建 `ACTIVITY_UPDATED`。
- PublicEvent 当前普通 END 不能证明 ended early；没有明确 event/reason 前不实现 `PUBLIC_EVENT_ENDED_EARLY`。
- Subscription 与 Registration 永远是两个事实；union/difference 只存在于 RecipientResolver。纯外部报名不能权威判断 `NOT_REGISTERED`，因此不启用 Activity deadline 差集规则。
- Direct event 不创建 `sendAt=now` 的 ReminderPlan。每个 event 需要稳定 identity/version，逐用户 source key 用 event identity + receiverId。

### Channel / Preference

- 正式 Channel：`IN_APP`、`WECHAT_MINI_PROGRAM`、`WECHAT_OFFICIAL_ACCOUNT`；禁止继续使用笼统 `WECHAT`。
- Notification 是稳定内容事实，Delivery 是逐渠道状态。`IN_APP` 是本地 Channel，事务内完成且无远程 Job；两个微信 Channel 各自拥有 Delivery/Job/result。
- Mini Program 与 Official Account 分别拥有 identity、permission、template registry/ID、field mapping、jump target、request API、provider error classification；只共享 HTTP/token/client foundation 和错误模型。
- 当前 `openid` 是小程序身份，`mp_openid` 是服务号身份。现有通知发送基础是服务号，不代表 Mini Program 通知已可用。
- 第一版 Preference 粒度是 `NotificationCategory × Channel`，Category 先启用 `ACTIVITY/PUBLIC_EVENT`，使用 sparse override；没有 row 时 IN_APP 默认开启、两个微信 Channel 默认关闭。
- Activity/PublicEvent 为 `USER_CONFIGURABLE`。当前没有确认的 `MANDATORY` NotificationType；未来账号安全类必须独立标记，不能受 Activity/PublicEvent 开关影响。
- provider permission 与用户 preference 是两个事实。Eligibility 至少同时满足 recipient、policy、preference、channel enabled、provider identity、provider permission 和 template/adapter capability。
- planning 时已知 enabled=false、templateId 缺失或 capability 不存在时不创建该 Channel Delivery；已存在 Delivery 执行时 capability 被取消则 `SKIPPED`。不能故意调用 provider 等其拒绝。
- fan-out 必须按批量读取 preference、identity、permission 并在内存规划；不能形成 receiver 级 N+1。第一版使用有索引 MySQL；只有测量证明需要时才加 Cache。

### Migration / Rollout

- 产品确认没有重要历史 Delivery 需要强行保留。新 Delivery 从 producer 切换后开始记录，不给旧 Notification 制造 fake Delivery，也不增加伪造送达含义的 Delivery 状态。V21 的 `LEGACY` 只是内部收件箱生成世代，不表示渠道送达。
- 永远不能从 `notify_plan.status=1` 或 `async_job.SUCCEEDED` 推导 `DELIVERED`。
- 必须在真实 DB preflight 统计 notification、notify_plan 和 v1 delivery Job 的各状态。若待处理/运行/死信均为 0，只保留一个兼容 release window，不建设复杂 drain 系统。
- 顺序保持：expand → compatible code → switch producer → inspect/drain v1 → later contract。
- 必须验证 empty DB Flyway replay、production-like version upgrade、唯一约束与 preflight。
- 本轮不新增 Broadcast 产品功能、表、管理 API、recipient engine 或 UI；只保留未来 System/Admin producer 接入 Notification → Delivery 的能力。

## 2. 实施 Agent 可根据真实代码小范围调整

- PublicEvent 的具体 semantic Timeline mapping：可以在真实数据分类后决定 `EXAM` 是否迁移为某类 PublicEvent start，但不能运行时按类型猜测，也不能改变“无明确 semantic 则无 Reminder”。
- Preference 表字段、主键/索引和 system default 的最终表示；必须保持 Category × Channel、sparse override、批量查询和 mandatory 边界。
- Preference 是否需要 Cache；默认不加，只有查询计划/压测证据才增加，并明确 TTL、更新失效和批量读取。
- v1 Job 是否需要 drain；以生产 preflight 为准，零数据时简化，非零时给出逐状态处理方案。
- 当前已有 System Notification 是否顺手迁移；仅在 producer/recipient/source identity 清晰且风险小时进行，不能扩展成 Broadcast 产品建设。
- `IN_APP` 本地完成的物理字段/事务细节，以及三渠道 capability 配置表形态；不得合并 Notification/Delivery 或两个微信 Channel。

如果调整会改变 Timeline truth、四类持久事实分离、Activity/PublicEvent 独立 Policy、Unknown Outcome、Outbox 边界或 Channel/Preference 核心模型，必须先报告，不得自行改变架构。

## 3. 后续实施顺序

1. 核对当前代码与新增规范的差距，按 Reminder、fan-out/Job、Notification/Planner、Delivery/repair 列出最小变更。先落实通知文档 §3–5 的意图 generation、逾期保留/有效期与 Async §7.2.1 的所有权协议；不从头重做已有 foundation。
2. 完成三渠道 Planner 的本地原子路径、一次规划/重复 no-op、同一可见集合及 owner/readiness 门槛。新 producer 显式写 CANONICAL 与真实 IN_APP/DELIVERED；渠道缺配置或权限证据时不创建外部意图。D1 不追溯、不补发。
3. 补全 §6.1 主动通知矩阵、Activity 并集接收者、业务提交时快照及 §5.1/§6.2 执行时接收集合；保留互动/审核原 owner。按当前调用链建立事件稳定身份，不从标题哈希推导。
4. 实现通知 terminal 分类、有界 repair 和受控恢复最小入口。外部渠道启用前证明最后一次 crash、迟到结果、UNKNOWN、不一致组合与人工结论不会丢失；可用受控应用 CLI/运维服务，不新增公开 HTTP 管理 API。
5. 在明确的一次性/隔离测试目标做 §4 的机制验证、全部现有 migration 加本轮新增版本的空库 replay/升级。目标环境历史数据缺失不阻止新数据路径开发，不允许借测试授权连接生产迁移。
6. 上线准备独立核验 Timeline semantic 样本、D2 preflight/旧 writer/reader 与 v1 Job。D3 核对旧有效值与缺省映射，不要求额外证明历史用户点击，不把保留意愿解释成 provider 授权。经授权才实际迁移 cohort、切 owner/producer；不得先开放新偏好再继续忽略其规划效果。
7. 两个微信渠道分别取得真实模板/permission/错误分类与运行证据后独立启用；一个渠道缺失不阻塞 IN_APP 验收。稳定后另行评审 contract migration；不自动 add/commit/push/deploy。

### 3.1 实现者必须保持的边界与未决项

本轮新增内部协议不改变公开路径、请求响应、偏好类别、类型矩阵与 D1/D2/D3。DB 内部字段、续租/terminal hook、Timeline 意图替换映射的物理实现由后续实现者按真实结构选择，并在必要时新增 migration；不得重写已发布版本。

以下不是继续扩大基础设施的理由：

- 待定→精确、精确→待定、节点删除、地点清空以及一次同时改时间/地点的通知触发仍缺明确产品判定。实现已确认的精确时刻/有效地点实际变化路径；未定义分支单独报告，不把它们自动升级为群发。它们不阻塞正常已确认路径。
- Notification 产品保留天数未定：不新增自动清理，遵守去重与恢复窗口约束。
- 微信 permission/额度及真实模板缺证据：对应渠道保持 unavailable。
- D2/D3/旧 Job 数量、生产 schema 与 Redis 能力是环境门槛：不得从仓库文档推断已通过。
- 公共接口不增加排序/送达保证；主动事件采用执行时集合、事件时内容、无异步顺序承诺，详见通知权威文档。若消费者要求更强保证，另行确认，不引入全量快照或顺序系统。

## 4. 验收重点

以下是后续必需验收，不是本轮已执行结果。纯规则可用定向测试，事务/并发/迁移须证明真实机制，provider 实际可用性单独验收。

| 范围 | 必测场景 | 通过依据 |
|---|---|---|
| Reminder/reconcile | 正常到期、零接收者、重复保存、逾期后无关编辑、Timeline 换号、时间/offset 变化 | 意图不误取消、不重复；真正变化 CANCEL+CREATE；已完成不复活 |
| 业务有效期 | 提醒逾期但节点未到、节点已到、已生成站内消息后外部过期 | 仍有效可恢复；失效停止新效果；既有站内事实不回滚 |
| fan-out | 并集/差集、外部报名禁用差集、分批关系变化、中途 crash、取消 | 每人同来源最多一次；批次与取消竞态符合约定；恢复可到尾部 |
| ownership | 超过原 lease 的多批任务、续租失败、同进程二次 claim、旧 owner 迟到 | token 区分 attempt；失权不开始新批次/调用，不覆盖结果 |
| 主动事件 | 两次真实变化、重复请求/事件、取消/提前结束、事件后新订阅/退出 | 快照与身份稳定；使用执行时集合；不因 ACTIVE 检查吞通知 |
| Planner | 六组合、旧 owner、全渠道关闭、关闭/重开后重放、并发 source key | Notification/初始 Delivery/Job 原子；重复不补建渠道；无 N+1 |
| Delivery | 成功、永久失败、明确可重试、预算耗尽、最后一次 crash、结果提交失败 | Job/Delivery 均可解释；UNKNOWN 不重发；repair 不伪造终局 |
| 兼容/HTTP | 同一可见集合、越权、并发已读、全部已读期间新增、D1/D2/D3 | 写入到读取语义一致；旧消息/已读不丢，未就绪不假装生效 |
| 恢复/清理 | DEAD retry、UNKNOWN accept、事故 replay、清理边界 | 有依据与处置记录；去重证据不早删；无批量盲重发 |
| 真实渠道 | 身份、模板、权限、错误码与实际请求 | 按渠道单独证据；Mock/站内成功不能替代 |

开启正常 IN_APP 路径至少要求前述相关业务、事务、ownership、兼容与恢复机制通过；不能以两微信渠道 unavailable 豁免本地正确性。真实 provider 项仅阻塞对应外部渠道。必要验证被明确暂缓时可交付代码，但必须标记 NOT RUN，不能宣称稳定可用。

报告按 PASS / FAIL / BLOCKED / NOT RUN 分开代码、Migration、production-like DB、真实微信和部署证据。设计完成或 Mock 通过都不等于生产上线。
