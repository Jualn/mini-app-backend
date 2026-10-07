# Async Processing Architecture

本文是本项目异步处理架构的唯一权威来源，定义 Outbox、Durable Job、Message、Redis Streams、Worker、retry、reclaim、dedup、dead 与 recovery 的职责和组合方式。

本文建立在 [Observability Baseline](observability.md) 与 [Reliability Baseline](reliability.md) 之上，不重新定义 trace、日志、指标基数、错误分类、Unknown Outcome、幂等、补偿或 reconciliation。Java / Spring 实现机制仍由工程标准负责，数据库可执行事实仍以 [Flyway migration](../src/main/resources/db/migration/) 为准。

当前目标是 Java 17、Spring Boot 3.5.13、MySQL 8.4、Redis、单体、Linux 单服务器、小规模和有限资源。本文不是通用消息平台设计，不引入 Kafka、RabbitMQ、Quartz、JobRunr、ShedLock、工作流引擎或 exactly-once 承诺。

> 状态：仓库已完成 V17/V18、producer/consumer 切换和旧代码删除，并通过本地真实 MySQL/Redis 集成验证；目标环境迁移、部署、重启与灾难恢复演练仍未证明。第 16 节保留实施批次和环境验收边界。

## 1. 核心结论

1. MySQL 是重要异步意图、执行状态和恢复证据的 durable truth。
2. `outbox_event` 表示“业务事实已经提交，必须可靠传播”；`async_job` 表示“系统必须执行一项工作”。生命周期不同，不合并为万能 `async_task`。
3. Redis Streams 只用于事件 transport、唤醒和 Consumer Group 交付；Stream entry 不是业务事实，也不是 Job 状态。
4. 当前所有 Durable Job 默认走 `MySQL async_job -> direct claim worker`。当前没有 Job 必须经过 Redis Streams。
5. Transactional Event 默认走 `business transaction + outbox_event -> publisher -> Redis Stream -> typed handler`。
6. Event handler 可以在本地事务中产生业务记录或创建 Job；不要求每个 Outbox 先转换成 Job。
7. Redis PEL reclaim 只恢复“已投递但未 ACK 的 transport ownership”；业务 retry 由 MySQL Job 或 Outbox 的 attempt / nextAttemptAt 管理。
8. delivery 是 at-least-once；XADD、DB commit、ACK 之间都可能产生重复。每个 Handler 必须声明自然幂等、唯一约束、条件状态转换或局部 processed-message 记录之一。
9. 外部写 timeout 仍是 Unknown Outcome。没有 provider idempotency / 状态查询时，不允许把 blind retry 包装成 recovery。
10. 普通同步 CRUD、SSE 连接内 AI 建议生成和可重建的周期扫描不自动进入 Durable Job。

## 2. 术语与职责

### 2.1 Transactional Event / Outbox

Outbox 记录一个已经提交的本地业务事实及其待传播事件。业务状态和 outbox row 必须在同一个 MySQL 事务提交。

```text
business state change
+ outbox_event(PENDING, stable messageId)
        same local transaction
             ↓
outbox publisher -> Redis Stream -> event handler
```

Outbox 完成条件是事件进入 transport；它不表示每个下游业务效果已完成。需要执行生命周期的下游工作由 Handler 创建 Job。

### 2.2 Durable Job

Job 是未来必须执行的 command，包括定时执行、外部调用、分批 fan-out 和需要跨重启恢复的后台工作。MySQL row 拥有 schedule、claim、attempt、lease、retry、terminal 和 manual recovery 状态。

```text
async_job(PENDING, next_run_at)
       ↓ due query / claim
async_job(RUNNING, lease)
       ↓
handler -> SUCCEEDED | PENDING(retry) | DEAD
```

“尚未到时间”和“当前可执行”由同一个 `PENDING` 状态加 `next_run_at > NOW()` / `<= NOW()` 表达；不为可推导条件额外保存 `SCHEDULED`、`READY`、`RETRY` 三个状态。

### 2.3 Transport Message

Message 是版本化 wire contract，用于传输一个已存在的事件。Redis entry ID 是 transport 位置；envelope 中的 `messageId` 是逻辑消息身份。Message 不承担业务状态、长期 retry schedule 或用户查询进度。

### 2.4 Ephemeral / Reconstructable Work

以下工作可以留在进程内 executor 或周期扫描：

- 客户端连接断开即可取消、用户可重新开始的 SSE / AI 建议生成；
- 丢失一次触发不会丢失事实、下一轮可从 MySQL / Redis dirty set 重建的维护扫描；
- cache warmup 等只影响性能的工作。

“可重建”必须有真实 durable source 和再次扫描入口；仅仅希望下一次能恢复不算可重建。

## 3. Phase 1 — 当前异步场景盘点

以下是迁移设计开始时对旧实现的审计快照，用于保留迁移依据；不等于当前代码或生产部署证明。

