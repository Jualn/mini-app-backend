# Database Engineering Standard

**Status:** Adopted — prospective use for task-scoped database changes  
**Target baseline:** MySQL 8.4 / InnoDB mechanism reference; Flyway, MyBatis-Plus, Java 17 / Spring Boot 3.5.13. Dependency versions are owned by `pom.xml` and its managed dependencies; actual database versions require target-specific evidence.  
**Scope:** Production-grade relational data design, schema/SQL correctness, performance, evolution, security and database-specific evidence  
**Normative terms:** MUST / MUST NOT = required; SHOULD / SHOULD NOT = strong default with concrete, recorded reason for deviation; MAY = permitted choice.

> **Quick entry for coding agents:** Do not read this entire document for every change. Start with the affected SQL/Mapper/migration and necessary business/contract sources; read only the triggered topics below. Consult §0–1 when authority or design defaults need clarification. Do not create infrastructure, run migrations, rewrite old tables, or add a new validation script merely because this standard exists.

## Quick routing: read only what changed

| Change surface | Sections to inspect | Typical proof (risk-dependent) |
|---|---|---|
| New table / relationship | §2–5, §9, §11–12 | Invariants + migration on a fresh owned schema |
| Column / comment / constraint | §2–5, §9, §11–12 | DDL + metadata / focused migration check |
| Mapper / SELECT / list endpoint | §4–5, §11–12 | Result/cardinality test; query count; plan for risky queries |
| Write / concurrency / transaction | §3–4, §6, §11–12 | Real MySQL competing operations / rollback outcome |
| Large table / slow query / batch | §4–7, §11–12 | Query plan + representative workload / resource impact |
| Sensitive data / access boundaries | §2, §4, §8, §11–12 | Trust boundary / privilege / negative tests |
| Migration / deployment / data repair | §9–12 | Fresh and upgrade-path tests, rollout and recovery evidence |
| No persistent/SQL semantics changed | No database-standard reading required | Only relevant ordinary verification |

## 0. Scope and authority

**DB-001 (MUST):** This standard owns database-specific *representation and mechanism*: relational modeling guidance, MySQL types/constraints/indexes/query semantics, Mapper SQL, schema migration and database-mechanism evidence. It does **not** own business definitions/states (Domain), external protocol (Accepted Contract), generic consistency/idempotency guarantees (Backend/Reliability), Java type rules, Spring proxy/transaction integration, async delivery policy, security policy (Security), deployment permissions or recovery targets (Operations). Technology choices do not redefine higher-level semantics.

**DB-002 (MUST):** Reconstructable schema is governed by versioned migration history, not by handwritten schema snapshots or current Java entities. The actual environment needs verified runtime evidence. Consult `src/main/resources/db/README.md`, current migrations, applicable architecture and commands for local conventions rather than assuming this standard supersedes them.

**DB-003 (MUST):** Apply new quality rules prospectively to objects/SQL touched by the task. An audit finding in legacy schema is **not** authorization to make mass changes, run migrations, create Docker containers or touch shared databases. Old-state remediation requires separately scoped impact and approval.

**DB-004 (SHOULD):** Every nontrivial data-design choice should be reducible to: *what fact is represented, who owns it, what must remain true, how it is read/written, what happens as it grows and evolves, and what evidence proves the chosen mechanism*. Do not generate lengthy design documents for ordinary CRUD.

**DB-005 (MUST):** A static check, SQL plan, integration test and production observation prove different properties. Do not promote one to another. Report `PASS`, `FAIL`, `BLOCKED` or `NOT RUN` with scope, actual target and evidence according to Backend §9.

