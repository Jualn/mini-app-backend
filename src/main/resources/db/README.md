# 数据库演进入口

`V29__protect_effective_user_profiles.sql` 为资料写入增加单调 `profile_revision` 和头像/背景的服务端快照 key。历史行 revision 从 0 开始，历史资料与媒体引用不自动改写；快照 key 初始 NULL，不伪造已审核事实。所有新旧资料 writer 与 callback 必须切换同一应用版本，不能混跑旧乐观 writer。资料与审核历史分类使用 [Profile preflight](validation/user_profile_contract_preflight.sql)；运行与恢复限制见 [运维手册](../../../../docs/operations/runbook.md#13-用户资料安全检查与恢复)。

旧审核日志未保存候选与旧有效版本，不能只按 pending/rejected/unknown 状态自动恢复资料或认定当前值已审核通过；历史 NULL nickname、媒体引用和未知审核结果由资料 owner 按可追溯证据处理。旧媒体源对象不自动成为已审核快照。不能直接回滚至旧乐观 writer/callback JAR；恢复须停止资料写入、明确数据兼容方案，不自动撤销 DDL 或删除已绑定快照。

[migration/](migration/) 是可重建数据库结构的唯一可执行来源。数据演进的通用保证见 [Backend §3](../../../../standards/backend-engineering.md#3-persistence--data-evolution)，模块所有权和 SQL 写法见 [项目架构](../../../../docs/architecture.md)。
本文件只补充 Flyway 与当前仓库流程，不维护手工 DDL 快照、生产版本表或服务器参数建议。

## 编写与切换

- 使用 `V<递增整数>__<英文描述>.sql`。已进入永久环境的版本不可重写，修正时新增版本；不要通过修改 Entity 假装完成迁移。
- 变更时检查历史数据、约束/索引、Entity、Mapper/XML、字段投影、相关测试与应用切换顺序。
- 新增字段的默认值和历史回填须明确。大批量回填与 DDL 分开设计，说明批次、校验、重试与恢复边界。
- 需要混合版本兼容时先扩展、迁移，再收缩旧字段。恢复旧 JAR 不撤销已执行的数据库变化。
- 不在 SQL 中写真实凭据、生产数据、环境专用库名或 USE 语句。

## 执行与证据

- 应用不负责生产启动时自动迁移。生产迁移通过受控运维流程在核对目标、备份及取得相应授权后显式执行。
- 现有数据库接入 Flyway 前必须比对结构和历史，再确定 baseline；不把任何固定 baseline 版本套用于未知环境，不启用 baselineOnMigrate 绕过核对。
- 空库完整重放与现有库升级是两种验证。CI 的 Flyway 配置见 [workflow](../../../../.github/workflows/backend-ci-cd.yml)，命令边界见 [commands](../../../../governance/commands.md)。
- 当前数据库版本以目标环境的 flyway_schema_history 和实际结构核验为证，不能由仓库存在某个 V 文件推断已部署。
- [validation/](validation/) 是专项盘点/检查 SQL，不能替代版本迁移；执行前检查其内容及目标。

迁移新增或机制变化时按 [maintenance-map](../../../../governance/maintenance-map.md) 检查依赖，只更新受影响来源，不重建旧数据库设计文档。

当前异步实现的可执行结构由 `V17__add_async_processing_foundation.sql` 创建：`outbox_event`、`async_job`、局部消费者幂等表 `async_event_consumption` 以及 `notification.source_key` 唯一约束。其状态含义、保留期与恢复责任由 [Async Processing Architecture](../../../../docs/async-processing.md) 定义；目标环境是否已执行 V17 仍以 `flyway_schema_history` 和真实结构为准。

`V18__backfill_pending_notification_jobs.sql` 只把 `notify_plan.status=0` 的 durable schedule 幂等回填为 fan-out Job；已有 `content_audit_log.final_result=0` 不能仅凭状态判断是否应重新提交外部审核，必须先运行 `validation/async_migration_preflight.sql` 分类并人工处理 unknown。

`V19__add_async_dead_message_evidence.sql` 增加 `async_dead_message`，持久保存 Redis Stream terminal/poison delivery 的记录身份、关联 ID、受控错误分类和投递次数，不保存原始 payload。它是定位和受控 replay 的运行证据，不改变 Outbox 已完成发布的事实。

`V20__expand_reminder_notification_delivery.sql` 是 Reminder/Notification/Delivery 的 expand migration：以 nullable 新列兼容历史 `notify_plan`/broadcast，为新 Notification 增加渠道无关冻结快照，并创建带渠道唯一约束和明确终态的 `notification_delivery`。它不从旧 plan 或 Job 状态推断外部送达，也不自动生成历史 Delivery。目标环境升级前先运行只读 `validation/reminder_delivery_preflight.sql` 分类，切换并 drain v1 Job 后才可设计后续 backfill/contract migration。

`V21__add_notification_preferences_and_inbox_generation.sql` 为 canonical 通知接口增加 Category × Channel sparse override，并把 Notification 明确分为 `LEGACY` 与后续 producer 才可写入的 `CANONICAL` 世代。默认值保持尚未切换的旧 writer 自动归入明确旧集合；迁移不制造 `IN_APP` Delivery，也不切换 producer 或旧设置 owner。canonical reader 的共同集合为明确旧集合与具有已生效 `IN_APP` Delivery 的新集合并集。D1 已明确不追溯历史消息；目标环境切换仍受 contracts 协调文档的 D2 preflight 和 D3 逐用户 owner 切换门槛约束。

`V22__add_notification_preference_owner.sql` 增加偏好来源 `USER_OVERRIDE/LEGACY_MIGRATION` 和逐用户 canonical owner 标记。无 owner 的历史用户不能由 canonical GET/PATCH 伪装为默认值；旧 activity/exam 有效布尔值须在锁定 `user_setting` 后原子导入服务号组合并切 owner，已有偏好冲突时停止而非覆盖。新用户创建时直接建立 canonical owner。V22 只提供结构，不自动批量迁移历史用户；历史批量回填由 V24 承接，两者的结构与数据效果不能混为一谈。

`V23__enforce_unique_official_account_identity.sql` 用唯一键保护服务号 `mp_openid` 不能关联多个用户；应用层对重复同一关联保持幂等，对替换和争用一律拒绝，不自动合并用户。升级前运行只读 `validation/wechat_identity_preflight.sql`；重复或空身份必须由身份 owner 明确处理，不能在迁移中静默选择任一用户。实际执行版本按目标库 migration history 核对，部署确认见运维手册。

`V24__backfill_notification_preference_owners.sql` 在旧设置写入已静默的发布边界内迁移历史用户：把 activity/exam 的有效布尔值仅导入对应的服务号组合，来源记为 `LEGACY_MIGRATION`，再在同一事务切换为 canonical owner；缺少 `user_setting` 时采用已核实的旧 DDL 缺省 `false`。已有 canonical owner 保持不变；存在“已有偏好但没有 owner”的冲突或非法旧布尔值时迁移直接失败，不覆盖或猜测。该迁移不改变 IN_APP/小程序偏好，不表示微信授权，也不替代 D2 producer/reader 切换证据。

`V25__freeze_reminder_subject_delivery_fields.sql` 为 ReminderPlan 增加渠道无关的开始时间与地点快照，使 Notification fan-out 能在不回查可变 Activity/Timeline 数据的前提下生成外部投递内容。迁移只对可由 `timeline_id` 明确关联的现有 `notify_type=8` 计划补值；无法关联的历史计划保持 NULL，并继续只生成站内通知，不制造服务号 Delivery。

Timeline 的业务 semantic 继续使用既有 `timeline.node_type VARCHAR(32)`；POINT/RANGE/DATE/TEXT 结构由 precision/time 字段表达，label 仅用于展示。正式启用 ReminderPolicy 前运行只读 `validation/timeline_semantic_preflight.sql`，分类未知/重复/非精确/缺失 START 与 orphan 节点。不得按 label、排序或首个 EXACT 节点自动回填 semantic；当前没有新增 Timeline schema migration。

`V26__expand_notification_read_and_inbox_stream.sql` 增加 canonical `read_at`、nullable `inbox_seq`、逐用户 counter 与未读/类别分页索引。`V27__backfill_notification_read_and_inbox_stream.sql` 在旧 Notification/read/Delivery 写入静默后执行：历史已读采用迁移确认时间；只对明确 LEGACY 或已 DELIVERED IN_APP 的行按 createdAt/id 建立一次性历史 baseline，external-only 保持无 sequence，不制造 Delivery。新写入的顺序由用户 counter 锁到提交保障。

`V28__version_unread_projection_and_complete_pending_anchors.sql` 增加 durable read_version 和历史 occurrence 查找索引；仅对 pending 当前代次、可靠 Timeline 节点关联的 type 9–11 补冻结 anchor。其他缺 anchor 的 canonical 计划需由业务 reconcile/运维分类，不能从 sendAt 或当前主体伪造历史。升级前运行只读 [notification_contract_preflight.sql](validation/notification_contract_preflight.sql)；该文件前半用于 V25，后半用于 V28 验收。

切换必须停止旧应用的通知创建、read、fan-out 与 Delivery 写入，完成 V26–V28 后启动新应用，再开放消费者。不能让旧 `is_read` writer 与新 `read_at` writer 并存。保留 is_read 列仅提供结构兼容，不保证旧 JAR 可安全回滚阅读事实；回滚需停写并制定 shadow/readAt 数据兼容方案，不能直接恢复旧 JAR 即称已恢复。MySQL DDL 非全事务回滚；备份、失败停止与恢复按既有发布流程执行。版本切换与恢复结果记录在对应发布证据中。
