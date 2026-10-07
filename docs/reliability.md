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

## 2. Failure inventory and migration record

下表保留基线建立时的旧执行形态，作为 failure window 与迁移决策记录；其中 Redis List/ZSet 异步路径已在 Async Implementation 中退出当前生产代码：

```text
Client -> HTTP -> Service -> MySQL
                         -> Redis cache/counter/queue
                         -> WeChat/COS/AI
```

| Boundary / current implementation | Outcome and current retry | Duplicate / recovery behavior | Decision |
|---|---|---|---|
| HTTP response after a committed write | A disconnect or client timeout is `Unknown Outcome`; the server may have committed | A client retry is safe only where the operation contract and storage make it safe | Never translate timeout into “the write failed” |
| MySQL service transaction | Commit is Known Success; an exception that is known to roll the transaction back is Known Failure; connection loss around commit may be unknown to the caller | No general deadlock/lock-timeout retry exists | If introduced, retry a fresh proxied transaction from outside the failed transaction and use a bounded policy |
| Activity enrollment / public-event subscription | Row lock plus `UNIQUE(activity_id,user_id)` or equivalent protects one fact | Reaching the same target state is naturally idempotent; constraints arbitrate races | Safe to retry only according to the endpoint contract; do not add an operation table |
| Canonical activity registration | `UNIQUE(activity_id,user_id)`, activity row lock, conditional/versioned transitions and ETag protect capacity and updates | Create POST explicitly does not promise replay; after an unknown result the client reads `GET .../me`. PUT/cancel use the current ETag | Keep reconciliation-by-read; do not invent a new idempotency key for this flow |
| Like and report creation | Unique keys protect one user/target fact | A constraint race is a duplicate/business conflict, not infrastructure failure | Do not retry a uniqueness violation as transient infrastructure |
| Admin manual review | Required `Idempotency-Key` is stored in `content_audit_log` with a global unique key; same key/same command returns compatible success, different command conflicts | Target transition and decision row share the DB transaction. A concurrent losing insert can return conflict rather than replay | Durable key is appropriate; current concurrent response equivalence is a known limitation, not a reason for a generic idempotency framework |
| Redis read cache / derived counters | Redis is not the durable business fact source | Cache miss refills from DB; many writes invalidate after commit. Some legacy counters/unread mutations still happen beside or inside DB transactions | Redis failure must not roll back a committed business fact; remaining mixed boundaries need case-by-case migration |
| Post/comment/profile audit publication | DB row commits, then `afterCommit` sends to the Redis queue | Crash or enqueue failure after commit can leave a permanent pending item; startup has no general audit redispatch | **Async Architecture prerequisite** |
| Main Redis queue (`BLPOP`) | Pop removes before executor/handler durable completion; handler failures retry up to 3 times at 10/60/300 seconds | Crash after pop loses work; retries may duplicate effects; Redis dead list is terminal storage only | Historical transport, not a reliability baseline. **Async Architecture prerequisite** |
| Delay queue (`ZSET` + body key) | Consumer removes the member to claim before durable completion, then recreates it after handled failure | Crash after claim loses work. A `notify_plan` can rebuild only plan-level status=0 work, not individual messages | **Async Architecture prerequisite** |
| Notification fan-out and inbox | Fan-out publishes inside a DB transaction then marks the plan sent; inbox insert has no dedupe key; WeChat push is called while the inbox transaction is open and its failure is swallowed | Partial fan-out, duplicate inbox rows, lost individual messages, and unknown external-send outcome are possible | Keep the user-visible inbox as primary product effect, but redesign dispatch/dedupe in Async Architecture; do not add ad-hoc retries now |
| WeChat read-like calls and token reads | Connect timeout 5 s and response/read/write timeout 10 s; selected token-expired responses refresh once | Retry is limited to explicit token-expired semantics, not arbitrary timeout | Read-like calls may later receive a bounded retry outside DB transactions if failure classification is explicit |
| WeChat message/audit POST | Same HTTP bounds; no generic transport retry | Timeout/connection loss can mean the provider accepted the write: `Unknown Outcome`; provider idempotency/status-query support is not established in this code | No automatic retry until a duplicate/reconciliation strategy is defined |
| COS credential request | External read-like call via SDK | No application retry; caller may start a new attempt | A bounded retry may be considered only with SDK timeout/attempt settings verified |
| COS object delete | Persistent `media_upload_record` uses PENDING/CLEANING, a 30-minute stale-claim reset and capped exponential schedule up to 24 h | Deleting the same object is treated as a retryable cleanup intent; the durable row survives process failure | Existing state recovery is valid; it is not proof that every COS operation is idempotent |
| AI document import / SSE | Executor-local session, finite stream timeout, no server retry; retryable failures tell the user to upload again | `importId` is attempt-local and state is lost on process failure; no durable business write is promised | In-memory loss is acceptable for draft suggestions. Do not create operation rows unless the product promises resumable/durable processing |
| Interaction counter sync | Scheduled Redis-to-DB projection repair | View delta attempts to restore Redis delta after DB failure; like dirty-set deletion before all DB writes can lose repair intent on crash | Counters are projections, but this crash window remains a future recovery improvement |
| Mini-program HTTP adapter | Only a 401 refresh path retries once; it replays the original request. There is no generic network/5xx write retry | A write can be repeated after token recovery | This is safe only for endpoint-defined idempotent/reconcilable writes; new clients must not add generic POST retry |
| Admin web HTTP adapter | No general automatic retry was found | User actions can still manually resubmit after an unknown result | Follow the same endpoint contract rules |

当前审核、提醒、通知已迁移为 MySQL Outbox / Durable Job 与 Redis Stream transport；具体状态机、保留期和迁移状态见 [Async Processing Architecture](async-processing.md)。WebClient timeout 仍只限制资源占用，不证明远程写失败；媒体清理和其他保留的 scheduler 只有在 durable source 与重新扫描入口真实存在时才可称为可恢复。

## 3. Outcome model

Admin QR Login v2 已增加短期 Redis session/scene/PNG 与固定候选恢复：reserve 唯一候选 → Sa-Token 同 token 登录写入 → Lua 原子 activation+CONSUMED。无 activation 的中间 mapping 不允许管理登录；Unknown Outcome 由同 session 重新读 truth 恢复，不能换 token。固定 QR 到期仅禁止新兑换/重放，不撤销已激活 Admin token 的正常寿命。旧 QR 流程从未上线，已按用户决定移除；管理认证仅接受已激活的新流程 Token。机制、证据与未验证部署边界见 [实现记录](admin-qr-login-v2-implementation.md)。不增加 DB 表、Outbox、后台签发或 scheduled cleanup。

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
this document selecting its transport or schema. Its existence does not mean the migration is
already implemented or deployed:

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

Concrete implementation and migration inputs are: audit enqueue after post/comment/profile commit; main-queue
`BLPOP` removal before completion; delay-queue claim/removal before completion; ZSET/body two-key
partial writes; notification plan partial fan-out; inbox deduplication; WeChat send Unknown
Outcome; and like-counter dirty-set crash recovery.
