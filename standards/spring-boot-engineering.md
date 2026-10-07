# Spring Boot Engineering Standard

**Status:** Frozen  
**Project baseline:** Java 17 · Spring Boot 3.5.13  
**Normative language:** MUST, MUST NOT, SHOULD, SHOULD NOT, MAY

---

## 0. Scope, Baseline & Authority

### 0.1 Scope

This standard governs Spring-specific engineering concerns in the backend application, including:

- `ApplicationContext`, bean registration, scopes, and dependency injection;
- Spring AOP/proxy behavior;
- declarative transaction integration;
- Spring MVC request/response adaptation;
- validation, conversion, and binding integration;
- Web exception translation;
- externalized configuration, profiles, and conditional beans;
- bean/application lifecycle;
- task execution and scheduling;
- Spring Boot auto-configuration and dependency management;
- Actuator/observability integration;
- Spring testing integration.

It does **not** own:

- business rules, state machines, or invariants;
- accepted HTTP/OpenAPI protocol design;
- Java language/style rules;
- SQL/index/database-specific semantics;
- Redis key/cache design;
- message-delivery semantics;
- authorization policy itself;
- deployment topology;
- Git or agent workflow.

Those concerns belong to their corresponding standards.

### 0.2 Baseline

The repository baseline is:

```text
Java 17
Spring Boot 3.5.13
```

Later Spring Boot 3.5.x documentation MAY be used to understand stable 3.5-series mechanisms, but MUST NOT silently change the project baseline.

Features requiring Java 21+ MUST NOT be introduced while Java 17 remains the baseline.

### 0.3 Authority by Subject

Use one authoritative owner per durable rule:

```text
Backend Engineering
→ system-level guarantees and failure/correctness semantics

Java Engineering
→ Java types, language rules, JVM semantics, resource/thread safety

Spring Boot Engineering
→ Spring container/framework integration

Domain / Business Specifications
→ business states, rules, policies, invariants

Accepted Contract
→ externally observable protocol

Database / Redis / Messaging / Security Standards
→ technology-specific semantics

Operations
→ deployment/runtime environment
```

Spring specializes higher-level rules; it does not replace them.

### 0.4 Accepted Contract Is Input

Spring MVC annotations, Jackson configuration, validation annotations, status mapping, and `ControllerAdvice` implement the accepted protocol.

They MUST NOT redefine it.

A required protocol change MUST be escalated to the contracts workflow and accepted before backend implementation treats it as authoritative.

### 0.5 Annotation Does Not Equal Behavior

A Spring annotation is metadata.

Framework behavior requires:

```text
annotation / configuration
+ required infrastructure
+ eligible bean/method
+ eligible invocation path
= runtime behavior
```

This principle applies especially to:

- `@Transactional`
- `@Async`
- `@Cacheable`
- method validation
- custom AOP advice

### 0.6 Dependency Injection Does Not Define Architecture

A dependency being injectable does not make it architecturally valid.

Project architecture still owns allowed dependency direction.

### 0.7 Framework Defaults

Prefer Spring Boot-managed defaults until a concrete requirement justifies customization.

When a default materially controls correctness, security, capacity, protocol behavior, or shutdown behavior, configure or document the required value explicitly.

---

# 1. Application Context, Beans & Dependency Injection

## 1.1 Explicit Dependencies

Spring-managed components SHOULD declare required collaborators explicitly.

Prefer constructor injection:

```java
@Service
class EnrollmentService {

    private final EnrollmentRepository repository;
    private final EnrollmentPolicy policy;

    EnrollmentService(
            EnrollmentRepository repository,
            EnrollmentPolicy policy) {
        this.repository = repository;
        this.policy = policy;
    }
}
```

Avoid ordinary application code obtaining collaborators through:

- `ApplicationContext.getBean(...)`;
- static bean holders;
- global service locators.

### 1.2 Constructor Injection Is the Default

Required dependencies MUST normally use constructor injection.

A single constructor does not need `@Autowired`.

Field injection SHOULD NOT be used in project-owned production code except for narrowly justified framework/generated/test cases.

Setter/method injection MAY be used when a dependency is genuinely optional or mutable after construction.

### 1.3 Optional Dependencies Must Be Truly Optional

A dependency MUST NOT be marked optional merely to let an invalid application configuration start.

If the component cannot perform its responsibility without the collaborator, the collaborator is required.

Use `Optional<T>` or `ObjectProvider<T>` only when absence/lazy lookup is an intentional semantic choice.

`@Lazy` MUST NOT be used as a general optional-dependency mechanism.

### 1.4 Spring Beans vs Ordinary Objects

A type SHOULD normally be a Spring bean when Spring needs to manage concerns such as:

- application-level dependency injection;
- lifecycle;
- infrastructure integration;
- proxy/interceptor behavior;
- shared application capability.

Ordinary values, DTOs, entities, domain values, temporary accumulators, and per-operation state SHOULD remain ordinary Java objects unless Spring management adds concrete value.

### 1.5 Stereotypes

Use meaningful stereotypes where appropriate:

```text
@Service
→ application/service capability

@Repository
→ persistence adapter where Spring repository semantics apply

@Controller / @RestController
→ Web boundary

@Component
→ generic project component when no more specific stereotype fits
```

Stereotypes do not replace Project Architecture.

### 1.6 @Bean

Use `@Bean` when construction is naturally owned by configuration, including:

- third-party classes;
- infrastructure clients;
- explicit factories;
- several configured variants of one type;
- configuration-dependent construction.

Prefer method-parameter dependency injection:

```java
@Bean
NotificationSender notificationSender(
        HttpClient client,
        NotificationProperties properties) {
    return new NotificationSender(client, properties);
}
```

### 1.7 Configuration Classes

Project configuration SHOULD normally use:

```java
@Configuration(proxyBeanMethods = false)
```

and express inter-bean dependencies through method parameters.

Do not rely on direct cross-`@Bean` method calls unless full `@Configuration` proxy semantics are intentionally required.

### 1.8 Bean Selection

Type-based injection is the default.

When several candidates exist:

- use `@Primary` only for a genuine default implementation;
- use `@Qualifier` for a semantic variant;
- inject a collection when the consumer genuinely needs all implementations.

Important wiring SHOULD NOT rely on parameter-name fallback or accidental bean names.

Repeated qualifier semantics MAY be represented by a custom qualifier annotation.

### 1.9 Circular Dependencies

Constructor cycles such as:

```text
A → B → A
```

SHOULD be treated as design problems.

Do not “fix” them by default with:

- field injection;
- setter injection;
- self injection;
- `@Lazy`.

Prefer restructuring responsibilities, extracting orchestration, or correcting dependency direction.

### 1.10 Scopes

The default Spring scope is singleton: one bean instance per bean definition per relevant `ApplicationContext`.

Spring singleton does **not** mean cluster-wide or JVM-global singleton.

Singleton service beans SHOULD normally be stateless with respect to per-request mutable data.

Prototype scope creates a new instance per resolution but does not provide full container-managed destruction after the instance is handed to the client.

A prototype directly injected into a singleton is resolved once during singleton construction; use an explicit provider/scoped mechanism when repeated runtime resolution is genuinely required.

Request/session scope SHOULD stay near the Web boundary.

Scope does not imply thread safety.

### 1.11 Fail Fast

Non-lazy singleton initialization is the normal default.

Missing required dependencies or invalid construction SHOULD fail startup rather than remain hidden until the first production request.

`@Lazy` SHOULD NOT be used to hide structural problems.

---

# 2. Proxy, AOP & Annotation Semantics

## 2.1 Proxy Model

Spring AOP is primarily proxy-based:

