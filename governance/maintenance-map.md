# maintenance-map.md

## Repository binding

Concrete authority paths and missing specialized-standard fallbacks are maintained in [root AGENTS.md](../AGENTS.md). Read that routing before using this map. Technology names below identify concerns, not installed frameworks or mandatory new documents. Spring Security / Compose examples do not require adopting those technologies. Only inspect sections triggered by the actual change. Start with §44 to locate affected surfaces; this map does not mandate scanning every listed technology or running every test level.

## 1. Purpose

This file defines how changes to durable facts propagate across the repository.

It answers:

```text
A durable fact changed.
→ Who has normative authority?
→ Where is the fact represented/executed?
→ Which dependents must be inspected?
→ Which dependents actually need updating?
```

This file does **not** duplicate the fact itself.

---

## 2. Core Rule

```text
durable fact changes
        ↓
identify normative authority
        ↓
inspect executable representations and dependents
        ↓
determine semantic impact
        ↓
update affected surfaces only
```

The governing rule is:

```text
inspect ≠ modify
```

A valid outcome is:

```text
dependent inspected
→ no semantic impact
→ no diff
```

---

## 3. Three Roles

Do not conflate these roles.

### Normative Authority

Defines what SHOULD/MUST be true.

Examples:

```text
Accepted Contract
Engineering Standard
Domain Specification
Project Architecture
Operations policy
```

### Executable Representation

Represents the fact in the repository/runtime.

Examples:

```text
pom.xml
Controller mapping
migration file
application.yml
Docker Compose
Nginx config
Java implementation
```

### Dependent / Evidence

Relies on or proves the fact.

Examples:

```text
tests
client code
CI
documentation
monitoring
generated artifacts
```

An executable representation is not automatically a second normative authority.

---

## 4. Durable Fact

A durable fact remains relevant beyond one local implementation step.

Examples:

```text
accepted endpoint shape
business invariant
Java baseline
Spring Boot baseline
configuration key
database schema element
message schema
Redis key contract
canonical command
deployment port/domain
authorization rule
```

Usually not durable:

```text
local variable name
temporary debugging result
one-off diagnostic command
intermediate implementation idea
temporary test fixture
```

---

## 5. One Durable Rule, One Normative Authority

Each durable rule SHOULD have one normative authority.

A dependent MAY summarize or implement the rule.

It MUST NOT silently become a competing authority.

---

## 6. Change Classes

### M0 — Local Implementation Detail

Examples:

```text
private rename
equivalent local refactor
local variable change
```

Usually no maintenance propagation.

### M1 — Internal Durable Implementation Fact

Examples:

```text
executor topology
new internal queue
new infrastructure bean
persistent internal status
```

Inspect relevant standards, configuration, tests, and operations.

### M2 — Cross-Layer Durable Fact

Examples:

```text
schema change
config-key change
message schema
cache-key contract
authorization model
worker lifecycle
```

Explicit dependent inspection required.

### M3 — External / Operational Contract Change

Examples:

```text
public API
public error schema
Java/Spring baseline
production deployment procedure
runtime port/domain
required environment variable
```

Broad dependent inspection and compatibility analysis required.

---

# 7. Authority Registry

| Durable rule/fact | Normative authority | Executable representation |
|---|---|---|
| Accepted HTTP/API protocol | Accepted Contract | Controller/DTO/serialization implementation |
| Business state/invariant/policy | Domain / Business Specification | service/domain/DB implementation |
| Backend correctness/reliability guarantee | Backend Engineering Standard | application/persistence/messaging implementation |
| Java engineering/baseline policy | Java Engineering Standard | build config, source, runtime image |
| Spring integration/baseline policy | Spring Boot Engineering Standard | build config, Spring configuration/code |
| Database engineering rules | Database Standard | migrations, SQL, repositories |
| Current reconstructable DB schema | migration history | running database |
| Redis/cache rules | Redis Standard | Redis keys/TTL/read-write logic |
| Messaging delivery/recovery rules | Messaging Standard | producer/consumer/state implementation |
| Reminder / Notification / Delivery semantics | `docs/reminder-notification.md` | business policies, intent generation/expiry, recipient timing, Timeline semantic preflight, expand/preflight/owner gates, notify services, preference/inbox/delivery tables, async ownership/terminal repair, WeChat mapping, retention/replay, runbook and implementation handoff |
| WeChat capability/account/message-product boundary and enablement evidence | `docs/wechat-integration.md` | account/config references, identity/permission/template adapters, callback routing, delivery handler, `WxClient`; inspect affected auth/audit/notify owners and environment evidence; COS remains separate |
| Authentication/authorization mechanism | Security Standard | security config/filter/service implementation |
| Project dependency direction | Project Architecture | modules/packages/dependencies |
| Production runtime/deployment policy | Operations | Compose/Nginx/system config/deploy scripts |
| Exact recurring command | `commands.md` | shell/Maven/Docker invocation |
| Agent repository workflow | `AGENTS.md` | agent execution |
| Maintenance dependency relationships | `maintenance-map.md` | maintenance workflow |