| 场景 | 创建者与当前事实 | 时效 / 外部效果 | 当前存储与消费 | 必须性与当前缺口 |
|---|---|---|---|---|
| 帖子、评论、用户资料文本/媒体审核 | 业务写入后创建 `content_audit_log` 预占记录，再由 `QueueProducer` 投递 `audit.text` / `audit.media.batch` | 可短暂延迟；调用微信审核；媒体审核还有异步 provider callback | Redis List `QUEUE_MAIN`，`BRPOP` 后线程池执行 Handler | 必须跨重启。after-commit 到 Redis 存在永久丢失窗；pop 后 crash 丢失；外部提交 timeout 可能 Unknown Outcome |
| 单媒体审核与批量媒体审核 | Audit Handler 调用 `AuditService`；DB 中已有 audit log ID | 必须；微信外部副作用；回调可能迟到或缺失 | Redis List + 微信回调；trace binding 在 Redis，DB audit log 可部分回源 | 需要 Durable Job；回调更新需条件幂等；无 provider 查询能力时不能盲重发 unknown submission |
| 审核通过后的评论/回复通知 | 审核 callback 构造 `NotifyPayload` 并入队 | 可延迟；站内通知必须，微信推送是附加外部效果 | Redis List -> `NotifyHandler` -> `notification` insert + Redis unread + 微信调用 | 站内通知无 dedupe；DB transaction 中混入 Redis/微信；commit/ACK 间重复会制造重复通知 |
| 用户资料审核结果通知 | profile audit callback 构造 `NotifyPayload` | 同上 | 同上 | 同上 |
| 活动/公共事项提醒计划 | 业务事务内写 `notify_plan(status=0, send_at)`，afterCommit 写 Redis ZSet；启动时扫描 pending 重投 | 必须按计划执行，允许分钟级延迟；fan-out 后可能微信推送 | MySQL `notify_plan` + Redis ZSet/body key；ZSet consumer 先 remove 再处理 | `notify_plan` 是部分 durable source，但两 Redis key 可部分成功、claim 后 crash 会丢；status=1 不能表达 partial fan-out |
| 通知计划 fan-out | `broadcast.plan` 到期后分页查询用户，为每人向 Redis List 投 `NotifyPayload`，最后把 plan 标为已发 | 必须；允许批处理延迟 | Redis delay -> List -> per-user handler | 中途 crash 会部分发布；重跑会重复；无 durable cursor / recipient dedupe |
| 个人站内通知 | `NotifyHandler` 插入 `notification`，修改 unread Redis counter | 用户可查询的业务事实 | MySQL notification + Redis derived counter | `notification` 是业务数据，不是 Message；需要稳定来源 dedupe。计数应由 DB 正确性主导、提交后更新或失效缓存 |
| 微信服务号通知 delivery | 当前与 notification insert 同一方法内调用，异常被吞掉 | 外部写；允许按产品定义降级，但 outcome 可能 unknown | 无独立持久状态 | 应与 inbox record 分离为 delivery Job；retry 必须基于 provider 能力，不能默认重发 timeout |
| 互动计数每 5 分钟回写 | `InteractCountSyncTask` 扫 Redis dirty/count 并更新 DB | 允许延迟；无第三方调用 | Spring `@Scheduled` 直接扫描 | 属于可重建/周期 repair，不为每个计数建 Job；仍需单独解决 dirty marker 与回写 crash/late write 的现有可靠性问题 |
| 上传记录清理 | `MediaUploadCleanupTask` 每 10 分钟扫描过期 `media_upload_record` 并清理对象 | 允许延迟；COS delete 是外部副作用 | MySQL durable upload state + `@Scheduled` batch scan | 扫描触发可丢，事实可重建。保留 scheduler；每个清理状态和未知 COS 删除结果继续服从现有 media lifecycle / Reliability |
| 文档导入与 AI extraction | HTTP 上传后提交 `aiTaskExecutor`，结果只经 SSE 返回；连接关闭/timeout 即取消 | 与连接同生命周期；用户可重新上传；不写业务事实 | 进程内 Future + SseEmitter | Ephemeral。不得伪装成 durable；若以后支持后台导入历史/状态查询，再单独建 Job |
| 微信媒体审核 callback | provider 调用 HTTP callback，按 traceId 更新 DB 并执行业务 callback | provider 驱动；可能重复/迟到/缺失 | HTTP + Redis binding，DB audit log 回源 | 不是 Redis consumer。DB 条件更新承担幂等；pending 超龄扫描是 reconciliation candidate，不是普通 retry |
| 活动报名成功后的副作用 | 当前 enrollment service 只持久化报名/订阅及通知开关，未发现“报名成功即异步通知”的已实现链路 | 当前无必须外部效果 | MySQL activity enrollment | 不凭示例虚构事件。以后若产品要求可靠副作用，在报名事务内创建 `activity.enrolled` Outbox，而不是 afterCommit 直接入 Redis |
| 普通同步微信/COS/token 调用 | 请求线程内完成 | 用户等待，语义由具体接口决定 | 同步 WebClient / SDK | 不因存在远程调用就自动异步；仅在可延迟且需隔离/恢复时迁移到 Job |
| Cache warmup / 普通派生缓存失效 | 当前未发现必须持久执行的独立任务 | 只影响性能 | Redis / 请求线程 | Ephemeral 或提交后 best-effort；不进入 Outbox / Job，除非产品正确性依赖它 |

### 3.1 当前实现与迁移结果（2026-09-23）

| 旧组件 / 场景 | 当前实现 | 代码迁移状态 |
|---|---|---|
| `QueueMessage<T>`、自动类名 topic、Redis List Producer、`BRPOP` Consumer、dead List | `MessageEnvelope(topic, schemaVersion, messageId, createdAt, operationId?, payload)` + 显式 `EventHandler` registry | 已删除；生产代码引用为零 |
| `audit.text` / `audit.media(.batch)` List 消息 | audit reservation 与 `async_job` 同事务；typed Job Handler 直接 claim | 已切换 |
| 审核结果直接调用 callback | 审核结果条件更新与 `audit.completed` Outbox 同事务；Stream Handler 以 `(consumer_name,message_id)` 消费记录和 callback effect 同事务提交，成功后 ACK | 已切换；首条 Outbox -> Stream 纵向链路 |
| `broadcast.plan` 与 reminder ZSet/body | `notify_plan` 与 `notification.plan.fanout` Job 同事务；取消使用 Job CAS | 已切换 |
| `NotifyHandler` | `notification.source_key` UNIQUE；站内通知与 `notification.wechat-deliver` Job 同事务 | 已切换 |
| Stream poison / unsupported delivery | `async_dead_message` 持久 terminal evidence，成功落库后 ACK；不保存 payload | 已切换（V19） |
| 微信 delivery 在 inbox 事务内远程调用 | 独立 Durable Job；无法判定的远程写转 DEAD / manual recovery，不盲重发 | 已切换 |
| Redis `consumerExecutor` | `jobExecutor` 与 `eventConsumerExecutor` 分责、均有界 | 旧 executor 已删除 |
| AI 文档导入、计数回写、媒体清理 | 仍分别为连接内 ephemeral 或可重建 bounded scan | 按分类保留，不迁成 Job |

旧 List/ZSet 已由用户确认排空，旧 producer、consumer、startup rebuild、payload、key 常量和 List queue API 已删除。目标 Redis 环境中的物理旧 key 删除属于部署操作，本次未对未指明环境执行 `DEL`。生产数据库是否已执行 V17、线上 JAR 是否已切换仍须以环境证据确认。

## 4. Phase 2 — 目标分类

| 业务场景 | 默认分类 | durable identity | 执行路径 | 幂等/恢复基准 |
|---|---|---|---|---|
| 业务提交后必须启动审核 | Durable Job，和 audit reservation 同事务创建 | `jobId` + `dedupeKey=auditLogId:kind` | MySQL direct claim | audit log 条件状态 + job unique key；外部 unknown 单独 reconcile/manual |
| 评论审核通过这一事实 | Transactional Event（仅当通知等下游必须解耦） | stable `messageId` | Outbox -> Stream | callback 状态转换 / outbox 唯一来源键 |
| 由已知接收者创建站内通知 | Handler 本地事务中的业务 record；必要时由 Event 驱动 | notification source/dedupe key | event handler -> notification insert | DB UNIQUE，不靠 processed-message 全局表 |
| 微信通知 delivery | Durable Job | `jobId` + notification/channel dedupe key | MySQL direct claim | provider idempotency/status；无能力时 timeout 转 recovery candidate，不 blind retry |
| 活动/公共事项提醒 | `notify_plan` 是业务 schedule，`async_job` 是 execution | `planId` + fanout jobId | MySQL direct claim | plan version/status + recipient notification unique key；可安全从头重扫 |
| 系统广播 fan-out | Durable Job | planId/jobId | MySQL direct claim，分页短批次 | 每收件人 dedupe；批次进度可选优化，不是正确性唯一保护 |
| audit result / comment / reply notification | Transactional Event 或同事务直接写 notification + delivery Job | messageId 或业务 source key | Outbox -> Stream，或同事务 DB records | 选择最短完整链路，不为“统一”多绕一层 |
| 互动计数同步、上传清理 | Reconstructable scheduled scan | 扫描 run 的 trace；无需逐条 jobId | Spring Scheduling -> bounded batch | durable source/dirty marker、条件更新；下次扫描恢复 |
| SSE AI extraction | Ephemeral Async | importId 仅本次连接相关，不是 durable jobId | in-process executor | 断线取消、用户显式重试 |