```text
caller
  ↓
Spring proxy
  ↓
interceptor / advice
  ↓
target method
```

For proxy-driven behavior, invocation MUST cross the proxy boundary.

### 2.2 Self-Invocation

A target calling another method through `this` does not re-enter the proxy.

Therefore:

```java
public void outer() {
    inner();
}

@Transactional
public void inner() {
    ...
}
```

does not activate `inner()`'s proxy advice merely because the annotation is present.

Preferred response:

1. identify the real application boundary;
2. refactor responsibilities so the call naturally crosses a Spring bean boundary.

Self injection and `AopContext.currentProxy()` SHOULD NOT be normal application patterns.

### 2.3 Proxy Strategies

Spring Framework supports:

- JDK dynamic proxies;
- class-based proxies.

Under the current Spring Boot 3.5 baseline, class-based auto-proxying is the normal default because `spring.aop.proxy-target-class=true`.

Code MUST still understand the effective proxy strategy rather than depend on proxy implementation accidents.

### 2.4 Proxyability

For class-based proxies:

- the target class MUST NOT be `final`;
- an advised method MUST NOT be `final`;
- private methods are not normal proxy interception boundaries.

Project-owned proxy-dependent application operations SHOULD normally be public.

Do not make arbitrary helpers public merely to place proxy annotations on them; reconsider the component boundary first.

### 2.5 Proxy-Driven Annotations

Annotations such as:

- `@Transactional`;
- `@Async`;
- `@Cacheable`;
- `@Validated`-based service method validation;
- custom AOP advice

SHOULD mark meaningful externally invoked application operations.

Do not scatter them over private/helper methods.

### 2.6 Advice Composition

Several applicable interceptors form an ordered chain.

When correctness depends on ordering between concerns such as:

- transactions;
- retry;
- security;
- metrics;
- async dispatch;
- custom advice,

ordering MUST be explicit and verified.

Do not depend on bean registration or source-file order.

### 2.7 Custom AOP

Custom AOP MAY implement genuine cross-cutting concerns.

It SHOULD NOT become hidden ownership of ordinary business flow or authorization policy.

Pointcuts SHOULD be narrow and intentional.

AspectJ weaving MUST NOT be introduced merely to avoid refactoring proxy self-invocation.

### 2.8 Verification

A plain:

```java
new SomeService(...)
```

test can verify target logic but cannot prove proxy advice.

Proxy-dependent behavior requires a Spring-managed/proxied invocation in the relevant test.

---

# 3. Transaction Management

## 3.1 Transaction Model

`@Transactional` is transaction metadata.

The effective flow is:

```text
caller
→ transaction proxy
→ TransactionInterceptor
→ transaction manager
→ physical resource transaction
→ target method
→ commit / rollback
```

The annotation itself does not lock Java code or independently create distributed atomicity.

### 3.2 Transaction Owner

A transaction SHOULD normally surround the smallest coherent application operation whose participating local transactional state changes must succeed or fail together.

Default structure:

```text
Controller
→ application/service transaction boundary
→ repository/data operations
```

Controllers SHOULD NOT normally own transactions.

Repository-level transactions alone may be too narrow for multi-step invariants.

### 3.3 Transaction vs Lock

`@Transactional` is not a database lock.

For example:

```text
Spring transaction begins
→ SELECT ... FOR UPDATE
→ database acquires row lock
→ later SQL participates in same transaction
→ commit / rollback
→ database releases transaction-scoped locks
```

The database owns locking semantics; Spring owns transaction integration/lifecycle.

### 3.4 Concurrency Correctness

A transaction alone does not prevent:

- lost updates;
- oversubscription;
- duplicate logical operations;
- write skew;
- stale-read decisions.

Correctness MAY additionally require:

- constraints;
- conditional updates;
- optimistic concurrency;
- row locks;
- appropriate isolation;
- idempotency.

Database/Backend standards own those details.

### 3.5 Propagation

`REQUIRED` is the ordinary default:

```text
existing compatible transaction
→ join

none
→ create
```

Several logical `REQUIRED` scopes may share one physical transaction.

An inner scope can mark the physical transaction rollback-only, causing the outer commit attempt to fail with `UnexpectedRollbackException`.

Do not catch inner failures casually and assume the outer physical transaction remains committable.

### 3.6 REQUIRES_NEW

`REQUIRES_NEW` creates an independent physical transaction and suspends the outer one.

Use it only when independent commit/rollback is deliberately required.

It may need an additional database connection while the outer transaction retains its own; pool capacity MUST be considered.

It is not a “stronger transaction” setting.

### 3.7 NESTED

`NESTED` typically represents savepoint-based partial rollback within one physical transaction when the actual transaction manager/resource supports it.

It is not equivalent to `REQUIRES_NEW`.

Do not use it without verifying support in the real persistence stack.

### 3.8 Isolation, readOnly, Timeout

`ISOLATION_DEFAULT` delegates to the underlying transaction system.

Do not raise isolation as a generic race-condition fix.

`readOnly=true` is primarily a transaction hint, not universal write protection.

Timeout is a transaction/resource limit, not proof that a business effect definitely failed or that retry is safe.

### 3.9 Rollback Policy

Default Spring semantics remain:

```text
RuntimeException / Error
→ rollback

checked Exception
→ no rollback by default
```

The project MAY deliberately adopt a global all-exception rollback policy, but that is a project configuration decision and MUST be verified against the application's exception model.

Do not mechanically copy:

```java
@Transactional(rollbackFor = Exception.class)
```

onto every method.

### 3.10 Exception Translation and Rollback

If a transactional layer catches/translates an exception, the resulting failure must preserve the intended rollback semantics.

Do not translate a rollback-causing failure into a checked exception that accidentally permits commit unless that behavior is intentional.

### 3.11 Local Transaction Is Not Distributed Atomicity

An ordinary local DB transaction does not automatically include:

- Redis;
- HTTP calls;
- message publication;
- filesystem writes;
- email;
- external platform APIs.

Code MUST NOT assume:

```text
DB rollback
→ all other effects rollback
```

Required cross-system effects need the appropriate Backend/Messaging pattern such as outbox, recovery, idempotency, or compensation.

### 3.12 afterCommit

After-commit callbacks can order an action after successful DB commit.

They do not make the following external action durable:

```text
DB commit
→ process crashes
→ after-commit effect never completes
```

Do not use after-commit callbacks as a replacement for durable recovery when the effect is required.

### 3.13 Thread Boundary

Imperative Spring transactions are normally thread-bound.

A task dispatched to another executor thread does not inherit the caller's ordinary DB transaction.

Async work that needs a transaction SHOULD establish its own transaction boundary.

### 3.14 Programmatic Transactions

`TransactionTemplate` MAY be used when transaction boundaries must be explicit/dynamic, for example:

```text
non-transactional preparation
→ short TransactionTemplate block
→ non-transactional remote work
```

Declarative transactions remain the default for ordinary service operations.

### 3.15 Multiple Transaction Managers

If several transaction managers exist, the owner of each transaction boundary MUST be unambiguous.

Several local transaction managers do not automatically form one atomic transaction.

XA/JTA is an explicit architectural choice, not a side effect of using Spring.

### 3.16 Verification

To prove transaction semantics, exercise:

- the real Spring proxy;
- the relevant transaction manager;
- the representative transactional resource.

Verify durable outcomes, not merely presence of `@Transactional`.

---

# 4. Web MVC Boundary

## 4.1 Controller Responsibility

Controllers are HTTP adapters:

```text
HTTP request
→ binding / conversion / boundary validation
→ application input
→ application operation
→ application result
→ contract response
```

Controllers do not own:

