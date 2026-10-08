# Operations Runbook

本手册的命令与 endpoint 只引用当前仓库已有名称；尚待实现的通知恢复流程在 §11 显式标注，不作为已具备的运行能力。默认日志目录来自 `LOG_DIR`（默认 `/app/mini-app/logs`）；数据库和 Redis 命令应在已建立安全连接且目标环境已确认后执行，命令中的表名/stream/group 是当前真实名称。

## 0. 当前部署确认与证据边界

2026-10-08 实时核验：生产 Flyway 历史已到 V29，最近版本为 Success，应用 Actuator 为 UP。数据库版本与应用提交分别核验；部署运行不推导所有模板、设备、容量或故障演练均已验证。

本次生产 Flyway 状态检查卡顿与内存余量不足有关：应用运行时可用内存约 280 MB，暂停应用后约 828 MB，同一 Flyway 13.4.0 的 `info` 检查降至 6.4 秒。服务器 `/opt/jualncampus/scripts/db-migrate.sh` 已增加检查前释放应用内存、容器资源限制、阶段超时和退出清理。检查/校验失败且数据库写入尚未开始时恢复原应用；迁移开始后失败或超时保持停止并核对实际状态。生产脚本的参数和权限属于部署表示，不由应用配置推断；排障时先核对服务器真实脚本。

修复后 run `37734039616` 第 7 次生产部署成功，耗时 2 分 50 秒，`info/validate` 分别为 5/4 秒且无 Pending。已发布提交 `c7fdadcc96211f89145e989512cffb8dfff71464`；服务器与运行容器 JAR 校验值一致，最终 Actuator 为 UP。本次未执行新的 SQL 迁移，业务页面验收仍需独立核对。

## 1. First response

1. 请求 `GET /actuator/health/liveness`：失败表示进程或应用生命周期异常。
2. 请求 `GET /actuator/health/readiness`：`db` 或 `redis` 会影响 readiness；根 health 不显示详情。
3. 在 `${LOG_DIR}/mini-app.log` 先按 `traceId`，再按 `operationId/messageId/jobId` 检索。不要依赖 DEBUG 才发现终止失败。
4. 用下方 MySQL/Redis 查询确认 durable truth 与 transport 状态。metric 是发现入口，不是最终状态权威。

仓库没有 Compose 文件，也没有已登记的 `docker compose` 服务名；不要凭空执行 compose 命令。生产进程、容器名、Nginx 和 logging driver 属 Deployment / Runtime 待核验事实。
仓库也没有生产 MySQL server 配置；不要为日常排障开启 general log 或应用全量 SQL INFO。下一轮在真实目标只读核对 slow-query 开关、阈值和日志轮转后再决定是否启用。

## 2. HTTP 5xx rises

- 看 `http.server.requests` 的 rate、status 与 templated `uri`；当前仓库未部署 meter backend 时，从入口代理状态和应用 ERROR 日志人工判断。
- 用响应 `X-Trace-Id` 检索 `traceId=<value>`。同一次非预期 MVC 失败应只在 `GlobalExceptionHandler` 记录一次完整异常。
- `database` 先检查 MySQL/readiness/Hikari；`redis` 检查 readiness 和 Stream；`remote/timeout` 再定位微信、COS 或 AI provider。
- 不把一次 500 当紧急事故；关注持续 rate、同一 route 和持续时间。

## 3. Outbox backlog

```sql
SELECT COUNT(*) AS pending,
       TIMESTAMPDIFF(SECOND, MIN(created_at), NOW(3)) AS oldest_pending_age_seconds
FROM outbox_event WHERE status='PENDING';

SELECT id,message_id,topic,operation_id,attempt,next_attempt_at,lease_until,last_error_category
FROM outbox_event WHERE status='PENDING'
ORDER BY created_at,id LIMIT 50;
```

先区分 pending 数量与 age：短时间流量可使 pending 增长；oldest age 持续增长才说明停止推进。检查 Redis health、publisher 的 `errorCategory=redis`、lease 是否过期、`next_attempt_at` 是否在未来。Redis 恢复后观察 oldest age 回落并确认新行转为 `PUBLISHED`。

DEAD Outbox：

```sql
SELECT id,message_id,topic,operation_id,attempt,last_error_category,last_error,dead_at
FROM outbox_event WHERE status='DEAD' ORDER BY dead_at DESC,id DESC LIMIT 50;
```

