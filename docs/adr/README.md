# Architecture decision records

Short records of the decisions that shape this service, written so a reviewer can
disagree with a specific choice rather than the whole design. Each one states what was
decided, what it costs, what was rejected, and which tests prove it.

| # | Decision | The uncomfortable part |
|---|---|---|
| [0001](0001-idempotency.md) | Idempotency via a claimed key, a request fingerprint and a lease | A retry inside the lease window gets a `409`, not the winner's answer |
| [0002](0002-locking-strategy.md) | Unique-constraint arbitration for creation, optimistic locking for transitions | Correctness of concurrent captures depends on the *downstream* idempotency key |
| [0003](0003-error-contract.md) | RFC 9457 problem documents with a stable machine-readable code | A decline is `201 DECLINED`, not `402` |
| [0004](0004-resilience-policy.md) | Timeouts, bounded jittered retries, circuit breaker | Read timeouts are retried — only safe because the acquirer deduplicates |
| [0005](0005-money-representation.md) | Minor units in a `long` + ISO-4217 currency | A fractional `amount` is rejected, never rounded |
| [0006](0006-layering.md) | Pure domain, three ports, no gratuitous interfaces | ~150 lines of hand-written mappers, paid on purpose |
