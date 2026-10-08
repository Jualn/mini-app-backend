# Reliability Baseline

## 1. Scope and authority

This document is the project source of truth for deciding what failure means and when retry,
idempotency, recovery, compensation, or reconciliation is required. It applies to important
writes, MySQL transactions, Redis, remote calls, background execution, and client retry
contracts.

It does not define Java/Spring mechanics already owned by the engineering standards, and it
does not redefine logging, metrics, or context propagation owned by the
[Observability Baseline](observability.md). Public HTTP shapes remain owned by
[contracts](../../contracts/README.md), and executable database guarantees remain owned by
[Flyway history](../src/main/resources/db/migration/).

This baseline does not select an Outbox schema, Job schema, Redis Streams API, consumer group,
worker state machine, or message envelope. Those concrete decisions are owned by the
[Async Processing Architecture](async-processing.md).

## 2. 当前链路的失败边界

本文维护必须遵守的结果与恢复约束；具体异步机制见 [Async Processing](async-processing.md)，排障入口见 [Runbook](operations/runbook.md)，接口重放语义见对应 contracts。

| 边界 | 结果与恢复约束 |
|---|---|
| HTTP 响应与 MySQL commit | commit 后断连或超时可能是 Unknown Outcome；按该操作契约读取或重放，不默认重试写请求 |
| MySQL 事务 | 明确回滚与提交附近连接丢失分开处理；事务重试须在失败事务外重新开始，且有明确幂等与预算 |
| 审核提交与结果回调 | 审核预约与 Durable Job 同事务；审核结果与 Outbox 同事务；重复/迟到 callback 由审核 owner 条件收尾 |
| 用户资料更新 | 安全检查在资料写事务外完成；最终短事务检查权限和 revision 后原子生效。503 或迟到审核不能后台发布候选，恢复见 Runbook §13 |
| Reminder fan-out 与 Notification | 计划与执行 Job 分责；稳定 source identity 和数据库唯一约束去重。部分执行可恢复，取消及失去 ownership 后不能继续新效果 |
| 微信 Delivery | Notification、Delivery 与 Job 分责；远程调用在事务外。明确成功、拒绝与 UNKNOWN 分开持久化，未知发送不盲重发 |
| Redis 普通派生缓存 | 数据库是事实源，提交后失效及读取回填按可接受陈旧范围处理；缓存失败不撤销已提交业务事实 |
| Redis 认证状态 | 与普通缓存分开，错误不能降级为 miss 或授权通过。扫码登录使用固定候选和 activation，见 Outcome model 与扫码登录内部设计 |
| COS 清理 | 清理意图保存在媒体上传状态，扫描/认领有界；相同对象删除的恢复不构成所有 COS 操作均幂等的承诺 |
| AI 导入 / SSE | 与连接同生命周期的草稿建议允许进程内丢失；明确让用户重新上传，不冒充 durable business acceptance |
| 同步远程调用与客户端恢复 | timeout 限制资源占用，不证明远程写失败；重试、401 恢复重放和用户手动重提均须遵守具体操作契约 |

## 3. Outcome model

