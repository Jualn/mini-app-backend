# Backend Engineering Standard

**Status:** Frozen  
**Scope:** Technology-independent backend engineering guarantees  
**Normative language:** MUST, MUST NOT, SHOULD, SHOULD NOT, MAY

---

# 0. Scope, Authority & Normative Language

## 0.1 Purpose

This standard defines backend guarantees that remain valid regardless of the specific implementation framework.

It governs questions such as:

- where authoritative state lives;
- which invariants must remain true;
- how concurrent operations remain correct;
- how failure, retry, idempotency, and recovery are modeled;
- how asynchronous work is accepted and recovered;
- how trust boundaries are enforced;
- how runtime configuration and secrets are classified;
- what evidence is required to claim backend correctness.

It does **not** define:

- Java syntax/style;
- Spring annotations or proxy mechanics;
- exact SQL/index syntax;
- Redis command/key implementation;
- message-broker-specific mechanisms;
- the external API protocol;
- business rules for one concrete domain.

Those belong to their corresponding standards/specifications.

## 0.2 Authority

Use authority by subject:

```text
Accepted Contract
→ externally observable protocol

Domain / Business Specification
→ business states, policies, invariants

Backend Engineering Standard
→ technology-independent backend guarantees

Java Engineering Standard
→ Java language/type/runtime rules

Spring Boot Engineering Standard
→ Spring implementation/integration mechanisms

Database / Redis / Messaging / Security Standards
→ technology-specific mechanisms

Operations
→ deployment/runtime environment
```

The Backend Standard may require a guarantee such as idempotency.

A technology-specific standard may define how that guarantee is implemented.

The implementation mechanism MUST NOT weaken the higher-level guarantee.

## 0.3 Normative Language

The keywords:

```text
MUST
MUST NOT
SHOULD
SHOULD NOT
MAY
```

are normative.

Examples and mechanism notes are non-normative unless explicitly stated otherwise.

## 0.4 Backend Guarantees Are Not Framework Features

A framework annotation or library feature is not itself a backend guarantee.

For example:

```text
@Transactional
≠ invariant preserved

@Async
≠ durable work accepted

cache hit
≠ current truth

HTTP 200
≠ logical operation uniquely succeeded
```

Backend correctness must be established at the level of system behavior.

---

# 1. Backend Boundaries & Side Effects

## 1.1 Model the Logical Operation

Backend design SHOULD identify the logical business operation independently from any one network request or execution attempt.

Use:

```text
Logical Operation
→ one intended business fact

Attempt
→ one execution trying to realize that fact
```

A timeout/retry may produce several attempts for one logical operation.

Correctness MUST be defined for the logical operation.

## 1.2 Request Identity vs Operation Identity

A request identifier MAY identify one transport/execution attempt.

An idempotency key or operation identifier MAY identify one logical operation.

These are different concepts.

Do not assume:

```text
requestId == logicalOperationId
```

unless explicitly designed that way.

## 1.3 Entry Adapters Do Not Own Business Guarantees

HTTP controllers, message consumers, scheduled triggers, admin endpoints, and CLI jobs are adapters.

Required business guarantees SHOULD be enforced inside reusable application/domain operations rather than only in one entry path.

If the same operation can be triggered through multiple adapters, all paths MUST preserve the same invariant and authorization semantics.

## 1.4 Separate Decision From Effect

When an operation includes irreversible or external effects, distinguish:

```text
decision / durable intent
→ effect execution
→ completion/recovery
```

This is especially important for:

- notifications;
- payment-like external actions;
- messages;
- remote HTTP calls;
- filesystem effects.

Do not assume a local transaction can roll back an external effect.

## 1.5 Side Effects Must Have an Owner

Every externally visible or durable side effect MUST have a clear owner for:

- initiation;
- success detection;
- failure handling;
- retry;
- deduplication;
- recovery.

Fire-and-forget work with no failure owner SHOULD NOT be used for required effects.

## 1.6 Boundaries Should Carry Meaningful Data

Cross-layer boundaries SHOULD pass data that remains valid outside the caller's local context.

Prefer values such as:

```text
userId
resourceId
operationId
immutable command
```

over passing:

```text
request object
DB connection/session
transaction-local object
mutable shared container state
```