---

# 8. Accepted Contract Change

## Trigger

```text
route
HTTP method
request/response field
requiredness
null/omission semantics
wire enum
date/time representation
status
header
public error shape/code
pagination semantics
```

## Authority

Accepted Contract.

## Inspect

```text
Controller mappings
request/response DTOs
serialization
validation
exception translation
security route/action mapping
MVC/contract tests
Mini Program client
Admin client
generated API artifacts
```

Update only affected dependents.

Do not modify reusable Backend/Java/Spring standards merely because one endpoint changed.

---

# 9. Business Invariant / State Machine Change

## Authority

Domain / Business Specification.

## Inspect

```text
application/service logic
authorization
database constraints/schema
transaction/concurrency protocol
cache derivation
message/event production
scheduled/background work
API mapping
tests
admin/client behavior
```

---

# 10. Backend Guarantee Change

Examples:

```text
idempotency
retry ownership
Unknown Outcome handling
recovery
outbox
source of truth
cache consistency
async acceptance
```

## Authority

Backend Engineering Standard.

## Inspect

```text
application workflow
persistent state
cache/Redis
messaging
HTTP outcome mapping
recovery/scheduler
observability
tests
specialized standards
```

Do not duplicate the guarantee into every technology standard.

---

# 11. Java Baseline Change

Example:

```text
Java 17 → Java 21
```

## Authority

Java Engineering Standard.

## Executable Representations

```text
pom/build toolchain
Docker/JRE image
CI runtime
source/API usage
```

## Inspect

```text
Spring Boot compatibility
Spring Boot Standard
commands.md
developer setup
deployment runtime
tests
version-sensitive code
```

A Java upgrade MAY unlock new Spring features; it does not automatically enable them.

---

# 12. Spring Boot Baseline Change

## Authority

Spring Boot Engineering Standard.

## Executable Representations

```text
build dependency/plugin configuration
Spring configuration
runtime dependencies
```

## Inspect

```text
Java baseline
managed dependency set
deprecated/removed APIs
auto-configuration
configuration properties/defaults
MVC/validation/transactions
async/scheduling
Actuator
testing
Docker/runtime
commands
dependency overrides
```

A patch upgrade does not require mechanically rewriting every document line.

---

# 13. Dependency Change

## Authority

Relevant Engineering Standard for policy; build configuration represents the selected dependency set.

## Inspect

```text
Boot auto-configuration
transitive graph
configuration
runtime resources
security exposure
tests
Docker/runtime
commands if build procedure changes
```

---

# 14. Database Schema Change

## Authority

For the current reconstructable schema: migration history.

Database Standard governs how schema evolution should be designed.

## Inspect

```text
SQL mapper/repository
domain/persistence mapping
application invariant
migration compatibility/backfill
constraints/indexes
query plans
tests
admin/reporting queries
backup/restore assumptions
deployment
```

Entity/mapper code is not schema authority.

---

# 15. Migration Mechanism Change

## Authority

Database/Operations policy according to the chosen migration ownership model.

## Inspect

```text
commands.md
Compose
Spring startup configuration
deployment scripts
CI/CD
backup/restore
Database Standard
developer setup
tests
```

Once finalized, register the exact recurring commands in `commands.md`.

---

# 16. Configuration Key Change

## Authority

The configuration contract/specification for that subsystem; Spring configuration type is its executable representation.

