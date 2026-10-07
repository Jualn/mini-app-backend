# Failure & Performance Verification — 2026-09-23

本文记录当前 checkout 在一次性 MySQL 8.0.40、Redis 容器和 Java 17 上获得的可复现证据。它是验证快照，不替代 [Reliability](../reliability.md)、[Async Processing](../async-processing.md)、[Observability](../observability.md) 或 [Runbook](runbook.md)。未运行的场景明确标为 `NOT RUN`，不能从单测或代码审查推断为生产已验证。

## 1. Verification matrix

| 层 | 正常 / 失败方式 | retry / duplicate / loss | 恢复与信号 | 本轮证据 |
|---|---|---|---|---|
| HTTP | 同步写、响应丢失、重复请求 | 报名使用业务自然幂等；不是所有 POST 都有 idempotency key | 客户端查询/同语义重试；trace + problem response | `PARTIAL`：20 次并发同用户提交；真实断连 `NOT RUN` |
| Transaction / MySQL | commit、rollback、锁竞争、唯一冲突 | 事务整体回滚；重复由 UNIQUE / 条件迁移收口 | DB durable truth、异常与 readiness | `PASS`：报名竞争、business+outbox rollback、SKIP LOCKED；deadlock 注入 `NOT RUN` |
| Outbox | PENDING -> PUBLISHED / retry / DEAD；XADD unknown | 同 messageId 可重复 transport；8 次 publisher attempt 后 DEAD | lease、backoff、pending/age、manual retry | `PARTIAL`：真实事务/索引 + mocked XADD unknown；真实网络半开 `NOT RUN` |
| Redis Stream | group delivery、PEL、claim、ACK、trim | at-least-once；entry 可重复或 Redis 可丢 | PEL reclaim；Outbox 保留期内受控 replay | `PASS`：真实 Redis pending/claim/ACK；完整 Redis 丢失重建 `NOT RUN` |
| Event handler | 本地事务 + message dedupe；DB commit 后 ACK | effect 可提交而 ACK 丢失；重复由 processed row / business UNIQUE 收口 | reclaim；terminal evidence 后 ACK | `PASS`：重复 consumption/effect、delivery budget；真实 kill -9 窗口 `NOT RUN` |
| Durable Job | due -> RUNNING -> success/retry/DEAD | claim 消耗 attempt；旧 owner 不得完成 | 120s lease、30s recovery、bounded attempt | `PASS`：真实 DB lease recovery/stale owner + state unit tests；真实进程 kill `NOT RUN` |
| External API | known 5xx、permanent rejection、timeout unknown | 仅 proven-safe failure retry；unknown 进入 DEAD/reconcile | job error category、backoff、manual decision | `PARTIAL`：classifier/state tests；真实 provider/stub outage `NOT RUN` |
| Notification / side effect | inbox UNIQUE + delivery Job | source key / job dedupe 阻止重复事实 | MySQL truth、job inspection | `PASS`（定向单测）；真实微信回执 `NOT RUN` |
| Observability | bounded labels、backlog/age/readiness | telemetry failure 不得伪装为 0 | `NaN + available=0`、transition log | `PASS`（单测）；运行时 scrape 成本因生产未暴露 metrics 为 `NOT RUN` |

## 2. Correctness and recovery results

`PASS` on MySQL 8.0.40:

- 20 个并发相同 user/activity/answers 最终只有一条报名事实、一个稳定 row id。
- 100 个用户竞争 capacity=10，成功数、ACTIVE 行和聚合 count 都是 10，没有超卖。
- cancel 与 re-enroll 并发后仍只有一行，ACTIVE 数与读取计数一致。
- business insert 与 Outbox insert 同事务回滚后均为 0。
- 两个事务用 `FOR UPDATE SKIP LOCKED` claim 到不同 Job。
- RUNNING lease 过期可回到 PENDING；旧 owner 的 completion CAS 影响 0 行。
- 同一 `(consumer_name,message_id)` 的第二次消费被数据库 UNIQUE 拒绝，effect 仍只有一次。
- V1–V19 在空 MySQL schema 完整 migrate 成功，耗时 3.378s。

`PASS` on real Redis:

- Consumer Group 创建、XREADGROUP、PEL、XCLAIM 和 XACK 行为符合设计；ACK 后 pending=0。
- Stream 运行时失败现在有 8 次 delivery 上限；第 8 次先写 `async_dead_message`，写成功后才 ACK。此项修复了原先可能永久 reclaim/hot-loop 的 implementation bug。

`PARTIAL`:

- XADD Unknown Outcome 通过 publisher failure-point test 证明 Outbox 保持 PENDING、messageId 不变并进入 backoff；没有制造“Redis 已执行 XADD 但 TCP 响应丢失”的真实网络半开。
- DB commit 后 HTTP response 丢失用相同业务请求并发/重试验证自然幂等，但没有在反向代理层断开 socket，也没有为不需要它的接口强加 idempotency key。
- DB commit 后 XACK 前 crash 的关键前提（handler effect 与 processed row 同事务、重复消费不重复 effect）已验证；真实进程 kill 窗口未执行。

## 3. Query and bounded-resource evidence

在 10,000 条 Outbox 和 10,000 条 Job（混合历史、ready、RUNNING）上执行 `ANALYZE TABLE` 与 `EXPLAIN ANALYZE`：

| 查询 | 选中索引 | 实际时间 |
|---|---|---|
| Outbox due, limit 20 | `idx_outbox_due` | 0.038–0.076 ms |
| Job due, limit 2 | `idx_job_due` | 0.023–0.042 ms |
| expired lease, limit 20 | `idx_job_lease` | 0.018–0.050 ms |
| succeeded cleanup, limit 200 | `idx_job_finished` covering range | 0.110–0.194 ms |

