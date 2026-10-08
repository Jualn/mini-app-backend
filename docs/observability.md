# Observability Source of Truth

本文是本项目可观测性契约的唯一权威来源，规定需要观察什么、关联 ID 的语义以及日志、指标和健康状态的边界。它不替代 [Backend Engineering](../standards/backend-engineering.md) 的失败责任与验证保证、[Java Engineering](../standards/java-engineering.md) 的日志调用纪律或 [Spring Boot Engineering](../standards/spring-boot-engineering.md) 的 MDC / 框架集成机制。

当前目标是 Java 17 / Spring Boot 单体在单服务器上的低成本运行能力，不建设 Prometheus、Grafana 或 OpenTelemetry backend。Outbox、Job、Worker 与消息协议由 [Async Processing Architecture](async-processing.md) 定义，本文件只拥有其关联 ID、日志、指标、health 与运行检查契约。

## 1. 运行观测边界

| 边界 | 当前规则与所属入口 |
|---|---|
| HTTP | RequestLogFilter 安装、校验及回传 traceId，作用域结束恢复；数量、状态与时延交给 MVC/Micrometer |
| 异常出口 | 预期拒绝不默认打堆栈；未预期 HTTP 失败由 GlobalExceptionHandler 记录一次 |
| 同进程异步 | 关联字段按本规范传播与恢复；身份上下文不构成跨边界授权 |
| Durable async | Outbox 使用稳定 messageId，Job 使用稳定 jobId；每次 attempt 建新 trace，机制归 Async |
| Metrics / health | 自定义 meters 的准确命名、有限 tags 与成本见 §6；health 与管理端精选诊断见 §6–7 |
| Logging | 固定关联字段、敏感数据边界与单点责任见 §3–5、§8；文件/保留策略见 §8 |
| Operations | health、精选快照、日志、MySQL durable truth 和 Stream 状态联合排查；流程见 runbook |

本文件维护有效观测规则，不保留建立基线时的旧日志行为、测试次数或本机基础设施状态。实际代理、采集平台、日志与告警的运行结果由指定环境证据证明。

## 2. ID 模型

| ID | 语义与生命周期 | 是否默认存在 |
|---|---|---|
| `traceId` | 一次实际执行链路。HTTP 请求、一次 Worker attempt、一次独立 Job attempt 各有自己的 trace | 普通 HTTP 必须有；后台 attempt 必须有 |
| `requestId` | 独立的传输请求标识 | 当前单体没有区别于 traceId 的已证实用途，因此不建立。`X-Request-Id` 不作为 traceId fallback |
| `operationId` | 一次逻辑业务操作，可跨 HTTP、事务、消息与多次 attempt 保持 | 仅在确有跨执行业务操作时建立；普通读请求不生成 |
| `messageId` | 一条异步消息的稳定唯一标识，用于定位该消息及其多次投递 | Outbox / Stream 消息必须有 |
| `jobId` | 一个持久后台 Job 的稳定标识，可经历多次执行 attempt | Job 执行边界使用；Job 存储与状态归 Async |
| `attempt` | 同一 message/job 的执行序号；必须是有界整数 | 只在重试型异步执行中存在 |

普通请求只有 `traceId`。有长期业务操作时为 `traceId + operationId`。异步消费者为每次 attempt 生成新的 `traceId`，并从可信消息/Job 状态恢复 `operationId? + messageId/jobId + attempt`。不得让原 HTTP traceId 永久跨越排队、延迟和重试，也不得把 traceId 当幂等键、数据库业务唯一键或审计标识。

HTTP 使用 `X-Trace-Id`：只接受 1–64 位 ASCII 字母、数字、下划线和连字符；缺失或非法时生成 32 位小写十六进制值，不回显非法输入。服务端始终通过响应 header 返回最终 traceId。Problem Details 和旧 `Result` 响应在其现有边界中读取同一 MDC traceId。