## Inspect

```text
application.yml
environment variables
Compose
CI/CD
secret store
production environment
Operations docs
commands
tests
configuration metadata
```

A rename of a deployment-facing key is an operational compatibility change.

---

# 17. Secret Requirement Change

## Authority

Security / Operations.

## Inspect

```text
Spring binding
deployment environment
secret store
Compose/deploy configuration
CI/CD secret reference
rotation
sanitization/logging
startup validation
```

Never copy production secret values into repository documentation.

---

# 18. Spring Bean / Wiring Change

## Authority

Spring Boot Engineering Standard governs mechanism; Project Architecture governs dependency direction.

## Inspect

```text
auto-configuration back-off
bean ambiguity
lifecycle ownership
dependent components
test overrides/context caching
configuration properties
observability integration
```

Document only durable architectural/infrastructure decisions, not every bean.

---

# 19. Transaction Boundary Change

## Authority

Backend/Database standards own required guarantees; Spring Standard owns mechanism.

## Inspect

```text
business invariant
DB locks/isolation
connection pool demand
rollback behavior
external effects
async boundaries
outbox/messaging
tests
```

Do not change Accepted Contract unless external semantics actually change.

---

# 20. Authorization Policy Change

## Authority

Domain / Security policy.

## Inspect

```text
service authorization
Spring Security integration
resource loading/query
admin capability
public error behavior
tests
audit/observability
client UI visibility
```

Client UI visibility is a dependent, not authorization authority.

---

# 21. Authentication Mechanism Change

## Authority

Security Standard.

## Inspect

```text
Spring Security integration
Controller subject extraction
authentication Contract
client login flow
configuration/secrets
observability
tests
deployment
```

---

# 22. Redis Cache Contract Change

## Authority

Redis Standard.

## Inspect

```text
writers/readers
DB source of truth
invalidation
rebuild
key cleanup/migration
tests
observability
multi-instance behavior
```

Cache representation does not become business truth unless architecture explicitly changes.

---

# 23. Messaging / Queue Change

## Authority

[Async Processing Architecture](../docs/async-processing.md) owns this project's Outbox, Job, Message, Redis Streams, Worker, retry/reclaim/dedup/dead and migration decisions. Reliability owns failure/recovery meaning; Observability owns IDs, context, logs and metrics.

## Inspect

```text
producers
consumers
message schema
serialization
idempotency
retry/recovery
pending/dead-letter handling
worker lifecycle
Redis/broker configuration
observability
tests
deployment
```

A Java payload class does not own delivery semantics.

---

# 24. Message Schema Change

## Authority

[Async Processing Architecture](../docs/async-processing.md), plus an accepted cross-component contract when a message crosses this application boundary.

## Inspect

```text
all producers
all consumers
stored/pending old messages
serialization compatibility
replay/retry
tests
rollout order
```

If old messages may remain during rollout, compatibility MUST be considered.

---

# 25. Worker Lifecycle Change

Examples:

```text
@PostConstruct worker → SmartLifecycle
executor ownership
BRPOP behavior
shutdown/recovery loop
```

## Authority

Spring Standard for lifecycle mechanism; [Async Processing Architecture](../docs/async-processing.md) and Backend/Reliability standards for delivery/recovery semantics.

## Inspect

```text
startup/readiness
shutdown timeout
executor
Redis/client lifecycle
in-flight work
claim/recovery
health/metrics/logs
tests
deployment grace period
```

---

# 26. Scheduled Job Change

## Authority

Application/job specification owns job meaning; [Async Processing Architecture](../docs/async-processing.md) owns durable Job/claim/lease boundaries; Spring Standard owns trigger mechanism.

## Inspect

```text
multi-instance duplication
idempotency
durable due-state
transaction boundaries
DB/downstream load
scheduler capacity
configuration/timezone
observability
tests
deployment
```

If the schedule represents a durable business obligation, inspect whether the obligation itself is stored durably.

---

# 27. Async Execution Change

## Authority

Spring Standard owns async mechanism; Backend/Reliability and [Async Processing Architecture](../docs/async-processing.md) own durability/recovery requirements.

## Inspect

