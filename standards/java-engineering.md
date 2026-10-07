# Java Engineering Standard

Status: Repository Java standard  
Baseline: Java 17  
Framework baseline relevant to compatibility: Spring Boot 3.5.13

This document defines Java-language engineering rules for backend implementation code. It specializes higher-level backend engineering guarantees without redefining business rules, external protocols, framework behavior, persistence semantics, or operational procedures.

Normative terms: **MUST**, **MUST NOT**, **SHOULD**, **SHOULD NOT**, and **MAY** are used in their ordinary standards sense.

---

# 0. Scope, Baseline & Authority

## 0.1 Scope

This standard owns Java-specific concerns including:

- source structure, naming, formatting, and Java documentation;
- type modeling, value semantics, equality, and generics;
- nullness and absence representation;
- mutability, immutability, collections, and ownership;
- Java visibility, construction, inheritance, and API surface;
- control flow, lambdas, and streams;
- exceptions and Java failure propagation;
- Java resource ownership and lifecycle;
- in-process concurrency and thread safety;
- Java-language, JDK API, reflection, annotation, serialization, and compatibility policy.

It does **not** own:

- HTTP/API or OpenAPI design;
- message/event protocol design;
- concrete business invariants and state semantics;
- Spring component/proxy/transaction behavior;
- SQL/schema/index design;
- Redis keys or cache policy;
- deployment/runtime procedures;
- Git or agent workflow.

Use authority by subject. One rule has one authoritative owner.

## 0.2 Java Baseline

The repository Java baseline is **Java 17**.

Code MUST compile and run against the declared Java 17 baseline unless a deliberate repository-wide upgrade changes that baseline.

A newer local JDK, CI JDK, IDE, formatter, or compiler MUST NOT by itself authorize use of later Java language features or APIs.

The build SHOULD use a platform-aware Java 17 compilation mode equivalent to `--release 17` rather than relying only on bytecode targeting.