`ObservabilityContext` 是进程内字段名、受控 capture/install 和清理入口。只传播本节定义的低风险关联字段，不能复制任意 MDC，更不能借 MDC 传播认证凭据、事务或完整业务对象。

## 3. 日志契约

日志回答“某一次具体执行为何失败或发生了什么重要边界事件”。数量、比例、时延分布、积压量和容量趋势属于 metric。

Logback 每行提供 timestamp、thread、level、logger 与存在时的 `traceId/operationId/messageId/jobId/attempt`。事件消息按需增加稳定键值：

```text
result=success|rejected|failure|retry|dead
errorCategory=validation|authorization|conflict|database|redis|remote|timeout|internal
errorCode=<稳定且低基数的代码，仅在确有定位价值时>
exceptionType=<异常简单类名>
durationMs=<单次重要边界时延；高频分布应使用 Timer>
```

不是每条日志都必须包含所有字段。不要在每层或每一步打印“开始/查询完成/Redis 完成/ACK 完成”。正常高频请求、普通 CRUD、缓存命中/未命中和循环内成功默认不打 INFO。INFO 用于低频重要状态边界、管理动作、恢复完成和部署/生命周期事件；WARN 用于需要关注但已被当前边界处理的异常状态；ERROR 用于需要运维处理的非预期或终止失败。

日志格式是便于 grep/采集的稳定文本键值，不宣称已经接入 JSON 日志平台。不得用日志替代 `admin_operation_log` 等持久审计事实。

## 4. 异常记录责任

- 参数错误、未登录/无权限、资源不存在、状态冲突、容量已满等预期业务拒绝不默认打印 stack trace，也不默认打印 WARN。HTTP status/自动指标已经提供总量信号；有明确运营需求时增加低基数 metric。
- Controller、Service、Mapper 在将同一异常继续向上抛出时不重复记录。翻译异常时保留 cause。
- 未预期异常到达 `GlobalExceptionHandler` 时，由该 HTTP 失败边界记录一次 ERROR 和完整 Throwable，客户端只得到脱敏 Problem Details + traceId。
- Filter、Security、SSE 完成回调、`@Async`、scheduler、consumer、启动/关闭等不一定到达 MVC Advice，各自由实际观察最终 outcome 的适配器负责一次记录。
- 中间 retry 默认用 metric；确需日志时使用无堆栈 DEBUG/WARN，并带 messageId/jobId、attempt、分类和下次计划。terminal/permanent/DEAD 在最终责任边界记录一次 ERROR，可带完整堆栈。禁止每次 retry 都打印相同完整堆栈。

## 5. Error Taxonomy

业务 ErrorCode 描述客户端/领域语义，observability category 用于聚合，两者不一一对应。

| category | 典型来源 |
|---|---|
| `validation` | 格式、必填、范围、不可解析请求 |
| `authorization` | 未认证、无权限、角色不足 |
| `conflict` | 业务状态冲突、容量/重复操作、数据唯一约束冲突 |
| `database` | 非预期数据库连接、SQL、事务失败 |
| `redis` | 非预期 Redis 连接/命令失败 |
| `remote` | 微信、COS、AI 等远端失败 |
| `timeout` | 已明确为超时的本地/远端等待失败 |
| `internal` | 无法归入以上类别的程序错误 |

`retryability=retryable|permanent|unknown` 是独立维度，由操作幂等性、错误语义和 owner 决定，不能仅从异常类或 category 猜测。具体 `ACTIVITY_FULL` 等业务码通常映射 `conflict`，不得把所有业务码都变成 metric tag。

外部服务 HTTP 异常日志保留 `code`（项目 ResultCode）与 `providerCode`（供应商错误码）两个独立字段。`providerCode` 只允许 1–64 个 ASCII 字母、数字、下划线、点或连字符；缺失或不安全时记为 `unknown`，不输出远端 message、响应体或凭据，也不将原始供应商错误码用作 metric tag。