- business rules;
- transactions by default;
- database concurrency;
- cache consistency;
- durable side effects;
- domain state machines.

### 4.2 Contract Authority

Paths, methods, headers, request/response shape, status codes, and external field names MUST conform to the accepted Contract.

Spring annotations implement the contract but do not define it.

### 4.3 Thin by Responsibility

A controller MAY legitimately perform:

- HTTP parameter extraction;
- request DTO mapping;
- authentication-context adaptation;
- protocol-specific response/status/header construction.

It SHOULD NOT contain the core business workflow.

### 4.4 Request/Response Models

Request DTOs are contract-facing adapter types.

Response DTOs are contract-facing adapter types.

Persistence entities and domain objects SHOULD NOT be bound/serialized directly by default.

Reuse a type only when semantics, nullness, validation, lifecycle, and compatibility obligations genuinely coincide.

Avoid artificial DTO duplication for identical semantics.

### 4.5 Binding

Use explicit Spring MVC mechanisms matching the Contract:

- `@PathVariable`;
- `@RequestParam`;
- `@RequestHeader`;
- `@RequestBody`;
- `@RequestPart`.

Important contract-facing inputs SHOULD NOT rely on implicit simple-parameter binding.

A client-supplied resource ID is data, not authorization.

Current authenticated user identity MUST come from server-authenticated context when the operation acts as that user.

### 4.6 Input Trust

Successful binding/deserialization means only that Spring could represent the client input.

It does not establish:

- resource existence;
- authorization;
- business eligibility;
- concurrency safety.

### 4.7 Writable Surface

A request type SHOULD expose only fields the Contract permits the client to control.

Do not bind a persistence/domain object containing protected properties simply because Spring can populate it.

### 4.8 Mapping

Simple request/response mapping MAY live in the controller.

Repeated/complex mapping MAY use dedicated mappers.

A mapper MUST NOT become a hidden business-policy engine.

### 4.9 ResponseEntity

`ResponseEntity<T>` is an HTTP boundary type.

Services SHOULD NOT normally return it.

Direct body return is sufficient when default success status/headers match the Contract.

Use `ResponseEntity` when the endpoint deliberately controls:

- status;
- headers;
- body presence.

### 4.10 Serialization Is Contract Surface

Changes to:

- DTO fields;
- Jackson visibility;
- JSON property names;
- enum serialization;
- date/time serialization;
- null inclusion

can change the external protocol and require Contract impact review.

Java field/enum names are not automatically wire-level authority.

### 4.11 HTTP Types Stay Near the Boundary

Types such as:

- `HttpServletRequest`;
- `HttpServletResponse`;
- `ResponseEntity`;
- `MultipartFile`;
- `HttpHeaders`

SHOULD remain at or near the Web layer.

### 4.12 Alternative Entry Paths

Required business guarantees MUST NOT exist only in the controller.

The same application operation invoked from an admin endpoint, worker, scheduler, CLI, or another adapter must preserve its durable guarantees.

### 4.13 Boot MVC Configuration

Do not add `@EnableWebMvc` casually in a Spring Boot application.

Prefer Boot MVC auto-configuration plus targeted `WebMvcConfigurer` customization unless full MVC takeover is a deliberate decision.

### 4.14 Hidden Work

Converters, argument resolvers, serializer getters, or lazy persistence objects SHOULD NOT hide significant DB access, authorization, remote calls, or business mutation.

Prepare response state before serialization.

---

# 5. Validation, Conversion & Binding

## 5.1 Four Separate Questions

Keep these distinct:

```text
Can Spring represent the input?
→ binding/deserialization

Can textual input become the target Java type?
→ conversion

Does the value satisfy structural constraints?
→ validation

May the business operation occur now?
→ application/domain decision
```

### 5.2 Boundary Validation

Jakarta Bean Validation MAY express stable structural/value constraints such as:

- requiredness;
- length;
- range;
- syntax;
- collection size;
- nested validity.

It MUST NOT be the sole enforcement mechanism for mutable business state such as:

- capacity;
- ownership;
- resource lifecycle;
- legal transition;
- authorization.

### 5.3 @Valid and MVC Method Validation

`@Valid` on a request object triggers nested/object validation.

Direct constraints on Controller method parameters/return values trigger MVC method validation.

Controller error translation MUST account for both:

- `MethodArgumentNotValidException`;
- `HandlerMethodValidationException`.

### 5.4 Controller @Validated

Controllers SHOULD NOT normally use class-level `@Validated`.

Spring MVC 6.1+ has built-in handler method validation; class-level `@Validated` switches the controller toward AOP method validation.

Service-level method validation using `@Validated` follows §2 proxy semantics.

### 5.5 Validation Groups

Validation groups MAY be used for genuinely related validation views.

When create/update/admin operations have substantially different writable fields or semantics, prefer distinct request types instead of one DTO controlled by many groups.

### 5.6 Conversion

Spring converters/formatters SHOULD be:

- deterministic;
- side-effect free where practical;
- thread-safe;
- focused on representation conversion.

Good:

```text
String → UserId
String → SortDirection
String → YearMonth
```

Bad:

```text
String → database-loaded User
```

Resource lookup belongs in the application layer.

### 5.7 @RequestBody vs WebDataBinder

JSON `@RequestBody` deserialization is handled through `HttpMessageConverter`/serializer infrastructure.

`WebDataBinder` property-binding restrictions such as `allowedFields` do not automatically control Jackson JSON properties.

For JSON APIs, dedicated request DTOs remain the primary writable-surface control.

### 5.8 @ModelAttribute Binding

`@ModelAttribute` property binding is an untrusted-input surface.

Prefer dedicated Web models and constructor binding.

If setter/property binding targets an object with extra properties, use an allowlist (`allowedFields`) rather than relying on a denylist.

`declarativeBinding` MAY be enabled where it matches the project's `@ModelAttribute` usage.

### 5.9 BindingResult

`BindingResult`/`Errors` MUST immediately follow the validated argument when a controller intentionally handles validation errors itself.

Do not add `BindingResult` mechanically.

For ordinary REST APIs, prefer:

```text
validation failure
→ Spring exception
→ centralized Web exception translation
```

### 5.10 Missing vs Null vs Empty

The boundary MUST preserve Contract-defined distinctions between:

- omitted;
- explicit null;
- empty string;
- empty collection;
- malformed value.

If deserialization collapses two states into the same Java value, later validation cannot reconstruct the original distinction.

PATCH-like protocols requiring omitted/present-null/value semantics MUST model that distinction explicitly.

### 5.11 Stateful Validators

Custom Bean Validation constraints SHOULD represent reusable structural/value validity.

They SHOULD NOT normally query current business state or remote systems.

A validator such as `@ActivityHasCapacity` is not an acceptable final capacity-invariant mechanism because the result can become stale before the write.

### 5.12 Defaults

`@RequestParam(defaultValue=...)` and similar defaults change protocol/application semantics.

Use defaults only when they are intentionally defined.

Do not silently normalize malformed values into valid defaults.

---

# 6. Exception Translation & HTTP Boundary

## 6.1 Separation of Concerns

Use this model:

```text
Java/application failure
→ internal semantic category
→ Spring Web translation
→ accepted HTTP error response
```

Internal exception type does not automatically define public HTTP status.

### 6.2 Centralized Translation

Shared REST error behavior SHOULD normally use one identifiable `@RestControllerAdvice` (or an equivalent `ResponseEntityExceptionHandler`-based boundary).

Controllers SHOULD NOT repeatedly catch exceptions only to convert them into HTTP responses.

### 6.3 Contract-Owned Errors

The accepted Contract owns:

- HTTP status;
- public error code;
- response body;
- headers;
- field-error structure;
- retry metadata if exposed.

Do not derive public codes mechanically from exception class names or messages.

### 6.4 Expected vs Unexpected Failure

Expected application rejection is not automatically HTTP 500.

Programming defects are not automatically HTTP 400.

Avoid blanket mappings such as:

```text
IllegalArgumentException → 400
```

unless the project deliberately reserves that exception category for one boundary meaning.

### 6.5 Spring MVC Boundary Failures

Translate Spring Web errors intentionally, including representative categories such as:

- malformed request body;
- missing required input;
- type conversion failure;
- validation failure;
- unsupported method;
- unsupported media type;
- unacceptable response media type.

Normalize internal Spring structures into the accepted public error model.

### 6.6 Validation Errors

Both `MethodArgumentNotValidException` and `HandlerMethodValidationException` SHOULD map into one consistent public validation representation where the Contract defines the same semantics.

Do not serialize `BindingResult`, `FieldError`, or other Spring internal objects directly.

### 6.7 Infrastructure Exceptions

Low-level failures such as:

- `SQLException`;
- `DataAccessException`;
- Redis client exceptions;
- HTTP client exceptions;
- serializer exceptions

SHOULD normally be translated/classified before becoming public API categories.

### 6.8 ProblemDetail

`ProblemDetail`, `ErrorResponse`, and `ResponseEntityExceptionHandler` are Spring implementation options.

Use them when they fit the accepted error Contract.

Do not redesign the Contract merely because Spring provides RFC 9457 support.

### 6.9 HTTP-Coupled Exceptions

Application/domain services SHOULD NOT normally throw:

- `ResponseStatusException`;
- domain exceptions annotated with `@ResponseStatus`.

Those mechanisms couple internal application logic to HTTP.

Keep them Web-specific when used.

### 6.10 Generic Fallback

The API SHOULD have a final sanitized mapping for unexpected ordinary `Exception`.

Client responses MUST NOT expose raw:

- stack traces;
- SQL details;
- class names;
- internal paths;
- secrets;
- arbitrary `exception.getMessage()`.

Server-side diagnostics should retain enough evidence for investigation.

### 6.11 Security Boundary

Authentication/authorization failures may occur before MVC controller invocation.

Do not assume `@RestControllerAdvice` catches the entire Servlet filter chain.

Security-specific error adapters MUST still produce the accepted Contract semantics.

### 6.12 Retry and Recovery

An exception handler MUST NOT generally re-execute failed business operations.

Retry ownership and safety are defined in Backend §4.3–4.10; Web exception translation does not own operation recovery.

### 6.13 Unknown Outcome

A timeout MUST NOT be translated into “definitely failed” when the business effect may already have occurred.

Web translation must preserve higher-level failure semantics.

### 6.14 Logging

HTTP exception translation and logging are separate responsibilities. Apply the HTTP-boundary integration in §11.17 and the primary ownership policy in Backend §8.2; translating an exception does not require another stack trace.

---

# 7. Configuration, Profiles & Conditional Beans

## 7.1 Configuration Model

Preferred flow:

```text
external configuration source
→ Spring Environment / Config Data
→ typed binding
→ startup validation
→ stable process configuration
```

Configuration is input, not mutable business state.

### 7.2 @ConfigurationProperties

Related project settings SHOULD normally use typed `@ConfigurationProperties`.

Prefer immutable configuration models, including Java 17 records where appropriate:

```java
@ConfigurationProperties("app.ai")
@Validated
public record AiProperties(
        @NotNull URI endpoint,
        @NotNull Duration timeout,
        @Min(0) int maxRetries) {
}
```

Configuration groups SHOULD represent coherent capabilities.

### 7.3 Namespace

Project-defined keys SHOULD use a project-owned namespace such as:

```text
app.notification.*
app.ai.*
app.security.*
```

Do not casually occupy Boot-owned namespaces such as `spring.*`, `server.*`, or `management.*`.

Canonical property names SHOULD use kebab-case.

### 7.4 Registration and Dependencies

Constructor-bound property types SHOULD be registered through the supported configuration-properties infrastructure such as:

- `@ConfigurationPropertiesScan`;
- `@EnableConfigurationProperties`.

Configuration properties SHOULD deal with configuration/environment values and SHOULD NOT depend on repositories, services, remote clients, or other application behavior.

Binding MUST remain side-effect free.

### 7.5 Validation and Fail-Fast

Required configuration SHOULD be validated during startup.

Missing, malformed, impossible, or inconsistent required configuration SHOULD fail startup.

Do not hide required configuration behind arbitrary defaults merely to make startup green.

### 7.6 Semantic Types

Prefer semantic configuration types:

- `Duration`;
- `URI`;
- `DataSize`;
- project value types where appropriate.

Units MUST be unambiguous.

### 7.7 @Value

`@Value` MAY be used for small isolated settings.

It SHOULD NOT become the default mechanism for coherent groups of related properties.

Avoid unnecessary SpEL for ordinary configuration.

### 7.8 Environment

Direct `Environment` lookup is infrastructure access.

Ordinary application services SHOULD prefer typed configuration.

Do not turn `Environment` into a global map accessed throughout the codebase.

### 7.9 Configuration Keys Are Operational Interface

Renaming a durable configuration key may affect:

- Docker/Compose;
- CI/CD;
- environment variables;
- operations docs;
- secret stores;
- deployment rollout.

Treat meaningful key changes as compatibility changes.

### 7.10 Secrets

Secrets are a special security class of configuration input.

Real production credentials, tokens, private keys, or signing secrets MUST NOT be committed to repository configuration files.

Production secret delivery is owned by Security/Operations.

### 7.11 Config Data

Use Boot Config Data as the ordinary application configuration mechanism.

Understand that:

- `spring.config.location` replaces default locations;
- `spring.config.additional-location` adds locations;
- `optional:` suppresses missing-location failure.

A production-required config/secret source SHOULD NOT be marked optional merely to let startup continue.

### 7.12 Profiles

Profiles MAY select coarse environment/configuration topology.

Use profiles when the bean graph genuinely differs.

If only values change, prefer environment-specific configuration values.

Profiles MUST NOT become:

- business state;
- dynamic feature flags;
- per-user modes;
- arbitrary boolean combinations.

Avoid profile explosion.

### 7.13 Conditional Beans

Conditions such as `@ConditionalOnProperty` are startup wiring decisions.

Use them for optional infrastructure/capability wiring, not ordinary per-request business decisions.

Where enable/disable semantics matter, make `havingValue` and `matchIfMissing` explicit.

### 7.14 Unknown/Invalid Fields

Keep invalid-field binding fail-fast.

For project-owned closed property namespaces, stricter unknown-field handling MAY be used to catch configuration typos when one binder clearly owns the namespace.

Do not enable strict unknown-field behavior mechanically where several binders intentionally share a prefix.

### 7.15 Runtime Changes

Do not mutate a bound `@ConfigurationProperties` object as a makeshift global runtime-control mechanism.

Runtime-reloadable configuration needs an explicit source, consistency, refresh, visibility, and failure model.

---

# 8. Bean & Application Lifecycle

## 8.1 Lifecycle Ownership

For every long-lived Spring-managed component, know:

```text
who creates it
when it initializes
when it may start work
what resources it owns
how it stops
who closes it
what happens if shutdown is incomplete
```

Spring ownership refines Java resource ownership; it does not erase it.

### 8.2 Construction

Constructors SHOULD establish local object validity and dependencies.

They SHOULD NOT normally:

- start background threads;
- run large DB jobs;
- perform remote synchronization;
- publish messages;
- start recurring work.