into work that outlives the caller.

---

# 2. State Correctness & Consistency

## 2.1 Define Invariants First

For state-changing operations, define:

```text
preconditions
postconditions
invariants
```

before selecting locks, transactions, caches, or retry mechanisms.

Examples:

```text
one user has at most one logical enrollment record per activity

ACTIVE enrollment count must not exceed capacity

a deleted resource cannot become visible through a stale cache
```

Mechanisms are chosen to preserve these properties.

## 2.2 Source of Truth vs Replica

Every important state value SHOULD have one authoritative source of truth.

Examples of replicas/derived state may include:

- caches;
- counters;
- search indexes;
- materialized views;
- metrics;
- client copies.

A replica MUST NOT silently become the authoritative source merely because it is faster to read.

## 2.3 Cache Is Not Final Authority for Invariants

A stale cache MAY be acceptable for:

- display;
- performance hints;
- approximate/derived information;

when the product semantics permit it.

A stale cache MUST NOT be the final arbiter for an invariant such as:

```text
may this enrollment be accepted?
is capacity still available?
does this user currently own the resource?
```

Final correctness decisions SHOULD be made using authoritative state or a protocol that protects the authoritative state.

## 2.4 Staleness Window Must Be Intentional

If replicas are eventually consistent, the system SHOULD know:

```text
what may be stale
for approximately how long
which actions may rely on it
how it is repaired/rebuilt
```

"Eventually consistent" MUST NOT mean "inconsistency is unbounded and nobody owns repair."

## 2.5 Write Invalidation / Read Rebuild

A common valid cache model is:

```text
write source of truth
→ invalidate affected cache
→ later read misses
→ reload source of truth
→ rebuild cache
```

If invalidation fails, the design SHOULD define a bounded recovery path.

## 2.6 Late Old Writes

A write-back or asynchronous replica update can arrive after newer state and overwrite it.

Where this is possible, protect ordering through an appropriate mechanism such as:

- version;
- sequence;
- conditional update;
- compare-and-set;
- owner/lease validation.

Do not assume network/thread execution order equals logical state order.

## 2.7 Derived Counters

Derived counters SHOULD be treated according to their role.

If a counter is for display/performance, authoritative records remain the correctness source.

If a counter participates in an invariant, its updates MUST be protected by the same concurrency protocol as the invariant.

## 2.8 Read-Your-Writes

Where user experience or subsequent operations require read-your-writes, the design SHOULD define how it is achieved.

Options MAY include:

- reading the authoritative store;
- updating/invalidation of replica;
- version-aware reads.

Do not assume eventual consistency automatically provides read-your-writes.

---

# 3. Persistence & Data Evolution

## 3.1 Durable Facts Belong in Durable State

A business fact that must survive:

- restart;
- redeploy;
- process crash;
- multi-instance operation

MUST NOT exist only in process memory.

Examples include:

- accepted logical operations;
- due reminders;
- durable workflow states;
- pending external effects;
- recovery ownership where required.

## 3.2 Database Constraints Are Correctness Mechanisms

Where the database can express an invariant safely, use constraints.

Examples:

```text
UNIQUE
NOT NULL
foreign key where semantics fit
CHECK where supported/useful
```

Application validation MAY improve error handling but does not replace a constraint required for correctness under concurrency.

## 3.3 Existence Does Not Mean Eligibility

A foreign key or existence check can prove that referenced data exists.

It does not prove business permission such as:

```text
activity is open
user is eligible
resource is visible
transition is legal
```

Business eligibility remains an application/domain concern.

## 3.4 Preserve Important History

Do not delete durable records merely to represent a state transition when history is required for:

- idempotency;
- recovery;
- audit;
- future analysis;
- uniqueness across state changes.

Use explicit state where appropriate.

## 3.5 Schema Evolution Is Managed

Production schema changes MUST use a governed migration mechanism.

Do not use:

- manual production mutation as schema source of truth;
- arbitrary startup SQL;
- ORM model changes alone

as migration ownership.

## 3.6 Forward Evolution

Migration design SHOULD consider:

- existing rows;
- default/backfill requirements;
- large-table impact;
- mixed-version rollout if applicable;
- rollback/recovery;
- index creation cost;
- old application compatibility during deployment.