仓库已有 `OutboxOperations.manualRetryDead`，但尚未暴露运维 endpoint/CLI。当前不得直接批量 UPDATE 或 replay all；先核对 topic/version、下游幂等和影响范围，再通过受控应用操作执行。建立经鉴权、审计的操作入口留给 Deployment / Runtime。

## 4. Job overdue / retry / DEAD

```sql
SELECT
  SUM(status='PENDING' AND next_run_at<=NOW(3)) AS ready,
  SUM(status='RUNNING') AS running,
  SUM(status='PENDING' AND attempt>0 AND next_run_at>NOW(3)) AS retry_waiting,
  SUM(status='DEAD') AS dead,
  TIMESTAMPDIFF(SECOND,
    MIN(CASE WHEN status='PENDING' AND next_run_at<=NOW(3) THEN next_run_at END),NOW(3)) AS oldest_overdue_age_seconds
FROM async_job;

SELECT id,job_type,operation_id,subject_type,subject_id,status,attempt,max_attempts,
       next_run_at,lease_owner,lease_until,last_error_category,last_error
FROM async_job
WHERE status IN ('PENDING','RUNNING','DEAD')
ORDER BY COALESCE(dead_at,next_run_at),id LIMIT 100;
```

oldest overdue 持续增长表示 Worker 没有推进；ready 数量本身可能只是正常 burst。RUNNING 且 lease 过期会由 recovery 扫描回 PENDING 或 DEAD。对 `notification.wechat-deliver` 的 timeout/unknown 不得盲目 retry。

仓库已有 `JobService.manualRetryDead`，但没有外部运维入口；手工恢复前必须重新验证 payload/version、目标当前状态和幂等条件。不要直接把所有 DEAD 改回 PENDING。

## 5. Redis Stream

```text
XLEN jualn:async:events:v1
XINFO GROUPS jualn:async:events:v1
XINFO CONSUMERS jualn:async:events:v1 backend-events-v1
XPENDING jualn:async:events:v1 backend-events-v1
```

- `lag`：尚未交付给 group 的数量；持续增长表示 consumer 未推进。
- `pending`：已交付未 ACK；结合 oldest pending age 判断卡死或 crash。
- `consumers`：应与当前 Worker 生命周期一致；旧 consumer 若仍有 pending 不能删除。
- reclaim counter 增长说明发生 ownership recovery，不等于业务 retry。

terminal/poison delivery 在 ACK 前写入：

```sql
SELECT id,record_id,message_id,operation_id,topic,error_category,error_detail,
       delivery_count,created_at,last_seen_at
FROM async_dead_message ORDER BY last_seen_at DESC,id DESC LIMIT 50;
```

该表不保存 payload。需要 replay 时用 `message_id` 回查保留中的 `outbox_event`，核对 handler/version 与幂等后受控 replay。

## 6. Redis down

预期：readiness 为 DOWN；Stream consumer/publisher 首次降级记录一条 WARN，后续指数退避不重复刷完整堆栈；MySQL 业务事实与 Outbox 可以提交，Outbox oldest pending 上升；MySQL-only Job 仍可运行。恢复后 group/consumer 恢复，Outbox 被发布，lag/pending/oldest age 回落。不要在 HTTP 线程同步执行所有下游副作用。

## 7. MySQL down

预期：readiness 为 DOWN；需要业务事实/Outbox/Job 的请求 fail closed；publisher/worker 无法 claim/confirm；相关状态 Gauge 为 `NaN` 且 `jualn.async.database.state.available=0`。不得先返回成功再只写 Redis。数据库恢复后先检查 lease、pending、unknown external outcome，再观察 backlog 消化。

## 8. External API 5xx / timeout

- 用 `jobId + operationId` 定位 Job attempt；看 `job.type` 与 `error.category`，不要用异常 message 作 metric tag。
- 明确 5xx 未接受且 handler 幂等时才按 durable backoff retry；timeout/connection loss 的远程写是 unknown，进入 DEAD/manual reconciliation。
- retry 只记录无堆栈 WARN 和 counter；terminal/unknown 在最终 owner 记录一次完整 ERROR 并持久化 DEAD。

### 8.1 微信登录异常与凭据泄漏