Repository build configuration owns enforcement of this baseline; developer probes do not establish it. Maven Wrapper selects Maven, not a JDK. A configured Java 17 toolchain can select compatible build/test tools; compiler release targeting alone does not select the test JVM. Keep compiler, annotation processing and test runtime compatibility aligned. Current repository entry points and environment-diagnostic triggers are in [commands.md](../governance/commands.md#环境).

## 0.3 Stable and Preview Features

Stable Java 17 features MAY be used when they improve semantics, correctness, or readability. Examples include:

- records;
- sealed classes and interfaces;
- pattern matching for `instanceof`;
- switch expressions and arrow-style case labels;
- text blocks;
- local-variable type inference with `var`.

Preview features MUST NOT be used by default.

Under Java 17, pattern matching for `switch` is preview and therefore is not part of normal project code.

Preview or incubator features MAY be adopted only through an explicit repository decision that reviews build, runtime, IDE, CI, production, dependency, and migration consequences.

## 0.4 Contracts Are Inputs to Backend Implementation

Accepted protocol artifacts produced by the contracts workspace are backend implementation inputs.

Java code implementing an accepted contract MUST preserve its observable semantics.

Java choices such as class vs record, exception hierarchy, `Optional`, internal enums, collection types, or visibility MUST NOT silently redefine the accepted protocol.

If an accepted protocol cannot be implemented correctly or the requested behavior requires protocol evolution, the issue belongs to the contracts workflow rather than an independent Java-side protocol redesign.

## 0.5 Domain Rules Are Not Java Rules

Domain or Business Specifications own concrete business states, transitions, invariants, and policies.

Java types SHOULD make important distinctions difficult to misuse where practical, but the Java representation does not become the authoritative business definition merely because it implements the rule.

## 0.6 Framework Independence

This standard MUST remain meaningful without Spring, MyBatis, Hibernate, Redis clients, Jackson, or other frameworks.

Framework-specific requirements belong to the relevant specialized standard.

## 0.7 Standard Library Before Custom Abstraction

Where the Java standard library provides an appropriate, well-understood abstraction, project code SHOULD prefer it over a project-specific replacement unless the custom abstraction adds concrete domain, safety, ownership, or volatility value.

Do not wrap standard Java behavior merely to rename it.

---

# 1. Source Form, Naming & Documentation

## 1.1 Source Form

Java source files MUST use UTF-8.

Tabs MUST NOT be used for indentation.

An ordinary source file SHOULD contain, in order:

1. optional repository-required header;
2. package declaration;
3. imports;
4. one primary top-level type.

A top-level type SHOULD normally reside in a file with the same name.

Multiple unrelated top-level types MUST NOT be grouped into one source file for convenience.

## 1.2 Packages

Production code MUST belong to an intentional package unless specialized tooling requires otherwise.

Package names MUST use lowercase components.

Package organization follows Project Architecture; this standard does not invent architectural layers through package naming.

Generic packages such as `common`, `misc`, `utils`, or `helpers` SHOULD be avoided when a clearer responsibility exists.

## 1.3 Imports

Wildcard imports MUST NOT be used.

Unused imports MUST be removed.

Import ordering SHOULD be deterministic and formatter-controlled.

Static imports MAY be used when they improve readability without hiding the origin or meaning of an important operation.

## 1.4 Formatter Ownership

The repository SHOULD have one canonical Java formatting mechanism.

Once configured, formatter output is authoritative for mechanical concerns such as indentation, whitespace, wrapping, braces, and import layout.

Developers and agents SHOULD NOT manually fight canonical formatter output for local aesthetic preference.

Formatting unrelated code as part of a behavioral change SHOULD be avoided when it creates unnecessary diff noise.

## 1.5 Braces and Declarations

Braces MUST be used for `if`, `else`, `for`, `while`, and `do`, even for one-statement bodies.

A field or local declaration SHOULD declare one variable.

Variables SHOULD be declared near their first meaningful use and have no broader scope than necessary.

## 1.6 Naming

Names SHOULD communicate meaning rather than mechanics.

Prefer names such as `remainingCapacity`, `enrollmentStatus`, and `retryDeadline` over `data`, `obj`, `temp`, or `info`.

Rules:

- classes, records, enums, interfaces, and annotations: `UpperCamelCase`;
- methods, fields, parameters, locals: `lowerCamelCase`;
- true constants: `UPPER_SNAKE_CASE`;
- package components: lowercase.

Boolean methods SHOULD normally read as predicates, for example `isActive`, `hasPermission`, or `canEnroll`.

Acronyms SHOULD normally participate in camel case as ordinary words: `HttpClient`, `ApiResponse`, `UserId`, `JsonPayload`, `UrlBuilder`.

Names such as `Manager`, `Processor`, `Handler`, `Helper`, or `Utils` SHOULD be replaced by more specific responsibility names when possible.

## 1.7 Constants

`static final` alone does not make an object a semantic constant.

A value SHOULD be treated as a constant only when its observable state is effectively immutable.

Mutable collections referenced by `static final` fields MUST NOT be presented as immutable constants merely because the reference cannot be reassigned.

## 1.8 Comments

Comments SHOULD explain information the code cannot clearly express, such as:

- why a non-obvious design exists;
- which invariant is protected;
- why a simpler implementation would be unsafe;
- an external compatibility limitation;
- a temporary constraint and removal condition.

Inline comments should explain ordering requirements, concurrency assumptions, non-obvious edge cases and workaround reasons where needed. Comments SHOULD NOT narrate syntax, such as describing count++ as incrementing count.

A stale comment MUST be updated or removed when the implementation changes.

Durable business or engineering rules SHOULD NOT exist only as source comments when another authoritative specification owns them.

## 1.9 TODOs

A TODO SHOULD identify unfinished or temporary work and, where practical, the condition for removal or an issue reference.

Vague TODOs such as `TODO: fix later` SHOULD be avoided.

A TODO MUST NOT excuse a correctness or security defect required to be solved by the current change.

## 1.10 Javadoc

Javadoc documents Java API semantics.

It SHOULD explain non-obvious purpose, preconditions, ownership/lifecycle, return semantics, failure behavior, thread-safety expectations, units/ranges, or important side effects.

Javadoc SHOULD NOT mechanically repeat names and signatures. Obvious getters/setters, DTO accessors and simple CRUD forwarding methods do not need documentation that adds no information. Document a precondition, postcondition, side effect or constraint when a caller cannot use the API correctly from its signature alone.

Javadoc is implementation-facing Java API documentation. HTTP/schema semantics remain owned by Accepted Contract/OpenAPI; Controller Javadoc may link to that authority but MUST NOT become a parallel HTTP contract or manually copied field/status specification.

Javadoc is especially useful for reusable public/protected APIs, cross-package abstractions, non-obvious value/domain types, extension points, and lifecycle-sensitive APIs.

Java 17 code MUST use traditional `/** ... */` documentation comments. Later `///` Markdown documentation comments MUST NOT be used while Java 17 remains the baseline.

Deprecated APIs SHOULD use `@Deprecated` plus Javadoc describing why they are deprecated and the replacement where one exists.

Generated code follows generator ownership and SHOULD NOT be manually reformatted or patched unless repository workflow explicitly makes it hand-maintained.

---

# 2. Type Modeling & Value Semantics

## 2.1 Model Meaning Before Representation

Before selecting a Java representation, determine whether a concept is primarily:

- an identity-bearing entity;
- a value;
- a finite alternative;
- a closed variant family;
- a collection;
- an optional value;
- a numeric quantity;
- an opaque identifier;
- a temporal concept.

Java types SHOULD represent application meaning rather than mirror JSON, database, or transport storage shape mechanically.

## 2.2 Identity vs Value

An identity-bearing object is distinguished by the entity it represents even as mutable attributes change.

A value is distinguished by its contained values.

Equality, mutability, and type choice SHOULD reflect that distinction.

Do not automatically generate structural equality over all mutable fields for identity-bearing entities.

## 2.3 Primitive and Wrapper Types

Primitive types SHOULD normally be used when the value is always present and object semantics are unnecessary.

Wrapper types SHOULD be used when object representation is actually required, including generics, nullable state, or integration boundaries.

Wrapper nullability MUST be intentional. A nullable `Boolean` creates three states (`true`, `false`, `null`) and SHOULD NOT be used accidentally for a two-state concept.

## 2.4 Numeric Types

Numeric representation SHOULD match range, precision, arithmetic, and domain semantics rather than database column type alone.

Exact decimal business quantities SHOULD use an exact representation such as `BigDecimal` where binary floating-point approximation would change business meaning.

Rounding behavior MUST be explicit when required.

`BigDecimal.equals()` includes scale while `compareTo()` provides numeric comparison. Code MUST choose the equality semantics deliberately.

## 2.5 String Is Not a Universal Domain Type

`String` SHOULD represent text, not every constrained concept.

Statuses, identifiers, currencies, permissions, or operation types MAY use stronger types where category mistakes are realistic and consequential.

Do not create wrapper types for every string or primitive. Additional type structure SHOULD solve a concrete ambiguity, validity, or safety problem.

## 2.6 Strong Identifier Types

Strong identifier types MAY be introduced where confusing IDs is realistic and harmful, for example `UserId` vs `PostId`.

They SHOULD NOT be introduced mechanically for every identifier.

Consider mapping cost, framework interoperability, API breadth, and real misuse risk.

Internal identifier wrappers MUST NOT silently change the accepted external protocol representation.

## 2.7 Records

A record SHOULD be considered when a type is fundamentally a fixed set of transparent components and structural equality matches its semantics.

Typical candidates include:

- value objects;
- query results;
- internal command/query data;
- immutable snapshots;
- small structured return values.

A record SHOULD NOT be selected merely to avoid boilerplate.

Record components are part of the Java API and record equality is component equality. If transparent representation or component equality is wrong for the concept, use another type.

Records are only shallowly immutable. Mutable component values require copying or protection when immutable value/snapshot semantics are required.

Compact/canonical constructors MAY enforce intrinsic validity. They SHOULD NOT embed changing external business policy requiring repositories, clocks, current users, or remote calls.

## 2.8 Classes

Use an ordinary class when the type requires hidden representation, mutable lifecycle, identity, controlled state transitions, non-transparent construction, or justified inheritance.

Classes and records are complementary tools.

## 2.9 Enums

Use an enum for a genuinely finite, named internal set of alternatives.

Enums are preferable to magic strings or integer codes when the set is intentionally closed.

A Java enum constant MUST NOT automatically become an external protocol or persistence value unless that mapping is explicitly governed.

Persistent or externally observable business semantics MUST NOT depend on enum ordinals.

## 2.10 Sealed Hierarchies

A sealed class or interface MAY represent a deliberately closed family of structurally different variants.

Use an enum when alternatives are primarily named constants with similar structure.

Use a sealed hierarchy when alternatives have meaningfully different associated data or behavior.

Java 17 stable code MUST NOT rely on preview pattern-switch syntax to consume sealed variants.

## 2.11 Avoid Boolean Explosion

Several related booleans SHOULD NOT encode one finite state machine if they permit impossible combinations.

Use an enum or explicit state type when multiple flags are actually one state dimension.

Independent boolean properties may remain independent.

## 2.12 Generics

Generics SHOULD preserve compile-time type relationships and reduce runtime casts.

Raw types SHOULD NOT be used in new ordinary application code.

`Object` SHOULD NOT replace known generic type information merely for convenience.

Generic bounds SHOULD express only capabilities genuinely required by the abstraction.

Wildcards MAY be used at flexible API boundaries when variance is required; avoid wildcard complexity that callers cannot understand.

Unchecked casts/conversions SHOULD be avoided, localized, justified, and suppressed at the narrowest meaningful scope when unavoidable.

Java type erasure MUST be considered at runtime boundaries such as serialization/deserialization of generic payloads.

## 2.13 Equality and Hashing

Reference identity (`==`) and logical equality (`equals`) are different concepts.

Use the comparison matching the concept.

If a type overrides `equals`, it MUST provide a compatible `hashCode` unless inherited behavior already satisfies the contract.

Fields participating in equality/hash SHOULD remain stable while an object is a hash-map key or set element.

Record equality is based on record components.

Arrays do not provide general element-wise object equality through ordinary `equals`; use appropriate array comparison or a value wrapper when array contents define equality.

Value-based JDK objects MUST NOT be used for identity-sensitive operations such as reference comparison, identity hashing, or synchronization on the instance.

## 2.14 Explicit Mapping at Semantic Boundaries

When contract, Java, and persistence representations differ deliberately, conversion SHOULD happen at visible boundaries rather than through scattered ad-hoc casts/conversions.

Mapping MUST preserve relevant validation, nullness, and failure semantics.

## 2.15 Temporal Type Modeling

Use `java.time` for new project-owned temporal modeling unless a legacy/framework boundary requires otherwise.

Choose types according to semantics:

- `Instant`: one definite moment on the UTC timeline;
- `LocalDate`: calendar date without time or zone;
- `LocalTime`: local time-of-day without date or zone;
- `LocalDateTime`: date and time without offset or zone;
- `OffsetDateTime`: date/time plus a fixed UTC offset;
- `ZonedDateTime`: date/time plus a region `ZoneId` and zone rules;
- `Duration`: time-based amount;
- `Period`: date-based amount.

`LocalDateTime` MUST NOT be treated as a globally unique timestamp by itself.

Use an explicit `ZoneId`/offset when converting local civil time to an `Instant`.

New project code SHOULD avoid introducing `java.util.Date`, `Calendar`, or `SimpleDateFormat` except at legacy/interoperability boundaries.

## 2.16 Do Not Over-Model

Do not create one wrapper per primitive, one interface per implementation, one sealed hierarchy per result, or one generic abstraction per call site merely to appear strongly typed.

Use the simplest semantically correct representation that provides concrete safety or clarity value.

---

# 3. Nullness & Absence

## 3.1 Distinguish Absence Semantics

The following are different states and MUST NOT be collapsed into `null` merely because Java permits it:

- no value;
- empty collection;
- not found;
- unknown;
- not loaded;
- invalid input;
- failure.

Reference values SHOULD be treated as non-null unless absence is intentionally part of the API semantics.

## 3.2 JSpecify Direction

JSpecify is the preferred project-level nullness vocabulary when the build/tooling is ready to adopt it safely.

Before rollout, verify compatibility with the Java 17 compiler, annotation processors, IDE, and chosen analyzer.

Once adopted for project-owned code:

- prefer `@NullMarked` scopes;
- use explicit `@Nullable` for intentional absence;
- avoid introducing competing project-level nullness annotation families where JSpecify can express the same semantics;
- use `@NullUnmarked` only as a deliberate migration boundary.

Nullness annotations express intent; they do not themselves provide runtime validation or prove enforcement.

## 3.3 Null Is Not Failure

A failed operation SHOULD NOT normally return `null` to indicate failure.

If callers need to distinguish not-found, forbidden, unavailable, or unknown, use an explicit representation.

## 3.4 Optional

`Optional<T>` SHOULD primarily represent an expected no-result state in return APIs.

It SHOULD NOT normally be used for fields, record components, parameters, collection elements, DTO properties, or persistent entity properties unless a concrete integration design requires it.

A method returning `Optional<T>` MUST return `Optional.empty()`, never `null`.

Avoid `Optional.get()` as ordinary control flow. Prefer APIs whose use makes the empty case explicit.

Use `orElse` vs `orElseGet` according to eager vs lazy fallback semantics.

## 3.5 Collections

Zero-or-more results SHOULD normally return a non-null empty collection when there are no values.

Empty MUST NOT silently mean not-loaded, unknown, or unavailable when callers care about that distinction.

Collection references and elements SHOULD normally be non-null unless nullable elements have deliberate semantics.

Project-owned maps SHOULD normally avoid nullable values unless absent-key vs present-null distinction is genuinely required.

## 3.6 Not Found, Unknown, Not Loaded, Invalid

Not-found may be represented with `Optional` where ordinary absence is expected.

Unknown and not-loaded are not the same as absent and require explicit state when the distinction affects behavior.

Invalid input MUST NOT be silently normalized to null if missing and malformed have different meaning.

Business states SHOULD NOT be encoded solely through null when named states exist.

## 3.7 Constructors and Required Values

Constructors and factories SHOULD establish valid Java-level nullness state before returning.

`Objects.requireNonNull` MAY enforce programming contracts such as required constructor arguments.

It does not establish business existence, authorization, or business eligibility.

## 3.8 Boundaries

Nullness validation SHOULD happen at the boundary that owns the requirement.

Do not repeatedly sprinkle defensive null checks through layers once a trustworthy internal invariant has been established.

Conversely, boundary input validation MUST NOT be omitted merely because internal APIs are null-marked.

## 3.9 Serialization and Partial Updates

When a contract distinguishes:

- field omitted;
- field present with null;
- field present with value;

the Java implementation MUST preserve all relevant states.

A single nullable field may be insufficient for partial-update models.

External contract meaning remains authoritative; Java nullness policy does not redefine it.

Database null and Java null are not automatically the same semantic state.

## 3.10 Nullness Decision Guide

- required value → non-null `T`;
- expected optional return → `Optional<T>`;
- optional internal input with one clear null meaning → `@Nullable T` where justified;
- zero-or-more → non-null collection;
- finite named state → enum/explicit state type;
- structured alternatives → result/sealed type where justified;
- failure → exception/result failure model;
- unknown/not-loaded → explicit state when relevant;
- omitted vs explicit null → representation capable of preserving both.

---

# 4. Mutability, Immutability & Collections

## 4.1 Distinguish the Guarantees

The following are different:

- final reference;
- unmodifiable container;
- immutable object;
- immutable object graph;
- thread-safe object.

Do not use these terms interchangeably.

## 4.2 final

For references, `final` prevents reassignment of the reference; it does not make the referenced object immutable.

Fields SHOULD be final when reassignment is not part of the object's lifecycle.

## 4.3 Immutability

An object is immutable only when its externally observable logical state cannot change after construction.

Immutability may require controlling mutable objects reachable through its state.

Records are shallowly immutable only.

## 4.4 Defensive Copying

When a caller provides mutable state and the callee needs independent snapshot ownership, create an appropriate defensive copy.

`List.copyOf`, `Set.copyOf`, and `Map.copyOf` are useful for unmodifiable collection snapshots where their constraints fit.

Collection copies are normally shallow: mutable elements remain shared unless separately copied or immutable.

## 4.5 Snapshot vs View

`Collections.unmodifiableXxx(source)` is generally a read-only view of a backing collection.

`Xxx.copyOf(source)` generally establishes an unmodifiable snapshot of collection structure when the source is mutable.

Use a live view only when observing later backing-state changes is intentional.

Use a snapshot when ownership should be isolated.

Unmodifiable is not the same as immutable.

## 4.6 Internal Collections

Do not normally expose internal mutable collections directly.

Use an immutable/unmodifiable snapshot, intentional live read-only view, specific mutation methods, or explicit ownership transfer according to semantics.

Mutation methods SHOULD protect invariants when mutation belongs to the owning abstraction.

## 4.7 Input and Return Ownership

When accepting a mutable collection, determine whether the method:

- reads it only during the call;
- retains the caller-owned reference;
- takes a snapshot;
- takes ownership and may mutate it.

Do not mutate caller collections unexpectedly.

Mutable return values require clear ownership semantics.

## 4.8 Collection Type Semantics

Use the least specific collection abstraction that expresses the required semantics.

Examples:

- `List`: ordered sequence, duplicates allowed;
- `Set`: uniqueness according to equality;
- `Map`: key/value association;
- `Deque`: one/both-end queue operations.

If order matters, use a Java 17 collection/implementation whose contract provides the required order or sort explicitly.

Code MUST NOT depend on unspecified iteration order.

## 4.9 Null Constraints

Factories such as `List.of`, `Set.of`, `Map.of`, and corresponding `copyOf` methods reject null values as applicable.

Replacing a mutable collection construction with these factories can therefore change semantics and MUST NOT be done mechanically.

## 4.10 Arrays

Arrays are mutable containers even when referenced from final fields.

Value-like objects containing arrays SHOULD defensively copy on ingress/egress when external mutation would violate semantics.

Reference-array copies are shallow.

`byte[]` deserves particular care for hashes, serialized bytes, binary IDs, credentials, or other value-like data.

## 4.11 Map Keys / Set Elements

Objects used as map keys or set elements SHOULD have equality/hash behavior stable while stored.

Immutable value objects are preferred as keys where practical.

## 4.12 Static Mutable State

Mutable static state creates process-wide shared ownership and SHOULD be avoided unless deliberately intended.

`static final` does not make the referenced object immutable or thread-safe.

## 4.13 Builders

Builders MAY use mutable construction state.

After `build()`, later builder mutation SHOULD NOT unexpectedly mutate already-built objects unless explicitly documented.

Snapshot mutable builder collections where required.

## 4.14 Copy at Ownership Boundaries

Copy/freeze at meaningful ownership boundaries rather than randomly inside every method.

Once ownership is established internally, repeated defensive copies are usually unnecessary unless state is exposed or transferred again.

## 4.15 Mutation and Concurrency

Immutability can reduce shared mutable state but does not automatically make multi-object operations atomic or system-level concurrency correct.

Unmodifiable wrappers are not synchronization mechanisms.

Concurrency rules remain in Section 9 and Backend Engineering.

---

# 5. Encapsulation & Java API Surface

## 5.1 Narrowest Useful Visibility

Use the narrowest visibility that satisfies legitimate consumers:

- `private`: one enclosing type;
- package-private: cohesive package implementation;
- `protected`: deliberate subclass extension surface;
- `public`: intended Java API beyond the package.

Do not use `public` by default.

Public Java API is not automatically an external protocol contract, but unnecessary public surface still creates coupling.

## 5.2 Package-Private

Package-private is a deliberate encapsulation tool, not an inferior form of visibility.

It works best when the package itself has a cohesive implementation responsibility.

Do not enlarge packages merely to exploit package-private access.

## 5.3 Fields and Accessors

Fields SHOULD normally be private.

Project-owned classes MUST NOT normally expose public mutable fields.

Read access does not imply write access; a getter does not require a setter.

Do not widen production visibility solely for tests.

## 5.4 protected and Inheritance

`protected` creates a subclass extension surface and SHOULD be used only when subclass customization is intentional.

Implementation inheritance SHOULD be used only when the subtype is genuinely substitutable for the parent and inherited behavior forms a stable extension model.

Prefer composition for use/contain/delegate relationships and for code reuse that does not represent a true subtype.

Classes not designed for extension MAY be final.

## 5.5 Interfaces

An interface SHOULD describe a capability or abstraction boundary that consumers can depend on independently of one concrete implementation.

Do not create an interface automatically for every class or solely for mocking.

One implementation does not disqualify an interface if a real boundary exists.

Interface surface SHOULD remain focused and cohesive.

Default methods MAY implement behavior naturally derivable from the interface contract; they SHOULD NOT hide implementation-specific policy merely to avoid updating implementations.

Interfaces SHOULD NOT be used as miscellaneous constant containers.

## 5.6 Constructors

Constructors SHOULD establish valid Java-level object state.

Required state SHOULD normally be provided during construction rather than through arbitrary post-construction setter sequences.

Constructors SHOULD avoid surprising effects such as remote calls, starting threads, publishing messages, or unrelated durable writes unless fundamental to the type.

Constructors MUST NOT publish partially constructed `this` to concurrent/external observers.

Constructor visibility SHOULD match legitimate creation paths.

## 5.7 Static Factories

Static factories MAY be used when they add meaningful creation semantics such as named construction modes, normalization, validation, reuse, subtype selection, or hidden representation.

Do not wrap every trivial constructor in a factory.

Factory names SHOULD match their usual semantics (`of`, `from`, `parse`, `copyOf`, `empty`, etc.) and MUST NOT hide major external effects behind innocent-looking creation APIs.

## 5.8 Setters and Behavior

A setter SHOULD exist only when arbitrary replacement of a property is a legitimate operation.

Prefer behavior-oriented operations where mutation has semantic rules.

Do not use generic setters to bypass domain state-transition ownership.

## 5.9 Static State and ThreadLocal

Static methods are appropriate for stateless type-owned behavior.

Mutable static state SHOULD NOT be used for request-specific data, authenticated identity, replaceable dependencies, or ad-hoc caches without deliberate process-wide ownership.

`ThreadLocal` is not ordinary encapsulation or a general replacement for explicit dependencies; lifecycle rules are defined in Section 9.

## 5.10 Nested Types

Prefer static nested classes when no enclosing instance is needed.

Nested types SHOULD represent tight ownership by an enclosing abstraction.

Substantial reusable concepts SHOULD move to package-level types when they develop independent responsibility or consumers.

## 5.11 Framework and Persistence Requirements

Reflection, serialization, DI, ORM, or mapping requirements MUST NOT automatically define the Java API exposed to all callers.

Localize integration constraints where practical and handle framework-specific rules in specialized standards.

## 5.12 Avoid Trivial Wrapper Layers

Do not create forwarding classes or interfaces that add no semantic, ownership, compatibility, volatility, or dependency boundary.

Encapsulation should reduce coupling, not multiply meaningless types.

---

# 6. Control Flow, Lambdas & Streams

## 6.1 Prefer Direct Control Flow

Use the simplest construct that clearly expresses the operation: `if/else`, switch, loops, or streams.

No construct is inherently more modern or preferable in every context.

Conciseness is useful only when it improves clarity.

## 6.2 Guard Clauses and Nesting

Early returns MAY separate invalid/exceptional paths from the main path when they reduce nesting.

Do not fragment short coherent logic into excessive exits.

Deep nesting SHOULD prompt consideration of guard clauses, meaningful extraction, switch, or an explicit state model.

## 6.3 Conditions

Complex boolean expressions SHOULD use meaningful predicates or structure when they are hard to reason about.

Prefer `if (enabled)` / `if (!enabled)` over comparisons to `true`/`false`, except where nullable `Boolean` intentionally carries three-state semantics.

Use ternary expressions only for simple value selection; avoid nested or effectful ternaries.

## 6.4 switch in Java 17

Use switch when behavior is selected by one supported closed decision axis such as enum, string, or other Java-17-supported switch selector.

Arrow-style cases SHOULD generally be preferred for new code to avoid accidental fall-through.

Switch expressions SHOULD be preferred when the decision produces one value.

When switching over an enum, code SHOULD preserve compile-time exhaustiveness where practical and SHOULD NOT add a default branch automatically when listing all known enum constants provides better compiler feedback.

Java 17 normal project code MUST NOT use preview pattern matching for switch, `case null` pattern-switch syntax, record patterns, or later guarded pattern syntax.

## 6.5 instanceof Pattern Matching

Java 17 pattern matching for `instanceof` MAY replace redundant type-test-and-cast plumbing when the type distinction is meaningful.

Do not replace a clearer polymorphic design with type branching merely because pattern matching exists.

## 6.6 Loops

Loops remain first-class Java and SHOULD be preferred when they make effects, early `break`/`continue`, procedural state, or per-element exception handling clearer than a stream pipeline.

Do not treat imperative loops as obsolete.

## 6.7 Streams

Streams SHOULD be used when an operation naturally forms a source → filter/transform → aggregate/collect pipeline and that representation is clearer than a loop.

A stream SHOULD NOT be used when it requires complex nested lambdas, shared mutable capture, hidden effects, deep `flatMap` chains, or clever collectors that obscure intent.

Behavioral functions SHOULD be non-interfering and normally stateless.

Intermediate operations such as `map`, `filter`, `peek`, `sorted`, and `distinct` SHOULD NOT carry required externally significant side effects.

Required business effects MUST NOT depend on intermediate operations being executed.

`peek` SHOULD NOT hide required mutation or persistence.

## 6.8 forEach

`forEach` MAY be appropriate when the terminal purpose is explicitly to perform one effect per element.

For complex effectful workflows, ordinary loops are often clearer because ordering, failure, retry, and partial completion remain visible.

## 6.9 Ordering

Code MUST NOT depend on ordering not guaranteed by the source/operation contract.

For parallel streams, `forEach` does not preserve encounter-order execution while `forEachOrdered` does where encounter order exists.

If strict sequential effect ordering is required, reconsider whether parallel execution is appropriate.

## 6.10 Parallel Streams

Parallel streams are not a default optimization.

Use them only after verifying:

- the operation is safe to parallelize;
- ordering requirements permit it;
- functions are suitable for parallel execution;
- shared mutable state is controlled;
- resource/downstream capacity is acceptable;
- representative measurement demonstrates benefit.

Do not use parallel streams to hide an uncontrolled concurrent workflow.

## 6.11 Lambdas and Method References

Lambdas SHOULD remain small and understandable at the call site.

If a lambda contains substantial branching, effects, exception handling, or business logic, extract a meaningful method or use clearer control flow.

Do not introduce mutable holders merely to bypass Java's effectively-final capture rule.

Method references MAY replace lambdas when they are at least as clear.

Project-owned functional interfaces MAY be used when they add meaningful semantic naming beyond standard `Function`, `Predicate`, `Consumer`, or `Supplier`.

## 6.12 Resource-Bearing Streams

Most in-memory collection streams do not need closing.

Streams backed by I/O or other closeable resources MUST follow Section 8 ownership rules.

Avoid returning resource-bearing streams when lifecycle transfer to callers is unclear.

## 6.13 Failure and Side Effects

Control-flow refactoring MUST preserve higher-level distinctions such as business rejection, failure, unknown outcome, and partial completion.

Important effects such as durable writes, external calls, message publication, and shared-state mutation SHOULD remain structurally discoverable.

---

# 7. Exceptions & Failure Representation

## 7.1 Java Failure Representation Follows Higher-Level Semantics

Backend Engineering owns the system-level failure model, including distinctions such as known failure, unknown outcome, retry/recovery, and partial effects.

Java code MUST preserve those distinctions when selecting exceptions, result types, async wrappers, or propagation behavior.

Exception types do not independently define business semantics.

## 7.2 Exceptions vs Results

Use exceptions for abnormal control transfer through the current abstraction.

Do not use exceptions for every negative result.

Expected finite business alternatives MAY use enum/result/sealed models when callers are expected to branch explicitly.

Result models SHOULD NOT grow into a universal container for every infrastructure failure.

## 7.3 Checked vs Unchecked

Checked vs unchecked is an API design decision about caller obligations.

Do not choose unchecked merely to avoid compiler work or checked merely because failure is possible.

Checked exceptions MAY fit recoverable Java-level obligations callers should explicitly consider.

Unchecked exceptions fit programming-contract violations and abstraction failures that intermediate callers cannot meaningfully recover from.

Recoverability is evaluated at the abstraction boundary.

## 7.4 Abstraction Translation

Higher-level APIs SHOULD NOT leak low-level implementation-specific exception types without intent.

Translate only at meaningful abstraction boundaries or when adding information callers/diagnostics genuinely need.

When translation is caused by another exception, preserve the original cause.

Do not double-wrap through many layers without adding semantics.

## 7.5 Catching

Catch only when the current layer can:

- recover;
- translate abstraction;
- add essential context;
- perform required cleanup;
- implement a semantically valid fallback;
- preserve interruption/cancellation;
- classify the outcome.

Do not catch merely to rethrow unchanged.

Broad `catch (Exception)` SHOULD be limited to genuine execution boundaries with deliberate classification.

Application code MUST NOT normally catch `Throwable` or treat `Error` as an ordinary recoverable business failure.

## 7.6 Exception Types and Messages

Custom exception classes SHOULD represent stable categories that callers/infrastructure can meaningfully distinguish.

Do not create one exception type per throw site.

Program logic MUST NOT parse exception message strings for machine classification.

Messages SHOULD add useful diagnostic context and MUST NOT intentionally include secrets or sensitive credentials.

## 7.7 Logging and Throwing

Follow the primary event/failure owner defined in [Backend §8.2](backend-engineering.md#82-logs-and-operational-ownership). Catching does not itself require logging. A catch that translates and rethrows should preserve the cause without logging another stack trace when the receiving boundary owns the failure.

Use parameterized logging rather than concatenating messages. Avoid eagerly constructing expensive diagnostic values for disabled levels; guard expensive work where needed. Pass the Throwable through the logger's exception argument when a stack trace is required rather than retaining only getMessage(). Do not use broad object toString() calls as a shortcut for selecting safe diagnostic fields.

Do not log-and-swallow when the caller must know the operation failed. Event selection, severity and audit guarantees belong to Backend §8.2; the concrete logging API and context integration belong to Spring §11.17.

## 7.8 Fallback

Fallback MUST preserve API semantics.

Catching a dependency failure and returning `null` or an empty collection SHOULD NOT silently convert failure into ordinary absence unless that equivalence is explicitly valid.

## 7.9 Timeout and Unknown Outcome

An exception such as timeout describes what the local Java call observed; it does not automatically prove the final business effect did not occur.

If Backend Engineering classifies the operation as an unknown outcome, the Java representation MUST preserve that uncertainty rather than translate it to a definitive rejection.

Exception class alone MUST NOT determine retryability.

## 7.10 Interruption

`InterruptedException` MUST NOT be swallowed as an ordinary transient failure.

If the current API can declare it, interruption SHOULD normally propagate.

If it cannot propagate directly, code SHOULD normally restore interrupt status with `Thread.currentThread().interrupt()` and terminate/translate the current operation according to its cancellation semantics.

Do not restore the flag merely before rethrowing the same `InterruptedException` through an API that already declares it.

Remember that `Thread.interrupted()` clears the current thread's interrupt status.

## 7.11 Cancellation and Future Wrappers

Cancellation SHOULD remain distinguishable from ordinary failure when callers care.

`CompletionException` and `ExecutionException` normally transport an underlying async failure; the cause carries the computation failure category.

`CompletableFuture.get()` and `join()` have different exception and interruption surfaces. Do not choose `join()` solely to avoid checked exceptions.

Async recovery operators such as `exceptionally`, `handle`, and `whenComplete` MUST NOT silently turn failures into successful defaults unless fallback semantics allow it.

## 7.12 Partial Effects

Java stack unwinding does not roll back remote or durable effects.

One thrown exception does not prove earlier database writes, messages, HTTP effects, notifications, or cache updates were reversed.

Compensation and recovery remain Backend Engineering concerns.

## 7.13 try-with-resources and Suppressed Exceptions

Use try-with-resources for owned `AutoCloseable` resources where appropriate.

Preserve the original throwable/cause chain so suppressed close failures are not lost.

## 7.14 Assertions

Java `assert` MUST NOT enforce untrusted input validation, authorization, business rules, or production-required invariants because assertions may be disabled.

## 7.15 Standard Programming Exceptions

Use standard exceptions precisely when their established Java semantics fit, for example:

- required Java value is null → `NullPointerException` may fit;
- argument violates Java API requirements → `IllegalArgumentException` may fit;
- invocation is invalid for current Java object lifecycle → `IllegalStateException` may fit.

Do not force business rejection into these categories merely because the names sound similar.

---

# 8. Resource Ownership & Lifecycle

## 8.1 Ownership Questions

Before using a finite-lifetime resource, determine:

- who creates it;
- who owns it;
- who borrows it;
- who closes/releases it;
- when ownership transfers;
- what happens when cleanup fails.

Deterministic resource release MUST NOT depend on accidental garbage-collection timing.

## 8.2 AutoCloseable

`AutoCloseable` signals a lifecycle obligation but does not determine ownership by itself.

When one lexical scope creates, fully uses, and owns an `AutoCloseable`, try-with-resources SHOULD normally express the lifecycle.

Do not close resources you merely borrow.

Creation generally implies ownership unless ownership is explicitly transferred.

Returning an open resource generally transfers lifecycle responsibility and SHOULD be documented where non-obvious.

## 8.3 Wrapper Ownership

Closing a wrapper may close its underlying resource.

Do not wrap a borrowed resource in try-with-resources if closing the wrapper would unexpectedly close caller-owned state.

Understand whether the outer resource owns the inner resource.

`flush` and `close` have different semantics and MUST NOT be treated as interchangeable cleanup operations.

## 8.4 Cleanup Failure

Resource release can fail and may carry meaningful information.

Try-with-resources preserves a primary failure and records close failures as suppressed exceptions.

Multiple resources close in reverse declaration order.

`AutoCloseable.close()` is not generally guaranteed idempotent. `Closeable` provides stronger repeated-close semantics.

Project-owned close implementations SHOULD be idempotent where practical, but callers MUST follow the declared resource API rather than assume repeated close is safe.

## 8.5 Explicit Lifecycle APIs

Not every lifecycle fits one generic `close()`.

Components MAY expose meaningful phases such as stop-accepting, shutdown, await-termination, or close when callers genuinely need distinct states.

Do not hide interruption-sensitive shutdown behind a generic `close()` when that would obscure cancellation semantics.

## 8.6 ExecutorService Under Java 17

Under Java 17, `ExecutorService` is **not** `AutoCloseable` and MUST NOT be managed with try-with-resources.

An application-created executor MUST have an identified lifecycle owner.

Ordinary request/business methods SHOULD NOT create fire-and-forget thread pools without a shutdown plan.

A Java 17 executor lifecycle SHOULD use, as appropriate:

1. `shutdown()` to stop accepting new tasks;
2. bounded `awaitTermination(...)` where required;
3. `shutdownNow()` only when policy requires escalation;
4. another bounded await where appropriate;
5. interruption preservation if the waiting thread is interrupted.

`shutdownNow()` is best effort; it does not prove running tasks have stopped.

Shared executors MUST NOT be shut down by callers that only borrow them to submit work.

## 8.7 Scheduled/Background Resources

Schedulers, watch services, polling loops, custom workers, and background tasks also create lifecycle responsibility.

The owner MUST know how work stops and how termination is observed.

## 8.8 Framework-Managed and Pooled Resources

A resource supplied by a framework/container may be borrowed, pooled, proxied, shared, transaction-bound, or container-owned.

Its lifecycle MUST follow the relevant specialized standard.

Do not apply “I received it, therefore I close it” blindly.

Pool-backed resource `close()` may return a borrowed handle to the pool rather than destroy the physical resource.

Transaction lifecycle is not defined by generic `AutoCloseable` rules.

## 8.9 Async Boundaries

A resource created in one scope SHOULD NOT be handed to asynchronous work if the creating scope may close it before the task completes.

Long-lived tasks SHOULD NOT capture request-, transaction-, callback-, or try-with-resources-scoped resources unless ownership is explicitly extended safely.

Short-lived borrowed resources SHOULD NOT escape into long-lived fields.

## 8.10 Partial Acquisition

When multiple resources are acquired and a later acquisition fails, previously acquired resources still require cleanup.

Try-with-resources SHOULD be preferred where it naturally handles partial acquisition and release.

## 8.11 finally

`finally` MAY manage cleanup when try-with-resources does not fit or the resource does not implement `AutoCloseable`.

It is a local cleanup mechanism, not distributed rollback or crash recovery.

## 8.12 Shutdown Hooks

JVM shutdown hooks SHOULD NOT be the default cleanup mechanism when normal application/framework lifecycle can own the resource.

Hooks may run concurrently and in unspecified order and SHOULD be bounded, defensive, and free of dependency cycles/deadlocks.

Graceful Java cleanup is not a substitute for persistent recovery after crash or abrupt process termination.

Components SHOULD NOT call `System.exit` as ordinary lifecycle control. `Runtime.halt` is not ordinary cleanup.

## 8.13 Lifecycle Evidence

Where lifecycle is a meaningful risk, verification should exercise the relevant property, for example repeated acquire/release, pool recovery, executor termination, failure during use, or failure during cleanup.

General verification ownership remains Backend Engineering §9 / future Testing Standard; exact commands belong to the command catalog.

---

# 9. Concurrency & Thread Safety

## 9.1 Scope

This section governs safe access to shared state among threads inside one JVM.

Java thread safety does **not** by itself guarantee database concurrency correctness, distributed coordination, message ordering, or business invariants.

## 9.2 Start With the State Model

Before adding `volatile`, `synchronized`, atomics, locks, or concurrent collections, identify:

- which state is shared;
- which operations mutate it;
- which values must change together;
- which visibility guarantees are required;
- which compound operations must be atomic.

Prefer immutable values, thread confinement, independent task-local state, or message passing where they naturally avoid shared mutation.

## 9.3 Happens-Before

Shared mutable state requires defined memory-visibility relationships.

Important mechanisms include:

- monitor unlock → later lock of same monitor;
- volatile write → later volatile read of same field;
- `Thread.start()`;
- successful `Thread.join()`;
- executor task submission → task execution;
- asynchronous computation → successful `Future.get()`.

Correctness MUST rely on defined Java Memory Model relationships, not timing or “eventual cache refresh” assumptions.

## 9.4 volatile

`volatile` provides visibility/ordering semantics for a field, not general mutual exclusion.

It MAY fit simple independent flags or publication of an immutable snapshot/reference.

It does not make read-modify-write operations such as `count++` atomic and SHOULD NOT be stretched across multi-variable invariants.

## 9.5 synchronized and Locks

`synchronized` provides mutual exclusion and visibility when all participating accesses use the same monitor protocol.

Lock the invariant, not random statements.

Use a stable lock identity under the owning abstraction's control; avoid locking on public objects, string literals, boxed values, or other externally shared value objects.

Keep critical sections focused and avoid unrelated slow remote/I/O work while holding a lock unless the invariant truly requires it.

Java locks protect only threads sharing that JVM state; they MUST NOT be the sole protection for cross-process/database invariants.

`ReentrantLock` MAY be used when capabilities such as interruptible/timed acquisition, `tryLock`, multiple conditions, or explicit fairness are concretely useful.

Explicit locks MUST be released in `finally`.

Fair locks are not automatically better and SHOULD NOT be enabled by default.

When multiple locks can be acquired, establish consistent lock ordering and minimize nested-lock designs.

Do not call unknown external callbacks/listeners/plugins while holding internal locks unless correctness requires it.

## 9.6 Higher-Level Synchronizers

Prefer higher-level utilities such as `BlockingQueue`, `CountDownLatch`, `Semaphore`, `Future`, executors, concurrent collections, or `Lock/Condition` over raw `wait/notify` where they fit.

`Thread.sleep()` MUST NOT be the primary synchronization guarantee in production code or tests.

## 9.7 Atomic Classes

`AtomicBoolean`, `AtomicInteger`, `AtomicLong`, `AtomicReference`, and related classes provide atomic operations over one variable.

They do not automatically protect multi-variable invariants.

CAS loops SHOULD clearly define expected state, new state, retry, and termination conditions.

Do not introduce hand-written lock-free algorithms without a concrete need.

`AtomicReference` MAY atomically publish an immutable whole-state snapshot when that accurately represents the logical state.

## 9.8 Concurrent Collections

Use concurrent collections only for state genuinely shared concurrently.

Thread-safe individual methods do not make arbitrary multi-step logic atomic.

Use compound operations such as `putIfAbsent`, `compute`, `computeIfAbsent`, or `replace` when their semantics match the required transition.

`ConcurrentHashMap` does not provide one global transaction across arbitrary keys.

Concurrent collection iteration may be weakly consistent; do not assume it represents a frozen global snapshot unless the API guarantees it.

Copy-on-write collections SHOULD be reserved for read-dominant, write-rare workloads.

## 9.9 Executor Ownership and Capacity

Application-created executors require lifecycle ownership and capacity reasoning.

Do not create independent thread pools per request/business call without a concrete lifecycle and resource policy.

More pools do not create more CPU, DB connections, or downstream capacity.

Pool and queue choices SHOULD reflect workload and downstream limits.

Executor rejection is a real runtime state and MUST NOT be swallowed automatically.

## 9.10 Future and Cancellation

`Future.isDone()` means terminal completion, which may be success, failure, or cancellation.

`Future.get()` participates in defined visibility and may expose interruption, execution failure, cancellation, or timeout depending on the method.

`cancel(true)` is a cancellation request, not proof that a running task has stopped or can no longer produce effects.

Task code must cooperate with interruption/cancellation where appropriate.

Avoid starvation designs where all executor threads wait for tasks queued to the same exhausted executor.

## 9.11 CompletableFuture

`CompletableFuture` expresses async composition but does not remove executor ownership, blocking, failure, cancellation, or capacity concerns.

`thenApply` vs `thenApplyAsync` (and similar pairs) have different execution semantics and are not style variants.

Where execution location matters, choose an owned executor deliberately.

Do not use async chaining merely to make blocking work look non-blocking.

## 9.12 ThreadLocal

`ThreadLocal` is hidden thread-associated state and SHOULD NOT replace ordinary explicit parameters/dependencies without a concrete reason.

Project code setting ThreadLocal values on reusable threads SHOULD remove them in `finally` when the logical operation ends.

Ordinary ThreadLocal values do not automatically propagate through async/executor boundaries.

`InheritableThreadLocal` is not a generic request/task-context propagation solution for thread pools.

Framework-managed context belongs to the framework standard.

## 9.13 Publication and final Fields

Correctly constructed objects with final fields receive useful Java Memory Model guarantees, but final fields do not make all mutable state thread-safe.

Do not publish `this` before construction completes.

Publish shared objects through defined mechanisms such as synchronization, volatile references, concurrent collections, executor submission, static initialization, or other `java.util.concurrent` facilities.

Lazy initialization accessed concurrently requires a real initialization and visibility strategy.

## 9.14 Documentation

Reusable types with non-obvious concurrency semantics SHOULD make their model understandable, for example:

- immutable/safely shareable;
- thread-confined;
- thread-safe;
- conditionally thread-safe;
- not thread-safe.

If callers must hold a lock or satisfy another condition, document it.

## 9.15 Cross-System Boundary

Java synchronization does not replace database locks/constraints/transactions or distributed coordination.

A synchronized Java method can still produce partial multi-system effects and does not turn those effects into one atomic transaction.

Backend Engineering remains authoritative for system-level concurrency and recovery.

## 9.16 Verification

Concurrency-sensitive behavior requires evidence that actually exercises the relevant competition/property.

Prefer deterministic coordination primitives such as latches/barriers over arbitrary sleeps.

General verification principles remain Backend Engineering §9 / future Testing Standard; exact commands belong to the command catalog.

## 9.17 Java 17 and Virtual Threads

Java 17 does not provide stable virtual-thread APIs.

Project code and Spring configuration MUST NOT depend on virtual threads while Java 17 remains the repository baseline.

Adopting virtual threads requires a deliberate Java/runtime baseline upgrade.

---

# 10. Language Feature & Compatibility Policy

## 10.1 Java 17 Is the Compatibility Boundary

The Java baseline constrains:

- language syntax;
- Java SE/JDK APIs;
- class-file/runtime compatibility;
- build tooling assumptions.

Compilation SHOULD use the declared Java 17 release so a newer compiler cannot silently expose newer JDK APIs.

Production runtime, CI, tests, annotation processors, packaging, and local development SHOULD remain compatible with that baseline.

## 10.2 Stable Java 17 Features

Stable Java 17 features MAY be used when semantically appropriate.

Normal project code MUST NOT use later-JDK features such as:

- stable pattern matching for switch;
- record patterns;
- virtual threads;
- sequenced collections;
- `///` Markdown Javadoc;
- later-JDK APIs.

When uncertain, verify against the Java 17 platform rather than a newer IDE.

## 10.3 Preview, Incubator, and Internal APIs

Preview features are disabled by default.

Incubator modules MUST NOT be introduced without explicit architectural approval.

Project code MUST NOT depend on internal JDK APIs such as `jdk.internal.*` or internal `sun.*` / `com.sun.*` implementation packages except through an explicitly governed exception.

`--add-opens` / `--add-exports` MUST NOT become ordinary application design tools. If tooling/framework compatibility requires them, identify the dependency, keep scope narrow, and remove the workaround when no longer necessary.

## 10.4 Reflection

Reflection MAY be used for framework infrastructure, serialization, DI, mapping, testing infrastructure, plugins, or tooling where runtime metadata genuinely matters.

Ordinary business logic SHOULD prefer normal typed Java calls when types are known at compile time.

Do not use reflection or `setAccessible(true)` casually to defeat encapsulation.

Reflective code SHOULD be tested against the actual runtime/module configuration it depends upon.

Lower-level APIs such as `MethodHandle` or `VarHandle` SHOULD be reserved for concrete infrastructure/performance needs.

## 10.5 Annotations

Annotations are metadata; behavior exists only because a compiler, processor, framework, or runtime interpreter consumes them.

Project-owned annotations SHOULD represent one coherent concept and use the narrowest appropriate `@Target` and retention policy.

Runtime retention SHOULD NOT be used automatically.

Annotations influencing authorization, transactions, serialization, validation, retry, or routing MUST have semantics owned by the corresponding authoritative standard rather than becoming hidden business rules.

Annotation processors are build-time code execution and SHOULD be deliberate dependencies.

## 10.6 Generated Code

Generated code must have an authoritative generator/input.

Change the authoritative source and regenerate where practical rather than manually patching generated Java.

## 10.7 var

`var` MAY be used for local variables when the inferred type remains obvious enough to understand the code.

Use an explicit type where numeric type, collection semantics, generic type, or another important distinction would otherwise be obscured.

`var` is local type inference, not dynamic typing and not an API-signature mechanism.

## 10.8 Text Blocks

Text blocks MAY be used for naturally multiline text such as SQL snippets, JSON test data, templates, or multiline messages when they improve readability.

They MUST NOT become a substitute for runtime configuration or secret management.

## 10.9 Native Java Serialization

Java native serialization (`Serializable` / `ObjectInputStream`) SHOULD NOT be the default format for APIs, messages, durable database data, cache formats, or cross-service protocols.

Implementing `Serializable` creates compatibility and security considerations and MUST NOT be added casually.

Where long-lived native serialization is intentionally supported, `serialVersionUID` and serialized-form compatibility MUST be deliberate.

Untrusted native serialized data MUST NOT be deserialized as an ordinary input mechanism.

`ObjectInputFilter` is defense-in-depth when native deserialization is unavoidable; it is not permission to accept arbitrary untrusted serialized graphs.

External formats such as JSON, message payloads, cache values, or database JSON are governed independently from Java class shape.

## 10.10 Deprecated APIs and Warnings

New code SHOULD NOT introduce deprecated Java/library APIs without a concrete compatibility reason.

APIs deprecated for removal represent stronger future-upgrade risk.

Compiler warnings SHOULD be resolved, localized, or deliberately suppressed at the narrowest reasonable scope rather than disabled globally.

A suppression does not make the underlying operation safe.

Global `-Werror` is a build-policy choice, not a requirement of this standard.

## 10.11 Do Not Depend on Unspecified Behavior

Project correctness MUST rely on documented contracts rather than observations such as:

- accidental `HashMap` order;
- internal JDK classes;
- GC eventually closing resources;
- exact exception-message wording;
- reflection currently succeeding under one runtime.

## 10.12 Java Patch and Major Upgrades

A Java 17 patch upgrade does not authorize newer language features; the platform baseline remains Java 17.

Patch upgrades SHOULD still receive relevant verification because runtime/security/provider/GC behavior can change.

Changing the repository major Java baseline is a deliberate engineering migration and SHOULD review:

- language/API changes;
- removed/deprecated APIs;
- JDK encapsulation;
- Spring/framework compatibility;
- dependency bytecode/runtime requirements;
- annotation processors;
- reflection;
- serialization;
- build plugins;
- runtime flags;
- container/runtime image;
- CI and production runtime;
- representative verification.

A major upgrade is not complete merely because source compiles.

## 10.13 Spring Compatibility

The current project baseline is:

```text
Java 17
Spring Boot 3.5.13
```

A future Java upgrade MUST consider Spring Boot and dependency support before source code begins using newer platform features.

A future Spring upgrade MUST NOT silently raise Java requirements without updating the repository Java baseline.

## 10.14 Dependency Compatibility

Third-party dependency upgrades MUST remain compatible with the Java 17 runtime unless the baseline is deliberately upgraded.

A dependency upgrade MUST NOT silently force production onto a newer JVM.

## 10.15 Runtime Vendor and Platform

The Java baseline does not automatically mandate one JDK vendor. Vendor/runtime-image policy belongs to Operations.

Project code SHOULD avoid vendor-specific APIs unless deliberately approved.

Filesystem, native-process, locale, timezone, charset, and OS-dependent behavior SHOULD NOT rely on developer-machine defaults when deterministic behavior matters.

Use explicit charset for persistent/protocol text; UTF-8 SHOULD be preferred unless another format is externally defined.

Use explicit locale/time-zone context where business meaning depends on them.

## 10.16 Runtime Inputs

`System.getProperty` and `System.getenv` are mechanisms, not architecture.

Runtime configuration and secrets SHOULD be owned by the application's configuration layer rather than read arbitrarily throughout business code.

Components SHOULD NOT call `System.exit` as ordinary failure handling.

Java 17's deprecated-for-removal SecurityManager MUST NOT be a new application security design dependency.

## 10.17 Tooling Compatibility

A language/API feature is not safely adopted solely because `javac` accepts it.

Compatibility includes relevant IDEs, formatters, analyzers, annotation processors, test runners, coverage tools, build plugins, bytecode tooling, and deployment tooling.

Major language/runtime upgrades SHOULD be separated from unrelated feature work where practical.

## 10.18 Baseline Summary

Allowed stable Java 17 examples:

- records;
- sealed / non-sealed types;
- `instanceof` pattern matching;
- switch expressions;
- arrow-style case labels;
- text blocks;
- local `var`.

Not normal project code under the current baseline:

- pattern matching for switch;
- record patterns;
- virtual threads;
- sequenced collections;
- `///` Markdown Javadoc;
- later-JDK APIs;
- preview/incubator/internal-JDK features without explicit governance.

---

# Appendix A — Authority Map

Use this routing model when a concern crosses layers:

```text
Accepted Contract
→ externally observable protocol semantics

Domain / Business Specification
→ concrete business meaning, states, transitions, invariants

Backend Engineering Standard
→ technology-independent backend guarantees

Java Engineering Standard
→ Java-language representation and runtime semantics

Spring Boot Standard
→ framework implementation/proxy/container/binding/lifecycle semantics

Database / Redis / Messaging / Observability Standards
→ technology-specific mechanisms

Commands Catalog
→ exact executable verification/operation commands

AGENTS.md
→ agent workflow and routing only
```

A specialized standard may refine how a higher-level guarantee is implemented, but MUST NOT silently weaken or redefine the higher-level owner.