## 3.7 Destructive Changes

Dropping/renaming fields or changing semantics SHOULD be staged when old application versions or existing data may still depend on them.

Prefer expand/migrate/contract style evolution where the deployment model requires compatibility.

---

# 4. Reliability, Resource & Process Lifecycle

## 4.1 Failure Outcome Model

Classify an operation attempt into:

```text
Known Success
Known Failure
Unknown Outcome
```

Known Success:
the intended effect is known to have completed.

Known Failure:
the effect is known not to have completed.

Unknown Outcome:
the caller cannot determine whether the effect happened.

Timeout and connection failure frequently produce Unknown Outcome rather than Known Failure.

## 4.2 Attempt Failure vs Operation Failure

One execution attempt failing does not necessarily mean the logical operation failed.

A later retry/recovery may complete the same operation.

Represent and communicate failure at the correct level.

## 4.3 Retry Requires Preconditions

Retry MAY be used only when the owner understands:

- whether the failure is retryable;
- whether the previous attempt may have succeeded;
- whether the operation is idempotent/deduplicated;
- retry limit;
- backoff;
- overload impact.

Do not use generic immediate retry for every exception.

## 4.4 Backoff

Retries SHOULD use bounded backoff when repeated immediate attempts can worsen:

- database load;
- remote outage;
- connection exhaustion;
- rate limits;
- synchronized retry storms.

Jitter SHOULD be considered when many clients/workers can retry simultaneously.

## 4.5 Idempotency

An operation is idempotent when repeated handling of the same logical operation has the intended effect no more than required and produces a compatible result.

Idempotency MUST be scoped to a stable logical operation identity where duplicate attempts are possible.

## 4.6 Idempotency Key Semantics

One idempotency key SHOULD identify one logical operation.

If the same key is reused for semantically different input, the system SHOULD reject the conflict rather than silently reinterpret the key.

Different keys normally identify different operations unless a stronger business uniqueness rule says otherwise.

## 4.7 Natural Idempotency

Some state transitions can be naturally idempotent.

Example:

```text
target state = ACTIVE
current state = ACTIVE
→ return compatible success
```

when the same logical business fact is already true.

This must still respect authorization and operation identity semantics.

## 4.8 State Machine Protection

Prefer conditional state transitions over read-then-unconditional-write.

Example:

```text
UPDATE ...
SET status = ACTIVE
WHERE id = ?
  AND status = CANCELLED
```

allows the database to arbitrate the state transition atomically.

## 4.9 Operation Recovery

Operation recovery answers:

```text
How can the intended logical operation eventually complete?
```

Mechanisms MAY include:

- retry;
- idempotent re-execution;
- durable queue;
- recovery scan;
- compensation.

## 4.10 State Recovery

State recovery answers:

```text
How can a new process determine what work was in progress?
```

Required recovery state MAY include:

- status;
- pending marker;
- attempt count;
- owner;
- lease expiry;
- operation ID.

If the process must recover after a crash, necessary recovery state MUST NOT live only in memory.

## 4.11 Lease vs Owner

For reclaimable long-running work, distinguish:

```text
lease
→ until when ownership is considered valid

owner/version
→ which worker generation currently owns the work
```

A stale worker whose lease/owner check fails MUST stop acting as the current owner.

## 4.12 Long Tasks

If work can outlive its lease, the system SHOULD either:

- renew the lease safely; or
- divide work into smaller recoverable units.

Do not simply make leases extremely long to avoid designing recovery.

## 4.13 Process Crash

Graceful shutdown is useful but MUST NOT be the only recovery mechanism for durable work.

Assume the process can disappear without cleanup callbacks running.

## 4.14 Resource Ownership

Every long-lived resource SHOULD have a clear owner responsible for:

- acquisition;
- normal release;
- partial-startup cleanup;
- shutdown;
- failure behavior.

Borrowed/shared resources MUST NOT be independently destroyed by consumers.

---

# 5. Async Work & Messaging

## 5.1 Acceptance vs Completion

For asynchronous operations, distinguish:

```text
request accepted
work durably accepted
work started
work completed
```

These are not interchangeable states.

## 5.2 202 Semantics