### 8.3 @PostConstruct

Use `@PostConstruct` for small bounded local initialization, for example:

- validate already-bound configuration;
- build local immutable structures;
- prepare cheap local state.

It SHOULD NOT be a general startup task runner or long-lived worker entry point.

### 8.4 @PreDestroy

Use `@PreDestroy` for owned cleanup.

A consumer MUST NOT close a shared Spring-managed dependency merely because the dependency is `AutoCloseable`.

Destruction callbacks are graceful-lifecycle mechanisms, not crash-recovery guarantees.

### 8.5 Spring Interfaces

`InitializingBean`/`DisposableBean` MAY be used in Spring-specific infrastructure but SHOULD NOT be the default for ordinary application beans when `@PostConstruct`, `@PreDestroy`, or explicit POJO methods are sufficient.

Avoid combining several lifecycle callback mechanisms for one responsibility without need.

### 8.6 @Bean Lifecycle

Third-party/infrastructure beans MAY declare explicit `initMethod`/`destroyMethod`.

Spring may infer public no-arg `close`/`shutdown` destruction methods for Java-configured beans.

When a bean exposes an externally owned/borrowed resource, explicitly prevent accidental destruction when necessary.

### 8.7 Prototype Lifecycle

Prototype beans do not receive complete container-managed destruction after being handed to the caller.

Resource-owning prototypes require an explicit cleanup owner.

### 8.8 Bean Initialization vs Application Readiness

Bean initialization is not application readiness.

Later lifecycle boundaries include:

- completion of singleton creation;
- context refresh;
- `SmartLifecycle`;
- Boot startup events;
- `ApplicationRunner`/`CommandLineRunner`;
- readiness state.

### 8.9 Runner

`ApplicationRunner` / `CommandLineRunner` MAY be used for bounded one-time startup work.

Runners MUST complete.

They MUST NOT contain infinite worker loops.

If required startup work fails, startup SHOULD fail rather than advertise a capability that is not actually available.

### 8.10 Long-Lived Workers

A continuously running component such as:

- queue consumer;
- polling loop;
- watcher;
- background worker

SHOULD have explicit start/running/stop/completion semantics.

`SmartLifecycle` SHOULD be considered when the component should auto-start, participate in ordered shutdown, and signal actual stop completion.

### 8.11 Phase and Shutdown

Lifecycle phases SHOULD express real dependency ordering.

Typical relationship:

```text
startup:
dependency → worker

shutdown:
worker → dependency
```

`SmartLifecycle.stop(Runnable)` MUST invoke the callback only when shutdown is actually complete.

A lifecycle component SHOULD tolerate destruction without assuming `stop()` always completed first; abnormal bootstrap/shutdown paths may bypass a clean stop sequence.

### 8.12 Bounded Shutdown

Shutdown MUST be bounded and compatible with:

- Spring lifecycle timeout;
- container/process termination grace period;
- in-flight work semantics;
- recovery model.

Do not increase shutdown time indefinitely instead of designing recovery.

### 8.13 Graceful Web Shutdown

Spring Boot 3.5 graceful Web shutdown is enabled by default.

That protects Web request handling only.

Custom workers, executors, schedulers, or consumers need their own managed lifecycle.

### 8.14 Crash Recovery

`@PreDestroy`, shutdown hooks, and graceful worker stop MUST NOT be the sole mechanisms protecting durable correctness.

If a claimed task can be lost by process failure, recovery must come from durable state/protocol such as lease, pending state, retry, or reconstruction.

### 8.15 Multi-Instance Startup

Lifecycle callbacks and Runners execute per application instance.

They MUST NOT be treated as cluster-wide exactly-once jobs without explicit distributed coordination/idempotency.

### 8.16 Schema Migration

Database schema migration MUST use the governed migration mechanism.

Do not reimplement migration as arbitrary `@PostConstruct` or Runner SQL.

---

# 9. Async Execution & Scheduling

## 9.1 In-Process Execution

`@Async` / `TaskExecutor` provide in-process execution.

They do not provide:

- durable work acceptance;
- persistent queues;
- retry state;
- crash recovery;
- deduplication.

A successfully submitted async task may still be lost if the process dies before completion.

### 9.2 Proxy Semantics

`@Async` follows §2 proxy rules.

Self-invocation does not create an async boundary.

An async method SHOULD represent work that can correctly execute independently from the caller thread.

### 9.3 Failure Ownership

Every async operation needs an explicit failure owner.

Prefer `Future`/`CompletableFuture` when the caller must observe completion.

`void @Async` SHOULD be used only when no caller result is needed and failure is owned through another explicit mechanism such as `AsyncUncaughtExceptionHandler`/observability/recovery.

### 9.4 Thread Boundary

Async execution crosses a thread boundary.

Do not assume automatic propagation of:

- transactions;
- arbitrary `ThreadLocal`;
- MDC;
- security/request context;
- transaction-bound resources.

Capture only context whose lifetime intentionally crosses the boundary.

### 9.5 Context Propagation

Apply §9.4's lifetime boundary when selecting context. Observability capture, task decoration and cleanup are defined once in §11.15; do not assume context propagation also carries a transaction or authorization.

### 9.6 Async + Transaction

The caller's ordinary thread-bound DB transaction does not move to the executor thread.

When async work needs a transaction, prefer an explicit async boundary invoking an explicit transactional collaborator.

Do not pass caller-owned transaction resources into the worker.

### 9.7 Executor Ownership

Before adding or replacing an executor, determine:

- whether Boot task-execution auto-configuration backs off;
- which executor `@Async` uses;
- whether MVC/framework integrations still receive the expected `applicationTaskExecutor`;
- whether `AsyncConfigurer` or an explicit `@Async` qualifier owns selection.

A generic custom `Executor` bean can change Boot-wide execution wiring and MUST NOT be introduced casually.

### 9.8 Capacity

Executor capacity MUST be deliberate.

Consider:

- CPU;
- blocking ratio;
- memory;
- DB connection pool;
- downstream limits;
- queue capacity;
- latency;
- rejection behavior.

An unbounded queue is an overload policy, not “no limit”.

Increasing queue capacity does not increase throughput.

### 9.9 Rejection

Bounded executors can reject work.

Rejection MUST be treated as a real state with explicit semantics such as:

- backpressure;
- operation failure;
- fallback;
- durable retry ownership.

Do not retry rejected work in a tight loop.

### 9.10 Lifecycle

Spring-managed executors SHOULD be owned/shut down through Spring lifecycle.

Consumers MUST NOT independently shut down shared executors.

Graceful waiting improves orderly shutdown but does not make in-memory tasks crash-durable.

### 9.11 @Scheduled

`@Scheduled` is an in-process trigger mechanism.

It is not:

- a durable job store;
- a business reminder database;
- a cluster-wide scheduler;
- an exactly-once execution guarantee.

### 9.12 Trigger Models

Use:

```text
fixedDelay
→ delay after previous completion

fixedRate
→ repeated target start rate

cron
→ wall-clock/calendar schedule
```

Choose according to semantics, not habit.

Business-relevant cron timezone MUST be explicit.

### 9.13 Scheduled Method

A scheduled method SHOULD primarily be a trigger entry point:

```text
scheduler trigger
→ application/job processor
```

Keep job meaning separate from “when it runs”.

Scheduled return values SHOULD NOT be treated as ordinary caller results.

### 9.14 Durable Business Deadlines

If a future obligation is a business fact, store it durably.

Prefer:

```text
durable reminder / dueAt state
→ scheduler discovers due work
→ claim/dispatch/process
```

over making a JVM timer the only source of truth.

### 9.15 Multi-Instance Scheduling