微信异常日志中的 `code` 是项目 ResultCode，`providerCode` 才是供应商错误码；`unknown` 表示缺失或未通过日志安全检查，不能由 `code=1000` 推断微信故障原因。用 `traceId` 关联请求，不开启完整 URL、body 或异常 message 日志获取细节。

如果已有日志包含 AppSecret，代码修复不能撤销泄漏：保留受限访问的取证副本，在微信后台轮换对应账号 AppSecret，同步生产秘密配置并按既有部署流程更新应用；检查旧 token 缓存及相关登录/取 token 能力。限制日志副本和附件传播，按既有保留策略处理。JNDI 探测字符串本身不是执行成功证据，应结合生产依赖、访问日志与异常外连核实。

## 9. Correlation path

```text
HTTP traceId
  -> operationId (durable logical operation)
  -> async_job.id = jobId, attempt N, new traceId
  -> outbox_event.message_id = messageId
  -> Stream delivery attempt N, new traceId
  -> async_dead_message or business effect
```

从用户/活动等业务事实先找到 audit log、notification 或 plan，再读取其 Job/Outbox subject/dedupe/source key；具体 attempt 用日志中的 `jobId/messageId/operationId` 关联。不要期待 HTTP trace 跨越排队和重试保持不变。

## 10. Verification boundaries

本机无已登记的一键故障注入环境。Redis stop/restart、Worker stop、远程 5xx、MySQL stop 的真实演练必须在明确的一次性本地或非生产目标执行，并记录：开始状态、注入动作、meter/log/DB 证据、恢复动作、backlog 归零和残留 DEAD。生产或共享环境不得用本手册推导授权。

验证方法见 [commands](../../governance/commands.md)。每次演练的结果与证据记录在对应任务或发布记录中，不以旧验证快照代表当前目标环境。

## 11. 通知未出现、延迟或渠道失败

本节是目标排障/恢复流程。现有表可用于只读核对；terminal repair、人工处置审计与 CLI 是否已实现须核对当前应用入口，不能把流程描述当作可执行入口。尤其 `JobService.manualRetryDead` 不等于 UNKNOWN 可以重发。

按如下顺序核对，不以 Job SUCCEEDED 证明用户收到：

1. 从主体与 rule/event identity 找计划或事件意图。没有符合语义的精确节点、没有真实事件或没有当前接收者，可以合法地没有通知；先确认 producer 与业务资格，不先重放。
2. 核对 sendAt、业务节点有效期、计划状态和 fan-out Job。未来等待是正常；已到期但未到业务节点可恢复；到节点后停止新的 Reminder。DEAD + 未完成计划表示未/部分完成待处理，不能记为已完成。
3. 按 source key/receiver 找 Notification。存在即复用；不因渠道缺失重新制造消息。CANONICAL 必须有 IN_APP/DELIVERED 才可见，LEGACY 按兼容集合；当前偏好不隐藏历史消息。
4. 核对每个 Delivery。无行可能是初始偏好关闭或能力/身份/权限不足，需结合规划 reason；配置后来可用不意味着应补发。PENDING 看 Job 的到期/退避；PROCESSING 看 lease 和调用证据；FAILED/SKIPPED 看稳定原因；UNKNOWN 停止普通发送。
5. 同时看 Job 与 Delivery：SUCCEEDED + FAILED 是已完成失败分类；DEAD + PENDING/PROCESSING 是必须收尾的组合；DELIVERED + 未完成 Job 可由幂等恢复吸收。采集不可用不能当作零积压。

经确认目标 schema 含 V20 后，可以只读汇总，不扫描或输出用户正文/provider payload：

```sql
SELECT channel,status,COUNT(*) AS total,MIN(updated_at) AS oldest_updated_at
FROM notification_delivery
GROUP BY channel,status;
```

针对具体实例，再按 deliveryId 查询对应 `delivery:{deliveryId}` Job；旧 v1 按兼容 payload/source 定位，不猜成新渠道 Delivery。大表排查须有界、利用索引，不将全表汇总加入高频请求路径。

### 11.1 受控恢复前置与结论