If an API returns an acceptance response such as HTTP 202, the accepted Contract SHOULD define what was actually accepted.

If the server can crash immediately and lose all record of the work, it MUST NOT claim durable acceptance unless that is still compatible with the Contract.

## 5.3 In-Memory Async

In-process executor/async dispatch MAY be appropriate when loss on process failure is acceptable or recoverable from another durable source.

It is insufficient by itself for required durable work.

## 5.4 At-Most-Once vs At-Least-Once

Delivery/execution semantics SHOULD be explicit.

In many backend systems:

```text
at-least-once delivery
+ idempotent/deduplicated processing
```

is safer and more practical than assuming exactly-once delivery.

Do not use "exactly once" unless the complete end-to-end guarantee is actually established.

## 5.5 Queue Is Not Source of Truth by Default

A message queue may carry work but often should not be the only record of a required business fact.

Where reconstruction is required, keep enough durable state to regenerate or reconcile work.

## 5.6 Consumer Processing

A consumer SHOULD distinguish:

```text
receive/claim
→ process
→ durable completion
→ acknowledge/remove
```

according to the messaging mechanism.

Removing a message before durable completion can lose work.

Holding it forever without reclaim semantics can stall work.

## 5.7 Poison Messages

Repeatedly failing messages SHOULD have an explicit policy such as:

- bounded retries;
- dead-letter state;
- manual review;
- permanent failure state.

Do not retry forever without visibility.

## 5.8 Message Schema Compatibility

When messages can remain queued across deployment, schema evolution MUST consider old and new producers/consumers.

A Java class definition alone is not a compatibility strategy.

## 5.9 Outbox

When one local DB state change must reliably result in a message/event, an outbox pattern SHOULD be considered.

Typical model:

```text
business state change
+ outbox record
→ same local DB transaction

then

outbox dispatcher
→ message broker / external effect
```

Outbox removes the atomicity gap between DB commit and event intent persistence.

It does not by itself eliminate duplicate publication; consumers still need appropriate semantics.

## 5.10 Scheduled Discovery + Queue

A valid durable workflow MAY combine:

```text
durable due state
→ scheduler discovers due work
→ durable dispatch/claim
→ worker processes
```

Scheduling and messaging solve different concerns:

```text
scheduler
→ when work becomes due

messaging
→ how work is transferred/processed
```

---

# 6. Security & Trust Boundaries

## 6.1 Authentication vs Authorization

Authentication answers:

```text
Who is the subject?
```

Authorization answers:

```text
May this subject perform this action on this resource in this context?
```

Authentication alone MUST NOT authorize resource operations.

## 6.2 Authorization Model

A reusable authorization decision SHOULD consider:

```text
Subject
Resource
Action
Context
```

Example attributes:

```text
Subject:
userId, role, capabilities

Resource:
ownerId, status, visibility

Action:
read, update, delete, enroll

Context:
time, tenant, request origin where relevant
```

## 6.3 Server-Owned Resource Facts

Resource ownership/status used for authorization MUST come from trusted server-side state.

Do not trust a client claim such as:

```text
ownerId = current user
resource status = editable
```

for authorization.

## 6.4 Authorization Belongs in Reusable Application Boundary

Required authorization SHOULD be enforced in a service/application/domain policy that cannot be bypassed simply by using another adapter.

Controller/security filters MAY perform coarse checks but MUST NOT be the sole owner of resource-level authorization when other call paths exist.

## 6.5 Deny by Default

When an authorization rule cannot establish permission, default to deny unless policy explicitly defines another outcome.

## 6.6 Business Rejection vs Authorization Rejection

Keep separate:

```text
not allowed because subject lacks permission
→ authorization failure

not allowed because resource/business state disallows operation
→ business rejection
```

Public status/error mapping may intentionally hide resource existence, but internal policy should preserve the conceptual distinction.

## 6.7 Input Trust

All external input is untrusted until validated and authorized.

Validation establishes structural acceptability.

It does not establish trust, ownership, or permission.

## 6.8 Secrets

Secrets MUST NOT be:

- committed into source;
- logged;
- returned in errors;
- embedded in public metrics/traces.

Secret retrieval, rotation, and deployment belong to Security/Operations.

## 6.9 Least Privilege

