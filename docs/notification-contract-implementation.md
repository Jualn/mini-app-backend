# Notification Contract 增量实现报告

日期：2026-09-30。范围：Backend Provider-first A–I；保留工作区原有未提交修改，未 add/commit/push，未部署生产，未切换 Mini Program consumer。本文是本轮证据报告，业务权威仍归 [Reminder/Notification](reminder-notification.md)、[共享契约](../../contracts/README.md)及执行迁移。

## 1. 实际修改文件

以下仅列本轮新增/修改项；既有未跟踪的 canonical/Delivery/Async 文件在本轮局部修改，不表示整份实现均由本轮新增。路径相对 backend；`java/` 表示 `src/main/java/cn/jualn/miniapp/`，`test/` 表示 `src/test/java/cn/jualn/miniapp/`。

| 文件 | 本轮变更 |
|---|---|
| `java/common/enums/NotifyType.java` | 互动语义类型 19–22；兼容旧类型投影 |
| `java/common/constant/RedisKeyConstant.java` | unread v2 projection namespace |
| `java/infrastructure/cache/RedisService.java` | best-effort projection 写入结果 |
| `java/module/notify/entity/Notification.java` | readAt、inboxSeq |
| `java/module/notify/mapper/NotificationMapper.java` | R/V、read、sequence、summary、历史 occurrence 查询入口 |
| `src/main/resources/mapper/NotificationMapper.xml` | 对应 SQL、锁序与索引查询 |
| `java/module/notify/converter/NotifyConverter.java` | readAt → legacy isRead 与旧互动 enum |
| `java/module/notify/converter/NotificationCenterConverter.java` | Query/BO/VO 转换 |
| `java/module/notify/bo/NotificationCenterBO.java` | bounded snapshot 与 structured 服务结果 |
| `java/module/notify/vo/NotificationItemVO.java`、`NotificationListVO.java`、`NotificationSummaryVO.java`、`NotificationReadResultVO.java` | structured wire 表示；数字计数、必需 null 字段 |
| `java/module/notify/dto/request/CanonicalNotificationQuery.java`、`BatchReadNotificationsRequest.java`、`ReadNotificationsThroughRequest.java` | representation/filter 与严格 body 边界 |
| `java/module/notify/controller/CanonicalNotificationController.java` | list/summary/get/batch/through 路由，默认 legacy 不变 |
| `java/module/notify/service/NotificationInboxService.java` | 首次 V entry 的 MANDATORY 事务、逐用户 sequence |
| `java/module/notify/service/NotificationCenterService.java`、`impl/NotificationCenterServiceImpl.java` | R 恢复、V 查询、原子 read、bounded summary |
| `java/module/notify/service/NotificationStreamCursorCodec.java` | opaque authenticated 用户/用途/筛选游标 |
| `java/module/notify/service/NotificationSnapshotProjection.java` | 只读冻结快照、可靠 target、历史 fallback |
| `java/module/notify/service/ReminderOccurrence.java` | 冻结业务 anchor identity |
| `java/module/notify/service/NotifyService.java`、`impl/NotifyServiceImpl.java` | snapshot、producer 接口、read/cache、semantic dedupe、metrics |
| `java/module/notify/service/impl/CanonicalNotificationServiceImpl.java` | legacy read truth、锁序、Capability 入口就绪 |
| `java/module/notify/service/impl/NotificationDeliveryServiceImpl.java` | 复用 IN_APP entry、origin ID、有限 result metrics、外部入口门禁 |
| `java/module/notify/payload/NotifyPayload.java`、`async/BusinessNotificationJobPayload.java` | 同一创建/Job payload 扩展冻结 snapshot，旧 Job JSON 可读 |
| `java/module/activity/service/impl/ActivityServiceImpl.java`、`java/module/exam/service/impl/ExamServiceImpl.java` | 时间/地点 before/after 创建时冻结 |
| `java/module/timeline/service/ActionableTimelineChangeDetector.java` | 在既有 actionable 判断范围提取变更值 |
| `java/module/comment/service/impl/CommentAuditCallback.java` | 评论/回复语义与小型快照 |
| `java/module/interact/service/impl/InteractServiceImpl.java` | 成功新增 POST/COMMENT like fact 生产者与 self 排除 |
| `java/module/post/service/PostNotificationFactsService.java`、`impl/PostNotificationFactsServiceImpl.java` | owner/title 本模块读取边界 |
| `java/module/comment/service/CommentNotificationFactsService.java`、`impl/CommentNotificationFactsServiceImpl.java` | owner/source/preview 本模块读取边界 |
| `java/module/wx/service/WxMpNoticeSendService.java` | 入口 origin ID 替换、模板+page/appId 就绪 |
| `src/main/resources/db/migration/V26__expand_notification_read_and_inbox_stream.sql` | expand |
| `src/main/resources/db/migration/V27__backfill_notification_read_and_inbox_stream.sql` | 历史 read/V baseline |
| `src/main/resources/db/migration/V28__version_unread_projection_and_complete_pending_anchors.sql` | read projection version 与安全 pending anchor backfill |
| `src/main/resources/db/validation/notification_contract_preflight.sql`、`src/main/resources/db/README.md` | 发布分类、切换与回滚边界 |
| `docs/reminder-notification.md`、`docs/observability.md`、`docs/wechat-integration.md`、`governance/commands.md`、本文 | 同步受影响事实、meter 注册、验证方法 |
| `test/module/notify/service/NotificationCenterDatabaseTest.java`、`NotificationStreamCursorCodecTest.java`、`NotificationUpgradeDatabaseTest.java`、`ReminderOccurrenceTest.java` | 真实 SQL/锁/事务、游标、升级、occurrence |
| `test/module/notify/controller/NotificationCenterMvcTest.java` | MVC 形状、数字/null、默认表示、非法输入 |
| `test/module/notify/service/impl/NotifyServiceImplTest.java`、`NotificationDeliveryServiceImplTest.java`、`CanonicalNotificationCapabilityTest.java` | 创建、投递、门禁与 metrics |
| `test/module/interact/service/impl/LikeNotificationProducerTest.java` | 真实 producer 方法与冻结/幂等/self |
| `test/module/activity/service/impl/ActivityServiceImplTest.java`、`test/module/exam/service/impl/ExamServiceImplTest.java` | 业务保存方法产生冻结 before/after |
| `test/module/timeline/service/ActionableTimelineChangeDetectorTest.java`、`test/third/wx/service/WxMpNoticeSendServiceTest.java` | 变更提取与 originating entry |
| `src/test/resources/notification-v25-upgrade-fixture.sql` | 专用 V25 synthetic 历史数据 |