## 6. Metrics implementation

使用 Actuator/Micrometer 原生 registry 与 binder；不要创建 `MetricManager`、通用 wrapper 或重复记录框架已有数据。

- Counter：只增不减的离散事件，如 publish、retry、dead、reclaim。
- Gauge：当前值的快照，如 pending/backlog；必须有可靠、低成本的取值 owner，不能为每个业务 ID 建 gauge。
- Timer：Job / Stream handler attempt 的时延。不要用 INFO `costMs` 代替聚合时延。

HTTP 复用 Spring 自动提供的 `http.server.requests` 数量、status 与 latency。`uri` 必须是 MVC route template（如 `/posts/{id}`），不能把 raw `/posts/123` 或 query 放入 tag；不要再手写同义 Counter/Timer。数据库连接池、JVM、进程和可用的框架 binder 同理优先复用。

项目自定义 meter 使用点分名 `jualn.<domain>.<measurement>`；Prometheus 等具体 backend 可自行转换命名。tag 只允许经审查的低基数集合，例如 `type`、`result`、`error.category`、有界 `provider`。每个新 meter 必须先回答 owner、单位、tag 枚举上界、采集成本、消费方和移除条件。

以下字段禁止作为普通 metric tag：`traceId`、`requestId`、`operationId`、`messageId`、`jobId`、`userId`、openid、post/activity 等资源 ID、未归一化 URI、任意自由文本或异常 message。具体实例通过日志的关联 ID 定位。

### 6.1 当前自定义 meters

Admin QR Login v2 复用 HTTP route/status/latency，新增下列应用内 meter；不包含凭证摘要、sessionId 或 userId tag，不以计数作为登录审计。运维通过既有 registry 消费，功能退休时移除；尚无生产 dashboard/alert 证据。

| meter | 类型 / 有限 tag | owner 与成本 |
|---|---|---|
| `jualn.admin.qr.login.transition` | Counter / from,to 为七状态的允许迁移 | AdminQrLoginStore 收到 Lua 提交结果后 O(1) 计数；幂等不计，崩溃可能少计；expiry 只计 lazy 观察到的迁移 |
| `jualn.admin.qr.login.consume` | Counter / result=`committed,replayed,recovered,denied,conflict` | QR Service 每次消费交付或准入/状态拒绝 O(1) 计数；恢复不是新登录次数 |
| `jualn.admin.qr.login.wechat.code.duration` | Timer / result=`success,failure`，error.category=`remote,timeout,internal` | WxClient 一次码生成链含允许的一次 token refresh，单位秒；成功使用 internal 表示本地已完成，无外部失败 |

仅 consume 在 finally 可恢复的作用域安装 operationId=sessionId；其余请求沿用 traceId。低频发布/消费提交 INFO，不逐次记录 poll/replay；provider 异常在 QR 边界去除 URI/body/cause，公开返回受控 503。微信码生成失败的 WARN 记录受控 code（微信数字 errcode 或内部固定诊断码），不记录原始 errmsg、response body、scene 或 access_token；HTTP 200 不等于微信业务成功。

Notification 增量 meters 由 notify creation / projection / Delivery owner 更新，均为 Counter：

| meter | 有限 tag / 单位 | 成本与消费 |
|---|---|---|
| `jualn.notification.creation` | result=`created,dedupe_suppressed` / rows | 创建提交后计数；唯一键抑制按 attempt 计数 |
| `jualn.notification.recipients` | type=`reminder,business_change` / recipients | 每批候选人数；重试可能再次扫描，不表示唯一收件人数 |
| `jualn.notification.reminder.dedupe` | 无 tag / attempts | 新 occurrence source key 唯一键冲突 |
| `jualn.notification.projection.failure` | 无 tag / attempts | 冻结 JSON 无法投影时 |
| `jualn.notification.unread.rebuild` | result=`success,cache_failure,database_failure` / attempts | Redis miss 回源与写缓存结果 |
| `jualn.notification.delivery.result` | result=`DELIVERED,FAILED,UNKNOWN,SKIPPED,RETRY` / transitions | 成功持久化状态变化后；RETRY 为非终态 |
| `jualn.notification.recovery` | result=`not_found` / attempts | 本人恢复查询缺失/非本人；格式与认证错误复用 HTTP status |