## 5. Phase 3 — 有价值的方案比较

### 5.1 MySQL Job direct claim（选择为默认）

```text
MySQL async_job -> SELECT/CLAIM -> Worker -> MySQL terminal/retry state
```

优点：durable truth 与 execution lifecycle 在一处；没有 DB->Redis publish、Stream 丢失、重复 transport 和 ACK 边界；低频 reminder/audit polling 对当前单服务器可控；恢复查询和后台管理直接基于表。

代价：需要小批量 DB polling、正确索引、lease recovery；不能用阻塞 read 实现即时唤醒。当前以约 1 秒 poll 和最多 2 个 worker 换取简单、可恢复的语义是合理取舍。

### 5.2 MySQL Job -> Redis Stream（当前不选为默认）

优点：低延迟 wake-up、Consumer Group 水平分发、可减少空轮询。

代价：Job dispatcher 自身又需要 publish lifecycle；XADD Unknown Outcome 会重复；DB completion 和 XACK 仍有双写边界；Redis 全丢后要从 MySQL 补投。当前任务量和单实例没有证明这些成本值得承担。

仅当未来出现持续高吞吐、独立 worker 进程或 DB due polling 已被测量为瓶颈时，才允许将特定 job type 加一条 Stream wake-up 路径。即使如此，Job row 仍是事实，Stream 只携带 jobId；Worker 必须回 MySQL claim，重复 wake-up 无效果。

### 5.3 为什么 Transactional Event 仍使用 Streams

事件传播天然是一对 transport/handler 边界，Stream 的 Consumer Group、pending 和 reclaim 能替代当前 List pop-loss。Outbox row 保留可重放事实；Stream 提供快速投递。它不会被用来承载定时 schedule 或业务 retry。

### 5.4 Outbox 不必须经过 Job

默认链路是：

```text
Outbox -> Stream -> Event Handler
Job -> direct claim Worker
```

当 Event Handler 识别出未来必须执行的 command 时，在 Handler 本地事务创建 Job。强制 `Outbox -> Job -> Message` 会把传播状态和执行状态混合，并增加无价值写入。

## 6. Phase 4 — Target Architecture

```text
HTTP / callback / domain service
        │
        ├─ synchronous result (ordinary CRUD)
        │
        ├─ same MySQL transaction ─ business fact + outbox_event
        │                                  │
        │                           Outbox Publisher
        │                                  │ XADD
        │                                  ▼
        │                         Redis Stream / group
        │                                  │
        │                         Stream Consumer
        │                                  │
        │                         Typed Event Handler
        │                                  │
        │                    DB fact and/or async_job
        │
        └─ same MySQL transaction ─ durable intent + async_job
                                           │
                                    Job Claim Worker
                                           │
                              Typed Job Handler / Service
```

职责边界：

- domain service 决定业务事实、Outbox event 或 Job 是否必须创建；
- outbox publisher 只负责 claim/publish/mark，不执行业务 handler；
- Stream consumer 负责 read/reclaim、envelope validation、context 和 ACK；
- dispatcher 以显式 `topic + schemaVersion` 查找 payload DTO / handler；
- event handler 拥有本地事务和幂等机制；
- job worker 负责 claim/lease/outcome，typed job handler 负责业务执行；
- retry classifier 只能由具体 handler/boundary 给出 `retryable | permanent | unknown`，不按所有 Exception 统一判断。

### 6.1 最小包结构

保持当前单模块，不建立独立 framework module：

```text
infrastructure/async/
├─ outbox/        # entity/mapper/publisher
├─ job/           # entity/mapper/claimer/worker
├─ message/       # envelope/codec/dispatcher
└─ stream/        # Redis Streams adapter and lifecycle
```

业务 payload、event handler 和 job handler 留在所属模块，例如 `module/audit/async`、`module/notify/async`。不要创建 `AsyncManager`、`TaskManager` 或泛型万能 engine。

### 6.2 Framework 决策

- 使用 Spring Data Redis Streams 和现有 Spring Scheduling / MyBatis。
- 不引入 Quartz / JobRunr：当前 cron/due job 数量和单实例不足以抵消新框架的表、线程、升级与运维成本。
- 不引入 ShedLock：Job claim 已提供并发安全；可重建 scheduler 用幂等扫描，当前单实例也没有额外锁需求。
- 不引入 Kafka / RabbitMQ：没有多服务事件流、高吞吐、broker retention/replay 或复杂 routing 证据。

## 7. Phase 5 — Schema 与状态机

以下结构已经由 V17 落为可执行 migration；本节保留字段与状态设计说明。当前 checkout 已完成临时库 migration replay 和关键访问路径 `EXPLAIN`，目标环境 upgrade 仍须独立验证。

### 7.1 `outbox_event`

```sql
CREATE TABLE outbox_event (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  message_id      CHAR(32)        NOT NULL,
  topic           VARCHAR(96)     NOT NULL,
  schema_version  SMALLINT UNSIGNED NOT NULL,
  operation_id    CHAR(32)        NULL,
  aggregate_type  VARCHAR(64)     NULL,
  aggregate_id    VARCHAR(64)     NULL,
  payload         JSON            NOT NULL,
  status          VARCHAR(16)     NOT NULL,
  attempt         INT UNSIGNED    NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(3)     NOT NULL,
  lease_owner     VARCHAR(96)     NULL,
  lease_until     DATETIME(3)     NULL,
  last_error_category VARCHAR(24) NULL,
  last_error      VARCHAR(1024)   NULL,
  created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  published_at    DATETIME(3)     NULL,
  dead_at         DATETIME(3)     NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_outbox_message_id (message_id),
  KEY idx_outbox_due (status, next_attempt_at, id),
  KEY idx_outbox_lease (status, lease_until, id),
  KEY idx_outbox_created (created_at)
);
```

- DB 主键沿用项目 `BIGINT UNSIGNED AUTO_INCREMENT`；`message_id` 独立使用 Observability 已采用的 32 位小写 hex 形式，不把所有主键改为 UUID。
- JSON 只用于版本化 payload；不在 JSON 字段上做业务检索。
- 状态只有 `PENDING | PUBLISHED | DEAD`。claim 时仍为 PENDING，由 lease 防止并发 publisher；不增加容易永久卡住的 `PUBLISHING`。
- publisher 成功 XADD 后以 `id + lease_owner + status=PENDING` 条件更新为 PUBLISHED。
- transient publish 失败回到 PENDING 并计算 next attempt；明确永久 schema/topic 错误或耗尽预算进入 DEAD。