```text
transaction boundary
context propagation
executor capacity
rejection
shutdown
failure observation
durability requirement
tests
```

If the work must survive process failure, inspect whether in-process async is the wrong mechanism.

---

# 28. Runtime Port / Domain / Reverse Proxy Change

## Authority

Operations.

## Inspect

```text
Nginx
DNS
TLS certificate
container port mapping
application config
firewall/security group
client base URL
CORS/security
health checks
deployment docs
commands
```

---

# 29. Docker Compose Change

## Authority

Operations; Compose file is the executable representation.

## Inspect

```text
commands.md
application config
local developer workflow
migration
ports/volumes/networks
secrets
CI/CD
deployment docs
```

If service names change, update corresponding `COMPOSE-*` commands.

---

# 30. Canonical Command Change

## Authority

`commands.md`.

## Inspect

```text
AGENTS.md references
CI/CD scripts
developer/Operations docs
automation scripts
maintenance-map Command ID references
```

If semantics/risk remain the same, stable ID MAY remain unchanged.

If blast radius materially changes, consider a new ID.

---

# 31. AGENTS.md Change

## Authority

Nearest applicable `AGENTS.md` for agent workflow in its scope.

## Inspect

```text
nested AGENTS.md
commands.md
maintenance-map.md
repository standards routing
repo automation instructions
```

Do not copy technical standards into `AGENTS.md`.

---

# 32. Engineering Standard Change

## Authority

The corresponding Engineering Standard.

## Inspect

```text
AGENTS routing
related specialized standards
existing implementation newly affected
review/checklist material
maintenance-map authority registry
```

Changing a standard does not automatically authorize repository-wide refactoring in the same task.

---

# 33. Project Architecture Change

## Authority

Project Architecture.

## Inspect

```text
module build config
Spring scanning
component dependencies
tests
documentation
CI
Docker packaging
AGENTS routing where needed
```

Spring bean availability does not override architecture dependency direction.

---

# 34. Test Infrastructure Change

## Authority

