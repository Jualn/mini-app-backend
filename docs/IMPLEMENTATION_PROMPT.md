# 后端执行 Prompt：通知业务闭环（第二轮）

工作目录：`D:/Jualn/Chan/jualnCampus/backend`。只修改 backend，不修改 contracts、mini-program 或 admin-web。

前置：contracts 已完成 `../contracts/docs/coordination/notification-delivery.md` 与 canonical paths/schemas，并交接 operationId、兼容规则和内容哈希。继续实施前固定并核对同一份 canonical 内容；后续契约发生变化时重新交接，不从本 Prompt 或旧 DTO 推导新 HTTP 字段。

你负责按已收口的文档，在当前 compatible foundation 上完成正常通知与基本恢复：Reminder/reconcile、三渠道 Planner、主动通知、接收者、租约保护、结果收尾及后续上线准备。客户端由各自项目接入。本任务不授权生产切换。当前未提交代码是工作起点；不要从 HEAD 重做第一轮，也不要重复创建 V21/V22、owner bridge 或七个 canonical 操作。

目标：运维修改或取消已发布活动/公共事项后，正确接收者有准确的站内消息，并能通过接口控制类别与渠道偏好。真实模板、生产迁移、历史修复与发布不在本轮执行范围。

## 权威来源与现状核对

按任务相关章节阅读，不重复全量加载所有标准：

- 后端 `docs/domain.md`、`docs/architecture.md`。
- `docs/reminder-notification.md`，尤其 §3–5、§6.2–6.3、§7.5–7.6；这是正常执行、逾期、事件时点和恢复的目标语义，不是已实现声明。
- `docs/wechat-integration.md`、`docs/IMPLEMENTATION_HANDOFF.md`。
- `docs/async-processing.md`、`docs/reliability.md`、`docs/observability.md` 的受影响边界。
- `docs/TIMELINE_SEMANTIC_PREFLIGHT.md`、`docs/REMINDER_NOTIFICATION_IMPLEMENTATION_REPORT.md` 作为已完成/待完成证据。
- 数据库 README 与实际迁移历史；按目录规范读取相关工程标准。
- contracts 的 README、PROJECT_RULES、API_GUIDELINES、COORDINATION_GUIDE 和 canonical OpenAPI 及其 fragments。

2026-09-28 文档同步后的静态核对起点，继续实施前仍以真实代码复核：

- 已有 ActivityReminderPolicy/PublicEventReminderPolicy、V20 Delivery expand 和 v2 deliveryId Job，不重复实现第一轮。
- 已有显式 `ACTIVITY_START/PUBLIC_EVENT_START/REGISTRATION_END`，不按 label/order/第一个精确节点猜测。
- ActivityBusinessNotificationJobHandler 当前只调用 notifyActivitySubscribers；与目标 union 接收者不一致。
- NotificationDeliveryServiceImpl 当前主要服务 `WECHAT_OFFICIAL_ACCOUNT`，尚非完整三渠道 Planner。
- V21/V22 已增加 `notification_preference`、来源、逐用户 owner 与 `notification.inbox_generation`；七个 canonical 操作、六组合偏好、33 项 capability、503 门槛、旧设置 adapter 和共同可见性 Mapper 已存在，但未经过专项/数据库测试，也未实际迁移历史 cohort 或切换 producer。
- 七种 Direct NotificationType 已进入内部枚举/能力表示，不等于对应 producer 已全部实现；当前 Activity 接收者并集、Activity 时间/地点变化及 PublicEvent 三种主动通知仍需补齐。

## 二、渠道和偏好基础

先按 `IMPLEMENTATION_HANDOFF.md` §3 顺序核对并完成，再按 §4 验收；不要跳过 Reminder generation/逾期冲突与 Async §7.2.1，仅补齐 HTTP 或 Planner 就报告完成。每批短事务不等于整个 Job 未超租约；实现批次条件续租、失权停止、Delivery 调用认领、末次崩溃 terminal/repair，复用现有设施，不建设通用工作流。

主动事件冻结事件时内容，fan-out 采用执行时有界接收集合；具体规则引用通知文档，不自行新增事件时全量快照。重复 source key 不按新偏好补建渠道；事件重放不是补发工具。评论/回复/审核目录保持原 owner。先完成已确认路径，未定义产品边界单列，不扩大群发。

- 正式渠道为 IN_APP、WECHAT_MINI_PROGRAM、WECHAT_OFFICIAL_ACCOUNT。
- ACTIVITY/PUBLIC_EVENT × Channel，sparse override；无 override 时 IN_APP 开启，两个微信渠道关闭。
- provider permission 独立于 preference；未绑定、未授权、模板缺失或 adapter capability 不可用时不规划远程投递。
- IN_APP Delivery 在 Notification 本地事务内完成，无远程 Job，不增加 provider attempt。新消息只有 IN_APP Delivery 生效才进入站内列表和未读数。
- 外部渠道失败不能回滚站内结果；Notification 是内容事实，Delivery 是渠道结果。
- 每批批量读取偏好、身份和 permission，避免 receiver 级 N+1；本轮不新增 Redis preference cache。
- 两个微信渠道各自管理身份、模板映射、permission、API 与错误分类。缺少真实配置时 capability unavailable，不靠假模板或真实拒绝来判断可用性。
- 保持 UNKNOWN 与明确失败的区别，不给 timeout 自动补发。已存在 Delivery 执行前复核偏好和 capability。
- 评论/回复、审核拒绝等现有通知保持原有语义；不要把未归属 ACTIVITY/PUBLIC_EVENT 的通知强塞进这两个偏好类别。