Repository authority routes: [Domain](../docs/domain.md), [Contracts](../../contracts/README.md), [Backend](backend-engineering.md), [Reliability](../docs/reliability.md), [Async](../docs/async-processing.md), [Spring](spring-boot-engineering.md), [Observability](../docs/observability.md), [Operations](../docs/operations/runbook.md). Security concerns follow the existing [AGENTS routing](../AGENTS.md); no separate Security Standard is presumed. [Architecture §5](../docs/architecture.md#5-mybatis-选型与字段读取) owns MyBatis placement and projection conventions; the [DB README](../src/main/resources/db/README.md) owns local Flyway procedures, [migration history](../src/main/resources/db/migration/) owns reconstructable schema, and [commands](../governance/commands.md) owns execution and authorization prerequisites.

## 1. Design defaults: simple, explicit, evidence-driven

**DB-101 (SHOULD):** Prefer the simplest normalized design that represents current business facts, relationships, history and legal state transitions without redundancy. Normalization is a starting point, not an instruction to split every attribute into a new table. Denormalization, JSON, precomputed projections, partitioning, and sharding need a concrete benefit and consistency/maintenance model.

**DB-102 (MUST):** Before adding a durable object, identify its real purpose: authoritative entity, association, operation record, immutable event/audit fact, outbox/job, derived projection or temporary data. Do not create a table simply because a Java type, DTO, endpoint, enum, future feature or individual workflow step exists.

**DB-103 (MUST):** For an added relationship establish cardinality (1:1 / 1:N / N:M), participation, optionality, delete/retention semantics and uniqueness. A bridge table is justified for actual many-to-many relationships; beware that historical states can alter uniqueness requirements.

**DB-104 (SHOULD):** Separate an entity into multiple tables only for a real independent lifecycle, one-to-many relation, different access/security/retention profile, significant cold/large payload, or well-supported performance reason. Merge or keep together if splitting would only create repeated 1:1 JOINs and fragmented invariants.

**DB-105 (MUST):** Derived counts, materialized results, search documents and cache representations need an authoritative fact, update/invalidation/reconciliation owner and permissible staleness. Do not quietly create multiple writable sources of truth.

**DB-106 (MUST):** Required idempotency history, accepted operations, audit evidence and recoverable work must survive process crash and applicable lifecycle transitions. Deleting a row is not automatically a legal state change. Consult Domain/Reliability/Async for the obligation and retention rules.

**DB-107 (SHOULD):** Model multi-tenancy, ownership and security boundaries explicitly where relevant. Evaluate globally unique vs tenant-scoped keys, uniqueness, read filters, data export/delete and administrator access; never assume a tenant column is itself proof of authorization.

**DB-108 (MUST):** Do not enforce universal maximum counts of tables, fields, JOINs or indexes. Require an explanation only when actual complexity/size/data flow makes correctness or performance harder to maintain.

**DB-109 (SHOULD):** Introduce database views, triggers, stored procedures/functions and scheduled events only with a clear ownership, versioning, execution and observability model. Hidden writes or cross-table side effects in triggers must not silently replace application/domain workflow, audit or recovery obligations.

## 2. Tables, keys, names and documentation

**DB-201 (MUST):** New project-owned tables and substantively changed table semantics require MySQL table `COMMENT` describing the authoritative business fact and scope. New or substantively changed columns require useful `COMMENT`: meaning, unit/currency/scale, time basis, source or identifier domain, or semantic code meaning where non-obvious. Do not merely rephrase the name. Framework/system-owned objects may be exempt.

**DB-202 (MUST):** Comments must remain consistent with actual behavior; they are explanatory metadata, **not** the sole authority for a business state machine, wire enum or access policy. Changing only a comment on an already-managed table is still DDL and needs governed migration and rollout impact analysis.

**DB-203 (SHOULD):** Default to readable lowercase `snake_case` English identifiers aligned with the existing bounded-context vocabulary. Avoid vague names (`data`, `info`, `tmp2`, `type2`, `flag3`) and unclear abbreviations. Adopt consistent treatment of singular/plural within a module; do not rename unrelated legacy objects for aesthetics.

**DB-204 (SHOULD):** Name constraints/indexes predictably where the project has no compatible convention: `pk_`, `uk_`, `idx_`, `fk_` are examples only. Identifiers must fit MySQL limits; avoid database-reserved keywords, case-dependent naming, and physical order assumptions.

**DB-205 (MUST):** Every authoritative relation has an explicit identity/primary-key strategy or an evidenced reason for an alternative. Business uniqueness and technical identity are different: define both when needed, with correct tenant/state/history scope. Never rely on `SELECT`-then-`INSERT` alone to enforce required uniqueness.

**DB-206 (SHOULD):** Choose generated numeric IDs, natural keys or opaque IDs based on stability, write locality, collision/guessability, mapping, size and public exposure. Do not mechanically introduce `BIGINT`, UUID or composite keys everywhere; application-facing opacity and database physical key strategy can differ without changing accepted wire contracts.

## 3. Columns, types and invariants

**DB-301 (MUST):** Data type, precision, range, signedness and size must be selected from semantic bounds and projected evolution. For exact decimal quantities choose `DECIMAL(p,s)` or an intentional integer minor-unit representation; avoid FLOAT/DOUBLE for quantities where approximation changes outcomes.

**DB-302 (MUST):** Distinguish `NULL`, absent, empty, zero, unknown and not-applicable where observable behavior differs. Use `NOT NULL`, defaults and `CHECK` where supported/appropriate; do not use magic sentinels to bypass missing-state design. A default value must not falsify a business event or ownership fact.

**DB-303 (MUST):** Declare temporal meaning for stored dates/times: calendar/local civil time vs absolute instant, timezone conversion, precision and expiry/comparison semantics. Follow the owning project's explicit business timezone and field-specific storage mapping; preserve explicitly UTC-owned data at its boundaries. Make MySQL `TIMESTAMP` session conversion vs timezone-free `DATETIME` behavior explicit; neither column type nor a JDBC timezone property proves runtime timezone correctness by itself. This project's mapping is owned by [Architecture §1.1](../docs/architecture.md#11-时间语义与存储边界) and the canonical operation, not by a blanket UTC storage default.

**DB-304 (SHOULD):** Use `utf8mb4` and consistent collation/normalization per project conventions; review case/accent sensitivity when text participates in uniqueness, lookup or identity. Join-key signedness, character set and collation must be compatible.

**DB-305 (SHOULD):** Avoid arbitrary `VARCHAR(255)` defaults, fixed oversized text fields, unbounded `TEXT`/`BLOB` on hot list paths, and encoding structured identifiers as free text without purpose. Consider row/index size, off-page storage, frequent columns and selective projections. Do not optimize a few bytes at the cost of a wrong semantic range.

**DB-306 (SHOULD):** Store stable, queryable business attributes as typed columns. JSON is appropriate for truly semi-structured payloads with governed schema evolution/access patterns; do not replace all relational modeling with an unvalidated JSON/EAV store. Define validation, generated/functional indexing if actually necessary, compatibility and search limits.

**DB-307 (MUST):** Database-enforceable correctness constraints should be used when suitable: primary/unique keys, not-null, checks, foreign keys and conditional DML. MySQL `UNIQUE` semantics with `NULL` need explicit review: multiple NULLs can be allowed, so required business uniqueness may need a different representation.

**DB-308 (SHOULD):** Use foreign keys when they meaningfully enforce integrity within a managed transactional boundary; where omitted, explain how orphan prevention, deletes and reconciliation are owned. Avoid uncontrolled cascades on history/audit records. Cross-service identifiers are not automatically relational FK targets.

**DB-309 (MUST):** If soft deletion or active-only uniqueness is required, prove how uniqueness works with historical rows and MySQL capabilities. Do not assume a conventional `UNIQUE(owner_id, deleted_at)` provides the intended active-only uniqueness when `deleted_at` is NULL.

**DB-310 (SHOULD):** For fields like status, version, created/updated/deleted times, record actor, source and relationship meaning only when genuinely required. Do not add every common audit field to every table mechanically. Audit/event ownership and immutable history rules come from the relevant authority.

**DB-311 (MUST):** Changes to SQL mode/strictness, timezone, connection charset or collation that affect persisted semantics require compatibility review and consistent test/runtime configuration; never rely on silent truncation, coercion or implicit default behavior as input validation.

## 4. Read/write SQL and MyBatis

**DB-401 (MUST):** Set-based operations on potentially large persisted data—filtering, join, grouping, aggregation, stable sorting and pagination—belong primarily in SQL or a justified dedicated data system. Do not fetch an unbounded collection and use Java `for`/Stream to implement avoidable database work. Application-side computation remains valid on **bounded** result sets or where domain semantics demand it.

**DB-402 (SHOULD):** Select only required columns on list/batch/hot queries; avoid default `SELECT *` for public/list projections. Separate read projections from large identity objects where it reduces unnecessary I/O without multiplying near-identical DTOs.

**DB-403 (MUST):** Avoid unbounded `N+1` lookups: query count must not grow roughly one extra remote SQL call per returned row on ordinary list endpoints. Inspect MyBatis nested `<association select>`/`<collection select>`, lazy loading, loops calling mappers and post-serialization access. Favor appropriately bounded JOINs, batch `IN`, two-phase fetches, or aggregate projections.

**DB-404 (MUST):** Preserve actual result cardinality. JOIN over 1:N can duplicate parent rows; paginating the raw JOIN can truncate/distort a parent page. When necessary, page parent IDs first and fetch children in one batch, or choose an equivalent proven query model.

**DB-405 (MUST):** Pagination needs bounded page size, stable ordering and deterministic tie-breaker. Deep `OFFSET` queries require impact assessment; keyset/cursor paging MAY be used for sequential navigation when compatible with the accepted API contract. Do not change external pagination semantics from the Mapper alone.

**DB-406 (MUST):** SQL filters used for tenant, resource scope, ownership and visibility must preserve the approved authorization policy through every access path, including list/count/export/update/delete. Do not mistake client-provided IDs or a `WHERE` clause for a complete authorization decision.

**DB-407 (MUST):** Use parameter binding (`#{}`/prepared parameters) for **data values**. Never interpolate untrusted SQL fragments via `${}`, string concatenation, unsafe dynamic `ORDER BY`, table names or column names. Where identifiers cannot be bound, map allowed external choices to a code-owned allowlist. Dynamic SQL conditions must preserve parameterization.

**DB-408 (SHOULD):** Bulk writes and bulk reads should bound batch/`IN` size, transaction duration, packet size and memory. Set-based SQL is not an excuse to bypass business per-row checks, required audit, idempotency or derived event production.

**DB-409 (SHOULD):** Avoid needless repeated round trips, repeated `COUNT(*)` for expensive lists, per-row updates, broad eager loading and silent full-materialization of result cursors. For streaming, manage connection/transaction lifetime, ordering, cursor closure and backpressure explicitly.

**DB-410 (MUST):** Read-modify-write paths must explicitly preserve concurrent changes. Prefer atomic `UPDATE ... WHERE ...`, version comparisons, uniqueness constraints or properly scoped locks according to the invariant. Interpret affected-row counts and duplicate key outcomes semantically.

**DB-411 (SHOULD):** For nontrivial statements, make Mapper ownership, returned projection, required filters, expected cardinality and sorting inspectable. Avoid duplicating complex SQL with subtly different authorization/paging semantics across multiple Mapper files.

**DB-412 (MUST):** Dynamic UPDATE/DELETE statements must not become unintentionally broad when optional filters are absent; enforce required predicates and affected-row semantics. Explicitly handle empty collections, nullable filters, batch overlap and partial-update meaning rather than relying on accidental MyBatis XML rendering.

## 5. Indexes, plans and query performance

**DB-501 (MUST):** Each new index needs a real integrity or access purpose (unique guarantee, predicate, JOIN, sort/group, range or covering access). Check left-prefix overlap, write amplification, disk, memory and maintenance costs. Never prescribe one index per column.

**DB-502 (SHOULD):** Evaluate composite index order using equality predicates, ranges, join keys, ordering, selectivity and expected workload—not rules of thumb alone. Consider casts/functions/implicit conversions, collation mismatch, leading-wildcard LIKE and non-sargable predicates; these can reduce useful index access.

**DB-503 (MUST):** For new or materially changed *high-risk* query paths (large/rapidly growing data, hot endpoints, multi-table reports, security filters, expensive aggregates, known slow SQL or deep pagination), inspect actual generated SQL and appropriate MySQL `EXPLAIN` output on a representative dataset. Report access path, estimated/observed rows, join/multiplicity, sort/temp work, indexes and actual dataset limits.

**DB-504 (SHOULD):** Use `EXPLAIN ANALYZE` only where executing the statement and its resource cost are safe and explicitly owned. Treat optimizer estimates and one-off timings as evidence **with limits**, not guarantees of production P95/P99. Do not run uncontrolled expensive analyses on live systems.

**DB-505 (SHOULD):** Measure statement count, elapsed time distributions, examined/returned rows, DB CPU/IO/locks, fetch bytes, connection use and app mapping cost where relevant. A single JOIN query can be worse than two bounded queries; full scans of tiny tables can be correct. No blanket rule mandates an index, forbids JOINs or dictates a fixed maximum field count.

**DB-506 (SHOULD):** Investigate skew and stale statistics before forcing optimizer behavior; review hints/invisible indexes/functional indexes only for measured needs and document fallback. Avoid hints that compensate for outdated stats or unknowable data distributions.

**DB-507 (MUST):** Performance optimization must preserve exact semantics—filter scope, multiplicity, nullness, sorting, historical states, permissions, error and concurrency behavior. Comparing only elapsed time without response equivalence does not establish correctness.

## 6. Transactions, locks and failure behavior

**DB-601 (MUST):** Identify the atomic write set, invariant, relevant isolation behavior and possible competing operations before selecting SQL locks or changing transaction boundaries. A Spring annotation is not a concurrency proof; this standard owns MySQL-specific mechanisms, not Spring proxy mechanics.

**DB-602 (SHOULD):** Prefer a small, predictable transaction: minimize locks, long scans, unnecessary application work, blocking user interaction and remote calls inside an open transaction. Where several paths lock the same entities, use a consistent acquisition order where practical.

**DB-603 (MUST):** Distinguish deadlock, lock-wait timeout, unique conflict, connectivity loss and uncertain commit outcome. InnoDB deadlocks roll back a transaction; a default lock-wait timeout commonly rolls back only the current statement, **not automatically the entire transaction**. Do not retry every SQL exception indiscriminately; higher-level retry/idempotency ownership is controlled by Reliability/Backend.

**DB-604 (MUST):** `SELECT ... FOR UPDATE`, conditional UPDATE, optimistic versions and isolation levels are alternative mechanisms with specific transaction and index requirements; choose based on actual race and storage behavior. Do not raise transaction isolation or add locks reflexively to fix an unknown bug.

**DB-605 (MUST):** A local DB transaction does not atomically include Redis, HTTP, file systems or external messages. Commit uncertainty and durable cross-system effects belong to Backend/Reliability/Async specifications; Mapper code must not invent independent retry or delivery policy.

**DB-606 (SHOULD):** Size JDBC connection pools and worker concurrency with actual DB capacity and workload; beware nested physical transactions consuming extra connections, connection retention during streams, transaction timeout and overload. Spring configuration mechanisms remain owned by Spring/Operations.

## 7. Volume, growth and advanced structures

**DB-701 (SHOULD):** For sizable or fast-growing tables, estimate growth (rows/time, average row/index size, hot set), expected access/retention and worst normal query sizes. Avoid speculative capacity targets for tiny features, but identify irreversible scaling risks early.

**DB-702 (SHOULD):** Use archiving/retention, bounded queries, index improvements and data lifecycle management before introducing sharding, partitioning or duplicate write paths. Partitioning is not a free performance improvement; evaluate pruning predicates, index/unique-key restrictions, operations and migration cost.

**DB-703 (MUST):** If introducing replicas, distinguish committed source truth from potentially lagging reads. Define which reads may be stale and how read-your-writes, authorization freshness and critical decisions remain correct. Replication is not backup by itself.

**DB-704 (SHOULD):** Read models, reports, search and caches may legitimately live outside the primary relational schema. Define freshness, security, rebuildability, failure handling and ownership; do not assume one DB query is always the right architecture for every workload.

## 8. Security, privacy and data lifecycle

**DB-801 (MUST):** Use least-privilege database identities. Separate application DML privileges from schema migration/administration privileges where deployment supports it; never use a broad/root account merely to simplify ordinary application runtime access.

**DB-802 (MUST):** Sensitive values (credentials, keys, tokens, personal data, raw request bodies or restricted identifiers) must not be exposed through SQL logs, exception text, slow-query captures, diagnostic output, test artifacts or broad report queries. Mask or omit sensitive bind values while preserving useful query shape and trace correlation.

**DB-803 (MUST):** External input remains untrusted through SQL and import/export paths. Prepared values, allowlisted identifiers and scope checks are separate requirements. Never bypass authorization by using a batch endpoint, reporting Mapper, background worker or administrator query path.

**DB-804 (SHOULD):** Data classification, consent/legal retention, deletion/anonymization and encryption/key rotation follow Security/Domain/Operations authority; this standard requires that schema and data flows can honor those requirements. Avoid indefinite retention of personal or audit data purely because storage is convenient.

**DB-805 (SHOULD):** Prefer synthetic/minimized test data. Production-derived datasets require explicit permission, sanitization, access controls and retention. A test-only schema name does not make a shared production server safe to use.

**DB-806 (MUST):** Every destructive or bulk correction action must target an explicitly identified, authorized environment and scope; provide dry-run/row-count/rollback or forward-recovery strategy as appropriate. This standard cannot authorize production, shared or security-sensitive operations.

## 9. Migration, compatibility and repair

**DB-901 (MUST):** Govern persistent schema changes through the existing Flyway migration mechanism. Never treat Java entity/Mapper changes, manual production SQL or arbitrary startup DDL as the authoritative migration process.

**DB-902 (MUST):** Never silently edit, delete or reorder versioned migration files already applied in governed environments. New changes require a new migration. `flyway repair` changes migration history metadata and is a controlled recovery action, not an automatic way to silence checksum mismatches.

**DB-903 (MUST):** Test new/changed migrations on a fresh owned schema and, where existing data or earlier deployed versions matter, on a representative upgrade path. A successful empty-schema migration does not establish old-data compatibility.

**DB-904 (MUST):** Before adding NOT NULL, uniqueness, foreign keys or changing types, plan for existing rows, duplicates, backfill, app compatibility and constraints becoming enforceable. Backfill must be bounded/idempotent or otherwise safe to resume where interruption is possible.

**DB-905 (SHOULD):** Use expand → backfill/migrate → contract for zero/low-downtime rollout and overlapping app versions when required. Dropping/renaming columns, altering enum semantics and changing read/write ownership are contract-level compatibility work, not isolated DDL edits.

**DB-906 (MUST):** Assess table size, algorithm (`INSTANT`/`INPLACE`/`COPY` where applicable), metadata locks, concurrent writes, long transactions, temporary disk and failure/recovery before substantial MySQL DDL. “Online DDL” does not mean “zero lock or zero operational risk.” Do not assume a set of MySQL DDL statements rolls back atomically as one application transaction.

**DB-907 (MUST):** Schema changes and repair scripts must not run implicitly against developer/shared/production DBs because a test starts or a script finds a missing column. Destructive actions require concrete target, ownership and authorization. Existing command risk classes remain authoritative.

## 10. Operations, backup and observability interfaces

**DB-1001 (SHOULD):** Critical database workloads need observable query/lock/pool/error behavior using the project's Observability policy. Useful categories include slow statements or normalized digests, lock waits/deadlocks, connection saturation, replication lag, storage growth, IO pressure and migration duration. Avoid raw high-cardinality SQL values or sensitive parameters in telemetry.

**DB-1002 (MUST):** Where durable data matters, Operations must define backup ownership, restore procedure, storage separation, retention and an appropriate RPO/RTO. A backup file being present or replica being healthy is not evidence of recoverability; periodically test restoration with authorized targets. Database code changes must not silently invalidate that plan.

**DB-1003 (SHOULD):** When point-in-time recovery is required, verify backup/binlog retention, relevant integrity and restore tests in Operations scope. Do not infer PITR merely from the existence of the binary log setting.

**DB-1004 (SHOULD):** Monitor database-dependent saturation and failure behavior with actual budgets: connection counts, timeouts, latency, backlog and DB load. Do not hardcode universal limits in this general standard; Operations and workload evidence decide thresholds.

## 11. Verification, automation and resource ownership

**DB-1101 (MUST):** Choose the smallest check that proves the changed property; examples: SQL comment/schema lint, Mapper-focused test, real MySQL concurrency test, Flyway fresh/upgrade test, SQL plan on suitable data, or deployment check. The verification method and report must reveal what **was not** proved.

**DB-1102 (MUST):** Do not call a check PASS if the requested test did not run, was skipped, read stale XML/JSON, pointed to the wrong DB or failed its prerequisites. A script may emit a small JSON diagnostic with stable rule ID, changed object, SQL/Mapper identity, target metadata, actual error, report path and `PASS/FAIL/BLOCKED/NOT RUN`.

**DB-1103 (MUST):** Test execution should use repository Maven Wrapper; use the existing registered script/command if available. Don't create a per-feature `validate-xxx` or a new Docker container by default. Reuse an existing **dedicated** test MySQL service with a fresh exclusively owned schema when test semantics allow it. Container/process failure-injection requires an exclusively owned disposable instance and explicit resource lifecycle.

**DB-1104 (MUST):** Any test resource creator must record ownership and reuse/cleanup policy, not delete unowned resources and not treat a demonstration command as permission to destroy data. Controlled cleanup happens after required evidence is retained. Test failures should not trigger unbounded new-container retries. Read the precise failing test/report and prerequisite diagnostics before a targeted correction; do not broaden a local failure into a full database audit.

**DB-1105 (SHOULD):** Prefer machine-checkable changed-object checks over dumping full Maven/SQL logs into agent context. Before adding a new checker, check whether existing migration validation, MyBatis tests, schema metadata queries or test infrastructure already proves the property. Scripts should check observable facts, not implement a second business specification.

### Evidence selection table (guidance, not a mandatory sequence)

| Change | Good minimum evidence | What it cannot prove |
|---|---|---|
| New field/table COMMENT | Changed-object metadata query on owned fresh schema | Business meaning or operational compatibility |
| New table/constraint | Fresh migration; upgrade if relevant; uniqueness / FK test | Live cutover readiness |
| Mapper query/filter | Result and authorization tests; query-count bound | Real large-data performance by itself |
| High-risk list/query | `EXPLAIN` + representative cardinality, query count, response equivalence | Production tail latency from toy fixtures |
| Concurrent write/invariant | Competing real-DB transactions and durable state assertion | Every scheduling/failure interleaving |
| Large/hazardous DDL | Upgrade/backfill/migration rehearsal, lock/space plan | Production approval |
| Backup/recovery change | Authorized restore/replay rehearsal | Real RPO/RTO without measured evidence |

### Minimal SQL metadata probes (read-only; only on an authorized target)

```sql
SELECT DATABASE() AS current_schema;

-- Restrict object names to this change when enforcing COMMENT completeness.
SELECT t.TABLE_NAME, t.TABLE_COMMENT
FROM INFORMATION_SCHEMA.TABLES t
WHERE t.TABLE_SCHEMA = DATABASE()
  AND t.TABLE_TYPE = 'BASE TABLE';

SELECT c.TABLE_NAME, c.COLUMN_NAME, c.COLUMN_COMMENT,
       c.DATA_TYPE, c.IS_NULLABLE, c.COLUMN_DEFAULT
FROM INFORMATION_SCHEMA.COLUMNS c
WHERE c.TABLE_SCHEMA = DATABASE();
```

Legacy missing comments are inventory findings, not automatic build failures or blanket migration authority. Scripts must verify the configured endpoint against task-recorded target ownership and the existing command prerequisites **before connection**, then verify the selected schema and permissions **after connection and before any mutating operation**. This does not require creating an environment registry.

## 12. Lightweight design/review flow

For a routine small Mapper change: locate affected method/SQL → preserve query results, authorization and paging → focused test → plan only if risk triggers → concise report. No full-standard reread or new design file.

For new persistence: briefly establish *fact + lifecycle; table/relationship rationale; keys/constraints/COMMENT; read/write paths/indexes; migration and verification*. More explanation is required only when risk or novelty justifies it.

For transactions, security-sensitive data, destructive migration, new read replicas or persistent external effects: consult the relevant Domain/Contract/Reliability/Async/Security/Operations owner **before** implementation. The cost of late semantic redesign exceeds the savings from skipping these sources.

## 13. Example (illustrative, not a mandatory schema template)

```sql
CREATE TABLE activity_enrollment (
    id BIGINT NOT NULL COMMENT '报名关系的内部稳定标识',
    activity_id BIGINT NOT NULL COMMENT '所报活动标识',
    user_id BIGINT NOT NULL COMMENT '报名用户主体标识',
    status VARCHAR(24) NOT NULL COMMENT '当前报名状态；有效值与转换以业务规范为准',
    created_at DATETIME(6) NOT NULL COMMENT '记录创建时刻，按所属字段的明确时区约定写入',
    PRIMARY KEY (id),
    UNIQUE KEY uk_activity_enrollment_activity_user (activity_id, user_id),
    KEY idx_activity_enrollment_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='每个用户对一项活动的一条逻辑报名关系（仅适用于不重复建历史行的业务模型）';
```

The unique constraint, surrogate key and additional query index are **examples**, not default requirements. If re-enrollment/history means multiple logical rows, or if the query workload differs, the model and keys must change. No example DDL may be applied to any database without task-specific approval.

## 14. Mechanism references (source documents, not project policy)

- MySQL 8.4 Reference Manual: [data types](https://dev.mysql.com/doc/refman/8.4/en/data-types.html), [foreign keys](https://dev.mysql.com/doc/refman/8.4/en/create-table-foreign-keys.html), [indexes](https://dev.mysql.com/doc/refman/8.4/en/mysql-indexes.html), [EXPLAIN](https://dev.mysql.com/doc/refman/8.4/en/explain.html).
- MySQL 8.4: [transaction isolation](https://dev.mysql.com/doc/refman/8.4/en/innodb-transaction-isolation-levels.html), [deadlock/error behavior](https://dev.mysql.com/doc/refman/8.4/en/innodb-error-handling.html), [online DDL limitations](https://dev.mysql.com/doc/refman/8.4/en/innodb-online-ddl-limitations.html).
- MySQL 8.4: [Performance Schema](https://dev.mysql.com/doc/refman/8.4/en/performance-schema.html), [replication](https://dev.mysql.com/doc/refman/8.4/en/replication.html), [backup and recovery](https://dev.mysql.com/doc/refman/8.4/en/backup-and-recovery.html).
- [MyBatis Mapper XML / nested selects](https://mybatis.org/mybatis-3/sqlmap-xml.html), [dynamic SQL](https://mybatis.org/mybatis-3/dynamic-sql.html).
- Redgate Flyway: [Validate](https://documentation.red-gate.com/flyway/reference/commands/validate), [Repair](https://documentation.red-gate.com/flyway/reference/commands/repair).
- OWASP: [SQL Injection Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/SQL_Injection_Prevention_Cheat_Sheet.html).