## 2. Migration 与发布边界

本轮开始时 checkout 最新迁移为 V25，新增 V26–V28，未改写历史 migration。content_payload 已存在，无第二张 Notification/snapshot 表。索引包括 `(user_id,inbox_seq)` 唯一键、user/read/sequence、user/type/sequence 和历史 user/type/target occurrence 查找索引。

V27 对 `is_read=1` 写迁移确认时间，其他行 readAt=NULL；历史排序只作为一次性 baseline，按 createdAt/id 为 LEGACY 与已 DELIVERED IN_APP 行赋 sequence。external-only 保持 NULL，不制造历史 Delivery。V28 仅补 pending、当前代次、准确 Timeline source/node/precision 关联的 type 9–11 anchor；processing/旧代次不可从现在的业务状态恢复历史。

发布顺序：目标环境只读 preflight → 停旧 Notification/read/Delivery/fan-out writer → V26–V28 → 启动新 Provider → 验证 → 后续 consumer 切换。新旧 read writer 不可并存。readAt shadow 并不保证直接回退旧 JAR 安全；回滚须停写并单独确定兼容/数据恢复方案。MySQL DDL 失败不是自动全库回滚。生产行数、异常历史计划、发布窗口、备份和执行结果均未核实。

## 3. Canonical Notification/read/V 最终形态

- 同一 Notification row/ID，`readAt != null` 是唯一已读判据；shadow is_read 仅在同一 SQL 同步，所有当前 read/unread 判断基于 readAt。
- R：本人拥有的 Notification。GET 与 batch-read 使用 R，不回查 target，也不因 target 失效而 404；非本人/缺失均为既有 404 problem，未认证为 401。
- V：R 中已首次进入 IN_APP Box、具有 inboxSeq 的行。LEGACY 在创建事务进入；canonical 在本地 IN_APP Delivery 事务进入。external-only ∈ R 且 ∉ V。
- list/summary/Badge/read-through/旧 inbox 使用 V。外部投递成功不写 readAt，偏好变化不改历史 V/readAt。