这些 meters 无实例 ID tag、不新增周期扫描；消费方是现有 Micrometer registry 的运维检查，未接入生产时序平台或自动告警。移除条件是对应通知链路退休。batch/read-through 的 latency/error 复用 `http.server.requests` route/status，不另建 HTTP Timer。Redis 命令失败日志与 AsyncJob terminal owner 保持已有责任。

| meter | 类型 | tag / 单位 | 数据源与成本 |
|---|---|---|---|
| `jualn.async.outbox.publish` | Counter | `topic,result` | publisher attempt；topic 只接受已注册 handler，否则归一为 `unsupported` |
| `jualn.async.outbox.retry` / `.dead` | Counter | `topic,error.category` | retry / terminal owner |
| `jualn.async.outbox.pending` / `.oldest.pending.age` | Gauge | events / seconds | 每 30 秒一条 MySQL 聚合查询 |
| `jualn.async.job.execution` / `.duration` | Counter / Timer | `job.type,result,error.category` | 每个 Job attempt；type 只接受 registry 值 |
| `jualn.async.job.retry` / `.dead` / `.recovery` | Counter | 受控类型与分类 | retry、terminal、lost lease recovery |
| `jualn.async.job.ready` / `.running` / `.retry.waiting` / `.dead.current` / `.oldest.overdue.age` | Gauge | jobs / seconds | 每 30 秒一条 MySQL 聚合查询 |
| `jualn.async.stream.delivery` / `.duration` / `.reclaim` | Counter / Timer | `topic,result,error.category` | delivery/reclaim；topic 由 registry 限制 |
| `jualn.async.stream.length` / `.lag` / `.pending` / `.oldest.pending.age` / `.consumers` | Gauge | events / seconds / consumers | 每 30 秒一次 bounded Redis inspection；不扫描完整 PEL |
| `jualn.async.database.state.available` / `jualn.async.stream.available` | Gauge | `1/0` | 区分真实 0 与采集源不可用；不可用时对应状态 Gauge 为 `NaN` |

MySQL Gauge 使用两条聚合查询而不是七次独立 COUNT；Redis 只执行 XLEN、XINFO GROUPS、XPENDING summary 和最多一条 oldest pending 查询。采集周期默认 30 秒，适合当前单机规模。上线后若聚合查询进入慢查询或产生可见 CPU/latency 影响，先降低频率，再基于实际执行计划决定索引或近似方案，禁止改成重启即漂移的进程内计数。

当前生产配置只暴露 `/actuator/health`，没有 Prometheus registry/scrape endpoint，也不公开 `/actuator/metrics`；因此应用内 meter 已可被测试和将来的 registry 消费，但尚不等于已有生产 dashboard/alert。未明确 management network/auth 边界前不得为了方便把 metrics 直接暴露公网。