[Backend §9.11–9.13](../standards/backend-engineering.md#911-completion-claims) owns scope, escalation, classification and evidence; Java/Spring and relevant technology standards own mechanisms. Build/test configuration is executable representation; commands.md owns invocations.

## Inspect

```text
commands.md
AGENTS completion/routing
Wrapper/toolchain/compiler/test JVM configuration
CI environment
Docker requirement
context caching
test isolation
developer setup
build time/resources
```

---

# 35. Observability Change

## Authority

[Observability Baseline](../docs/observability.md) owns this project's ID, context, logging, error taxonomy, metric/cardinality, sensitive-data and health contract. [Backend §8.2](../standards/backend-engineering.md#82-logs-and-operational-ownership) owns technology-independent event responsibility; Java §7.7 owns calls, Spring §11.15–11.17 owns integration.

## Inspect

```text
primary failure/event boundaries and duplicate stack traces
retry terminal/intermediate evidence
durable audit storage where required
MDC propagation and cleanup
runtime logging configuration
metric cardinality
sensitive data
runtime cost
security
monitoring backend
alerts
deployment probes
async context propagation
tests
```

Health/metrics do not become business-state authority.

---

# 36. Public Error Model Change

## Authority

Accepted Contract.

## Inspect

```text
ControllerAdvice
Spring Security error adapters
MVC binding/validation errors
Boot fallback/error path
clients
contract tests
documentation
```

---

# 37. Client-Facing Enum Change

Distinguish:

```text
internal Java enum semantics
→ Domain / Java implementation

wire enum value
→ Accepted Contract
```

## Inspect

```text
serialization/deserialization
DB representation
message representation
clients
migration/backfill
tests
```

A Java enum rename MUST NOT silently change an accepted wire value.

---

# 38. Time Semantics Change

Authority depends on the fact:

```text
Java temporal meaning
→ Java Standard / Domain specification

wire representation
→ Accepted Contract

business schedule/timezone
→ application/domain/operations specification

DB representation
→ Database Standard / migration
```

## Inspect

```text
serialization
database
clients
scheduler
tests
migration
```

---

# 39. Generated Artifact Change

## Authority

Generator input/source.

## Inspect

```text
generator command
commands.md
generated output
CI validation
consumers
```

Edit source first; regenerate output.

---

# 40. File Rename / Move

The file retains its semantic role unless authority itself changes.

## Inspect

```text
AGENTS routing/links
maintenance-map references
commands
CI scripts
build/resource paths
documentation links
imports
```

A rename alone does not require rewriting content.

---

# 41. Deleting an Authoritative Artifact

Before deletion:

1. identify the durable facts it owns;
2. assign a new authority or declare the facts obsolete;
3. inspect known dependents;
4. update/remove references;
5. ensure no dependent summary becomes accidental authority.

---

# 42. Creating a New Durable Artifact

Before creating one:

```text
Does an authority already exist?
        ↓ yes
use/extend it

        ↓ no
Is this fact durable and worth governing?
        ↓ yes
define authority and dependents
```

Avoid documentation proliferation.

---

# 43. No-Change Result

After required inspection, this is valid:

```text
No dependent update required.
```

Completion reports MAY distinguish:

```text
Updated
Inspected, unaffected
Not inspected
Unverified
```

Do not create a diff merely to prove inspection happened.

---

# 44. Compact Dependency Matrix

| Change | Primary dependents to inspect |
|---|---|
| Accepted API Contract | Controller, DTO, serialization, validation, errors, clients, contract tests |
| Business invariant | service/domain, DB, concurrency, auth, cache, messaging, tests |
| Reminder / Notification / Delivery semantics | Activity/PublicEvent policy, Timeline/intent identity, recipient timing, notify plan/inbox/delivery schema, Job ownership/terminal repair, WeChat mapping, retention/runbook, handoff acceptance and tests |
| WeChat integration | target dependency/owner boundaries, account/credential lifecycle, identity/permission, OAuth/state, callback routing/ACK, template/config, Delivery result classification, client, secrets, observability, compatibility/tests; `docs/WECHAT_REFACTOR_PROMPT.md` only routes execution, not a second authority |
| Java baseline | build, Spring baseline, CI, runtime image, commands, code |
| Spring Boot baseline | Java, BOM, configuration, framework integration, tests, deployment |
| DB schema | migrations, SQL/mappers, constraints/indexes, tests, deployment |
| Config key | YAML/env, Compose, CI, secrets, Operations, tests |
| Security policy | service policy, Spring Security, API errors, clients, audit/tests |
| Redis cache | readers/writers, DB truth, invalidation, tests |
| Message schema | producers, consumers, queued old messages, rollout, tests |
| Worker lifecycle | executor, shutdown, recovery, health, deployment |
| Scheduled job | ownership, durable state, idempotency, capacity/load |
| Executor | async selection, framework use, capacity, context, shutdown |
| Actuator/health | exposure, security, probes, monitoring, deployment |
| Canonical command | docs/scripts/CI referencing the command |
| Build environment / verification policy | Java baseline, Wrapper/toolchains, compiler/test JVM, commands, AGENTS, CI/test configuration |
| Comments / Javadoc policy | Java §1.8–1.10, affected source documentation, Contract references if duplicated; no blanket source rewrite |
| Logging ownership / integration | Backend §8.2, Java §7.7, Spring §11, execution boundaries, runtime config, context tests, audit persistence |
| Compose | commands, configuration, ports, volumes, developer workflow |
| Deployment topology | Nginx/DNS/TLS/ports/config/health/client endpoints |
| Engineering Standard | AGENTS routing, related standards, affected implementation |
| Project Architecture | modules, DI/scanning, build, tests, docs |

---

# 45. Repository Governance Relationship

```text
AGENTS.md
→ how an agent works

commands.md
→ exact recurring repository procedures

maintenance-map.md
→ what to inspect when durable facts change
```

None of these three files owns Java, Spring, Backend, Database, Contract, Security, Messaging, or business rules.

They route to those authorities.

---

# 46. Final Maintenance Model

```text
normative authority
        │
        ▼
executable representation
        │
        ├───────────────┐
        ▼               ▼
dependent surfaces   tests/evidence
```

When a durable fact changes:

```text
authority changes
→ maintenance-map identifies representations/dependents
→ inspect them
→ update only semantically affected surfaces
→ verify the changed property
```

This preserves consistency without turning every repository edit into documentation churn.