## 4. Structured 与 legacy compatibility

原 `/notify` 路由、canonical 默认 legacy、legacy ID/isRead 和 deprecated mark-all 保留；新表示只由同一 row 投影。新互动类型 19–22 在旧表示映射 COMMENTED_ME/REPLIED_ME/LIKED_ME，不改变旧数值。

structured 提供 id/category/type/presentation/actor?/subject?/target?/readAt/createdAt；必需 readAt=null、summary latest=null 保留，计数为 JSON number，ID 为 string。历史未知 code 退化 SYSTEM；缺 before/after 的旧 change 消息退化 SYSTEM type，但保留其可靠 box category 与内容；不按文案猜新类型。历史 POST/ACTIVITY/EXAM 引用可以形成 semantic target，COMMENT 引用不推测 postId。

## 5. Global inbox sequence 与并发保证

每用户 counter row 在 first-entry 事务加锁并保留至外层提交，sequence assignment 与 head advance 同事务；后续同用户只能在前事务提交后分配下一值，其他用户可独立推进。重复 enter 保留原 sequence；rollback 不前推 durable head。read writer 也先锁用户 counter 再锁 Notification，避免与 entry 使用相反锁序。

head/through 只表达用户 V 全局 head，category 不参与；page cursor 绑定 structured/category/boxCategory/isRead。AES-GCM 认证并隐藏内部格式，隔离用户/用途，24 小时过期。生产/多实例须配置稳定至少 32 字符 `NOTIFICATION_CURSOR_SECRET`；未配置用进程临时密钥，重启/不同实例旧游标会返回 invalid-cursor，需要 consumer 重建 baseline。

## 6. Summary、batch 与 read-through

| 契约语义 | 实现入口与持久化 | 本轮证据 |
|---|---|---|
| structured list、全局 head | CanonicalNotificationController → NotificationCenterServiceImpl.list → selectStructuredInbox/selectInboxHead | MVC 响应/筛选；真实 SQL 分类、分页、head 独立 |
| baseline/incremental summary | Controller → summary，RR 单快照 → indexed countUnreadThrough/countNewThrough/latest LIMIT 1 | 历史 unread 不重放，新增与 read 状态独立 |
| R GET，无 target existence 门禁 | Controller → get → selectOwned | external-only 本人恢复，其他用户 404，失效 target 冻结内容 |
| R batch 去重与原子 read | Controller → batchRead → counter/owned IDs FOR UPDATE → readAt CAS/readVersion | 单条、多条、重复、重复 read、混合 ownership 零写入 |
| V read-through，边界外 unread | Controller → readThrough → head purpose 验证/锁 → seq<=boundary CAS | 新消息/并发未提交 entry 保留 unread，重复幂等 |
| legacy 同一 read fact | 旧 reader/NotifyConverter 与同一 Mapper | 真实旧 list/filter、兼容 isRead、重复旧 mark read 保留 timestamp |

summary 不拼完整 list、不 N+1、不 join 业务对象；使用单表覆盖边界 count 与最新一行。实际大数据量计划/延迟、真实鉴权 filter 与完整 HTTP→部署 DB 联调尚未测量。read 响应 unreadCount 为提交后的 DB V unread，允许包含响应读取时已提交的新通知。

## 7. Snapshot 与 target

复用 content_payload 冻结有界 presentation、actor、subject、target。活动与公共事项更新在原保存链路读取旧节点、保存后读取新节点并马上冻结 time/location before/after；后续主体改名/改时间不改变旧消息。读取不查询 User/Post/Comment/Activity/PublicEvent。actor 可用时保存 nickname/avatar，评论 callback 保存昵称与 preview；无可靠 avatar 时省略，不复制业务实体。