Runtime identities, DB accounts, Redis credentials, deployment users, and admin capabilities SHOULD receive only the privileges required for their responsibilities.

---

# 7. Runtime Configuration & Secrets

## 7.1 Configuration vs State

Configuration describes how the process should operate.

Business/runtime state describes what has happened or what is currently true.

Do not use configuration files/environment variables as a database for mutable business state.

## 7.2 Externalized Environment-Specific Values

Environment-specific values SHOULD be externalized from source code.

Examples:

- DB endpoint;
- Redis endpoint;
- external API URL;
- timeout;
- feature/integration enablement where operational;
- credentials.

## 7.3 Required Configuration

If the application cannot safely operate without a required setting, startup SHOULD fail rather than substitute an arbitrary fallback.

## 7.4 Defaults

A default SHOULD exist only when it is a valid semantic default across intended environments.

Do not use defaults to conceal missing production configuration.

## 7.5 Configuration Compatibility

Public/deployment-facing configuration keys are an operational interface.

Renaming/removing them SHOULD consider rollout compatibility.

## 7.6 Secrets Are Not Ordinary Configuration

Even when secrets are delivered through environment/configuration systems, they require stronger controls for:

- storage;
- access;
- rotation;
- logging;
- debugging;
- incident response.

## 7.7 Runtime Reload

A value that changes during process lifetime needs an explicit consistency model.

Do not mutate static configuration objects and assume every component observes the new value safely.

---

# 8. Observability & Diagnostics

## 8.1 Observable Properties

The backend SHOULD expose enough evidence to answer important operational questions such as:

```text
Are requests failing?
Which operation fails?
Are retries increasing?
Is queue lag increasing?
Did a worker stop progressing?
Is the DB pool saturated?
```

Instrumentation SHOULD be designed around operational properties, not arbitrary classes.

## 8.2 Logs

Logs are for detailed discrete events.

They SHOULD include useful correlation context where available.

Do not log secrets or uncontrolled sensitive payloads.

Expected business rejection SHOULD NOT automatically generate high-severity stack traces.

## 8.3 Metrics

Metrics are for aggregated numerical behavior.

Useful categories include:

- count;
- error rate;
- latency;
- queue depth;
- oldest pending age;
- retry count;
- active/in-flight work;
- pool saturation.

Metrics MUST NOT become business source of truth.

## 8.4 Cardinality

Metric dimensions SHOULD be bounded.

Values such as:

```text
userId
postId
activityId
requestId
traceId
raw error message
```

SHOULD NOT normally be metric labels.

Use logs/traces for high-cardinality correlation.

## 8.5 Tracing

Tracing follows one causal execution.

A trace ID is not a durable business operation ID.

Retries/recovery may create several traces for one logical operation.

## 8.6 Health

Health checks represent selected operational capability.

They are not full correctness proofs.

A service can be "healthy" while a business invariant is wrong.

## 8.7 Liveness vs Readiness

Liveness asks whether restarting the instance may recover an internally broken process.

Readiness asks whether the instance should receive traffic now.

External shared dependency failure SHOULD NOT automatically imply liveness failure.

## 8.8 Worker Observability

Long-lived background work SHOULD expose enough state to detect:

- unexpected worker death;
- repeated processing failure;
- growing backlog;
- lack of progress.

A thread merely existing is not sufficient evidence of useful progress.

## 8.9 Observability Is Not Recovery

Logs/metrics/alerts can reveal a failure.

They do not repair state automatically.

Required recovery still needs a recovery owner and mechanism.

## 8.10 Telemetry Must Not Break Core Work by Default

Optional logging/metrics/tracing export SHOULD normally degrade independently from valid business processing.

Do not make the telemetry backend a synchronous correctness dependency without an explicit requirement.

---

# 9. Backend Verification Evidence

## 9.1 Evidence Follows the Property

Choose verification according to the property being claimed.

Examples:

```text
pure policy
→ unit test

DB uniqueness/lock/isolation
→ real DB integration test

HTTP Contract
→ boundary/contract test

retry/idempotency
→ repeated/duplicate attempt tests

crash recovery
→ interruption/restart/reclaim test

multi-instance ownership
→ competing owner test
```

## 9.2 Do Not Mock the Mechanism Under Test