publisher 每轮在短事务中用 `FOR UPDATE SKIP LOCKED` 选择 `status=PENDING AND next_attempt_at<=NOW(3)` 且 lease 为空/过期的最多 20 行，设置 owner、lease 并递增 attempt 后提交；逐条 XADD 必须在该事务之外。已知成功后条件标记 PUBLISHED；已知失败持久化分类/backoff；响应丢失则保留 PENDING，等待 lease 过期后以相同 messageId 重发。任何路径都不存在永久 `PUBLISHING`。

```text
PENDING --XADD known success--> PUBLISHED
   │
   ├--retryable / unknown--> PENDING(nextAttemptAt, same messageId)
   └--permanent / exhausted--> DEAD
```

### 7.2 `async_job`

```sql
CREATE TABLE async_job (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  job_type        VARCHAR(96)     NOT NULL,
  schema_version  SMALLINT UNSIGNED NOT NULL,
  operation_id    CHAR(32)        NULL,
  dedupe_key      VARCHAR(160)    NULL,
  subject_type    VARCHAR(64)     NULL,
  subject_id      VARCHAR(64)     NULL,
  payload         JSON            NOT NULL,
  status          VARCHAR(16)     NOT NULL,
  next_run_at     DATETIME(3)     NOT NULL,
  attempt         INT UNSIGNED    NOT NULL DEFAULT 0,
  max_attempts    INT UNSIGNED    NOT NULL,
  lease_owner     VARCHAR(96)     NULL,
  lease_until     DATETIME(3)     NULL,
  last_error_category VARCHAR(24) NULL,
  last_error      VARCHAR(1024)   NULL,
  created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                  ON UPDATE CURRENT_TIMESTAMP(3),
  started_at      DATETIME(3)     NULL,
  finished_at     DATETIME(3)     NULL,
  dead_at         DATETIME(3)     NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_job_dedupe (job_type, dedupe_key),
  KEY idx_job_due (status, next_run_at, id),
  KEY idx_job_lease (status, lease_until, id),
  KEY idx_job_finished (status, finished_at)
);
```

MySQL 允许 unique key 中多个 NULL；不需要幂等创建的 Job 保持 `dedupe_key=NULL`。需要幂等创建的 job type 必须定义稳定、非敏感、长度有界的 key。

```text
PENDING -> RUNNING -> SUCCEEDED
   │          │
   │          ├-> PENDING(nextRunAt)  retryable / lost lease
   │          └-> DEAD                permanent / exhausted
   └----------------> CANCELLED       only before claim or by handler-defined CAS
```

- `PENDING + next_run_at > now`：尚未到时间；`<= now`：ready。
- `attempt` 在成功 claim、真正开始一次 execution attempt 时递增。Worker crash 也消耗 attempt，并记录 `worker_lost` 的稳定错误代码/分类，避免无限 crash loop。
- 默认 lease 120 秒，只适用于目标执行时间明显小于 lease 的短任务。长 fan-out 的短事务不能代替整个 execution 的租约保护，按 §7.2.1 在批次边界条件续租；不建设独立 heartbeat 服务。
- lease recovery 每 30 秒小批量扫描 `RUNNING AND lease_until < NOW(3)`，CAS 回 PENDING 或在 attempt 耗尽时 DEAD。旧 owner 即使迟到也必须用 `lease_owner` 条件完成更新，防止覆盖新 owner。
- 当前 2 vCPU / 2 GiB 基线使用 claim batch 2、worker concurrency 2；真实吞吐与连接池争用验证前不提高，不能使用无界线程池。

claim 在一个短事务内执行：

```sql
SELECT id
FROM async_job
WHERE status = 'PENDING' AND next_run_at <= NOW(3)
ORDER BY next_run_at, id
LIMIT ?
FOR UPDATE SKIP LOCKED;

-- 对选中的 id 更新 RUNNING、attempt=attempt+1、lease owner/until 后提交；
-- 业务 handler 在事务外逐项执行。
```

### 7.2.1 业务执行所有权与通知收尾

以下为通知执行的目标协议，不能由既有 Job 完成 CAS 推断已实现：