## 三、主动通知与接收者

严格实施 `docs/reminder-notification.md` §6.1：

| 业务 | 场景 | 接收者 |
|---|---|---|
| Activity | 已发布后取消、显式提前结束、已发布且 ACTIVE 时行动时间/地点实际变化 | 订阅者与有效报名者并集，userId 去重 |
| PublicEvent | 已发布后取消、已发布且 ACTIVE 时行动时间/行动节点地点实际变化 | 订阅者 |

- 时间变化只比较已确认的 semantic 精确时刻；地点使用权威矩阵中的真实字段。重排节点、只改标题/正文、不变值保存不得触发。
- 先核对时间从待定变精确、节点删除、清空地点、一次同时改时间和地点等边界是否已有规则；未定义时单列具体问题，不把任何字段变更都升级为群发。
- 每个事件具有稳定且持久的 identity/version；同一业务事务记录事件意图和冻结内容，异步重放不得再次生成事件身份。source key 使用 event identity + receiverId。
- 主动通知不经 ReminderPlan；复用现有 durable Job/本地事务，不增加无必要的 Outbox 链路。
- RecipientResolver 只实现 SUBSCRIBERS、SUBSCRIBERS_NOT_REGISTERED、SUBSCRIBERS_OR_REGISTERED_USERS。订阅和报名仍是独立事实，不因 union 自动建立订阅。
- canonical 和仍可调用的旧生命周期入口保持相同事件语义；避免双发或漏发。
- 取消事件不能被通用“主体取消则跳过”预检吞掉；提醒资格与业务取消通知资格分别处理。
- 按已确认规则处理 Activity deadline 的未报名差集；纯外部报名不能权威判断未报名，不启用该规则。

不增加报名成功/取消通知、通用 UPDATED、PublicEvent 普通 END 通知、@ 提及、广播产品或运维 UNKNOWN 恢复后台。

## 四、持久化与兼容

- 读取全部现有迁移版本，新增下一个可用版本；不修改已存在的 V1–V20 或之后已有 migration。
- 保持 notification source key、逐渠道唯一 intent、事务与批量查询所需约束和索引。
- 新 producer 从切换后记录真实 Delivery；不补造历史 DELIVERED，也不从旧 Job SUCCEEDED 推断送达。
- 未做真实 preflight 前保留必要的 v1 reader，不删除历史数据，不执行 contract 收缩。
- 目标数据库样本缺失不阻止新数据路径开发；旧 semantic 不猜测、不自动 backfill。历史上线准备单列。
- 本轮只写 migration/只读 preflight 源文件；不连接共享或生产数据库执行迁移。
## 接口与读取闭环

复核并完成 contracts 已明确的偏好读取/更新、收件箱列表/未读数/已读行为和新类型表示。当前 compatible code 已覆盖七个操作、owner 503、旧设置桥接和新旧 reader 共用集合；后续重点是机制测试、真实 D2 preflight/D3 cohort 迁移和 canonical producer 切换，不得另建平行接口。当前用户由认证上下文取得，不能越权访问。写入、读取、列表过滤、未读计数和已读操作必须继续引用同一集合；返回最终偏好状态供客户端同步。

按共享协调约定处理历史无 IN_APP Delivery 的 Notification 与旧设置过渡。不直接 INNER JOIN 丢掉历史消息，不伪造历史 DELIVERED。D1 已确认：关闭只影响未来规划，既有消息/已读/已规划投递不变，重开不补发。D2/D3 的真实数据证据不足时暂停生产切换，不自行猜测边界。

保留现有评论/回复、审核拒绝等通知，不用 ACTIVITY/PUBLIC_EVENT 开关错误屏蔽其他类别。通知创建时冻结内容，详情读取当前主体状态。取消通知不能被提醒执行前的“主体已取消”检查吞掉。

## 交付给客户端 agent

更新现有 `docs/REMINDER_NOTIFICATION_IMPLEMENTATION_REPORT.md`，保留第一轮历史证据，分开本轮结果。按 operationId → Controller/Service/Mapper/Migration/Handler 说明实现状态，列出请求到落库到读取的语义、兼容边界与未决项。

交接契约修订、已支持 operationId、客户端启用前置、新 migration 文件与未执行状态、渠道实际 capability。微信缺配置时保持关闭，不伪造可用状态。列出当前未运行的幂等/并发/迁移/真实渠道验证，不声称生产就绪。

下一步交给小程序 agent；管理端只有操作契约变化才需要调整。不要为完成跨端任务越界修改其他项目。
## 本轮共同约束

这是实施任务，不是要求再写一份计划。先读当前项目 AGENTS.md，检查并保留未提交修改。只修改当前子项目，其他目录只读；发现跨项目缺口时交接给对应 agent，不越界修改、不自行扩大任务。

前轮 compatible foundation 曾按当时要求暂缓测试，不能把那轮编译证据当成本轮验收。后续实现按交接 §4 和项目 commands 选择定向业务测试及必要的事务/并发/迁移机制验证；若当前任务仍明确要求暂缓，则遵守并将必需验证记为 NOT RUN，不宣称稳定可用。不建设新 CI/CD，不升级依赖，不将本地测试授权解释成真实微信发送、共享数据库迁移或部署授权。

不 add/commit/push，不生产迁移或真实群发，不覆盖无关修改。遇到待定业务语义，仅暂停相关分支并继续其余工作。完成报告分别写已实现、静态检查结果、BLOCKED 和 NOT RUN；不得把其他 agent 的完成报告当作自己执行过的验证。