Each application instance can execute the same `@Scheduled` method.

Therefore:

```text
@Scheduled
≠ one execution across cluster
```

Cluster-wide single ownership requires an explicit mechanism such as:

- DB claim/lease;
- suitable distributed lock;
- durable job owner;
- external scheduler;
- queue dispatch.

Mutual exclusion alone does not define crash recovery.

### 9.16 Re-execution

Scheduled work that can run more than once SHOULD be idempotent or protected by appropriate state transitions.

Possible duplicate causes include:

- restart;
- manual rerun;
- several instances;
- overlapping triggers;
- recovery.

### 9.17 Scheduler Capacity

Do not rely on the current default scheduler pool size as a correctness mechanism.

Increasing scheduler concurrency requires analysis of:

- task overlap;
- thread safety;
- DB/downstream capacity;
- duplicate-execution semantics.

### 9.18 Java 17

Virtual-thread execution features requiring Java 21+ MUST NOT be enabled while the repository baseline remains Java 17.

---

# 10. Auto-Configuration & Dependency Management

## 10.1 Boot-First Customization

Use this order:

```text
Boot default
→ Boot property
→ supported customizer
→ targeted user bean / back-off
→ auto-configuration exclusion
```

Use the lowest sufficient level.

### 10.2 @SpringBootApplication

The primary application class SHOULD normally use one `@SpringBootApplication`, which composes configuration, auto-configuration enablement, and component scanning.

Do not repeat `@EnableAutoConfiguration` unnecessarily.

Keep the primary application class in an appropriate root package.

### 10.3 Component Scanning

Do not broaden component scanning casually.

`scanBasePackages` controls component scanning but does not automatically redefine every other framework discovery mechanism.

Prefer correct package organization or explicit imports/configuration.

### 10.4 Auto-Configuration Model

Auto-configuration is conditional startup configuration based on factors such as:

- classpath;
- existing beans;
- properties;
- application type.

Adding a dependency can therefore change runtime configuration even when Java source does not change.

### 10.5 Starters

Prefer official Boot starters for supported capabilities when they match project needs.

A starter is primarily a curated dependency descriptor.

Do not conflate:

```text
starter
```

with:

```text
auto-configuration
```

Do not add starters speculatively.

### 10.6 Dependency Management

Spring Boot's managed dependency set is the default version authority.

For Boot-managed dependencies, omit explicit versions unless a concrete reason requires an override.

Do not independently pin Spring Framework modules without an explicit compatibility requirement.

### 10.7 Overrides

A managed-version override MUST answer:

- why the Boot-managed version is insufficient;
- Java 17 compatibility;
- Boot 3.5.13 compatibility;
- transitive dependency coherence;
- verification evidence.

“Newer version exists” is not sufficient reason.

Security/critical bug fixes MAY justify a temporary override.

### 10.8 Version Families

Closely coupled library families SHOULD remain coherent.

Prefer supported BOM/version-property override mechanisms rather than independently pinning individual modules.

### 10.9 Auto-Configuration Back-Off

When Boot exposes a supported back-off point such as `@ConditionalOnMissingBean`, providing the project-owned replacement is generally preferable to excluding the entire auto-configuration.

Replacing a bean means the project now owns that component's lifecycle/configuration/integration consequences.

### 10.10 @Primary Is Not an Override Mechanism

`@Primary` changes candidate preference.

It does not necessarily remove the Boot bean.

When exactly one infrastructure implementation should exist, use the supported back-off/customization mechanism.

### 10.11 Exclusions

Auto-configuration exclusion is a broad tool.

Use it only when:

- the auto-configuration is genuinely unwanted;
- normal property/customizer/back-off mechanisms are insufficient;
- the replacement/absence is understood.

Do not exclude configuration merely to silence a startup error.

Remove unnecessary dependencies instead of keeping them forever with exclusions where practical.

### 10.12 Custom Auto-Configuration

Ordinary application configuration SHOULD normally use `@Configuration(proxyBeanMethods = false)`.

`@AutoConfiguration` is mainly appropriate for reusable library/shared infrastructure that should activate conditionally.

Reusable auto-configuration SHOULD:

- be conditional;
- back off where reasonable;
- not force one implementation on consumers.

### 10.13 Diagnostics

When Boot behavior is unclear, inspect:

- condition evaluation report (`--debug`);
- effective bean graph;
- effective configuration;
- Actuator conditions endpoint where securely available.

Do not diagnose auto-configuration by guessing.

### 10.14 Boot Defaults

Do not copy the entire Boot property catalog into `application.yml`.

Keep only project-relevant overrides.

Explicitly configure defaults when the project depends on a specific value for correctness, security, capacity, protocol behavior, or operations.

### 10.15 Dependency Changes

Automated work MUST NOT opportunistically upgrade Spring Boot, Spring Framework, drivers, Jackson, Redis clients, security libraries, or other managed dependencies simply because newer versions exist.

Dependency changes require explicit task scope or a concrete correctness/security reason.

### 10.16 Direct Dependencies

If project source directly imports/depends on a third-party library API, declaring it directly is generally clearer than relying accidentally on another starter's transitive dependency.

Do not declare every transitive artifact directly merely for visibility.

### 10.17 Testing Configuration

Test-only configuration SHOULD use test mechanisms such as `@TestConfiguration`.

Production configuration MUST NOT depend on test classes, mocks, or test-only libraries.

---

# 11. Observability & Health Integration

## 11.1 Scope

Spring Boot integrates:

- logs;
- metrics;
- traces;
- observations;
- health;
- liveness;
- readiness;
- Actuator diagnostics.

Backend Engineering owns what properties must be observable.

Spring owns the integration mechanism.

### 11.2 Distinct Signals

Use:

```text
logs
→ detailed discrete events

metrics
→ aggregated count/rate/duration/current value

traces
→ one causal execution path

health
→ current configured operational status
```

Do not collapse them into one mechanism.

### 11.3 Actuator

Actuator is an operational interface, not the business API.

Endpoint exposure MUST be deliberate.

Avoid public wildcard exposure.

Endpoints such as:

- `env`;
- `configprops`;
- `beans`;
- `conditions`;
- `heapdump`;
- `threaddump`;
- `loggers`

may expose sensitive operational information and require explicit security review.

Sanitization is defense in depth, not permission for public exposure.

### 11.4 Health

Health is an aggregation of configured operational contributors.

`UP` does not prove business correctness.

`DOWN` does not automatically mean restart.

Health checks SHOULD be bounded, observational, and inexpensive.

They MUST NOT mutate business state.

### 11.5 Liveness

Liveness should represent an internally broken application state where restarting the instance may help.

External dependency availability SHOULD NOT normally determine liveness.

A shared DB/Redis outage should not automatically cause every application instance to restart.

### 11.6 Readiness

Readiness answers whether this instance should currently receive traffic.

External dependencies MAY influence readiness only after deliberate analysis of:

- whether they are required for nearly all useful requests;
- whether degradation/fallback exists;
- whether the dependency is shared by all instances;
- whether removing all instances from traffic would make the outage worse.

### 11.7 Health Groups

Use separate health groups when general health, readiness, liveness, or dependency-specific diagnostics require different contributor sets.

Global health and readiness need not be identical.

### 11.8 Custom HealthIndicator

A custom indicator SHOULD represent one coherent operational capability and remain cheap/bounded.

For long-lived workers, meaningful health MAY include:

- expected-running state;
- unexpected termination;
- progress freshness;

rather than merely “thread exists”.

Queue lag/backlog is often better represented as metrics than a single binary health value.

### 11.9 Metrics

Prefer existing Boot/Micrometer instrumentation before creating duplicates.

