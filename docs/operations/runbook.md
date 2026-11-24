# Operations Runbook

本手册的命令与 endpoint 只引用当前仓库已有名称；尚待实现的通知恢复流程在 §11 显式标注，不作为已具备的运行能力。生产日志默认 JSON stdout，Docker 采集；只有显式 `prod,log-file` 才使用 `LOG_DIR`（默认 `/app/mini-app/logs`）。数据库和 Redis 命令应在已建立安全连接且目标环境已确认后执行，命令中的表名/stream/group 是当前真实名称。

RBAC v1 双签工具只允许专用可销毁合成库；v2 首次迁移/v3 更正另有清单与摘要校验入口，不沿用 v1 guard 作为批准。各入口默认 dry-run，生产执行均需明确目标与独立批准，不复制测试 guard、清单或目录状态。无ROOT不代表可跳过授权；运维主体及独立operation/effect审计见[离线治理](../rbac-offline-governance.md)，命令边界见[commands](../../governance/commands.md#rbac-offline-governance-validation)。未知提交保留原operationId/签名请求，在同一受控环境恢复原回执并inspect当前事实，不能换ID盲重试。灾难恢复仍须先核对最新撤权/来源/key，不能仅重建ROOT或恢复旧role；生产执行器、身份托管和维护发布链路另行批准。

已知离线ROOT的effect缺失、快照/固定收据不可解释或授予引用不一致会使当前RBAC读取/历史查询503。先隔离受影响治理入口，按V43 preflight核对operation/effect/Assignment及独立证据；不得清理operation以掩盖origin、补随机grantChangeId或回旧role绕过。当前已验证单个effect缺失/损坏与恢复原测试事实后的收敛，不证明任意历史整体丢失可自动修复。

## 0. 部署核对与证据边界

发布或排障先核对目标环境的应用制品/hash、真实启动参数、Actuator、Flyway history、SHOW CREATE TABLE 和受影响数据；源码与旧部署成功记录不能证明当前状态。

Flyway 检查和应用争用内存时，先按服务器真实脚本核对维护窗口、检查前释放应用内存、容器资源限制、阶段超时与退出清理。检查/校验失败且数据库写入尚未开始时可按既有流程恢复原应用；迁移开始后失败或超时保持停写，核对实际数据、DDL 与 history 后决定恢复。不能只 repair 后重跑，也不能由仓库说明推断服务器脚本已更新。迁移历史保存在目标 schema，临时 Flyway CLI 容器删除不解释 history 缺失。

## 1. First response

RBAC G2的新二进制要求完整显式Authority。启动若报 `RBAC Authority coverage incomplete`，停止发布并按[数据库说明](../../src/main/resources/db/README.md#rbac-v41-与-authority-启动门禁)核对目标/V41、旧User writer静默和preflight；仅在批准目标执行显式LEGACY回填，保留batch及缺失/冲突计数，提交后再次核对。数据库不可读同样不得绕过门禁。不得加“缺行即LEGACY”、覆盖ASSIGNMENT、给真实账号ROOT或为恢复服务启用候选角色。人员来源切换及旧reader回滚保持独立批准，详见[RBAC基础](../rbac-foundation.md)。

正式RBAC HTTP使用独立admin认证、QR activation/revocation和当前ROOT；旧ADMIN不成为ROOT。有效且无grant的会话可读取`/v1/admin/authorization/me/capabilities`得到false/[]，不是重新登录或放权依据；来源缺失/存储故障为503，不能回旧role。已实现能力默认纳入运行支持清单，运行支持不替代数据库目录状态或真实账号授权。V42仅增加审计发布序号，目标DDL及人员初始化保持独立批准。

授予/撤销断连或5xx按原key/原请求恢复固定收据，不换key盲重试；读取当前Assignment再判断现在状态。expiresAt现按正式Contract接受明确offset及0–6位小数秒，规范化UTC微秒；超精度（含多余零）400 validation-error，禁止截断或舍入。等价offset/尾零同语义重放，差1微秒同key冲突；到期或撤销后仍返回原固定收据，不重新授予。ROOT必须省略期限，生产审计/幂等保留和归档仍待批准。cursor过期、重启/换节点或授权/目录变更导致400 CURSOR_STALE时从首屏刷新，不拼接旧页；跨节点续页策略需受控部署评审。仍无Bootstrap、来源切换、角色编辑/启停或匿名恢复HTTP。

1. 请求 `GET /actuator/health/liveness`：失败表示进程或应用生命周期异常。
2. 请求 `GET /actuator/health/readiness`：`db` 或 `redis` 会影响 readiness；根 health 不显示详情。
3. 用有界 `docker logs --since 30m --tail 2000`（已确认实际容器名）先按 `traceId`，再按 `operationId/messageId/jobId` 检索；文件模式读取 `${LOG_DIR}/mini-app.log`。不要依赖 DEBUG 才发现终止失败。
4. 用下方 MySQL/Redis 查询确认 durable truth 与 transport 状态。metric 是发现入口，不是最终状态权威。

仓库 `compose.local.yaml` 只定义本地 Flyway；`config/logging/compose.logging.yaml` 是日志覆盖片段，`backend` 是待替换的服务名，不是已登记的生产服务。生产进程、容器名、Nginx 和实际 logging driver 仍须在部署目标核验。
仓库也没有生产 MySQL server 配置；不要为日常排障开启 general log 或应用全量 SQL INFO。下一轮在真实目标只读核对 slow-query 开关、阈值和日志轮转后再决定是否启用。

## 2. HTTP 5xx rises

- 看 `http.server.requests` 的 rate、status 与 templated `uri`；当前仓库未部署 meter backend 时，从入口代理状态和应用 ERROR 日志人工判断。
- 用响应 `X-Trace-Id` 检索 JSON `traceId`（文本模式为 `traceId=<value>`）。同一次非预期 MVC 失败应只在 `GlobalExceptionHandler` 记录一次异常类型/位置/cause，异常 message 统一排除；请求完成摘要不再打印堆栈。
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

扫码登录默认接入，无独立启用开关；AppID 复用 wx.ma。正式环境 env-version 默认 release，dev 默认 develop，`ADMIN_QR_LOGIN_ENV_VERSION` 可覆盖。确认页固定 `subpkg_setting/pages/admin-login-confirm/index`，需核对目标 AppID/env/page 和实际页面发布；dev 默认 check-path=false，正式环境默认 true，`ADMIN_QR_LOGIN_CHECK_PATH` 可覆盖。生成码成功不等于真机页面可打开。

session TTL 不超过 5 分钟，终态保留至少到 expiresAt 后 5 分钟，poll interval 至少 1000ms，Admin Token TTL 必须覆盖恢复。核对 Web origin、私有 Header、JSON/PNG/错误响应与代理的 no-store。默认全局 60 create/min、2 分钟图像 TTL、最大 2MiB PNG 的理论图像预算约 240MiB；按真实码大小和可用 Redis 内存调整额度，不能据本地测试认定生产容量充足。

## 13. 用户资料安全检查与恢复

业务保证见 [媒体与用户资料](../domain.md#媒体与用户资料)，迁移和回滚边界见 [数据库说明](../../src/main/resources/db/README.md)。资料写入直接执行必要检查，无独立总开关；检查拒绝或不可用时保留原有效资料。

核对微信文本检查、图片审核 Job、验签 callback、COS 服务端 COPY 和快照读取链路。有效图片位于 `user/retained/{userId}/{random}`，客户端上传权限不能写该前缀；原始 `user/upload/` 对象不能直接认定为已审核快照。快照先登记清理记录再复制，COPY 失败或未知结果不发布，由现有 PENDING cleanup 处理。

图片检查等待预算由 `app.user-profile.media-check-wait-millis` 控制，默认及上限为 10000ms；这是 HTTP 等待预算，不是微信完成时延承诺。超时或缺完整结论返回 503，不返回自动生效的 pending/202；迟到 Job/callback 只保留审核事实，不能提交或清空资料。用户需重新提交，不能后台盲重发未知 provider 写。

并发请求在最终短事务重新检查 `profile_revision`、权限和媒体引用，旧 revision 不得覆盖后提交的资料。排障先区分内容拒绝、检查不可用、并发版本变化和媒体引用错误，不绕过检查或直接改库认定通过。恢复须同时核对资料有效值、审核事实、快照引用及残留 Job；不能直接恢复旧乐观 writer/callback 版本。

## 14. 附件与媒体生命周期切换

V74 按 file-foundation-v1-r2 区分临时上传、已登记 CANDIDATE、业务已接管 FORMAL、业务绑定媒体和删除墓碑。保存失败保留候选原期限；正式附件解除引用仍保留。本人独立删除先检查引用/历史保留，再持久化删除墓碑及清理意图；现有每 10 分钟任务执行对象回收。软删除业务不产生自动永久清除。

切换先完成新客户端 PUT/分片必带 x-cos-forbid-overwrite:true、退出 PostObject、原键原请求登记恢复与 retention/expiresAt 适配；停止并排空旧 writer/cleanup，迁移 V74 后一致启用登记、接管和清理。新签发凭证使用精确对象及强制头条件，公开直传桶版本控制须为 Off，签发身份需有读取桶版本配置权限；证据不可读或状态不符返回 503，不降级旧授权。同步核对 CORS、微信域名与客户端 SDK 自动分片。

安全保证启用前必须排空/阻断已经签发的宽权限旧凭证，核对应用身份不能获得服务端快照前缀的客户端写权。既有源对象可变不能冒充快照不可变。源码/单元策略检查不证明云端 CAM 实际拒绝漏头、篡改头、PostObject、覆盖、ACL 写与未分配键；需在授权目标逐项测试，保留实际桶/版本/凭证截止时间证据。未核实旧端退出和切换窗口前不宣称已完成跨端上线。

V30 切换需针对目标环境明确授权、备份及恢复边界。先只读执行 db/validation/media_attachment_lifecycle_preflight.sql，核对部署的 public-url-prefix、custom-domain、bucket/region 及历史域名。按精确 URI/path 解码核对 URL/objectKey、上传者、实际引用与 COS 存在；V11 的 registered=1 也覆盖历史帖子，不能据此全量保护或绑定。重复元数据保留全部 ID，选稳定的首次接管者。没有上传记录或已有直接业务 BOUND 时不伪造归属，不覆盖原绑定。

停止并排空全部旧媒体 writer/cleanup 实例（确认旧请求和远程删除已结束，不仅停止定时触发），执行 V30，再按 <=100 行审核批次加载带 evidence_ref 的临时映射并执行 media_attachment_lifecycle_repair.sql。脚本默认回滚，只有明确批准并核对 batch_eligible、输出与行数后才提交；迁移 DDL 与回填不是一个全事务。曾 CLEANING 的对象只有排空旧执行者并确认对象仍存在才可修复；缺失对象需版本／备份恢复或重新上传、登记新资源并替换引用，回填状态不能恢复文件。

重新开放 cleanup 前必须解决非规范编码、旧域名、query URL 等无法精确匹配的历史受管资源；新代码的保护查询不是任意历史 URL 的自动归属证明。保存已审核映射及执行结果，复查元数据 key、BOUND 归属、引用、COS HEAD 与待清理队列。禁止旧清理实例混跑或直接回滚旧 JAR。生产对象消失需具体 key、上传记录快照、COS 删除审计／版本及 lifecycle 配置证据；批次数量日志不足以归因。

旧数据缺上传记录时，媒体替换不会伪造上传者或强行删除对象，记录未接管删除意图的 WARN，由 owner 核对后受控处理。当前机制不是整个 bucket 的 inventory 回收：绕过本站凭证／登记的对象、软删除保留内容、有效但暂未引用的附件，以及历史 afterCommit 崩溃残留 BOUND 均需按各自事实核对，不能扫 bucket 按目录或文件年龄一律删除。

### 14.1 COS 环境隔离与混用桶迁移

目标拓扑、职责与配置含义见[架构 §2.2](../architecture.md#22-cos-环境与访问类别隔离)。先形成可审查的四桶/地域/域名/应用身份清单，再执行云端写操作。现有桶作为 legacy 混用迁移源保留；“从此禁止新测试数据写入生产资源”必须由实际配置切换与 CAM 授权收口共同实现，不能只改桶名注释或 objectKey 前缀。四个新桶尚未具备时，不把空示例写进现用配置或切换到不存在的资源。

1. **只读盘点。** 分别记录实际 dev/prod 进程使用的 bucket、region、public URL/CDN、私有桶，以及身份标识的脱敏指纹。核对两环境是否共用桶/密钥/域名。用有权巡检的身份读取 bucket ACL/policy、CDN 服务授权与访问鉴权、CORS、版本控制、复制/事件和 lifecycle；403 表示未能核实，不能视作配置正确。应用身份不为方便巡检而取得全账户权限。
2. **新资源准备。** 先建测试两桶及测试应用身份，验证测试身份不能读写生产桶；再准备生产两桶及生产身份。桶策略按动作、资源与 prefix 最小授权。运行私有材料能力所需的 GetBucketACL/GetObjectACL、读/复制/删除、STS 下发权限应明确区分；云端建桶/修改策略/全量清单属于独立运维权限。当前公开媒体使用本环境自定义源站域名直连 COS（公有读私有写），不要求启用 CDN；私有桶保持私有。浏览器 CORS 和微信 request/uploadFile/downloadFile 合法域名另验。
3. **历史归属清单。** 同时盘点客户端静态资源（如分享默认图），它们不一定出现在数据库引用中；只查媒体表不等于完整客户端切换。 交叉核对生产/测试数据库的媒体上传记录、附件、业务引用及 Points 文件记录，与 COS 实际 key/version/大小/类型/内容摘要对应。每项至少记录 source bucket/region/key/version、目标 bucket/key、业务引用、环境判定及证据、公开/私有类别、复制/核验状态。资源未知、无记录或跨环境共用引用时进入待核实集合；不得按上传时间、文件夹名、用户 ID 或“看起来像测试”删除。数据库重复 ID 或 key 不代表两环境同一业务资源。
4. **先切测试。** 新测试上传进入测试桶。只有确认属于测试且确有需要的历史对象才复制；测试不能继续依赖生产桶或生产 CDN。撤掉测试应用身份对生产/旧混用资源的访问前，先检查生产是否仍使用同一旧身份；禁止直接撤销双方仍共用的凭据导致生产中断。
5. **生产受控切换。** 保存数据库受影响元数据、配置、策略和迁移清单快照；停止并排空该环境的媒体上传、替换、清理和相关远程任务 writer。按核准清单复制到目标桶，保持 key 并逐批校验实际内容与 ACL，不能只数文件。COS COPY 与数据库更新不是一个事务。对已确认成功复制项执行精确 URL/引用映射；无法解析的历史 URL 不做字符串全局替换。若保留原生产媒体域名，则先确认该域名旧内容均可在新公开源站读取，再切换其源站绑定/解析；当前不新增 CDN 回源。配置切换同时核对私有 Provider、STS/CORS、签名读取和业务引用。
6. **恢复与验收。** 先验证跨环境拒绝、公开媒体访问、匿名访问私有对象被拒绝、私有签名过期、跨人/越权读取拒绝；再跑真实上传→登记→绑定→读取链路、响应丢失恢复和清理保护。Points 图片通过 WechatPointMaterialSafety 调用微信异步检查，配置私有桶不等于微信身份、图片下载与回调已验收。只有这些链路通过后才恢复 writer/cleanup。公开读取成功不能替代私有隔离证明。
7. **保留和回退。** 迁移期间不删除旧桶或未知对象；复制完成也不是删除授权。保留旧对象、核验清单、数据库映射和域名/策略快照，按明确保留期复核后再批准回收。恢复写入前可按快照撤回配置/映射；一旦新桶接受新写入，回退必须先识别并回迁新对象和对应引用，不能只切回旧 COS_BUCKET。私有数据不能通过回退到公开桶恢复可用性。

可丢弃对象按现有业务 owner 回收。尤其 `points/retained/` 同时可能包含未绑定和已正式绑定对象，不能给整个前缀配置统一自动删除；`points/upload/` 也须核对上传/登记窗口和恢复责任。测试库数据可重建不代表旧混用桶对象都可删除。版本控制与访问日志提供恢复/追踪证据，不代替业务永久删除授权。

验收证据分层记录：本地配置绑定/静态检查、COS ACL/policy/STS、真实无签名/有效签名/过期签名访问、CDN 和 CORS、小程序设备、数据库引用与清理保护、生产切换分别标 PASS/FAIL/BLOCKED/NOT RUN。源码或配置片段不证明已建桶、已隔离、已迁移或 Points 已可用。

参考：[COS 最小权限](https://intl.cloud.tencent.com/zh/document/product/436/32972)、[桶策略](https://cloud.tencent.com/document/product/436/18031)、[CDN 回源与访问鉴权](https://cloud.tencent.com/document/api/436/18669)、[版本控制](https://intl.cloud.tencent.com/zh/document/product/436/19883)。

### 14.2 已确认四桶的应用身份准备

测试和生产使用同一套文章上传凭证逻辑，在申请 STS 前读取各自公开桶的版本控制状态。两份应用策略分别授予本环境公开桶的 `cos:GetBucketVersioning`。若查询返回 `AccessDenied`，检查运行身份是否关联对应环境的权限。查询成功后，凭证签发仍要求 SDK 返回 `Off`。

当前选定资源为 APPID `1356013274`、地域 `ap-nanjing`，公开/私有桶分别使用 `jualn-campus-{test|prod}-{public|private}-1356013274`。名称及地域来自资源 owner 的创建结果；ACL、policy、默认加密、CORS、CDN 和外部调用尚须实际读取核对，列表中的“指定用户”不替代权限检查。

在 CAM 创建独立的 `jualn-campus-test-app`、`jualn-campus-prod-app` 程序访问用户，分别关联[测试应用策略](../../config/cos/app-test-policy.json)与[生产应用策略](../../config/cos/app-prod-policy.json)。策略是用户策略，不是存储桶 policy，不带 principal；在“策略 → 新建自定义策略 → 按策略语法创建”导入后，关联对应用户。不要再关联 COS 全权限、可访问另一环境的用户组或旧混用桶权限；应用不能修改桶配置、对象 ACL、创建或删除桶。用于准备策略的控制台管理身份保持独立。

这两份策略按业务命名空间覆盖对象读写、分片和必要清理：公开桶包含 `user`、`activity`、`exam`、`official-content`、`article`，私有桶包含 `post`、`comment`、`growth-record`、`points`、`learning-task`，均只开放各自的 `upload/*`、`retained/*`；另授予私有桶 ACL/Policy/版本状态读取及公开桶版本状态读取。COPY 按源 GetObject 与目标 PutObject 授权。公开媒体客户端仍由后端按当前上传对象下发更窄的 STS；Points 保持精确 PutObject/私有 ACL/禁止覆盖等会话限制。父用户与 STS 策略共同约束临时密钥，不能只靠临时 policy 弥补父用户的跨环境全权限。策略不额外添加 STS 全权限、KMS、数据万象/视频审核或其他业务存储权限；实际 Provider 需要这些能力时按具体 API 与目标资源补齐，不能称本文件涵盖全部云产品。

后端只维护 `.env.dev` / `.env.prod` 两份完整环境文件，均被 Git 忽略。测试密钥、测试双桶及媒体域名直接填写在 `.env.dev`，生产对应值填写在 `.env.prod`，与数据库、微信等服务配置一起维护，不另设 COS 文件。旧桶配置逐行注释保留，不参与加载；新环境缺失的密钥、域名留空待填，不能借用旧桶密钥补齐。不在聊天、日志、策略 JSON 或受版本控制文件中传递密钥。启动器或部署平台须加载对应文件的环境变量并选择匹配的 Spring profile（dev/prod）；文件本身不会由 Spring 自动读取。配置整理不等于云端切换：仍须核对四桶、两身份、域名和权限，按 §14.1 完成存量引用及公开权限验收后再重启切换。

测试浏览器 CORS 准备值：仅在测试桶中添加实际开发页面 Origin（当前本地为 `http://127.0.0.1:5173`，如实际使用 `http://localhost:5173` 则另列）；公开测试桶允许 PUT/POST/GET/HEAD/DELETE，支持现有普通及分片上传；私有测试桶只需 PUT/GET/HEAD，Points 不开放 POST/分片。允许请求头可用 `*`，暴露 `ETag`、`x-cos-request-id`，MaxAgeSeconds 可用 600。新增部署域名时精确补充，不给生产桶添加本地 Origin。CORS 不授予对象读取权限，也不代替 bucket policy 或业务鉴权。

尚未准备媒体域名时，可先使用 COS 原生端点完成真实 SDK/STS 上传、ACL、默认加密、复制与带签名读取的组件验证；浏览器上传仍须通过桶 CORS 预检。这不要求先配置 CDN，但不证明匿名公开图片展示或整条业务链路可用。域名配置可在这些组件验证后进行。

公开测试桶按当前目标采用公有读私有写和自定义源站域名直连，暂不开启 CDN。准备实际测试媒体域名和 HTTPS，核对桶 ACL、对象 ACL 及策略无意外拒绝后填写 `COS_PUBLIC_URL_PREFIX`、`COS_CUSTOM_DOMAIN`；私有测试桶不接公开分发入口。前期迁移对象显式 private，须按已核准公开清单调整为继承公开桶或公有读，不能只改桶 ACL。新域名需要在浏览器和微信客户端分别验证。当前 `cos.custom-domain` 为必填，不能为通过启动校验填一个不可访问的地址，也不能复用仍回源旧桶的生产域名。

操作完成后先只读验证测试私有桶 ACL 和对象权限，再用明确归属的合成文件验证上传、签名读取、匿名拒绝及清理。Points 图片已实现微信检查适配，须先应用 V69，再验证原上传账号的小程序 OpenID、短期私有签名可供微信下载、真实回调及登记。PDF/Word/VIDEO 暂不做内容审核，仍保留格式/大小/私有ACL校验；不需要为本次积分材料开通数据万象或全桶自动审核。未完成环境验收前，配置四桶/身份不代表积分业务上传已接通。待测试存储、分发、CORS、内容安全及业务链路验收后，再按 §14.1 切换测试环境；生产配置和旧对象保留到单独的切换步骤。

依据：[用户策略](https://cloud.tencent.com/document/product/436/68280)、[COS API 授权映射](https://cloud.tencent.com/document/product/436/31923)、[STS 权限交集](https://cloud.tencent.cn/document/product/436/68283)。策略文件的 JSON/范围检查不等于 CAM 已接受策略或真实 COS 授权通过。

前端切换不做全局 URL 字符串重写：业务读取继续使用后端 URL；上传 transport 使用后端下发的环境凭证字段；私有 access URL 原样短期消费。Mini 的 defaultShareImageUrl 由 config/index.ts 环境配置集中维护，实际域名就绪后核对静态对象已复制、公开读取及小程序域名配置。保存接口响应、凭证 customDomain 和实际访问端点的对应证据。

环境对应关系：Mini develop/trial → 测试 API，release → 正式 API；Web 开发由 DEV_API_PROXY_TARGET 选择后端，正式由部署 /api 反向代理选择。公开/私有桶不由客户端 mode 再判断，而由后端分别使用 COS_BUCKET / COS_PRIVATE_BUCKET 签发上传凭证及访问 URL。四个原生端点分别为 jualn-campus-{test|prod}-{public|private}-1356013274.cos.ap-nanjing.myqcloud.com；小程序须按 request / uploadFile / downloadFile 的实际路径配置合法域名，不能仅设置公开媒体域名。测试和正式身份只允许自身两个桶，实际凭证与访问需分别验收。

待填写域名时，COS_PUBLIC_URL_PREFIX 填完整 HTTPS 访问前缀，COS_CUSTOM_DOMAIN 填主机名（当前共享上传端兼容后者带协议，但统一配置使用主机名）；Mini shareImages.test / shareImages.production 为对应环境的完整公开分享图 URL。前端不填写私有桶自定义域名或长期 COS 密钥。当前文件片段未自动启用、旧对象 ACL 和数据库 URL 未切换，域名留空不能视为运行就绪。

### 14.3 积分图片微信检查恢复

V73 将普通审核表的时间统一为北京时间，只把旧 `WechatPointMaterialSafety.reserve` 明确按 UTC 写入的 `content_audit_log` 行（`target_type=12 AND audit_source=1`）的 `created_at` 转成 `+08:00`。不变更积分文件期限、Points 账务、Person / RBAC 等已有 UTC 数据，也不重发检查或补写审核结论。执行前停止相关审核 writer，核对原版本、实际写入来源、行数与 ID 范围，并保存目标行的 `id,created_at` 原值及可恢复备份；若存在其他写入来源或已被人工平移的行，应停止迁移并逐行确认，不能直接运行统一 UPDATE。按既有 Flyway / 手动管理方式执行一次 V73 后部署匹配新代码，确认新审核记录使用北京时间，JDBC 会话 `SELECT @@session.time_zone` 为 `+08:00`，`NOW()` 与 `UTC_TIMESTAMP()` 相差 8 小时。不要直接重跑 SQL，也不要混跑旧 writer。

回退时先停止 writer，按已保存的 ID 和原 `created_at` 精确恢复，并核对迁移后的新行未被覆盖；不得把整张表或所有 scene=12 时间统一减 8 小时。需要回退旧代码时，其后续 UTC 写入仍需纳入下一次迁移前核验。V73 的隔离迁移测试不代表业务库已修复；未核实其他历史业务时间来源时保留其原值。

V69 增加 media_point_file.safety_audit_log_id 外键和唯一约束，并拒绝已有未知 scene=12 数据。迁移不补写旧材料安全结论，不改现有登记/绑定事实。升级前按目标库备份并核对 scene=12 碰撞；先迁移再部署匹配代码。图片登记在原有本人与受理资格检查后保留不可变私有对象，短事务绑定自动审核日志，再于事务外向微信提交一次。最多等待10秒，尚无明确结果返回503 CONTENT_SAFETY；原 body/key 重试只读既有日志。

检查时仅向微信提供15分钟私有签名，禁止在日志、历史或 Job 保存 URL。文档与视频免内容审核不免格式、大小、所有权和私有ACL验证。回调只保存安全事实，不登记文件、不授予积分。真实验收应覆盖图片通过/拒绝/不完整回调、原账号 OpenID、私有对象下载、同 key 恢复及未登记文件回收。

若提交超时或进程在绑定日志后中断，保留原记录并核对 wx_trace_id 和回调；没有 trace 时无法证明已提交或未提交，禁止清空绑定或自动重发。先读取权威文件状态；确认仍未登记且无法恢复后，用户才可明确放弃该上传并以新上传意图重新选择文件，旧临时对象按原回收窗口处理。该恢复不重试积分申请或账务命令。

### 14.4 社区媒体私有读取切换

此项只切帖子/评论图片，不整体改公开媒体桶、头像、文章、活动附件或 Points 保留协议。配置 `MEDIA_PUBLIC_BASE_URL` 为实际 Backend HTTPS 根入口，`COS_PRIVATE_BUCKET` 指向本环境既有私有桶，与公开桶不同。应用身份只访问两桶所需对象，并允许 GetBucketACL/GetBucketPolicy/版本控制状态的只读核对；不可授予客户端读取、复制、删除、修改 ACL 或通配对象写入。私有桶策略/ACL不能授匿名，应用签发/复制时拒绝未知证据；控制台、其他身份或策略在运行时变更仍须运维巡检和告警，代码不代替云配置治理。

1. 先部署读取方支持：公开图片用原样完整 URL；作者待审预览用普通Bearer下载到本地临时文件；管理预览用admin URL和管理Bearer fetch/blob。不要把Token拼查询串或发往COS，不缓存失效内容。小程序上传按凭证bucket/region/customDomain定位，继续使用精确key和forbid-overwrite，不能硬编码公开源站。
2. 核对私有桶地域、ACL/policy、CORS、应用CAM、版本控制以及清理前缀。帖子和评论原始上传分别进入 `post/upload/`、`comment/upload/`，服务端核验快照分别进入 `post/retained/`、`comment/retained/`；原始上传过期、快照采信、正式引用仍复用数据库生命周期。共享私有桶中的 Points 永久保留内容不得按此社区候选规则清理。
3. 在单独批准的只读环境执行 `db/validation/community_media_preflight.sql`，结合桶 inventory、实际字节、对象版本/ACL及其他引用核对文件ID、上传证明和业务引用。只接受当前业务键规范；`UNRESOLVED_KEY`、`UNRESOLVED_UPLOAD_PROOF`、`UNSUPPORTED_KEY_REQUIRES_REVIEW`、共用对象及 URL-only 记录必须单独核实，不从作者或 Person 猜造证明，也不自动复制、改写或删除。
4. 验证真实匿名COS拒绝、公开→隐藏/删除/过期后的新请求404、作者私有与跨人拒绝、管理同源职责、具体评论/占位线程、Range/HEAD、取消下载资源释放、微信取图与回调、候选清理和跨端设备。网关不得缓存/改写no-store，不以长TTL或裸COS重定向绕过控制；适当限制连接/请求速率与超时。已开始的响应和已下载副本不在撤回保证内；旧公开副本未禁用时只报告新入口收口，不能宣称历史直链已撤回。

云端切换必须有明确目标、备份、逐项核验清单和当前发布授权。本地实现/隔离测试不证明实际桶或旧链接已切换；源与目标对象处置、业务库变更及真实客户端分别留证。

## 15. 文档辅助填表诊断样本

文档辅助填表的样本采集默认关闭。调试时设置 `DOCUMENT_IMPORT_DIAGNOSTICS_ENABLED=true`，
可用 `DOCUMENT_IMPORT_DIAGNOSTICS_DIRECTORY` 指定仅服务进程与运维可读取的目录（默认 `target/document-import-samples`，不得设为静态站点目录）。
每次实际执行输出 `<importId>.json`，包含文件名、目标、提取范围、提取文本、实际 prompt、模型名称、完整模型文本、规范化候选、提醒与具体拒绝原因。
`initialValidation`、`rejected`、`evidenceMatches` 对应原模型输出的首次校验；`validation` 为实际返回的最终候选。
`sectionsRecovery` 区分 UNCHANGED、PAGE_FOOTER_EVIDENCE_RESTORED、EXCERPTS_RELOCATED、SOURCE_TEXT_FALLBACK 及未恢复原因；正文恢复不调用模型，`recoveryModelCalls` 记录时间线引用修复的实际请求数（0 或 1）。
跨页引文恢复仅适用于引用中每行能原样定位、顺序一致，且缺失间隔只含独立数字页脚与排版空白的情况；
返回包含页脚的完整连续原文，而非删除数字获得匹配。冻结所有章节内容，再执行完整 Validator，并保留原有其他通过候选。
夹有业务条件、年份文字、无法定位的行或超出引用长度时不采用此恢复，不能把该机制当作模糊或语义匹配。
仅当全部 PLAIN_TEXT 章节的完整标题及内容可定位，且标题与正文相邻、之间只有排版空白时冻结 value 并重建 excerpts；仍重新执行完整 Validator。
否则对缺失或被拒绝的正文生成一章“文档提取原文（需人工核对）”，逐字保留不超过 50000 字符的提取文本，
引用按连续原文切分，使用既有 INVALID_SUGGESTION 明确提醒这不是 AI 已整理正文，保存前需整理及核对公开范围。
原文回退可能包含页码、落款、附件说明、联系人及电话，不做静默删除；公共 SSE 仍是普通 /sections 候选，
可通过章节标题和提醒人工辨认，但没有独立的机器可判定 origin 字段。采纳只是写入管理端本地草稿，仍需管理员审阅并决定保存内容。
该结果不是原模型章节通过，也不是完整语义提取证明。
重复 /sections 不自动选取；重验失败或正文超限则维持原结果；已通过正文及其他候选保持不变，timeline 不自动修复。
`rejected.stage` 区分 candidate-structure、target、source-evidence、field-schema、array-schema、source-semantics 与 cross-field；
sections/timeline 的元素结构错误包含从 0 开始的元素下标，引用错误包含引用下标。数组仍为完整原子候选，不跳过错误元素。
`evidenceMatches` 记录每项已匹配引用的 target、excerptIndex、mode 和原文半开区间 sourceStart/sourceEnd；
该记录可能属于随后被拒绝的候选，不能作为候选已通过的计数。
mode 为 EXACT 或 LAYOUT_WHITESPACE：先精确匹配，再只归一化 ASCII 空格、制表符、CR/LF 的排版差异；
仅汉字之间及汉字与 ASCII 数字之间的空白允许省略（如 `10 月 13 日`）；数字之间和英文词界保留，
空行、换页符、页码及所有非空白字符不删除，不能把 `1 0` 合并为 `10`。
归一化成功后返回该区间的连续原文，保证公共 SSE 的 source.excerpts 仍可精确定位；不进行编辑距离或语义相似度放行。
机器时间的月日及钟点须有原文依据，年份可来自原文明确日期或同一文档无矛盾的通知编号、标题、落款等可靠上下文，
仅在没有可靠年份上下文时，按已确认的辅助填写策略使用当前 Asia/Shanghai 业务年份；上下文冲突仍拒绝确定年份。
时区可来自明确原文偏移或可信业务策略 Asia/Shanghai，不能把系统策略伪装成引用。
`timeEvidence` 记录原日期、年份上下文位置、钟点、时区来源与精度；也可能属于随后被拒绝的整体候选，不能作为通过计数。
原文分钟可补秒 00，明确到秒须保留；下午三点/三点半、晚上八点可按有依据的中文语义匹配。
日期级不补钟点，区间必须在原文中有连续范围连接，不能将不同材料的两个截止合成区间。
暂定或预计时间保留 TEXT；日期明确但具体钟点另行通知时，日期候选必须保留说明。
数字钟点 12:00 固定按中午解释，即使带上午、下午或凌晨前缀；00:00 不因此获得来源支持。
缺少年份的补全诊断使用 SYSTEM_CURRENT_BUSINESS_YEAR:Asia/Shanghai，区别于 SOURCE_EXPLICIT 和 DOCUMENT_CONTEXT；
日期、钟点、条件和真实精度仍须通过来源校验，不补造钟点或把待定安排转换成确定时间。
ACTIVITY_START/PUBLIC_EVENT_START 与正式 TimelineSemantic 共用类型定义，分别约束目标，并要求原文明确主体开始语义；OTHER 不自动猜测升级。
`outcome=validated` 仅表示模型结果完成校验，不代表 SSE 已送达、候选已采纳或业务已保存。
失败时仅保存已取得的阶段数据；提取失败没有模型输出，供应商流中断也不能从当前 Gateway 获取部分文本。
采集不增加模型调用，不改 prompt，不向普通日志输出正文或凭据；存储故障不改变填表结果。
目录达到 100 个 JSON 后停止新增并记录提醒，运维导出所需样本后清理，再继续采集；结束调试后关闭开关。
比较样本时同时提供人工期望及实际页面差异，按提取、模型、后端校验、前端映射定位，不能仅依据缺少候选就调整 prompt。
本地旧输出回放可显式设置 JVM 属性 `documentImport.samples=target/document-import-samples`，运行
`DocumentImportSampleRegressionTest`；汇总写入 `target/document-import-regression.json`，不调用模型。
关键事实词组命中仅是覆盖代理指标，不证明候选全部事实得到引用支持，也不验证 Prompt 改动效果。
另设 `documentImport.liveSamples=target/document-import-live-samples` 时，同一回放测试会用当前校验器
重验已完成的在线输出，并写入 `target/document-import-live-validation.json`，仍不调用模型。
`DocumentImportLiveRegressionTest` 默认关闭；显式设置 `DOCUMENT_IMPORT_LIVE_TEST=true` 和进程环境
`DEEP_SEEK_API_KEY` 后，使用三份既有提取文本各调用两次（六次初始调用，每次最多追加一次引用修复，合计最多十二次；不重试供应商错误）。
该测试不创建活动；完整本地样本位于 `target/document-import-live-samples`，汇总位于
`target/document-import-live-regression.json`，同样按上述敏感样本目录控制访问与清理。
重新运行时复用 Prompt、模型一致的已完成输出，保留中断样本；不自动重试供应商错误。
文档导入模块的 PDF 提取使用 PDFBox 位置排序；共享 DocParser 仍保持默认内容流顺序。未引入段落重组或 OCR。
三份实际单栏 PDF 已对照全部页面和非空白字符保留；这不证明任意多栏、表格或印章布局可靠。页码不删除，跨页引用仍需拆分。
显式设置 `documentImport.pdfDirectory` 可运行 `DocumentImportRealPdfTest`，原 PDF 必须保留在私有目录，比较文本及页面图写入 target。
同一属性下 `DocumentImportRealPdfTimeTest` 用三份原 PDF 验证确定性时间候选，记录于 `target/document-import-real-time-validation.json`；这不是模型生成测试。
`DocumentImportRoundTwoReplayTest` 同时重验九份旧输出和四份已完成在线输出，分别统计原模型章节、校验章节、原文回退及时间线。
在线测试设置 `documentImport.liveOutputDirectory` 可隔离新实验；提供 pdfDirectory 时通过当前 FileReader 重新读取原始 PDF。
时间线恢复仅在未保留 /timeline 且存在来源引用错误时最多追加一次请求，冻结完整 value，只让模型重新选取 excerpts；不得增删、重排节点或修改事实与时间。
原文明确宣传准备且误标开始的“启动阶段”可改为 OTHER，不能反向推测开始节点，也不能混用 Activity/PublicEvent 类型。
修复后对完整响应重新执行 Validator，并要求原先通过的其他候选保持相同；失败、断连、预算不足时保留初验结果，不输出部分数组。
Service 在 SSE 剩余时间不足一个读取空闲超时加五秒时跳过模型修复；修复仍受原 SSE 生命周期约束，不延长总超时。
私有诊断记录 timelineRecovery、timelineRecoveryMillis 和 recoveryModelCalls，不向普通日志输出文件内容或联系人。
线上报告应分别记录生成率、结构化正文保留与原文回退，不能把回退计为模型质量提高。缓存在线样本重验不再次付费修复，不等于新修复调用已验证。
`contentCoverage` 是内部确定性诊断，不改变来源接受规则。识别“一、…”等明确章节，在资格/规则/提交/工作要求/时间地点等章节中按完整事实句定位。
区分 VERBATIM_VALUE、CITED_REQUIRES_VALUE_REVIEW、OTHER_FIELD_EVIDENCE_REQUIRES_REVIEW、COVERAGE_NOT_ESTABLISHED 和 RAW_REFERENCE_ONLY；仅原样完整句能直接证明文字保留，
赛程/时间/地点章节也检查已通过 timeline/primaryLocation 的引用，避免要求正文重复专用字段；这种定位仍需人工核对值，不能掩盖工作要求等章节缺失。
有来源引用不证明候选改写包含全部条件，没有匹配也不证明改写遗漏。章节识别、句界和引用定位都有限，不能把这些计数当语义覆盖率或组织质量分数。
事实句须由原文引用区间联合覆盖全部非空白字符，少量交集不能代表整个要求已有依据。
覆盖诊断会定位相同引文的全部精确出现位置（每组最多 512 处，超限跳过诊断），避免独立句号等重复引文仅定位首处造成误补。
仅覆盖诊断可忽略独立一行的 `— 2 —` 等明确页脚；不删除原文、不改变 EvidenceMatcher 或来源接受规则，数字、日期及业务条件仍须覆盖。
已通过正文存在未建立覆盖依据的信息块时，按原章节追加可精确定位的事实句，标题标为“原文补充”，不改写原先通过的正文。
仅补 COVERAGE_NOT_ESTABLISHED 块；已完整引用的改写仍是语义覆盖未知，不自动重复追加。专用时间/地点字段的完整引用也可避免正文重复。
新增完整 sections 数组重新通过 Validator，所有旧章节和其他候选须保持相同；超出章节/引用限制或重验失败保留原结果。
sectionsCompletion 与 sectionsAppendedBlocks 分别记录补入模式和原文信息块数量；原文补充不计为 AI 整理或语义完整性证明。
原文回退不再追加章节。仍有覆盖缺口时复用 INCOMPLETE_CONFIGURATION /sections 提醒，不新增公共 SSE 字段。
无明确章节及诊断规模超限时不强行判断；联系人段落不作为工作要求事实。完整原文回退独立标记，不计为 AI 整理成功。
`DocumentImportThirdRoundAuditTest` 用实际 PDF 和已保存模型输出定位父级错误引用与各节点错误；隔离节点探针只作诊断，不输出部分时间线。
提供 `documentImport.thirdRoundSamples` 可追加新在线实验的确定性重验，报告位于 `target/document-import-round3-audit.json`。

## Article COS video audit

当前业务决定各模块视频不做自动内容审核，保持 `COS_VIDEO_AUDIT_ENABLED=false`；不需要开通数据万象。下述启用步骤仅供将来另行明确采用该能力时使用，不是当前部署前提。关闭审核不跳过权限、所有权、不可变留存或 MP4 文件头检查。Points 私有 MP4 沿用10MiB限制，并与 Article 共用可选的 `ARTICLE_FFPROBE_PATH`（映射到 `app.article.ffprobe-path`）。当前保持空值，视频上传及登记继续开放，仅检查 MP4 文件头，不验证编码、尺寸、时长或完整可解码性；Article 播放表示省略未知宽高和时长。以后填写有效绝对路径即可启用深度检查；已配置但不可用仍返回503，不静默降级。部署时先更新 Web / Mini 对缺省播放元数据的兼容，再关闭 Provider 深度检查。


实现与状态边界见 [Article](../article.md)，数据库新增 V37，Job 类型为 `audit.cos-video.check`。采用主动查询，无需新增公网回调或更改微信审核回调。

### 开通和配置

LearningTask V1 私有视频有独立承诺：不能使用上述 Article/Points 的文件头降级。`LEARNING_TASK_PRIVATE_BUCKET` 默认空；启用前核对私有桶/对象 ACL、精确写权限及禁止覆盖、单对象短期 HTTPS 读取、CORS 与小程序合法域名，并实际证明 Range 206 和签名到期后重新鉴权。VIDEO 只有在 `ARTICLE_FFPROBE_PATH` 对应程序可执行且完整 H.264/可选 AAC 校验可用时进入能力集合。未配置 LearningTask 桶为零能力，已配置但依赖未知为503；不因通用 COS 或其他业务私有桶就绪而开放。正式成果没有终态自动清理授权。客户端和云端验收完成前不得把隔离替身测试写作已启用证据。

1. 将现有 `COS_BUCKET` 对应桶绑定数据万象并开通视频审核；核对地域支持、计费及服务角色读取资源的权限。在控制台创建适合本业务的审核策略，确认所需的画面 / OCR / 音频场景；本实现发送 `DetectContent=1`。
2. 服务端现有 COS 子账号需有桶范围内的 `ci:CreateAuditingJobs`、`ci:DescribeAuditingJobs`，异步服务角色还涉及 `cam:PassRole` 与实际对象读取授权；按官方 [提交接口](https://cloud.tencent.com/document/product/436/119485) 和 [查询接口](https://cloud.tencent.com/document/api/436/119486) 核对最小资源范围。不要给客户端 STS 增加这些权限，不使用全权限策略代替核对。
3. 先执行 V37 并部署新 Handler，设置 `COS_VIDEO_AUDIT_BIZ_TYPE=<控制台策略ID>`、`COS_VIDEO_AUDIT_POLICY_REVISION=1`、`COS_VIDEO_AUDIT_ENABLED=true`。同一策略在控制台修改时同步递增 revision；只改策略内容而不改 revision 会继续复用旧结果。
4. 如需深度检查或重新开启依赖时长的视频审核，服务器部署 ffprobe 并配置 `app.article.ffprobe-path` 绝对路径，保持现有 COS 上传凭证 / CORS 配置。容器、编码和尺寸检查由 ffprobe 执行，不代表内容安全结果。Article 管理能力仍需获批的权限映射；不能通过开启 COS 绕过它。
5. 在指定验证环境使用正常、违规和疑似样例验证上传、不可变快照、任务、结果和发布。查询正常结果落库后重新发起发布；审核期间 503，违规 409，疑似继续拒绝发布。验证现有正文 / 引用 / ETag 未因失败或迟到结果改变，不把 provider 任务受理成功当通过。

### 查询和恢复

使用 `media_video_audit.id` 关联 Job 的 `subject_type=media-video-audit`、`subject_id` 和 payload.auditId；平台 DataId 固定为 `article-video-audit:<id>`。只读诊断示例：

```sql
SELECT id,attachment_id,policy_key,state,provider_job_id,poll_sequence,deadline_at,updated_at
FROM media_video_audit WHERE id=:audit_id;
SELECT id,status,attempt,max_attempts,last_error_category,last_error,next_run_at
FROM async_job WHERE job_type='audit.cos-video.check' AND subject_id=:audit_id ORDER BY id;
```

- PENDING 是未提交；RUNNING 是已取得 provider JobId，正常查询可重复。REVIEW / REJECT 不自动变 PASS，现阶段没有人工批准接口。FAILED / 截止超时不能放行。
- 查询失败复用 Job 的有界重试，耗尽后 Job DEAD；资源状态可能仍为 RUNNING，发布仍拒绝。排查配置 / 授权 / 结果格式后，通过既有受控 Job DEAD 恢复入口重试读取；不要新建第二个提交任务。接口结果过期、超过截止时间或资源被移除时不得手工伪造 PASS。
- SUBMITTING 表示远程提交前已持久化尝试，崩溃恢复或不确定响应转 UNKNOWN 并 DEAD。先在平台按 DataId 核对实际任务和不可变 objectKey，再确认桶、策略及资源大小。**不能删除记录、改回 PENDING 或直接重试 POST 来消除 UNKNOWN。**
- 找到唯一匹配的 provider JobId 后，按批准的运维变更流程、停止该任务 worker，并备份原记录。在短事务内锁定该审核行与对应 DEAD Job，核对原序号未变化、未过截止且状态仍 UNKNOWN，将 provider JobId 绑定并将状态改 RUNNING，保留原序号及策略快照；然后恢复同一 DEAD Job。后续签名 GET 仍需匹配 DataId / JobId / objectKey 才能写结果。该操作不是审核批准；没有增加对外恢复接口。多个候选、无法确认、无任务证据时保持阻断，由 provider / 运维 owner 核实，不能把“查不到”自动当成未提交。
- 停用审核配置会停止后续远程调用，已验证且通过原有授权的视频仍可发布，不等待机器审核结果，已排队 Job 可在有界尝试后 DEAD。重新开启时先检查并恢复既有任务；不清空审核事实重新收费。更换 bucket / region / 策略 / revision / 截帧间隔会产生不同策略指纹，并为新策略单独预占。

HTTP / 网络和 XML body 均有界，XML 禁用 DTD / 外部实体；不保存 OCR / ASR 全文、临时签名 URL、密钥或原始响应。查询响应超过 2 MiB 或结果不完整时拒绝批准并进入有界查询重试，长视频须核对该边界与实际结果规模；不能通过截断响应取顶层 PASS。CI 每秒采样不是逐帧审核的保证，也不承诺替代人工运营复核。


## Post organization / mentor QA

Post V40 的 preflight、升级和旧 writer/reader/审核 worker 切换以 [数据库入口](../../src/main/resources/db/README.md#post-organization-v40) 为准；独立 Person 存储切换另核对 V65/V66。正式回答由 Person owner 的 PersistentMentorQualification 读取当前绑定和持久资格；排障核对档案、绑定、核准、有效期、撤销与账号状态，不能以管理角色替代。出现 `/problems/mentor-qualification-unavailable` 不得改成默认允许或盲重试。403 表示已可靠否定资格或授权，503 表示资格来源缺失/不可用；作者追问不要求导师资格。

PATCH 失败按 [canonical Post contract](../../../contracts/api/paths/post.yaml) 的 Problem Details 处理：412 保留草稿、重读 view=edit 并人工合并；422/503 保留旧有效内容，不通过审核回调后台发布候选。独立 POST_EDIT 审核记录及 Job/Outbox 是检查证据，不能把完成状态当作有效 Post 已修改。只有明确批准且完成身份、真实媒体/审核及三端验收后才能开放正式导师回答；现有隔离测试不授予生产操作权限。


## RBAC 发布与恢复

### ROOT 运维与旧任命退出

服务器使用生产发布包中的 `tools/invoke-root-operations.sh` 和同包 `app.jar`，要求 Java 17。连接明确的 MySQL 8.4 主库，通过受控本地转发使用 loopback 连接。独立运维身份通过环境变量提供 `RBAC_INITIAL_DB_USER` 与 `RBAC_INITIAL_DB_PASSWORD`。执行 ROOT 生命周期前应用 V96，再校验 ROOT 目录和账号来源。

在已解包并通过 `sha256sum -c SHA256SUMS` 的发布目录运行。设置 `JDBC_URL` 为明确的 loopback JDBC 地址，再执行对应命令。以下以计划交接为例，首次初始化传 `BOOTSTRAP` 和原账号参数 `-`；应急恢复传 `RECOVERY`，原任命不存在时同样传 `-`。

```sh
umask 077
sh tools/invoke-root-operations.sh app.jar "$JDBC_URL" inventory
sh tools/invoke-root-operations.sh app.jar "$JDBC_URL" inspect TRANSFER "$ENVIRONMENT_ID" \
  "$OLD_USER_ID" "$NEW_USER_ID" "$EXECUTOR_REF" "$APPROVAL_REF" "$APPROVAL_FILE" > root-plan.json
sh tools/invoke-root-operations.sh app.jar "$JDBC_URL" root-plan.json
sh tools/invoke-root-operations.sh app.jar "$JDBC_URL" root-plan.json --execute "$APPROVED_PLAN_SHA256"
```

执行参数中的摘要使用审阅批准的计划文件摘要。本地 Windows 开发可继续使用 `tools/invoke-root-operations.ps1`。


| 操作 | 前提与结果 |
| --- | --- |
| `BOOTSTRAP` | 从未有 ROOT。提供正常候选账号，建立第一条 ROOT 任命。ROOT 目录可已就绪，或全部处于原始登记状态，由工具在同一事务启用 ROOT 四项权限。旧库的 LEGACY 来源初始化仍按历史迁移处理。 |
| `TRANSFER` | 正常计划交接。明确当前有效 ROOT 与新账号，确认接任账号可使用正常登录渠道。 |
| `RECOVERY` | 已有 ROOT 历史、原账号或凭据不可用。审批材料记录事故与处置依据。仍有未撤销 ROOT 时填写该账号，无当前任命则省略；新账号必须正常。 |

`Inspect` 生成绑定目标服务器 UUID、数据库、授权状态和审批证据的计划。保存为 UTF-8 JSON，复核目标账号、操作类型及 `dependentAuthorizations`，再用 `DryRun -PlanPath <plan.json>` 获取精确摘要。批准后用 `Execute -PlanPath <plan.json> -ApprovedPlanSha256 <摘要>` 执行。计划只在生成后 30 分钟内可首次提交。未知提交时保留原计划和摘要重放。工具在同一事务内更新 ROOT、版本和离线审计。交接与恢复后，旧 ROOT 任命依赖的职责和委派按原规则失效；按已批准的范围重新签发，检查原账号失去所有者能力和新账号扫码成功。

部署统一授权收口之前，使用 `Inventory` 获取尚在期限内的独立普通任命，核对每笔用途，在旧服务仍可用时补发明确等价的统一职责。从前的独立任命在新服务中只供历史查询。没有等价职责或缺少范围证据时由业务负责人确认。完成后先发布兼容读取旧任命响应的 Admin Web，再更新后端，验证原任命写接口退出、旧独立权限失效、统一职责照常生效。生产环境保留已审批计划、固定收据和核验记录。


### 放行条件与未决项

| 责任 | 发布前所需事实 |
|---|---|
| 业务与授权 owner | 明确目标环境、真实人员、完整角色/namespace/业务范围、允许收紧的旧效果、期限与替补责任；不把旧 ADMIN/OPR 候选码表直接作为 grant |
| 数据与目录 | Authority 无缺行/损坏、无未解释 shadow，受控字节/摘要与运行支持及完整角色一致；旧 writer 已停止；当前 Person/绑定及业务资格单独核对 |
| Provider 与消费者 | 所选切片的真实 QR/会话、撤权/到期、客户端版本与普通 Token 兼容、安全/微信/COS 证据；未接通业务明确排除，不以 ROOT 绕过 |
| 执行与发布 | 不可变制品、目标/窗口/执行与复核人、批准清单、迁移前备份及恢复责任，先预发布；生产首次切源/更正不沿用旧测试批准 |
| 审计保留 | 现有成功事实/原 key/指纹/版本/原收据无清理入口。D06 的合规依据、保留/删除/匿名化、访问名单、备份过期和复审日期仍需数据责任人确认；不自定法定期限 |
| 备份与拓扑 | 独立故障域备份、最新撤销/来源/key 水位及恢复演练；RPO/RTO、频率、密钥、成本、单/多实例和跨节点续页均由实际发布方案明确，不把候选目标写成已达成保证 |

当前在线保留行为不代表已满足合规。若要归档后裁剪，先解决历史查询、原成功收据与永久去重域，必要时先改契约；不能直接 DELETE。现有审计 HTTP 只对当前 ROOT 且具 audit-log:read 开放，不能借 user:read 或数据库导出扩大访问。

首次 Authority 建立按 [RBAC 基础 §G](../rbac-foundation.md#g-authority-建立部署顺序与验证)；无 ROOT 首次迁移/更正按 [离线治理 §8](../rbac-offline-governance.md#8-指定初始-root-的运维迁移)。目标无 Flyway history 不自动 baseline/repair，不重复首次引导。缺失 effect、原收据或不可解释来源保持拒绝，不补占位审计。

### 从旧备份恢复

1. 保持管理流量、治理及相关 writer 关闭，保全故障库、日志和唯一较新证据；确定恢复时间/GTID/binlog 水位。
2. 在独立目标恢复全库及 history、账号限制、Authority、Assignment、Change、offline operation/effect、目录与永久收据；核对事务一致性、唯一域及引用。
3. 对照独立批准/差异清单、旧库与 binlog，逐项核对最新撤销、封禁、离职、到期、目录停用和切源。到期按当前主库时间判断，旧 role 和 ACTIVE 不能直接恢复权限。
4. 无法证明最新授权或去重身份完整性时保持受影响入口维护；范围不明则关闭整个管理面。只能通过获准工具补齐可靠事实，不重放成功收据为新 grant，不删旧成功事实。
5. Redis 旧快照可能复活 Token/activation；受控使受影响会话失效并重新真实登录，不泛删共享 Redis。按最终制品验证当前 Reader、目录、权限拒绝及恢复链路后再开放。

旧评审原始输入、逐效果 CSV 和检查 JSON 保存在 `rbac-v1-release-review/`，仅用于回溯采集时点，不提供当前 Go/No-Go 或新执行授权。

## 活动发现切换

1. 在 V30 目标库运行只读 [preflight](../../src/main/resources/db/validation/activity_discovery_preflight.sql)，核查前四组结构阻断项，以及后两组未知 IDs/位掩码待转换记录。阻断项必须按真实业务事实解决；未知 ID 单独记录，不自动猜名。
2. 核实实际 Flyway history、结构及所有尚未应用迁移的独立切换要求，尤其 [数据库 README](../../src/main/resources/db/README.md) 中 V30 媒体切换。取得并验证迁移前备份，记录受众原值与版本用于前后核对。
3. 停止所有旧活动 writer 和应用实例，再通过既有发布流程执行迁移。不要混跑旧 JAR；旧版本仍依赖将删除的列。
4. 核对 V31 成功 history、列已删除、记录数、受众表示与版本变化；启动新应用，检查管理保存/读取/发布，以及 ALL/CAMPUS/DEPARTMENT、分页和搜索的真实 HTTP，再恢复流量。

MySQL DDL 不能保证整个脚本回滚。若失败，保持停写，核查实际更新、列与 history 状态；必要时恢复经验证的备份后重新发布。不要只执行 Flyway repair 后盲目重试，也不能直接回滚旧 JAR。产生新写入后，恢复必须包含这些数据的保留方案，不能覆盖掉新增业务事实。

## Activity Reminder 切换

Activity V1 当前代码依赖 V84。先按 [数据库说明](../../src/main/resources/db/README.md#activity-v1--v84) 核对 V83 与备份、主时间/精度、持续性业务选择、公开摘要来源及状态3时间证据；迁移只默认普通活动，不自动推断持续性。Provider 新读取先行，固定受支持客户端后统一切换报名/取消写语义，必要时维护窗口；旧二进制不了解新准入边界，不能随机混跑。恢复活动只到 UNPUBLISHED，不恢复提醒。当前有效期内可补 Activity 必要提醒，沿原 occurrence 去重，不能通过删通知/换 Job 身份重发历史事实。隔离验证与真实 Worker/微信、生产切换分别记录，本文不授权目标环境执行。


先在获准目标运行 Activity Reminder 只读 preflight，盘点旧 scope/anchor、notify_enable=false、旧 v1 Job/UNKNOWN 和 DB/session 时间口径；未知用户意愿不自动回填。停止旧 fan-out、通知 writer 和 worker 后执行 V33，核对实际 history/DDL/数据，再切匹配新二进制。旧 JAR 不理解新规则/类型，不能当作语义回滚。

合法 overdue 旧意图保留原 source identity，不补建过去未创建的提醒；恢复仍核对当前关系、原 anchor、ownership 和剩余有效期。站内事实不因微信失败撤销，UNKNOWN 不直接重发。业务规则与尚未决定的时间/地点细则见 [提醒专题 §11](../reminder-notification.md#11-activity-reminder-v1-实现与兼容边界)，验证方法见 commands。

## 内容安全审核结果未知与版本切换

内容安全新轮次、原提交版本、全部检查和恢复尝试是判断依据，不能只看旧日志 final_result 或旧 status。先按 taskId 读取完整 review/evidence，再沿检查所属 Job/Outbox 排查。确证未调用 Provider 的失败允许既有 Job 生成同轮替代尝试；失去远程结果后不得盲重发，媒体等待原 trace 的可信 callback，无查询/回调证据的未知文字结果保持阻断。不得手工改为 pass、清状态或以人工决定代替必要检查。历史活动任务与旧 ID 无法定位版本的处置须另行批准。切换、回退、证据边界及方案比较统一见[内容安全审核](../content-safety-review.md#旧数据与活动历史)，测试资源前提见[commands](../../governance/commands.md#内容安全审核专项验证)。本轮未执行业务库迁移、生产处置或部署。


### 通知生命周期恢复

先核对 `notification` 的 deleted_at / created_at / inbox_seq、Delivery 和关联 Job；站内到期和删除不因证据保留而恢复。V75 只做 expand，不改历史 readAt、createdAt、inbox_seq 或 head；在真实目标确认列/约束、baseline、旧 writer 与备份后通过既有 Flyway 流程执行，不从一次性环境推导业务库迁移许可。回退应用必须仍认识删除/到期规则，不能启用旧 reader 复活内容。

自动维护每批 100、每轮 10 批/5 秒，观察 cleanup counter、backlog、unclassified 与异常类别；数据库恢复后可重入 `cleanup()`，不要手工 DELETE 全表。UNKNOWN 无权威结果时禁止 ordinary retry；当前 ROOT 可通过内部 `NotificationDeliveryResolutionService.resolve` 记录证据引用与结论，ACCEPT_UNKNOWN 不改送达事实。没有真人 ROOT、证据或受控执行入口时保持 BLOCKED，不以直接 SQL 修改代替此服务。来源摘要尚可重放时不得清除；内容 90 天不可见，已分类未决完整快照至 180 天脱敏边界。NULL/纯空白来源继续阻断删除和自动脱敏，先由 owner 确认归属或可信停止重放边界及必要处置证据，不能从过期、已读、空队列或 ACCEPT_UNKNOWN 推导删除许可；这是须跟踪解除的历史异常，不是无限保留策略。旧 v1 Job 的确切归属/结论仍需单独盘点，不能用 v2 结果补造历史。

## 运行日志部署与容量

日志字段、等级与可靠性边界以 [Observability §3–8](../observability.md#3-日志契约) 为准。部署步骤是人工操作指南，本轮不执行服务器变更。

开发/生产等级矩阵与配置归属见 [Observability §3.1](../observability.md#31-开发与生产等级)。日常不携带 `--debug/--trace`。需要逐请求排查时，在已确认的目标应用启动环境临时设置 `LOGGING_LEVEL_REQUESTLOG=DEBUG`；需要本项目安全外呼摘要时使用 `LOGGING_LEVEL_PROVIDERLOG=DEBUG`。每次只打开所需范围，完成后移除覆盖并按原流程重启/发布恢复；不把整个业务包、网络库或 root 长期设为 DEBUG，也不启用公开 loggers endpoint。普通包级环境变量与区分大小写的类级覆盖方式见该节。

1. 在隔离环境准备与目标一致的应用制品和 Compose。按实际服务名合并 `config/logging/compose.logging.yaml`；profile 选择 `prod`，不叠加 `dev` 或 `log-file`，清除遗留 `logging.config` 覆盖及 shell 文件重定向。先对合并后的 Compose 执行 `config --quiet`，不要把包含秘密环境变量的完整 config 输出到聊天或普通日志。
2. 检查所有常驻容器的日志预算、宿主空闲磁盘和 Docker data-root。应用建议 `local` 20m × 3、压缩、non-blocking/4m buffer；其他服务按其流量单独定额。此片段只控制应用容器，不能约束 MySQL 数据/binlog、Redis 数据、Nginx 文件日志、旧容器、旧应用文件、镜像和构建缓存。为轮转/压缩/读取留余量，不能把名义 60MiB 当整机硬配额。服务器真实容量尚未核实。
3. 用临时容器验证启动、单行 JSON、traceId、受控失败、`docker logs` 及轮转；可先运行仓库 `tools/verify-logging-docker.ps1` 复核 Docker 机制。禁止为验证访问业务数据库或回调外部业务。
4. 正式发布按既有维护窗口执行。Docker logging 配置只对新建容器生效，普通 restart 不更新 driver/options；应由原部署流程重建目标应用容器。重建/删除容器会丢失该容器日志，需要保留的故障片段先按受限取证流程导出。不要因日志变更重建数据库或删除卷。
5. 在新容器核对实际配置，使用 `docker inspect --format '{{json .HostConfig.LogConfig}}' <实际应用容器>`，再核对 profile、启动事件、health、一次受控请求及 `X-Trace-Id`。日志中的 `@timestamp` 为 +08:00；Docker 外层 `--timestamps` 可为 UTC，二者不能按无时区字符串直接比较。
6. 日常用 `docker logs --since 30m --tail 2000 <实际应用容器>`；持续查看加 `--follow`。Linux 可接 `jq -R 'fromjson? | select(.traceId == "已校验的traceId")'`，PowerShell 可逐行 `ConvertFrom-Json` 后按字段过滤；JVM/容器启动诊断可能不是 JSON，读取端应容忍并单独处理。不要直接 tail/truncate Docker daemon 内部 log 文件，不在容器里另跑 logrotate。

默认 stdout 模式不创建应用日志文件。回退到文件采集时显式使用 `prod,log-file` 并挂载可写的受限目录；日志为 UTF-8 JSON，活动 `mini-app.log`，归档 `mini-app.YYYY-MM-DD.N.log.gz`，50MB/14 天/1GB 归档。归档 cap 不含活动文件，清理只在启动/滚动触发。`LOG_MAX_FILE_SIZE/LOG_MAX_HISTORY/LOG_TOTAL_SIZE` 只在文件模式生效，修改后须隔离验证。不要同时启用文件采集与应用 stdout 双份保留。

队列满、Docker buffer 满、强杀、磁盘故障可能丢弃 ERROR；正常停机应用 logger 最多 flush 5 秒，应保留原应用/worker 优雅停机窗口并核对实际容器 stop grace。不能从队列压力测试推导生产 CPU/heap/P95，也不能用运行日志代替持久审计。异常日志仍包含必要内部 ID、类名及代码位置，应限制 Docker socket/日志目录/导出片段的访问。

迁移到 stdout 后，旧 `app/error/queue` 等文件不会自动清除；先核对保留、事故取证与现有采集依赖，再人工处理明确路径。回滚使用原制品和原日志配置/profile，保留新旧格式查询能力，不修改业务数据。上线后关注空闲磁盘、实际日志增长速度、CPU/heap/请求 latency 及故障可诊断性，再调整预算；本轮没有线上压测或生产容量保证。

参考：[Spring Boot 3.5 logging](https://docs.spring.io/spring-boot/3.5/reference/features/logging.html)、[Docker local driver](https://docs.docker.com/engine/logging/drivers/local/)、[Docker delivery mode](https://docs.docker.com/engine/logging/configure/)。

## 安全基础凭证切换与恢复

V78增加User-owned session_version。发布前在获准隔离库验证完整/升级迁移，目标业务库只读核对版本、Authority覆盖和现有ROOT；确认备份及匹配Backend/Admin/Mini版本后另行授权部署。本任务不执行目标迁移、不初始化真人ROOT、不切来源。V78初始generation为0，但不接受缺少版本claim的旧凭证，双端须重新登录/扫码；旧QR候选不可继续消费。新旧安全二进制不可混跑：旧版本不认识失效边界/普通Token管理禁令，回退不得恢复旧放行行为。

封禁成功以数据库状态和凭证版本提交为准，Sa kickout失败不撤销封禁。解封/期限到期要求重新登录；QR固定候选不能换用新凭证版本。MySQL/Redis/限流存储故障保持拒绝，注销超时保留原Token显式恢复，不自动重登录。恢复数据库或Redis备份前停止流量；不能证明撤销水位完整时，丢弃专用认证namespace的旧会话/激活记录再恢复登录，绝不对共享Redis执行FLUSHALL。备份、访问名单和保留期限由环境owner明确，不能据日志在线可查声称合规完成。

应用只接受Authorization Bearer，不接收Cookie/query/body中的Token。小程序登录与QR限流使用解包Forwarded wrapper后的socket peer；反向代理后的请求共享后端peer预算，可在可信边缘另加每客户端限制。小程序登录限流由该peer地址摘要计数，默认每分钟30次，可用app.auth.login-attempts-per-minute按实际容量配置；不信任任意X-Forwarded-For，反向代理部署应按受信代理配置验证真实peer语义，不通过转发头绕过限流。凭证和登录body不得入日志。Admin Web内存保存Token，刷新重新扫码，未知注销结果保留当前凭证供显式重试；部署模板CSP限制同源脚本、禁止object/base及外站嵌入，保留附件预览所需frame-src；COS直传仅允许已声明云端域名。新增域名先核对实际用途再收窄/调整CSP，不启用unsafe-eval或通用外部script。

运行验收还需独立核对TLS、精确CORS、管理端口/Actuator暴露、DB/Redis最小权限和持久化、JWT及Person加密密钥轮换、日志采集访问及备份恢复；源码模板不证明目标环境已应用。Person加密/HMAC密钥不能像JWT轮换一样直接丢弃。