通知业务验收还需要区分 planning 未创建渠道、Delivery FAILED/UNKNOWN/SKIPPED、部分 fan-out 和 Job/业务状态待收尾；已有 Job counters 不能替代这些结果，尤其 Job SUCCEEDED 可以对应 Delivery FAILED。所需事件范围由 [通知 §9](reminder-notification.md#9-observability-接入) 定义；上表没有列出的业务 meter 不视为已实现。后续实现须在本文登记准确 meter 名、受控低基数 result/reason、采集成本和 unavailable 语义，再接入现有观测机制，不另造 error category，也不默认新增监控服务或管理 API。具体排查见 [Runbook §11](operations/runbook.md#11-通知未出现延迟或渠道失败)。

### 6.2 管理端精选状态快照

`GET /v1/admin/system/overview` 在独立管理员认证及 `system:read` 权限后，可读取 liveness/readiness、受控依赖探测与以下当前状态 Gauge：Outbox pending/oldest age、Job ready/running/retry waiting/dead/oldest overdue age、Stream length/lag/pending/consumers/oldest pending age，以及 database/stream available。它还提供一个有界的当前进程诊断快照：进程启动时间/uptime、`http.server.requests` 总量/5xx/平均值/最大值和累计耗时最高的 12 个归一化路由、JVM heap/live threads、process/system CPU、Hikari active/idle/pending/max、GC pause，以及既有 Outbox/Job/Stream Counter/Timer 累计值。该接口只消费既有 meter，不创建第二套计数，也不暴露 meter 名、原始 tag、Actuator 通用查询、日志内容、连接信息或关联/业务 ID。

管理端响应必须保留采集可用性与 `null` 语义：source unavailable、尚未首次采样或 `NaN` 不得显示为 0。响应同时返回配置的最大正常采样滞后，不能把接口读取时间伪装成 Gauge 的精确采样时刻。进程诊断中的 Counter/Timer 从当前进程启动后累计，重启即清零；当前 overview 请求要在响应结束后才进入 `http.server.requests`，因此本次读取最多只能看到此前已完成的请求。它没有历史 bucket，不能计算窗口 rate、趋势、P95/P99 或告警，也不能替代跨服务 trace。可靠时序仍需要 Prometheus/Grafana 或同类监控 backend。

## 7. 同步与异步传播

HTTP → Controller → Service → Mapper 的同步调用共享当前 MDC trace。`@Async` 的现有装饰器仅复制本规范列出的关联字段，并在 finally 恢复 worker 线程原上下文；它不传播事务，也不能把 `UserContext` 的历史复制行为解释为跨消息授权。

跨持久消息或延迟 Job 时不捕获线程 MDC 作为未来执行上下文。生产者将 operationId（若有）和稳定 messageId/jobId 写入受版本治理的消息/持久状态；消费者验证后为本次 attempt 新建 traceId，再安装关联字段。结束时按作用域恢复/清理。

当前持久链路为 `HTTP trace + operationId -> Job(jobId) -> Outbox(messageId) -> Stream delivery`。每个 publisher、Job 和 Stream attempt 都创建新 trace，并从持久状态恢复 operation/message/job identity。Stream terminal/poison delivery 先写 `async_dead_message`（不保存 payload）再 ACK；写入失败则不 ACK，避免只留下易丢日志。

## 8. 安全与性能

禁止默认记录 Authorization、JWT、Cookie、session credential、password、secret、完整 openid/fromUserName、手机号、微信 scene/eventKey、完整 request/response body、上传内容、用户正文/标题、自由文本理由、实体/DTO `toString()`、DB/Redis 凭据。确需定位时优先使用内部 ID；必须展示外部标识时采用稳定脱敏并限制长度。

URL 日志不得包含 query/fragment；动态路径先归一化。异常 message、远端响应和 Bean Validation 文本都可能携带输入，未审查前不进入日志。日志中的不可信文本还须限制长度并防止换行伪造。

生产不默认开启 SQL 全量日志、请求/响应 body 日志或同步高频磁盘写。本项目只保留一个异步应用文件 appender：单分片 50MB、14 天、总量 1GB；不再将异步 ERROR 同时复制到 app/error/queue 三份文件。队列满、进程崩溃时日志仍可能丢失，因此它不能承担可靠审计。仓库不存在 Compose，Docker `json-file` 的 `max-size/max-file` 必须在下一轮部署事实核验中确认，不能由应用配置推断。

## 9. Health 语义

- Liveness：进程和 Spring 应用生命周期是否存活，只包含 `livenessState`。MySQL/Redis 短暂异常不得触发 liveness 失败和重启循环。
- Readiness：当前实例是否可安全接收业务流量。当前单体核心请求依赖 MySQL/Redis，因此生产 readiness 组包含 `readinessState,db,redis`。
- 根 `/actuator/health` 仍用于汇总框架 health contributor；生产 `show-details=never`，不得泄漏连接、主机或凭据细节。

仓库只暴露 Actuator `health`。`/actuator/health/liveness` 和 `/actuator/health/readiness` 是 health 子路径，不等于已完成 Nginx、容器编排或生产探针接线；部署策略与实时运行验收另行负责。

## 10. Runtime inspection and alert candidates

当前最低 inspection 方案是：health 子路径判断进程/依赖；有 `system:read` 权限的管理员可查看精选健康、进程诊断与异步状态快照；应用日志按关联 ID 定位 attempt；MySQL 查询 Outbox/Job/DEAD durable truth；Redis `XINFO`/`XPENDING` 判断 transport。真实命令和恢复顺序见 [Operations Runbook](operations/runbook.md)。管理端快照是人工入口，不是 durable truth、时序监控、调用链分析或自动告警；未部署监控 backend 时仍需人工执行 runbook，不声称已有自动发现能力。

首版告警候选只有：持续 HTTP 5xx rate、Outbox oldest pending 持续超阈值、Job oldest overdue 持续超阈值、新增 Job/Outbox/Stream DEAD、Redis/DB 持续不可用、readiness 持续失败。阈值必须由生产正常数据确定，并使用 duration/rate/consecutive checks；单次 500 或一次 retry 不触发紧急告警。

核心面板若未来接入 Prometheus/Grafana，只做 HTTP、JVM/process、Hikari、Redis 和 Async 五个区块；当前资源预算和消费方不足以授权部署额外常驻服务。

### 10.1 Resource-cost result

- 固定成本：每 30 秒两条 MySQL 聚合查询；每 30 秒一次 Redis XLEN、XINFO GROUPS、XPENDING summary 和最多一条 oldest pending；不扫描完整 PEL，不按业务 ID 建 gauge。
- 日志磁盘：单一生产文件链路，50MB 分片、14 天、总量 1GB；异步 appender 队列 512，最多等待 5 秒 flush。
- 事件成本：Counter/Timer 只在 publish、Job attempt、Stream delivery/reclaim 边界更新；没有 HTTP 重复计时和逐条成功 INFO。
- 本 checkout 未启动完整应用/Redis，也没有稳定的 before baseline，因此 CPU、heap、HTTP latency 与 Worker throughput 对比是 **NOT RUN**，不能用单元测试耗时伪装成性能测量。上线前在相同流量和数据量下采样这些指标；若两条聚合查询进入慢查询，先把 refresh 降至 60 秒并检查执行计划。

## 11. 变更检查与后续边界

修改 HTTP pipeline、异常处理、日志、metrics、remote client、SSE、`@Async`、scheduler、consumer/retry、health 时必须阅读本文。新增 ID 类型、MDC 字段、error category 或 metric 命名约定先更新本权威边界，不在业务模块自行发明。

后续 Deployment / Runtime 阶段仍负责：监控 backend 与 alert evaluator 的选择、management endpoint 网络/鉴权、Nginx/进程 probe 接线、Docker logging driver 限额、MySQL slow-query 生产参数、实际容量/阈值校准和生产故障演练。它不得重写这里的 ID、meter 或 health 语义。

新增 observability 行为的验证按 [Backend §9.12–9.13](../standards/backend-engineering.md#912-verification-scope-and-escalation) 选择真实边界。至少覆盖上下文安装/非法输入/作用域恢复、异常单点责任及敏感字段；若新增自定义 meter，再覆盖 tag 白名单与基数边界。不要为测试一个尚不存在的自定义 metrics API 而先实现该 API。