Custom metrics SHOULD use Micrometer (`MeterRegistry`) or Observation integration instead of coupling directly to one backend vendor.

### 11.10 Cardinality

Metric labels MUST remain low-cardinality.

Usually acceptable:

- operation type;
- bounded result category;
- queue/provider type;
- HTTP method/status class.

Usually unacceptable as metric tags:

- user ID;
- post/activity ID;
- request ID;
- trace ID;
- raw URL;
- exception message.

High-cardinality context belongs in traces/logs when needed.

### 11.11 Metric Semantics

Use:

- counters for monotonic event counts;
- gauges for current observable state;
- timers for duration/count.

Metrics are observability replicas, not business source of truth.

### 11.12 Observation

Use `Observation` when one meaningful operation benefits from coordinated metrics and traces.

Do not instrument every helper/getter.

Avoid duplicating existing framework observations.

### 11.13 Tracing

Tracing identifies one causal execution.

It is not a durable business audit log.

Sampling means absence of an exported trace does not prove absence of the operation.

Trace IDs MUST NOT replace logical operation IDs or idempotency keys.

One logical operation may span several request/retry/recovery traces.

### 11.14 Client Instrumentation

Prefer Boot-managed HTTP client builders where automatic observation/trace propagation matters.

Replacing Boot client construction requires observability impact review.

Tracing headers are observability context, not authentication.

### 11.15 Async Context

MDC is thread-associated context, not durable operation state; arbitrary MDC does not automatically follow executor boundaries. For Boot-managed executors use supported context propagation where applicable; for custom executors use an explicit task decorator when correlation must propagate.

Capture only selected safe context when submitting a task, install it for that task, then restore the prior context in finally (or clear when none existed). Request filters/interceptors likewise own cleanup on pooled request threads. Do not clear another scope's context or leak previous users' context between tasks. Callback/consumer boundaries derive correlation from their validated input; they cannot inherit the original HTTP thread implicitly.

Verify required propagation and cleanup across success, failure and reused-thread paths when changing this wiring. Context propagation does not transfer authentication authority or a transaction.

### 11.16 Sensitive Data

Apply Backend §8.2's safe-field policy to logs, traces, health details and baggage. Configure framework request logging, access logging and exception rendering so they do not bypass it. Review runtime logging configuration as well as application calls.

### 11.17 Logging

Use the project's SLF4J facade and Boot-managed logging configuration (normally Logback with the default logging starter). Avoid introducing a second backend or custom logger infrastructure without a concrete need. Parameterized calls and exception propagation follow Java §7.7; event selection, severity, correlation requirements and audit distinction follow Backend §8.2.
The repository-specific field names, ID semantics, metric/cardinality and health groups are defined by [Observability Baseline](../docs/observability.md); this section owns only their Spring integration mechanisms.

GlobalExceptionHandler / ControllerAdvice records unexpected HTTP failures that reach its boundary. Failures outside MVC need their corresponding adapters (§6.11 for security/filter boundaries, §8 for lifecycle, §9.3 for async completion). Use Future completion or the void @Async failure handler according to §9.3; a TaskDecorator that installs context is not by itself proof that task exceptions are observed. Do not duplicate a service stack trace when adapting the same failure.

Runtime levels, appenders, formats and retention belong in Boot logging properties or the applicable logging configuration, not scattered per-call special cases. MDC rendering should use only context actually supplied by the relevant boundary. Required durable audit state must use its own persistence design; logback configuration cannot provide that guarantee.

### 11.18 Telemetry Failure

Optional telemetry export SHOULD normally degrade observability rather than fail valid business operations.

Do not make the tracing/metrics backend a synchronous dependency of every request without a concrete requirement.

### 11.19 Custom Actuator Endpoints

Custom Actuator endpoints MAY expose genuine operational capabilities.

They MUST NOT become hidden substitutes for accepted public/admin APIs.

State-changing management operations require explicit authorization, exposure, audit, and failure semantics.

---

# 12. Spring Testing Integration

## 12.1 Testing Principle

Choose the test from the property being proved:

```text
property
→ risk
→ smallest boundary capable of exhibiting that risk
→ evidence
```

