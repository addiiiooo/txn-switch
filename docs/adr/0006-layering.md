# ADR-0006: A pure domain, three ports, and no interface without a second implementation

* Status: Accepted
* Date: 2026-09-23

## Context

"Hexagonal architecture" degenerates in two directions. One is a framework-annotated
domain where the state machine is impossible to unit test without a Spring context. The
other is ceremonial: every class shadowed by an interface, `FooService` /
`FooServiceImpl`, four mapping layers, and a `port` package with one implementation each,
where the architecture costs more than the problem.

The requirement here is a domain with no Spring annotations. The judgment call is how much
further to go.

## Decision

**The domain is plain Java — no `org.springframework`, and no `jakarta.persistence`
either.** `Authorization`, `Money`, `Pan` and the state machine are constructed by
factories, validate their own invariants, and are unit-testable in microseconds.
Persistence uses separate JPA entities plus hand-written mappers.

Rejecting JPA annotations on the aggregate is the expensive half of this decision. The
requirement only bans Spring annotations, and annotating the aggregate would delete about
150 lines of mapper code. It is rejected because JPA would dictate the domain's shape: a
no-arg constructor, non-final fields, setter-shaped mutation, and lazy-loading semantics
leaking into the state machine — the aggregate would no longer be able to guarantee that
an `Authorization` cannot exist without an acquirer decision. The mapper code is boring,
mechanical, and covered by tests; the invariant is not recoverable once lost.

**Three packages, one Maven module:**

```
domain/       pure Java
application/  use cases, ports, transaction boundaries
adapter/      in/web · out/persistence · out/acquirer · simulator
```

A single module rather than three: module boundaries would enforce direction at compile
time, but cost a reactor build, three POMs and a slower loop, for a codebase of this size
where one test already enforces the rule that matters.

**Exactly three ports**, each justified by a real second implementation or a real test
seam:

* `AcquirerGateway` — a real acquirer is the obvious second implementation; the simulator
  is the first.
* `AuthorizationRepository`, `IdempotencyStore` — keep Spring Data and JPA types out of
  the application layer, which is precisely what lets the domain stay pure.

There will be **no** `FooService`/`FooServiceImpl` pairs, no interface introduced "for
testability" where the class is already testable, and no mapping layer between the
application and the web DTOs beyond one explicit assembler.

**Enforcement is a test, not a convention.** `DomainPurityTest` walks the compiled
`com.txnswitch.domain` package and fails if any class, field, method or constructor
carries an annotation from `org.springframework` or `jakarta.persistence`, or if a domain
type references one. ArchUnit would express this more richly, but the stack is fixed, and
this rule fits in thirty lines of reflection with no new dependency.

**Transactions belong to the application layer**, never to the domain and never to a
controller. Three short transactions per authorization, none spanning the acquirer call
(ADR-0002).

## Consequences

**Good.** The state machine — the part a reviewer will actually read — is a plain object
with a test that runs in milliseconds and no infrastructure. Swapping in a real acquirer,
or a second persistence strategy, touches one adapter. The layering is checked by CI, so
it survives contact with future changes.

**Bad.** Two representations of an authorization and a mapper between them: real code, real
lines, a real place to introduce a bug (mitigated by round-trip persistence tests). The
domain carries a `version` field that exists only because the persistence adapter needs
it — an honest leak, called out here rather than hidden. Single-module packaging means
nothing but a test stops an adapter import creeping into the domain.

**Rejected.** JPA-annotated aggregates (see above); a multi-module reactor (cost without
benefit at this size); a `port`/`adapter` interface for every collaborator (ceremony);
MapStruct (a code generator and an annotation processor to replace 150 lines of code that
a reviewer can read).

## Verified by

`DomainPurityTest`, `AuthorizationTest` (runs with no Spring context at all),
`AuthorizationPersistenceIT` (domain → entity → domain round trip preserves every field).