恢复 owner 先确认 type/version、稳定身份、当前资格、剩余有效期、是否可能已经调用 provider，以及当前执行 ownership。已知安全的本地 fan-out/明确失败可复用原身份受控 retry；不能新建随机身份绕过去重。UNKNOWN 只有权威查询或已验证 provider 幂等能力才自动解析；无证据时人工接受 best-effort 并保留 UNKNOWN，不标记送达。

处置须记录 operator、对象、依据、动作和结论，不只依赖可能丢失的普通日志。若还没有受控入口/持久处置证据，报告恢复 BLOCKED，先实现最小应用服务/CLI；不手工批量改表、不 replay all、不要求新增公开管理 HTTP API。取消剩余工作不删除已经生成的站内消息。清理前核对 [通知去重窗口](../reminder-notification.md#76-清理与去重窗口)，未知结果和未完成恢复不得被清理抹去。

完成检查包括业务结果终局/明确待处理结论、Job 状态、backlog 推进、残留 UNKNOWN/DEAD 与去重不变量。恢复不是要求所有状态变成 SUCCEEDED，也不承诺 UNKNOWN 最终一定能证明真实送达。

## 12. Admin 扫码登录运行与恢复

内部协议见 [Admin QR Login 内部设计](../admin-qr-login.md)，对外语义见 [共享契约](../../../contracts/docs/coordination/admin-qr-login.md)。仅保留新流程，所有承接管理 Token 的实例必须使用 activation gate；不能回退至绕过 gate 的版本。Redis 会话或 Sa-Token mapping 丢失后重新扫码，不从数据库或内存重建旧授权。

QR Store 不支持 Redis Cluster；QR 与 Sa-Token 必须使用同一 connection factory/database。核对目标 primary/failover、ACL、持久化和内存，不允许认证 key 不受控淘汰。consume 恢复使用原会话保存的固定 token/profile；UNKNOWN 不能换 token。QR 到期禁止新兑换和重放，不撤销已消费 Token 的正常寿命。

配置 `admin-auth.qr-login-v2.enabled` 默认 true；AppID 复用 wx.ma。正式环境 env-version 默认 release，dev 默认 develop，`ADMIN_QR_LOGIN_ENV_VERSION` 可覆盖。确认页固定 `subpkg_setting/pages/admin-login-confirm/index`，需核对目标 AppID/env/page 和实际页面发布；dev 默认 check-path=false，正式环境默认 true，`ADMIN_QR_LOGIN_CHECK_PATH` 可覆盖。生成码成功不等于真机页面可打开。

session TTL 不超过 5 分钟，终态保留至少到 expiresAt 后 5 分钟，poll interval 至少 1000ms，Admin Token TTL 必须覆盖恢复。核对 Web origin、私有 Header、JSON/PNG/错误响应与代理的 no-store。默认全局 60 create/min、2 分钟图像 TTL、最大 2MiB PNG 的理论图像预算约 240MiB；按真实码大小和可用 Redis 内存调整额度，不能据本地测试认定生产容量充足。

## 13. 用户资料安全检查与恢复

业务保证见 [媒体与用户资料](../domain.md#媒体与用户资料)，迁移和回滚边界见 [数据库说明](../../src/main/resources/db/README.md)。资料写入直接执行必要检查，无独立总开关；检查拒绝或不可用时保留原有效资料。

核对微信文本检查、图片审核 Job、验签 callback、COS 服务端 COPY 和快照读取链路。有效图片位于 `profile-effective/{userId}/{random}`，客户端上传权限不能写该前缀；历史源对象不能直接认定为已审核快照。快照先登记清理记录再复制，COPY 失败或未知结果不发布，由现有 PENDING cleanup 处理。

图片检查等待预算由 `app.user-profile.media-check-wait-millis` 控制，默认及上限为 10000ms；这是 HTTP 等待预算，不是微信完成时延承诺。超时或缺完整结论返回 503，不返回自动生效的 pending/202；迟到 Job/callback 只保留审核事实，不能提交或清空资料。用户需重新提交，不能后台盲重发未知 provider 写。

并发请求在最终短事务重新检查 `profile_revision`、权限和媒体引用，旧 revision 不得覆盖后提交的资料。排障先区分内容拒绝、检查不可用、并发版本变化和媒体引用错误，不绕过检查或直接改库认定通过。恢复须同时核对资料有效值、审核事实、快照引用及残留 Job；不能直接恢复旧乐观 writer/callback 版本。