Verification scope, escalation and failure classification are owned by [Backend §9.12–9.13](backend-engineering.md#912-verification-scope-and-escalation); this section selects Spring mechanisms. Do not begin with “which Spring annotation should I use?”

### 12.2 Plain Java Tests

If Spring runtime behavior is irrelevant, prefer plain JUnit/Mockito/fakes.

Constructor injection SHOULD make ordinary application logic directly testable without an `ApplicationContext`.

### 12.3 When Spring Is Required

Load Spring when the property depends on:

- bean wiring;
- proxy advice;
- MVC infrastructure;
- configuration binding;
- transaction integration;
- auto-configuration;
- lifecycle;
- framework-specific behavior.

A Spring context does not automatically make a test stronger.

### 12.4 @SpringBootTest

`@SpringBootTest` is a broad integration test and SHOULD NOT be the default annotation.

Use it when broad application wiring/configuration is part of the property.

Keep an appropriate small set of full-context startup/integration tests.

Context startup alone does not prove correctness.

### 12.5 Web Environments

Understand the difference:

```text
MOCK
→ no real HTTP server

RANDOM_PORT
→ real embedded server on random port

DEFINED_PORT
→ real embedded server on configured port

NONE
→ Boot context without Web infrastructure
```

Prefer `RANDOM_PORT` over fixed ports when a real server is required.

### 12.6 MockMvc

`MockMvc` verifies Spring MVC behavior without a real network server.

Use it for:

- routing;
- request binding;
- validation integration;
- filters;
- controller advice;
- serialization;
- HTTP status/header/body behavior.

Direct controller invocation does not prove those properties.

MockMvc does not prove real socket/server/reverse-proxy behavior.

### 12.7 @WebMvcTest

`@WebMvcTest` SHOULD be the focused default for Controller/Web-boundary tests where full application infrastructure is unnecessary.

Keep slices as slices; do not import most of the application until the test is effectively a disguised `@SpringBootTest`.

### 12.8 Bean Test Overrides

For the current Boot 3.5 / Framework 6.2 baseline, new tests SHOULD prefer:

- `@MockitoBean`;
- `@MockitoSpyBean`;
- `@TestBean`;

instead of deprecated Boot `@MockBean` / `@SpyBean`.

Do not load Spring merely to use Mockito.

A replaced bean does not prove proxy behavior that belonged to the original production bean.

### 12.9 Test Configuration

Use `@TestConfiguration` for test-only Spring configuration.

Do not let test configuration leak into normal production scanning.

A nested ordinary `@Configuration` can change the test's primary configuration semantics; choose deliberately.

### 12.10 Data Tests

Use a data slice matching the actual persistence technology when useful.

Do not use a JPA-specific slice for a project that does not use JPA merely because it is familiar.

### 12.11 Production-Family Database

When the property depends on MySQL-specific semantics, use MySQL-compatible integration infrastructure.

Examples:

- `SELECT ... FOR UPDATE`;
- locking/isolation;
- constraints;
- MySQL SQL syntax/functions;
- window functions;
- migration behavior;
- index/planner behavior.

Do not let H2 prove MySQL-specific correctness.

### 12.12 Testcontainers

Testcontainers MAY provide production-relevant MySQL/Redis/broker behavior.

Use `@ServiceConnection` where Boot's supported service-connection abstraction cleanly supplies connection details.

Do not launch real containers for plain value/policy/unit behavior that gains no evidence from them.

### 12.13 Isolation

Integration tests MUST NOT accidentally connect to production infrastructure.

Use isolated test resources and test-specific credentials.

Test data MUST be deterministic and independent of execution order.

### 12.14 Test-Managed Transactions

A test method annotated with Spring TestContext `@Transactional` usually executes in a test-managed transaction and rolls back by default.

This is a test feature, not production transaction semantics.

The test framework does not fully interpret all production `@Transactional` attributes.

For the test method annotation:

- transaction manager selection is supported;
- `NOT_SUPPORTED` / `NEVER` have special meaning;
- attributes such as isolation, timeout, readOnly, rollbackFor are not equivalent to production transaction configuration.

Application service methods called inside the test still use normal production Spring transaction semantics and may participate in the surrounding test transaction.

### 12.15 Real Server Transaction Trap

With:

```java
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Transactional
```

or `DEFINED_PORT`:

```text
test thread transaction
≠ server request thread transaction
```

Server-side commits are not automatically rolled back by the test thread.

Real HTTP tests need explicit data isolation/cleanup.

### 12.16 Commit-Sensitive Tests

A permanently rolled-back enclosing test transaction may be the wrong tool for properties involving:

- actual commit;
- after-commit callbacks;
- another transaction observing committed state;
- commit-time constraints;
- durable outbox state.

Use `@Commit`, `@Rollback`, `TestTransaction`, or another explicit transaction boundary where appropriate.

### 12.17 Preemptive Timeout

Test-managed transactions are thread-bound.

Preemptive timeout mechanisms that execute the test body on another thread can escape the test transaction and commit unexpectedly.

Avoid such combinations when rollback is assumed.

### 12.18 Persistence Reality

When the persistence mechanism buffers changes, flush/clear/read-back MAY be necessary to prove actual database behavior.

Do not assert only against the same in-memory entity/object that was mutated.

### 12.19 Context Caching

Spring TestContext reuses compatible `ApplicationContext` instances.

Preserve context reuse where practical.

Excessive variations in:

- profiles;
- test properties;
- dynamic properties;
- bean overrides;
- configuration classes

can create many unique context cache entries and slow the suite.

### 12.20 @DirtiesContext

`@DirtiesContext` evicts the cached context and SHOULD NOT be routine test cleanup.

Use it only when a test genuinely corrupts/modifies shared context state that cannot safely be reset otherwise.

If tests constantly require it because a singleton holds mutable test state, inspect the production design.

### 12.21 Shared State and Parallelism

Tests SHOULD be independently executable.

Parallel integration tests require explicit analysis of:

- database tables;
- Redis keys;
- files;
- ports;
- static state;
- external containers.

Use namespacing/isolation when helpful.

### 12.22 Do Not Mock the Mechanism Under Test

Examples:

```text
testing DB locking
→ do not mock repository

testing @Transactional
→ do not instantiate the service with new

testing MVC serialization
→ do not call controller directly

testing Redis command/Lua semantics
→ do not mock Redis client
```

Mocks can replace collaborators outside the property boundary.

### 12.23 Do Not Start Everything Without Need

The opposite extreme is also wrong.

Plain policy/value/mapper logic does not need MySQL, Redis, or the entire Spring context.

Use only the infrastructure that contributes evidence.

### 12.24 Async/Scheduled Tests

Do not use arbitrary `Thread.sleep(...)` as the primary synchronization mechanism.

Use bounded deterministic mechanisms such as:

- Future completion;
- latch;
- eventual assertion with deadline;
- durable observable state.

Separate scheduled trigger registration from job processing so job logic can be tested without waiting for wall-clock intervals.

### 12.25 Time-Dependent Logic

Inject/use a `Clock` or equivalent time abstraction when deterministic testing of `now`, expiry, deadline, or due-state logic matters.

### 12.26 Configuration Tests

When the project introduces:

- `@ConfigurationProperties`;
- conditional beans;
- profile-specific wiring;
- executor replacement;
- auto-configuration customization,

test the effective configuration behavior rather than merely asserting annotations are present.

Invalid required configuration SHOULD have representative startup-failure tests where risk justifies them.

### 12.27 Contract Tests

HTTP Contract verification SHOULD assert externally observable properties:

- path/method;
- status;
- headers;
- JSON names;
- null/omission semantics;
- error shape;
- date/time representation.

Java DTO equality alone is not Contract evidence.

### 12.28 End-to-End Tests

A small number of full-path tests MAY prove critical integrations such as:

```text
HTTP
→ Controller
→ Service
→ transaction
→ real DB
→ response
```

They complement focused tests; they do not replace them.

### 12.29 Framework Upgrade Verification

After a Spring Boot/Framework upgrade, prioritize evidence around project-used framework boundaries:

- context startup;
- MVC binding/error translation;
- transaction interception;
- configuration properties;
- security integration;
- serialization;
- data integration;
- async/scheduling;
- Actuator/health.

Compilation alone is insufficient upgrade evidence.

---

# Appendix A — Framework Boundary Map

This appendix is non-normative.

```text
Accepted Contract
      ↓
Spring MVC Controller
      ↓
Application / Domain
      ↓
Persistence / Cache / Messaging

Spring Boot provides:
- bean creation and dependency injection
- proxy/interceptor infrastructure
- transaction integration
- MVC binding/serialization
- configuration
- lifecycle
- task execution/scheduling
- auto-configuration
- observability
- testing support

Spring Boot does NOT own:
- protocol design
- business invariants
- DB locking correctness
- distributed atomicity
- durable messaging semantics
- authorization policy
```

# Appendix B — High-Risk Review Questions

Use these questions during review when Spring-specific behavior changes.

## Beans / DI

```text
Is this actually a Spring-managed responsibility?
Are required dependencies constructor-injected?
Did a new bean create ambiguity or change Boot back-off?
Is a circular dependency being hidden instead of redesigned?
```

## Proxy

```text
Which proxy/infrastructure activates this behavior?
Does the call actually cross the proxy?
Is self-invocation possible?
Is the class/method proxyable?
Does advice order affect correctness?
```

## Transactions

```text
What is the physical transaction boundary?
Which transaction manager owns it?
Are DB locks held for the intended duration?
Are remote/Redis/message effects incorrectly assumed atomic?
Could exception translation change rollback behavior?
```

## MVC / Validation

```text
Does the mapping implement the accepted Contract?
Does client input expose only writable fields?
Is binding being mistaken for authorization/business validity?
Are both MVC validation exception paths handled?
```

## Configuration

```text
Is this config, secret, or mutable state?
Should this be typed @ConfigurationProperties?
Will invalid required config fail startup?
Is a profile/condition being used as a business feature flag?
```

## Lifecycle / Async

```text
Who owns start/stop?
Who owns the executor/resource?
What happens on process crash?
Is in-memory async being mistaken for durable work?
Will this scheduled task run on every instance?
```

## Boot Auto-Configuration

```text
Can Boot property/customizer solve this before bean replacement?
What auto-configuration backs off?
Does a custom bean change framework-wide wiring?
Why is a managed dependency version being overridden?
```

## Observability

```text
Is this log, metric, trace, or health?
Is a metric tag bounded?
Does dependency failure really affect liveness/readiness?
Could observability expose sensitive information?
```

## Testing

```text
What property is the test proving?
Is Spring actually required?
Is the real mechanism under test being mocked?
Does RANDOM_PORT create an independent server transaction?
Is the test relying on context rebuild instead of state isolation?
```

---

# Appendix C — Baseline Constraints

The following constraints are repository-wide until explicitly upgraded:

```text
Java 17
Spring Boot 3.5.13
No Java 21 language/API assumptions
No virtual-thread Spring execution policy
No opportunistic Spring/managed dependency upgrades
No protocol redesign inside Spring implementation
```