If claiming:

```text
database lock correctness
```

do not mock the database.

If claiming:

```text
transaction rollback
```

do not instantiate the service outside transaction infrastructure.

A test double may replace unrelated collaborators.

## 9.3 Invariant Tests

Critical invariants SHOULD have tests that attempt to break them.

For concurrency-sensitive invariants, include competing operations rather than only sequential happy paths.

## 9.4 Failure-Point Testing

For workflows with several durable/external steps, test failure at representative boundaries:

```text
before local commit
after local commit
before external effect
after external effect but before acknowledgement
during retry/recovery
```

The system should converge to a legal state.

## 9.5 Idempotency Tests

Where duplicate attempts are expected, verify:

- same operation ID + same semantic input;
- same operation ID + conflicting input;
- retry after timeout/Unknown Outcome;
- concurrent duplicate attempts;
- repeated completion query where applicable.

## 9.6 Recovery Tests

If durable work can be claimed/leased, verify:

- owner crash;
- lease expiry/reclaim;
- stale owner rejected;
- redelivery/retry;
- no duplicate illegal effect.

## 9.7 Cache Consistency Tests

Where cache is a replica, test:

- stale cache after authoritative write;
- invalidation failure;
- cache miss/rebuild;
- old asynchronous write arriving late;
- no invariant decision based solely on stale cache.

## 9.8 Contract Tests

External protocol tests SHOULD assert externally observable behavior rather than internal class structure.

## 9.9 Production-Like Mechanism

Use production-family infrastructure when semantics matter.

Examples:

```text
MySQL-specific locking
→ MySQL-family test

Redis atomic command/Lua
→ real Redis-compatible test
```

Do not let an approximation claim behavior it cannot reproduce.

## 9.10 Verification Is Not Specification

Tests provide evidence that implementation follows the authority.

A test does not become the authority merely because it currently passes.

If test and accepted specification disagree, resolve the disagreement at the authoritative layer.

## 9.11 Completion Claims

Do not claim:

```text
correct
safe
idempotent
transactional
recovered
```

without evidence appropriate to the claimed property.

When evidence is partial, state the remaining uncertainty.

---

# Appendix A — Concept Map

This appendix is non-normative.

```text
                    Backend Correctness
                           │
         ┌─────────────────┼──────────────────┐
         ▼                 ▼                  ▼
      State             Failure           Trust
         │                 │                  │
   truth/replica       outcome model     authn/authz
   invariant           retry             resource policy
   concurrency         idempotency       secrets
   migration           recovery
         │                 │
         └────────┬────────┘
                  ▼
             Async Work
          acceptance/completion
          queue/outbox/lease
                  │
                  ▼
            Observability
                  │
                  ▼
             Verification
```

---

# Appendix B — Review Questions

Use these during backend review.

## Correctness

```text
What is the invariant?
What state is authoritative?
Can stale replica data decide the invariant?
What prevents concurrent illegal state?
```

## Failure

```text
Is the outcome Known Success, Known Failure, or Unknown?
Who owns retry?
Is retry idempotent?
What happens after process crash?
```

## Async

```text
What exactly has been accepted?
Is the work durable?
Can it be reconstructed?
Can duplicate delivery happen?
Who owns acknowledgement and recovery?
```

## Security

```text
Who is the subject?
What resource/action/context is authorized?
Are resource facts server-owned?
Can another entry path bypass the decision?
```

## Persistence

```text
Which constraints enforce correctness?
Does schema evolution preserve existing data?
Is important history being deleted?
```

## Observability

```text
How would we know this capability stopped working?
Are metric dimensions bounded?
Does health represent the right operational question?
```

## Verification

```text
What property is being claimed?
Does the test exercise the actual mechanism?
Does the test attempt the relevant failure/concurrency case?
```

---

# Appendix C — Core Backend Principles

```text
one logical operation may have many attempts

one durable fact should have one authoritative source

replica freshness is not invariant authority

transaction does not equal distributed atomicity

timeout may mean Unknown Outcome

retry requires idempotency/deduplication and ownership

durable recovery requires durable recovery state

async acceptance is not completion

graceful shutdown is not crash recovery

authentication is not authorization

telemetry is not business truth

verification follows the property being proved
```