POST_DETAIL/ACTIVITY_DETAIL/PUBLIC_EVENT_DETAIL 只来自可靠引用，COMMENT 来自已知 POST 上下文才带 postId/commentId；Notification 读取与目标 navigability 分离。目标 API 自己裁决 404/权限。测试验证保存方法捕获的 snapshot 以及 DB 中失效目标仍可恢复，未验证小程序展示。

## 8. Reminder identity 与 recipient/producer

type 8–11 新 identity：`reminder:{sourceType}:{sourceId}:{notifyType}:{actual frozen anchor instant}:user:{userId}`。offset、recipient reason、planId 不在 identity 内；真实时间变更形成新 occurrence。继续使用唯一 source_key，无第二套去重系统。缺 canonical anchor 拒绝执行并要求 reconcile；旧 type 1–7 保留 plan key 兼容。可精确匹配已冻结 startTime 的旧 type 8 plan-key 消息抑制再创建；其他缺可靠历史 anchor 的去重不声称已恢复，生产 preflight 后分类。

Activity 主动变更继续 subscriber ∪ eligible registration 按 userId 去重；开始提醒保持当前权威 contract 的 SUBSCRIBERS，截止提醒保持 SUBSCRIBERS_NOT_REGISTERED。PublicEvent 使用 subscriber。不借本轮把全部提醒接收者改成 union。

Like 真实 insert=1 才生产 POST_LIKED/COMMENT_LIKED；同 actor/subject/recipient 首次正向事实形成稳定 source key，重复 insert 或 unlike/re-like 不产生新 Notification。无历史扫描，无 MENTIONED。self 在 producer 与通用创建入口排除。事实读取由 Post/Comment 小型本模块 service 提供，避免把既有循环依赖 Service 注入 Interact。

## 9. External Delivery 复用

沿用 NotificationDelivery、v2 AsyncJob、Job ownership 和 UNKNOWN/no-blind-retry；IN_APP 不新增 Job，普通通知不额外包 Outbox。外部冻结 payload 加 originating notificationId，发送路径替换同名错误参数并保留目标参数。template/pagePath/小程序 appId 未就绪不规划外部任务，已有任务重查为 SKIPPED，Capability 同一门禁。type 19/20 复用旧 comment/reply 外部模板，like 不默认发送。微信失败不撤销 Notification；success 不等于 read。

真正模板、账号授权、provider 对 pagePath 的接受、设备打开与 consumer GET→read→navigate 未环境验证。Mini Program 微信订阅消息 adapter/一次性权限仍不可用；未改变既有 Preference 默认值或伪造 GRANTED。

## 10. Redis 与 observability

Redis unread 只缓存 V ∩ unread。命中先读低成本 durable `(head_seq,read_version)` 获取版本，再读该版本 cache；miss 用同一 DB 快照重建并 TTL 15 秒。创建/可见与 read 提交后按原 invalidation 风格删除 base key；版本隔离保证迟到旧版本重建不污染当前读取，旧版本 key 到期清理。summary 和 read 结果直接使用 MySQL，Redis 丢失不改变阅读事实。