这些数字来自本机一次性容器，只证明访问路径和当前数据量级，不是生产 SLA。通过 `docker exec` 逐查询的约 130 ms 包含进程启动开销，不能当数据库 latency，因此未采纳。

当前所有批次有界：Outbox claim 20、Job claim 2、Job executor 2 threads + queue 10、Stream consumers 2/read 10/reclaim 10、cleanup 200、payload 64 KiB。Hikari=10、Redis max-active=8。在 2 vCPU / 2 GiB 目标上保留 Job concurrency=2；本轮没有证据支持升到 4 或 8。

## 4. Observability, log and runbook

- 定向测试确认 async metric 使用注册表控制的 `topic/job.type/result/error.category`；ID 不作 label，数据库采集失败为 `NaN` 且 available=0。
- retry 记录无堆栈 WARN，terminal 记录一次 ERROR；新增 delivery budget 防止单条永久失败无限刷日志。
- Runbook 的 Outbox/Job SQL 与 Redis Group/PEL 命令已在一次性服务上核对；索引名、表名、stream/group 名真实存在。
- health endpoint、反向代理、生产日志 driver/retention、MySQL slow query 和运行时 metrics endpoint 仍依赖实际部署。仓库生产配置目前只暴露 health，因此无法从部署外部消费 custom metrics，这是已有 observability gap。

## 5. Recommended production range

| 参数 | 当前建议 | 依据 |
|---|---|---|
| Job concurrency / claim batch | 2 / 2 | 与 2 vCPU 对齐，最多并行占 2 个 DB/remote slot；未测到可安全提高 |
| Job poll / recovery | 1s / 30s | 当前小批量能平滑发现；recovery 远小于 120s lease |
| Job lease | 120s | 仅适合明显小于 120s 的短任务；长任务必须拆批或另行证明续租 |
| Outbox batch / poll | 20 / 1s | 10k 数据下 due index 快；单轮仍有限，Redis outage 有 backoff |
| Stream consumer/read/reclaim | 2 / 10 / 10 | 与 CPU 和 DB pool 保持保守比例；delivery 最多 8 次 |
| Hikari / Redis pool | 10 / 8 | 预留 HTTP 连接；不能仅为提高 worker 数盲目扩大 |
| Cleanup batch | 200 | 使用 cleanup index，避免一次大 DELETE 长事务 |
| Payload | <=64 KiB | 代码已拒绝更大 Job/Outbox JSON；媒体/正文只传 identity/minimal snapshot |

## 6. Known limits and remaining risks

`NOT RUN` and still required before production capacity claims:

- 持续数分钟 Redis down、MySQL stop/start、完整应用 restart、SIGTERM/kill -9、真实 XADD half-open。
- 真实 external API 100% 5xx/timeout、1,000 个同时 due retry Job、producer > consumer backlog 消化曲线。
- concurrency 1/2/4/8 的应用级 throughput 拐点、Worker+HTTP 对 Hikari 的争用、CPU/RSS/GC、日志每分钟字节数。
- GET feed/login/comment/enrollment 的 p50/p95/p99 与 messages/jobs per second；本轮未启动完整应用，不伪造 latency baseline。
- runtime `/actuator/metrics` 成本与完整 label 输出；生产当前未暴露 metrics endpoint。
- Redis Stream 实际长期 retention/memory、Redis 全丢后的批量 replay 工具、经鉴权审计的 DEAD manual retry 入口。
- 数据库 deadlock/lock-timeout 注入。报名链路通过单 activity row lock 规避本轮竞争，但这不证明其他写接口的 deadlock 策略。

因此，本轮能确认关键事实正确性、核心 claim/reclaim/lease 机制和 10k 状态表索引；不能声称已经得到生产容量、完整故障演练或端到端 latency baseline。

## 7. Build and test result

| Scope | Result | Evidence |
|---|---|---|
| Main compile through targeted suites | `PASS` | Java 17 compiled 658 main sources in both validation paths |
| Async unit + real MySQL + real Redis | `PASS` | 35 tests, 0 failure/error/skip; 49.181s total, integration class 9.760s |
| Activity registration real MySQL | `PASS` | 19 tests, 0 failure/error/skip; test class 22.10s |
| Empty-schema Flyway V1–V19 | `PASS` | MySQL 8.0.40, 19 migrations, 3.378s |
| `git diff --check` | `PASS` | no whitespace error; existing Windows line-ending warnings remain |
| Whole `mvn test` | `FAIL` before execution | unrelated existing test compilation drift in `InteractServiceImplTest`, `WxEventServiceImplTest`, and `WxSubscribeServiceTest`; targeted temporary-POM workflow isolates affected tests |

MapStruct unmapped-target warnings remain across pre-existing converter work. They did not fail these suites and are not treated as failure/performance verification success.

## 8. Reproduction

使用 Java 17，准备隔离的 MySQL/Redis 后运行：

```powershell
.\tools\validate-async-processing.ps1 -Maven '.\mvnw.cmd' -JavaHome 'D:\Jualn\Java\jdk-17.0.18+8' `
  -IncludeIntegration -MysqlIntegration -RedisIntegration `
  -MysqlPort 3307 -MysqlPassword '<disposable-test-password>'
```

报名数据库测试需要一个已执行 V1–V19 的一次性 schema：

```powershell
$env:JAVA_TOOL_OPTIONS='-Devent.test.jdbcUser=root -Devent.test.jdbcPassword=<disposable-test-password>'
.\tools\validate-event-information.ps1 -Maven '.\mvnw.cmd' `
  -JavaHome 'D:\Jualn\Java\jdk-17.0.18+8' `
  -JdbcUrl 'jdbc:mysql://127.0.0.1:3307/jualncampus' `
  -TestNames @('ActivityRegistrationDatabaseTest')
```