- 每次成功 claim 的 token 必须区分不同 execution attempt，即使同一进程再次 claim 同一 Job 也不能复用旧 token。业务写入前检查 `RUNNING + 当前 token + 未过期 lease`；时间判断使用数据库时钟。Job 完成、退避、续租都使用同一条件，不允许过期 owner 自行复活。
- 本地 fan-out 每批最多 100 人，批次写事务内先锁定并验证 Job ownership，再写 Notification/Delivery/下游 Job；lease recovery 使用同一 Job 行协调。单批事务必须有界且不做网络调用，固定锁顺序，禁止为了整个 fan-out 持有长事务。主体取消沿业务文档的一批竞态保证，不增加全局取消屏障。
- 当前采用同一 Job 循环短批次、批次边界条件续租。剩余 lease 必须大于单批预算与安全余量才开始下一批；否则先续租，失败立即停止。续租不能绕过 job type 的总执行预算；实施时登记 per-batch/per-attempt/total budget 和 maxAttempts，并证明单批低于租约。超过预算的已知安全本地工作交回持久重试；反复耗尽不能无限循环，进入 DEAD/人工检查。当前不增加持久 cursor；若目标规模不能在预算内推进到尾部，则不得声称该规模已支持，另行评估分段。
- provider 调用前，在短事务中同时验证 Job ownership 与 Delivery PENDING，并将 Delivery 条件转 PROCESSING、增加 provider attempt 后提交。网络调用在事务外，超时预算小于剩余 lease；不同 Worker 不得同时把同一 Delivery 认领为新的 provider attempt。PROCESSING 是调用可能已开始的保守证据，即使 crash 实际发生在发包前也不能盲重发。
- 返回结果更新必须验证当前执行 token/attempt 与预期 Delivery 状态。失去 ownership 的迟到结果不得覆盖新终局，保留有限诊断证据，由受控 reconciliation 决定是否采纳；这牺牲自动确认率以避免重复外部写，不承诺 exactly-once。
- 正常结果落库与 Job 收尾尽量同一本地事务；若框架分开提交，必须先提交业务结果，再完成 Job，并由下一次执行读取结果 no-op。不能先 SUCCEEDED 再尝试保存 Delivery。
- exhausted/permanent、lease recovery 最后一次 crash 均须触发通知类型自己的 terminal 分类，不依赖“还有下一次 Handler”。有界 repair 扫描处理 DEAD Job 对应的 PENDING/PROCESSING Delivery：有可能开始远程调用则 UNKNOWN，有证据未开始/明确失败则 FAILED。更新失败后记录仍待修复，恢复 DB 后继续；不得只改 Job 就丢弃结果责任。
- fan-out DEAD 保留部分效果和未完成计划，由运维组合状态表示待处理；不自动标为 COMPLETED。显式 retry 重新验证有效期，复用原身份；失效计划关闭剩余工作。Delivery 的结果表与人工恢复规则见 [通知 §7.5](reminder-notification.md#75-终止与人工结论)。

这要求实现具体 notify terminal/repair 路径，不要求为所有 Job 建通用工作流引擎。repair 只是从 durable state 重建并收敛结果的有界扫描，绝不对 UNKNOWN 再次发送 provider 请求。

### 7.3 `processed_message` 不是默认表

优先使用业务 UNIQUE、条件状态转换、业务记录本身的唯一身份。只有无法自然表达时，某个 consumer 局部使用 `(consumer_name, message_id)` processed record，并与业务效果同事务写入。其 retention 必须覆盖 Outbox replay window。

### 7.4 通知领域的配套数据约束

ReminderPlan、Notification 和 NotificationDelivery 的业务语义由 [Reminder、Notification 与 Delivery 设计](reminder-notification.md) 维护；本文只规定其执行状态如何落到 Job。

`notification` 是每用户稳定内容事实；是否进入收件箱由 `IN_APP` Channel Delivery 决定。V17 已增加稳定的 `source_key` 并建立 UNIQUE：

```text
activity reminder: plan:{planId}:user:{userId}
comment notice: comment:{commentId}:receiver:{userId}:approved
audit result: audit:{auditLogId}:receiver:{userId}:result
```

不要把完整消息 JSON 或 title/content 当 dedupe key。V20 已新增持久 `notification_delivery`；远程 Channel Job 的 dedupe key 使用 `delivery:{deliveryId}`，payload 只携带 deliveryId。`IN_APP` 在本地事务内完成，不创建远程 Job；v1 `notification:{notificationId}:wechat-mp` 只作为兼容 reader/drain 边界。

`async_job.attempt` 只记录一次 Worker execution attempt，不等于 provider delivery attempt。后者由 NotificationDelivery 在即将发起远程调用时独立增加；预检、解析或 claim 失败可以消耗 Job attempt，但不能增加 delivery attempt。

Reminder cancel 不能只依赖取消 PENDING Job。fan-out Handler 在入口和每个有界批次开始前都必须重读计划/主体有效性；RUNNING、lease recovery 或重复 Job 发现计划已取消时成功 no-op。当前默认保证把取消竞态限制在一个已经开始的短批次内，不声明取消返回后的绝对零新增。

### 7.5 保留与 cleanup

初始默认值是运行参数，不是不可变业务契约：

| 数据 | 初始策略 | 删除前条件 |
|---|---|---|
| PUBLISHED outbox | 14 天 | 已超过最大 Stream/replay/recovery window；group 无超龄 pending/lag、无进行中的事故 replay；异常积压时暂停 cleanup |
| DEAD outbox | 90 天或人工关闭后 30 天 | 已记录处理结论；不再需要 replay/evidence |
| SUCCEEDED job | 30 天 | 业务/audit 表已持久保存必要事实 |
| DEAD job | 90 天 | 人工决定 retry/cancel/accept 后仍保留诊断窗口 |
| CANCELLED job | 30 天 | 无后续恢复需要 |
| processed-message | 不短于 outbox 可 replay 的 14 天，建议 30 天 | 对应 message 已不可能被正常/事故 replay |

cleanup 本身采用可重建 scheduled batch，每批有限行数；不创建“cleanup 自己的每条 Job”。生产容量证据出现前不建设 cold archive。

上述天数不是忽略业务引用的强制删除时间。通知关联 Job、Outbox 与 Notification 去重证据须一起满足 [通知 §7.6](reminder-notification.md#76-清理与去重窗口)：业务结果未收尾、人工处理未完成或仍允许 replay 时不得先清掉唯一恢复依据。超过保留窗口的历史重发不属于普通 retry。

## 8. Phase 6 — Message Contract 与 Redis Streams

### 8.1 Envelope v1

```json
{
  "messageId": "32-lowercase-hex",
  "topic": "comment.approved",
  "schemaVersion": 1,
  "createdAt": "2026-09-22T10:15:30.123+08:00",
  "operationId": "optional-32-lowercase-hex",
  "payload": {}
}
```

- `messageId/topic/schemaVersion/createdAt/payload` 必填。
- `operationId` 只在确有跨阶段逻辑操作时存在。
- 不跨持久边界携带 HTTP `traceId`、`UserContext`、认证数据、Entity、Mapper object 或任意 Java class name。
- consumer 每次 delivery/reclaim attempt 创建新的 traceId，并安装 `operationId? + messageId + attempt`；`attempt` 来自 delivery 观察值，不写回 payload。
- handler 的一般运行时失败最多保留 8 次 delivery；第 8 次仍失败时先写 `async_dead_message` 再 ACK。terminal evidence 写入失败时不得 ACK。该上限用于阻止永久 PEL/hot loop，不把 Stream 变成业务 retry 状态机；修复后从保留的 Outbox 受控 replay。
- `createdAt` 使用带 offset 的 ISO-8601 wire value；数据库调度时间继续遵循项目时间语义，不能靠 JVM 默认时区猜测。

Event-style payload 携带事件发生时 Handler 做出稳定判断所需的最小快照和业务 identity；Command-style Job payload 携带完成该工作所需的最小参数。既不序列化完整 Entity，也不机械地只传一个 ID 导致每个消费者无意义回查。是否包含快照按事实所有权、事件时点语义、payload 大小和数据敏感性逐 topic 决定。

Stream entry 可以把 envelope 字段展开为固定 field，`payload` 为 JSON string；不依赖 Java 泛型运行时反序列化。dispatcher 使用显式 registry：

```text
(topic, schemaVersion) -> payload DTO decoder -> typed handler
```

未知 topic/version 是 permanent poison message：先写 terminal operational evidence，再 ACK 并等待升级后从 Outbox 受控 replay；不能静默跳过，也不能无限 pending/hot-loop。

### 8.2 Topic / job type 规范

- 使用小写点分、稳定业务语义，不使用 Java 类名、`queue`、实现版本或方法名。
- Event 用已发生事实：`comment.approved`、`user.profile-audit.completed`。
- Job type 用 command：`audit.text.submit`、`audit.media.submit`、`notification.plan.fanout`、`activity.business-notification.fanout`、`notification.wechat-deliver`。
- schema version 独立字段，不把 `.v1` 拼进 topic。breaking change 新增 version，rollout window 同时支持旧/新版本。

| 历史 topic | 目标 |
|---|---|
| `audit.text` | Job type `audit.text.submit` |
| `audit.media` / `audit.media.batch` | Job type `audit.media.submit`；batch 仅是创建优化 |
| `broadcast.plan` | 历史 sourceType=3 兼容期仍由 `notification.plan.fanout` 消费；新系统广播 producer 尚未迁到独立直接 fan-out Job，不能继续把 ReminderPlan 当目标语义 |
| 自动推导的 notify topic | 明确事件 handler 创建 notification；外部 delivery 为 Job |

`notification.wechat-deliver` 在兼容窗口同时注册两个 schema version：v1 payload 仍携带旧收件人/类型/数据且不补写虚构 Delivery 结果；v2 payload 只携带 `deliveryId`，执行时重读 `NotificationDelivery + Notification` 冻结快照。2026-09-29 恢复既有服务号发送时，v1 因缺逐次 Delivery 历史按 UnknownOutcome 停止普通 retry，保持 reader 并等待 backlog/preflight 和受控处置，不随 v2 恢复自动重发。业务决定见 [WeChat §17](wechat-integration.md#17-恢复既有服务号订阅通知的决定2026-09-29)。确认 v1 Job 已处理且有明确终局后才能删除旧 decoder/handler。

### 8.3 Stream / group

初始只建一个事件 Stream：

```text
key:   jualn:async:events:v1
group: backend-events-v1
consumer: <instance-id>-<worker-index>
```

当前所有 Handler 属于同一个应用逻辑消费者，所以一个 group 足够；不同 topic 由 dispatcher 分发。未来只有同一事件必须被另一个独立 projection 完整消费时才新增 group，不能把多 worker 错建成多个 group。

初始运行参数：block 2 秒、read batch 10、consumer concurrency 2、pending idle 120 秒、reclaim batch 10。consumer name 每次进程启动唯一，停止时在无 pending 后清理旧 consumer 元数据；不能删除仍有 pending 的 consumer。

group 用幂等初始化创建；全新 stream 从 `0-0` 建立，切换已有 stream 时必须按迁移 cutoff 明确起点，不能默认 `$` 跳过先于 group 创建的 entry。实现 `XAUTOCLAIM` 前先验证目标 Redis 支持该命令；若版本低于 6.2，只能用等价的 XPENDING + XCLAIM，并补同样的所有权测试，不能静默关闭 reclaim。

### 8.4 ACK 与 reclaim

```text
XREADGROUP
 -> validate/decode
 -> handler local transaction COMMIT
 -> XACK
```

- DB COMMIT 成功、XACK 失败会 redeliver；handler 幂等使重复无副作用。
- worker receive 后 crash：entry 留在 PEL；reclaimer 在 idle 超过阈值后用 XAUTOCLAIM 取得 transport ownership。
- Redis reclaim 只处理 delivery ownership。需要分钟级 backoff/maxAttempts/terminal 的业务失败必须创建或更新 MySQL Job，再 ACK 当前事件。
- reclaim 次数只用于诊断 poison/crash loop，不是业务 Job attempt。

### 8.5 Stream retention

不能在 XADD 上盲目 `MAXLEN` 删除仍 pending 的 entry。初始策略：

1. 正常写入不做激进 trim；
2. 周期任务读取 group pending 边界，只对已 ACK、早于保留窗口且早于所有 group 最老 pending 的 entry 执行近似 `XTRIM MINID`；
3. 默认 transport retention 3 天，实际容量上线后再按事件率校准；
4. 50,000 entries 是容量告警/人工处置阈值，不把硬 trim 当正常机制；
5. Redis 完全丢失或应急丢 transport 时，按 incident cutoff 从保留中的 PUBLISHED Outbox replay，使用相同 messageId。

Outbox 的 14 天保留长于 Stream 的 3 天，保证 Redis 不是唯一恢复来源。

## 9. Retry、reclaim、dedup、dead、reconciliation

### 9.1 Retry ownership

- Outbox publish retry：Outbox Publisher。
- Job business retry：Job Worker，持久化到 `async_job.next_run_at`。
- Stream reclaim：Stream Consumer/Reclaimer，只处理 delivery ownership。
- HTTP client retry：仍由具体 client contract / Reliability 决定，不因发生在 Worker 就自动允许。

Job 初始 backoff 建议为 10 秒、1 分钟、5 分钟、30 分钟，加 0–20% jitter；每种 job type 明确 maxAttempts、per-attempt timeout 和 total budget。DB/Redis/provider 故障时 batch/concurrency 不提高；publisher 和 workers 分别最多 2 并发。

### 9.2 Failure classification

- `permanent`：payload/version 不支持、validation/auth/business conflict；直接 DEAD 或安全终止。
- `retryable`：已证明重复安全的临时 DB/Redis/远端失败；按持久 backoff retry。
- `unknown`：远端写 timeout/connection loss、DB commit response loss；进入 handler-specific reconciliation/manual state，不盲重试。

对于有独立业务结果表的 Job，Job DEAD 不是业务结果的替代品。尤其 NotificationDelivery 必须先记录 `FAILED`、`UNKNOWN` 或 `SKIPPED`，再让 Job 按该结果终止；明确永久渠道失败可以表现为 Delivery FAILED + Job SUCCEEDED，表示 Worker 已正确完成分类。

### 9.3 DEAD

DEAD 表示普通自动执行停止。记录 type、subject、operationId、attempt、last error category、截断脱敏 lastError、deadAt；普通 worker 查询排除 DEAD。

Redis Stream 的 poison/unsupported delivery 使用 `async_dead_message` 保存 stream/group/record、可解析的 messageId/operationId/topic、有限错误分类和 delivery count；不得保存完整 payload。只有 terminal evidence 提交成功后才 ACK。它与 Job/Outbox 的状态事实分开，恢复时通过 messageId 回查保留中的 Outbox 并受控 replay。

未来管理接口语义：retry 前重新验证 payload/version、目标当前状态和幂等条件；cancel/accept 记录 operator 和结论；禁止无条件 `replay all failed`。管理 UI 留后续，首轮可用受控 service/CLI 和审计日志，不直接手改表。

### 9.4 Reconciliation

只为真实 Unknown Outcome 建立：

- 微信审核提交 timeout：有 trace/idempotency/status query 时读取权威状态后修正；没有时进入人工恢复，不自动重发。
- 微信通知发送 timeout：只有 provider 提供 idempotency/status 时自动 reconcile；否则记录 unknown 并按产品接受 best-effort 或人工判断。
- 微信异步审核 callback 超龄：扫描 DB PENDING audit log，查询 provider 状态（若可用）或形成 repair candidate。

“到点再调用同一个 POST”是 retry，不是 reconciliation。

## 10. Redis / MySQL 故障与资源行为

### 10.1 Redis 暂时不可用

- 创建业务事实 + Outbox 的 MySQL 事务可以提交；Outbox 保持 PENDING，publisher 退避。
- Job 直接由 MySQL 执行，不依赖 Redis transport；只依赖 DB 的 handler 可继续。
- Stream 事件延迟但不丢失；不在请求线程同步 fallback 执行全部下游副作用。

Redis 完全丢失后：重建 group，再从 Outbox retention window replay PUBLISHED + PENDING event。重复由 handler 幂等吸收。已超过保留期的任意历史 replay 不承诺，因此 retention、事故检测和备份/恢复目标必须匹配。

### 10.2 MySQL 不可用

- 需要创建业务事实 / Outbox / Job 的请求 fail closed；不能先写 Redis 等以后补。
- publisher 和 job workers 停止 claim 并退避；不能 ACK 尚未持久完成的 Stream event。
- 已执行外部效果但无法持久确认时为 Unknown Outcome，恢复后 reconcile，不盲重发。

### 10.3 性能边界

- due queries 走 `(status, next_*_at, id)`，lease recovery 走 `(status, lease_until, id)`；不在 JSON 上检索业务。
- claim transaction 只选 ID 和更新 ownership，不在锁内执行 handler、网络调用或 sleep。
- fan-out 每批最多 100 recipients；每个短事务写 notification + 可选 delivery + delivery job。recipient UNIQUE 是正确性保护，cursor 是性能优化。
- fan-out 每批开始前重新验证 ReminderPlan 与主体状态；失败重跑从头扫描，当前规模不持久化 cursor。
- 微信调用独立限制并发（初始 1–2），不占用 claim transaction。
- 当前主要风险是 retry storm、fan-out 瞬时写放大、无限 Stream/表增长和长事务，而不是极端 TPS。

## 11. Phase 7 — Failure Matrix

| 故障点 | durable / transport 状态 | 丢失或重复 | 恢复 owner 与机制 |
|---|---|---|---|
| business DB commit 前 crash | business/outbox/job 同事务回滚 | 不产生已接受工作 | 客户端按同步契约重试或查询 |
| business DB commit 后、HTTP response 前 crash | business + outbox/job 已提交 | 客户端看到 Unknown Outcome，内部不丢 | 客户端 reconcile；publisher/worker 继续 |
| Outbox publish 前 crash | PENDING，lease 未建或将过期 | 不丢，延迟 | publisher 下一轮 claim |
| XADD 成功但 response 丢失 | Stream 可能已有；Outbox 仍 PENDING | 重试可能重复 | 同 messageId 重发；consumer 幂等 |
| XADD 成功、mark PUBLISHED 前 crash | 同上 | 重复可能 | 同上 |
| mark PUBLISHED 后 Redis 全丢 | PUBLISHED row 在 retention 内 | transport 丢 | recovery publisher 按 cutoff replay |
| Worker receive 后 crash | entry 在 PEL | 不丢，可能重复 | XAUTOCLAIM |
| handler DB commit 前 crash | transaction rollback；entry pending | 不丢 | reclaim/redelivery |
| handler DB commit 后、XACK 前 crash | effect 已提交；entry pending | 重复 delivery | UNIQUE/conditional/processed record |
| XACK 成功后 crash | handler 已完成 | 不丢 | 无需恢复 |
| Job claim 前 crash | PENDING | 不丢 | 下个 worker claim |
| Job RUNNING 时 crash | RUNNING + lease | 不丢，可能重复执行 | lease recovery CAS；新 attempt |
| Job effect commit 后、完成更新前 crash | effect 可能已提交，Job RUNNING | 重跑可能重复 | handler 幂等；外部 unknown reconcile |
| 外部 API 5xx 且明确未接受 | RUNNING | 无外部效果 | proven-safe bounded retry |
| 外部 API timeout / connection loss | RUNNING，outcome unknown | 可能已有外部效果 | provider query/idempotency 或人工恢复 |
| Redis down | Outbox retained；Job 在 MySQL | event 延迟 | publisher backoff；恢复后 replay |
| MySQL down | 无法创建/claim/confirm | 关键新操作不接受 | fail closed；恢复后 reconcile |
| application/worker restart | leases/PEL 保留 | 不丢，可能重复 | lease recovery + Stream reclaim；新 traceId |
| duplicate message | 相同 messageId 多个 entries | delivery 重复 | handler 最小幂等机制 |
| poison / unknown version | pending + Outbox retained | 无限 reclaim 会 hot loop | terminal evidence/dead owner 后 ACK；升级后 replay |
| executor saturation | Job/PEL durable | 延迟 | bounded queue；暂停读取；不 immediate requeue |
| plan fan-out 中途 crash | Job RUNNING；部分通知已写 | 重跑重复遍历 | lease recovery + notification UNIQUE |
| DEAD 后普通扫描 | query 排除 DEAD | 不执行 | 仅 operator recovery 改状态/建新 Job |

## 12. Observability 接入点

沿用 Observability 的名称、tag 与 context 规则；当前已实现的精确 meter 名、采集频率与 unavailable 语义以 [Observability Source of Truth](observability.md#61-当前自定义-meters) 为准：

| 范围 | meter / evidence | 允许的低基数 tag |
|---|---|---|
| Outbox | pending count、oldest pending age、publish rate/failure/dead | topic（registry 限制）、result、error.category |
| Job | pending/running/dead、oldest overdue age、duration、retry/success/dead | job.type、result、error.category |
| Stream | group lag、pending、oldest pending age、reclaim | stream/group（配置白名单）、result |
| Worker | executor active/queue/rejection、handler duration | worker.type、result |

禁止把 messageId/jobId/operationId/userId/subjectId/异常文本作为 metric tag。具体实例写日志关联字段。正常每条消息不打 INFO；中间 retry 用 metric 或无堆栈 WARN；DEAD 由最终 owner 打一次 ERROR。

## 13. Phase 8 — 历史实现迁移计划

不采用长时间 dual-write。List 与 Stream/Job 同时执行同一 command 会放大重复；切换采用“新生产者单写 + 旧队列排空”的分段方式。下列步骤保留为实施记录；当前仓库已完成 producer switch 与 legacy code cleanup。

### Expand

1. 新增 `outbox_event`、`async_job` 及 notification source dedupe；不删除旧列或 Redis key。
2. 实现 claim CAS、lease recovery、Envelope codec 和 context scope。
3. 建 Stream/group、publisher、consumer/reclaimer；旧 List consumer 暂时仍运行。
4. 增加 targeted integration tests、metrics 和受控 dead inspection 命令。

### Migrate / switch

5. 第一批 audit：audit reservation 与 Job 同事务；新 producer 停写 List。核对旧 `audit.*` 消息排空后关闭旧 topic。
6. 第二批 inbox：notification source UNIQUE；event handler 本地事务写 inbox + optional delivery Job。comment/profile callback 依据真实事务 owner 选择 Outbox 或同事务直接记录。
7. 第三批 reminder：保留 `notify_plan` 业务表，创建唯一 fan-out Job；停止写新 ZSet。旧 ZSet/body 全部核对到 DB 后停 delay consumer。
8. 第四批微信 delivery：从 notification transaction 移除远程写，启用 job-specific unknown/recovery。

### Drain / cleanup

9. rollout window 内旧消息只用显式 legacy decoder，不让新 Envelope 假装兼容泛型 `QueueMessage<T>`。
10. 按 topic、DB 状态和 dead item 核对旧 List/ZSet，不以“长度为 0”单独证明业务完成。
11. 停旧 consumers/startup rebuild，保留一个发布周期的只读诊断。
12. 取得环境授权后受控删除旧 Redis keys；不由 Flyway 自动删除。
13. 删除 `QueueMessage<T>`、自动类名 topic、旧 adapter 和无用 executor。
14. 更新部署/恢复文档；Flyway expand 与收缩 migration 分开发版。

应用回滚不会撤销 Flyway。producer 切换后回滚旧 JAR 前必须核对旧版本能否理解新状态，不能依赖 Redis dual-write。

## 14. Migration 数据处理

- 不修改已发布 V1–V16；使用下一个可用 Flyway version，创建新表并兼容新增 notification 字段。
- 先查询目标环境 `flyway_schema_history`、真实结构和数据量；仓库文件不能证明生产版本。
- `notify_plan status=0` backfill 为 Job 时使用 `dedupe_key=plan:{id}:fanout`，可重跑；status=1 不自动 replay。
- `content_audit_log` PENDING 需区分未提交、已提交等待 callback、unknown。当前证据不足的记录不能全部自动建 submit Job，只生成 report/manual repair candidate。
- 旧 Redis List/ZSet 只在切换窗口用于核对尚未反映到 DB 的 item；无法映射稳定业务身份的消息不得盲 replay。
- backfill 分批、可重跑、带计数校验；DDL/DML 与应用开关分开。

## 15. Phase 9 — Documentation 与权威边界

- 本文是 Async Processing 唯一 Source of Truth；AGENTS 只负责把相关任务路由到本文，不复制规则。
- Reliability 继续拥有 failure、retryability、Unknown Outcome、idempotency、compensation 和 reconciliation 的含义；本文只选择异步机制。
- Observability 继续拥有 traceId / operationId / messageId / jobId、MDC、日志、metric 和 cardinality；本文只列接入点。
- Flyway migration 是表结构的可执行事实；本文 SQL 在 implementation 前只是候选设计。
- 跨应用消息若未来出现，先进入 contracts；当前 Redis Stream 是本应用内部协议。
- 运行参数、Redis/MySQL 实际版本、生产数据量、部署和告警必须以目标环境证据核验，不能由本文推断已上线。

## 16. Phase 10 — Async Implementation & Migration Plan

### Batch 1：Foundation（不接业务流量）

- Flyway expand：两张表和 notification dedupe foundation。
- MyBatis mapper：due claim、conditional complete、lease recovery、cleanup。
- Envelope v1、显式 registry、payload size limit、legacy decoder 隔离。
- Streams adapter：group init、XADD、XREADGROUP、XACK、XPENDING/XAUTOCLAIM、safe trim。
- Worker lifecycle：bounded executor、graceful stop、不在事务内 sleep。
- 测试：空库 replay、现有库 upgrade fixture、真实 MySQL 双 worker claim、lease/stale-owner CAS、真实 Redis reclaim、context scope、unknown version/dead。

### Batch 2：Audit Job pilot

- reservation + Job 同事务；payload 只含 auditLogId、scene、targetId 与最小执行快照。
- text/media Handler 从 List 迁到 typed Job Handler。
- 明确微信 API timeout、idempotency/status-query 和 unknown owner。
- 测试：原子性、重复创建、provider call 前后 crash、callback duplicate、restart recovery、旧 List drain。

### Batch 3：Notification event/inbox

- 确认 comment/profile callback 的事务 owner；同事务创建 Outbox 或 notification intent。
- notification source UNIQUE；inbox 与 delivery Job 同事务。
- Stream consumer commit-before-ACK。
- 测试：XADD unknown 同 messageId、commit/XACK fail、duplicate event、Redis flush + replay、unread cache。

### Batch 4：Reminder/fan-out

- `notify_plan` 与唯一 fan-out Job 一致提交；取消使用条件状态/Job CAS。
- 每批最多 100 人并在批次边界重验计划/主体；recipient notification UNIQUE；cursor 仅作优化。
- 测试：到期顺序、取消竞态、中途 crash、从头 replay、部分已存在、restart。

### Batch 5：External delivery 与 recovery

- 微信远程调用从 inbox transaction 拆出；目标形态以 `notification_delivery` 记录渠道状态并与 Job attempt 分离，按 provider contract 实现 retry 或 unknown/manual reconciliation。
- pending audit callback 超龄扫描与 operator repair interface。
- 测试：known 5xx、timeout unknown、status reconciliation、DEAD 停止、manual retry 审计。

### Batch 6：Retire legacy

- 核对 producer cutoff、旧队列 drain 和 DB reconciliation report。
- 停删 List/ZSet consumers、startup rebuild、QueueMessage 和 dead List。
- 受控删旧 Redis keys；更新运行/恢复文档。
- 测试：旧引用为零、旧 key 无新写、重启只启动新 lifecycle、shutdown 释放资源。

当前仓库已完成上述 Batch 1–6 所指的旧 List/ZSet 到 MySQL Async/Stream 基础设施切换与旧路径删除；这不代表 [Reminder、Notification 与 Delivery 设计](reminder-notification.md) 中新定义的业务 Policy、reconcile、`notification_delivery`、取消再校验和微信稳定快照已经实现。应用完整重启、Redis flush/replay、目标环境 migration 与生产 rollout 仍须按验证状态单独报告，不能由代码或本地局部集成推断为环境 PASS。

## 17. When to use / when not to use

| 选择 | Use when | Do not use when |
|---|---|---|
| Outbox | commit 后必须可靠传播事实 | 未来 command、cache warmup、同步调用 |
| Durable Job | 必须跨重启、定时、需 attempt/lease/dead | 普通 CRUD、连接内可取消工作 |
| Redis Stream | 有 MySQL durable source，需要 event transport/group | 唯一任务事实、长延迟 schedule、business retry timer |
| Direct DB claim | 低/中频 Job、单服务器、简化故障边界 | 已测得 DB polling 瓶颈且需独立扩展 |
| In-process executor | 丢失可接受或可从独立事实重建 | 已承诺接受且必须完成的工作 |
| Processed-message row | 无业务唯一性/状态保护 effect | 为所有消息理论性插表 |
| Reconciliation | 可读权威状态解析 unknown | 只是再次发送未知远程写 |

## 18. 验收问答

- Outbox 传播已发生事实；Job 执行未来工作，二者创建者、完成条件、retry 和 retention 不同。
- Message 只是 transport representation，可以重复或从 MySQL durable source 重建，不是业务事实。
- Redis Streams 负责 event transport、wake-up、group、pending/reclaim，不负责 schedule 和 business retry。
- Redis 全丢时 Job 仍在 MySQL，Outbox 在保留窗口内用同 messageId replay。
- BRPOP 在 durable completion 前移除，worker crash 后没有 PEL/claim 恢复。
- PEL reclaim 恢复 delivery ownership；business retry 恢复执行失败，状态在 MySQL。
- Worker crash 后 Stream 用已验证的 XPENDING + XCLAIM，Job 用 lease recovery；二者都可能重复。
- XADD Unknown Outcome 让 Outbox 保持 PENDING 并以同 messageId 重发。
- DB COMMIT 后 XACK 失败会 redeliver，所以 consumer effect 必须幂等。
- 当前 Job 选择 MySQL direct claim，因为低规模下少一层故障边界比 wake-up 更重要。
- Outbox 用于必须传播的 committed facts；Job 用于审核提交、提醒 fan-out、微信 delivery；SSE AI 和可重建扫描不需要 durable async。
- Stream 只 trim 已 ACK 且越过 pending/保留边界的 entry；Outbox 保留更久。
- DEAD 后自动执行停止，由 operator 验证状态后 retry/cancel/accept。
- 主要索引是 Outbox `(status,next_attempt_at,id)`、Job `(status,next_run_at,id)` 与 lease 索引。
- 当前主要性能风险是 retry storm、fan-out burst、无限增长和长事务，不是极端 TPS。
- List 迁移先建 foundation，再按 audit/notification/reminder 单 producer switch，排空核对后删除，不做无条件双写。