复用 Micrometer registry，新增 creation、recipients、projection failure、unread rebuild、occurrence dedupe、Delivery result、recovery counters；有限 tags 与成本已登记 [Observability §6.1](observability.md#61-当前自定义-meters)。batch/through latency/error 用 http.server.requests；未部署时序 backend/告警。Redis 替身测试覆盖 miss、清空与迟到写入、重建写失败回 DB；真实 Redis 断连/负载/延迟测量 NOT RUN。

## 11. 测试结果

| 边界 | 状态与证据 |
|---|---|
| Java 17 main compile | PASS，实际重编译全部 721 main sources；有既存 MapStruct warnings，未改 unrelated mapping |
| focused/related 测试集合 | PASS，100 tests，0 failures/errors/skips，19 classes；见下方清单 |
| NotificationCenterDatabaseTest | PASS，11 tests，真实 MySQL/MyBatis/Spring proxy；含同用户未提交 entry 阻塞下一分配、其他用户独立、未来消息不被 through 消费、R/V、batch 原子/readAt、legacy/filter、snapshot、缓存版本与 reminder 实际 fan-out 去重 |
| NotificationUpgradeDatabaseTest | PASS，2 tests，V25 synthetic 历史 fixture 升级；read 确认时间、V/external-only、无虚构 Delivery、pending 当前 anchor 与旧/processing 保持 NULL |
| MVC | PASS，真实 converter/Advice + mock Service；wire shape/null/number/default legacy/非法输入。不表示真实 filter/登录/session 验收 |
| producer/Delivery/provider | PASS，真实 Service 方法 + 协作者/provider mocks；四类变更 before/after、Like、配置门禁、原 ID、UNKNOWN 等；不表示真实微信送达 |
| 常规 Maven 全仓测试编译 | BLOCKED，原有 InteractServiceImplTest 已使用过期构造器与已移除 RedisKeyConstant.viewCount；本轮新增依赖也需其跟随，未扩大任务去修旧 view 测试。已用仓库 focused script 隔离受影响测试 |
| 默认 shell Java 24 rebuild | BLOCKED，现有 Lombok TypeTag.UNKNOWN；使用已配置 Java 17 后 main/focused PASS，未升级依赖或改 pom |
| 生产、完整应用/Worker/Redis、消费者/设备、真实微信、性能/容量 | NOT RUN；需要目标环境验证 |

100-test 集合：ActivityReminderPolicyTest、ActivityServiceImplTest、ExamServiceImplTest、PublicEventReminderPolicyTest、CanonicalNotificationControllerTest、NotificationCenterMvcTest、NotificationDeliveryMapperSqlTest、ReminderReconcileServiceTest、NotifyServiceImplTest、NotificationDeliveryServiceImplTest、NotificationCenterDatabaseTest、NotificationStreamCursorCodecTest、ReminderOccurrenceTest、ActionableTimelineChangeDetectorTest、LikeNotificationProducerTest、CanonicalNotificationCapabilityTest、WxMpNoticeFieldRendererTest、WxMpNoticeTemplateRegistryTest、WxMpNoticeSendServiceTest。后续仅对新增 legacy/historical occurrence 断言重跑 DatabaseTest，未重复声称累加测试数。

## 12. Flyway replay / upgrade 验证

隔离 localhost:33987 MySQL 8.4.7；Flyway Docker 13.4.0 与本仓库 CI 选型一致，未访问共享/生产数据库。

- PASS：空库 `notification_contract_replay_final` 完整 migrate V1–V28，28 migrations 全部成功。
- PASS：`notification_contract_upgrade` 先到 V25，加载 synthetic fixture，再 V26–V28；2 项升级断言成功。
- PASS：上述两个 schema 各执行 Flyway validate，28 migrations 校验成功。
- PASS：升级 schema 执行只读 preflight，shadow/visibility mismatch 均为 0，无 head 小于可见最大 sequence 的行；刻意保留的旧代次/processing anchorless fixture 仍被分类，未假装恢复。

数据目录在忽略的 target 下保留；测试进程在验收后关闭。生产 Flyway history/checksum/权限/备份、unique 大数据成本、历史异常数量 NOT RUN。迁移存在与本地成功不表示生产已经执行。

## 13. 剩余上线/环境验收

Backend 本轮行为已实现并取得局部集成证据。上线前仍需：真实库 preflight 和静默写切换；明确无法恢复 anchor 的计划处置；配置持久 cursor secret 和合法 Notification page-path；部署后真实 auth/HTTP/DB/Worker/Redis 与执行计划/延迟检查；确认服务号模板/身份/权限以及设备点击恢复。Mini Program consumer 切换是后续 Phase D，本轮未改。legacy retirement/contract-drop 未实施。

## 14. Contract reconciliation（2026-09-30 已同步）

上轮提出的四项待同步语义已由最新 [Contract 实施交接](../../contracts/docs/coordination/notification-implementation-handoff.md#2026-09-30-消息中心扩展)、[消息中心约定](../../contracts/docs/coordination/notification-center.md)和 canonical notification paths/schema 收口。本次重新读取并核对当前 Backend，四项均一致，无 reconciliation 引起的实际业务实现 drift：

1. **Batch**：DTO/Service 先限制原始数组 1–50，Service 去重后校验 ownership，整批原子更新；重复 ID 不是 validation error，changedCount 只计唯一首次变化。对应 BatchReadNotificationsRequest、NotificationCenterServiceImpl.batchRead、lockOwnedIds/markOwnedRead。
2. **排序**：canonical legacy 使用 createdAt/id 降序，structured 使用 inboxSeq 降序；category 不改变 head/through。对应 CanonicalNotificationServiceImpl.list、selectCanonicalInbox、NotificationCenterServiceImpl.list、selectStructuredInbox。opaque 格式不外露。
3. **Like occurrence**：insert=1 才生产，source key 固定 actor+subject+recipient，唯一约束抑制重复/重新点赞；unlike 不修改已有 Notification，self 排除。对应 InteractServiceImpl.like/notifyLike/unlike 与 Notification 创建唯一键处理。
4. **历史 change fallback**：13/14/17/18 缺 changes 时 type=SYSTEM，保留原 id/readAt、可靠 category、冻结内容和可靠 target；不查询当前主体推导 before/after，不改变 legacy type。对应 NotificationSnapshotProjection.item/snapshot/category。

本次 validation：Contract `npm.cmd run validate` PASS（OpenAPI lint/bundle、26 tests）；Backend Java 17 Notification/相关生产者 focused validation PASS（19 classes、100 tests，0 failures/errors/skips，其中真实 MySQL NotificationCenterDatabaseTest 11 tests）。采用本轮原隔离 localhost:33987 schema，未连接业务数据库。未改业务实现、测试或迁移，只更新本节过期的待同步状态；生产、真实微信/Redis与消费端环境验收仍 NOT RUN。本次没有重跑 migration replay/upgrade，§12 是此前已执行证据。

Activity recipient 不是本轮新偏差：主动变更 union，start reminder subscribers-only，沿用已明确权威矩阵。审计附件并未作为第二套权威实现来源；本轮以当前工作区代码与最新 contract 逐边界复查，已有 Framework 全部复用。

## 15. 展示快照契约跟随（2026-10-07）

已跟随 [Contract 展示快照补充](../../contracts/docs/coordination/notification-center.md#展示快照补充--2026-10-07)：

- Presentation BO/VO 增加可选 subjectTitle/quote，MapStruct 按字段映射，复用 content_payload 保存；list/GET 继续使用同一冻结投影。未定义的字段在 wire 省略，不返回 null，也不回查当前实体补历史。
- 评论/回复保存一次 UserService 读取得到的昵称/可用头像；无头像仍可保留 actor。reply body 是新回复，quote 是父评论，subjectTitle 是关联事项标题，subject 仍为 COMMENT。原 context 不变。
- POST like 冻结标题与有界正文 quote；COMMENT like 冻结原评论 quote，并在可靠 POST 引用可用时由 Post 所有者服务取得所在帖子标题。保留原 context、source key/self/首次 occurrence 与渠道策略。
- 活动/公共事项变更、取消及活动提前结束在原创建链路冻结标题；四类新定时提醒从已冻结计划标题形成 subjectTitle。未解析标题/正文推测身份，未改已有 target/readAt/R/V/cursor。

本轮实际修改：NotificationCenterBO、NotificationItemVO；CommentAuditCallback、InteractServiceImpl、PostNotificationFactsService/Impl；ActivityServiceImpl、ExamServiceImpl、NotifyServiceImpl；对应 MVC/DB/Like/活动/公共事项测试，并新增 CommentNotificationSnapshotTest。同步本报告与 reminder-notification.md。没有改共享 Contract、SQL 或 migration，没有消费者修改、commit/push 或部署。

验证：PASS — Contract npm.cmd run validate（lint/bundle 与 32 tests）；PASS — Java 17 main 重编译 726 sources，Notification/受影响生产者 focused 20 classes 共 105 tests，0 failures/errors/skips，包含 12 项真实 MySQL 测试。新增断言覆盖回复三字段不同含义、创建后内容/昵称改变仍保持快照、头像与无头像、list/GET 相同内容、JSON 持久化、历史 SYSTEM 缺省、可选字段 wire 省略及旧 context 保留。测试使用原专用 localhost:33987 schema 与真实 Mapper/事务，业务实体/provider/Redis 部分为 mock；隔离 MySQL 验证后关闭。本轮无 schema 变化，未重复 migration replay/upgrade。生产/真实 HTTP/session/Redis/微信/设备/消费者展示 NOT RUN。