Admin QR Login v2 使用短期 Redis session/scene/PNG 与固定候选恢复：reserve 唯一候选 → Sa-Token 同 token 登录写入 → Lua 原子 activation+CONSUMED。无 activation 的中间 mapping 不允许管理登录；Unknown Outcome 由同 session 重新读 truth 恢复，不能换 token。固定 QR 到期仅禁止新兑换/重放，不撤销已激活 Admin token 的正常寿命。旧 QR 流程已移除；管理认证仅接受已激活的新流程 Token。机制见 [内部设计](admin-qr-login.md)，运行与恢复限制见 [运维手册](operations/runbook.md#12-admin-扫码登录运行与恢复)。不增加 DB 表、Outbox、后台签发或 scheduled cleanup。

Every attempt is classified as one of:

- **Known Success**: the caller has authoritative evidence that the intended effect completed.
- **Known Failure**: authoritative evidence says the intended effect did not complete, for
  example validation rejected it before execution or a local transaction definitely rolled back.
- **Unknown Outcome**: available evidence cannot distinguish not-started, failed, and succeeded
  with a lost response.

An attempt outcome and a logical operation outcome are different. A failed attempt may later be
recovered. A timeout, socket reset, process crash, or lost response after `COMMIT` is not evidence
of Known Failure.

For a local transaction plus a remote system, there is no combined atomic result. DB commit does
not roll back Redis or WeChat, and a remote success cannot be undone by a later DB rollback.

## 4. Retry decision and policy

Retry is allowed only when all of the following are known:

1. the failure is plausibly temporary;
2. repeating the operation is safe through natural idempotency, a DB constraint/state machine,
   an idempotency record, or a verified provider guarantee;
3. the retry owner and transaction boundary are explicit;
4. attempts, total time, concurrency, and terminal behavior are bounded.

Typical permanent failures are validation, authentication/authorization, not found, malformed
input, business/state conflict, and constraint violations representing a duplicate fact. They are
not automatically retried. `409`, `429`, or `5xx` alone does not prove that retry is safe.

Deadlock and lock timeout may be transient, but any retry must start a new service invocation and
new transaction outside the failed transaction. There is currently no global DB retry. Do not
sleep inside a transaction or hold DB locks while backing off.

Policies are per boundary, not one global annotation:

| Boundary | Baseline |
|---|---|
| Synchronous HTTP write | No automatic retry by default. Reconcile an unknown result first; use the same idempotency key when the endpoint defines one |
| Read-like remote call | May use small bounded attempts with exponential backoff and jitter, within one total deadline |
| Remote write | No transport retry until duplicate-effect and unknown-outcome handling are proven |
| Redis cache | Prefer DB correctness and graceful cache degradation; avoid request-amplifying retry loops |
| Background work | Must eventually have bounded attempts, backoff+jitter, concurrency limit, terminal state, and an operator/recovery owner |

`maxAttempts`, per-attempt timeout, total retry budget/deadline, backoff, jitter, and concurrency
limit are separate settings. A retry loop that catches every `Exception`, retries forever, or
immediately retries across many requests is forbidden. Intermediate failures log a compact WARN;
the terminal owner logs the final ERROR as defined by Observability.

## 5. Idempotency model

Use the smallest mechanism that protects the real invariant:

1. **Natural idempotency**: reads and “set state to X” operations where repeated execution has a
   compatible result.
2. **Data-model idempotency**: `UNIQUE`, conditional update, version/ETag, row lock, and transaction
   protect one durable business fact or state transition.
3. **Idempotency key/record**: only when the server must recognize repeated submission of the same
   caller intent and return a compatible prior result despite response loss.

An application `exists` check is only an early friendly check. The DB constraint or conditional
write is the race arbiter. A uniqueness violation must be translated according to the business
fact it protects, not generically retried.

When an endpoint uses an idempotency key, its contract must define:

- the caller that generates it and the scope (`principal + endpoint/operation + key` unless a
  stronger documented scope applies);
- the same key for every retry of one intent;
- a canonical request fingerprint, with same key/different meaning rejected as conflict;
- processing and completed behavior, stable result representation, and unknown-outcome behavior;
- retention/expiry and cleanup, index bounds, and whether terminal failures are retained.

The current admin review key is a permanent audit-decision identity and uses a global unique
scope. Do not generalize that table to unrelated operations. Activity registration deliberately
uses business uniqueness plus `GET .../me` reconciliation instead of an idempotency key.

## 6. ID semantics

- `traceId` identifies one execution attempt and diagnostic flow.
- `operationId` identifies one server-owned logical business operation across phases when the
  operation actually needs such correlation.
- `idempotencyKey` identifies the caller's same intent and prevents repeated business effect.

They are never aliases. A replay with the same idempotency key may return the original operation
and result; it does not require a new logical operation. A new trace is still needed for a new
attempt. The frontend does not generate runtime `operationId` by default. OpenAPI's `operationId`
is the stable specification method name (for example `createActivityRegistration`), not this
runtime correlation ID.

Persistent operation state is justified only for multi-stage work with external effects,
unknown outcomes, crash recovery, or user-visible progress. Profile edits and ordinary CRUD do
not receive operation rows merely because they can fail. If a future operation record is needed,
its minimum concepts are operation identity/type, subject/owner, request fingerprint, lifecycle
status, result/error category, attempt/recovery timestamps, and bounded retention; the Async
design owns its concrete schema.

## 7. External effects, compensation, and reconciliation

A remote call inside `@Transactional` does not make a distributed transaction. Avoid it because
locks remain held and either side can succeed without the other. For required asynchronous
effects, persist the intent atomically with the business fact and deliver/reconcile later; an
`afterCommit` callback only orders best-effort work and is not durable.

For a remote write timeout:

1. classify it as Unknown Outcome unless the provider proves rejection;
2. do not issue a blind retry;
3. use the provider's idempotency key or status query when available;
4. otherwise persist enough local intent/evidence for explicit reconciliation or accept and
   document best-effort semantics.

**Retry** repeats the same intended operation. **Compensation** is a separate forward business
action that counteracts a prior effect and can itself fail; it is not rollback. **Reconciliation**
re-reads authoritative facts, determines the actual state, and then repairs a mismatch. A timer
that merely repeats the same unknown write is retry, not reconciliation.

Current concrete recovery mechanisms are limited to `media_upload_record` cleanup recovery,
MySQL Async Job lease recovery, retained Outbox replay, cache TTL/refill, selected counter repair,
and the V20 NotificationDelivery result foundation. Delivery code can persist
`FAILED/UNKNOWN/SKIPPED`, but target-database migration, operator reconciliation and the second-round
three-channel capability model remain unverified or unimplemented; they must not be described as
production recovery. These mechanisms must not be described as a generic recovery system.

## 8. HTTP error and client retry contract

Problem Details must keep stable distinctions for invalid request (400), authentication (401),
authorization (403), missing resource (404), conflict (409), throttling (429), upstream failure
(502), local/dependency unavailability (503), and unexpected internal failure (500). Internal
exception classes, SQL, credentials, provider payloads, and topology are never returned.

The status describes the observed failure category, not replay safety. `Retry-After` is required
when the API contract promises a retry time; none is inferred today. The external exception
handler must preserve the status chosen by `ApiProblemCatalog` rather than forcing every remote
error to 502.

Client rules:

- automatic retry is permitted for GET and explicitly idempotent operations within a small
  budget, subject to authentication and load limits;
- POST/PATCH and any write with a remote side effect are not automatically retried unless their
  operation contract explicitly defines replay behavior;
- on timeout/connection loss, present an uncertain state or reconcile through a read before a
  new intent;
- if an endpoint requires `Idempotency-Key`, all retries reuse the same key; a new user intent
  gets a new key;
- the current mini-program one-time 401 replay is not blanket authorization to replay every new
  unsafe write. New endpoints must be reviewed against this behavior.

## 9. Performance and resource boundaries

Every network boundary must have finite connect and response/read/write limits. A timeout must be
short enough to release resources but long enough for the operation class; AI streaming may use a
separate idle/stream deadline from ordinary WeChat calls.

Retries must use exponential or otherwise bounded backoff and jitter where concurrent callers can
synchronize. Set a total budget and concurrency limit so Redis, MySQL, or a provider outage is not
amplified. No retry sleep belongs inside a transaction. Persistent idempotency/recovery records
need bounded payloads, supporting indexes, retention, and cleanup unless the record is a durable
business/audit fact.

## 10. When to use / when not to use

| Mechanism | Use when | Do not use when |
|---|---|---|
| Retry | temporary failure and duplicate execution is proven safe | validation/conflict, unknown remote write with no dedupe, or already overloaded dependency |
| DB unique/conditional transition | protecting one durable business invariant or legal state edge | needing replay of a prior response or tracking multi-stage progress |
| Idempotency key | caller can repeat one important non-idempotent intent after response loss | every GET/PUT, ordinary profile edit, or where natural/data-model idempotency already gives the required contract |
| Operation record | durable multi-stage progress, external effect recovery, or user query of a long operation | ordinary synchronous CRUD |
| Compensation | a completed effect needs an explicit inverse business action | pretending an external effect participates in DB rollback |
| Reconciliation | authoritative state can be queried to resolve uncertainty/mismatch | merely repeating a command without re-evaluating truth |

## 11. Async Architecture Requirements

The [Async Processing Architecture](async-processing.md) must satisfy these requirements without
this document selecting its transport or schema. Implementation and operational verification remain separate from these requirements:

1. A committed DB fact and its required async intent cannot have a permanent loss window.
2. Redis cannot be the only truth for work that must survive restart or Redis data loss.
3. Acceptance, durable acceptance, start, completion, and terminal failure are distinct states.
4. Delivery may be duplicate; every consumer effect needs an explicit idempotency/dedupe or
   naturally idempotent state transition.
5. Receive/claim, processing, durable completion, and acknowledge/removal must be separated; a
   worker crash must leave work reclaimable without concurrent stale-owner effects.
6. Retry classification must be explicit and bounded by attempts, per-attempt timeout, total
   budget, backoff+jitter, and concurrency. Permanent failure must become terminal and visible.
7. Unknown external-write outcomes need provider idempotency/status query or a reconciliation
   owner; blind resend is not recovery.
8. Fan-out must record progress so partial publication can resume without duplicating user-visible
   notifications. Inbox creation requires a stable dedupe identity.
9. Required notification/audit work must not depend on `afterCommit` callbacks for durability.
10. Message schema evolution and queued old/new producer compatibility must be explicit.
11. New attempts get new trace context; stable `operationId`/future `messageId`, attempt number,
    and logging ownership follow Observability. They are not authentication data.
12. Dead/reclaim/manual-repair paths need an operator-visible owner and enough durable evidence to
    decide retry, compensation, or reconciliation.

重要异步链路的机制由 Async 文档维护；新场景仍需逐项验证这些保证，不复用已经退出的 Redis List/ZSet 迁移清单。
