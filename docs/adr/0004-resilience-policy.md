# ADR-0004: Timeouts, bounded jittered retries and a circuit breaker around the acquirer

* Status: Accepted
* Date: 2026-09-23

## Context

The acquirer is the only dependency that can fail in an interesting way. It can be slow,
it can fail transiently, it can be down, and — the case that actually hurts — it can do
the work and fail to tell us. A naive client turns each of those into either a hung thread
or a double charge.

Retrying a write over a network is only safe if the receiver deduplicates. That precondition
is established in ADR-0001: every acquirer call carries the pre-allocated authorization id
as its idempotency key. **Everything in this ADR depends on that.** Without it, retrying a
read timeout is a double-charge bug, and the correct policy would be "retry connect
failures only".

## Decision

**A real HTTP hop with real socket timeouts.** The acquirer is called with a `RestClient`
configured with a 300 ms connect timeout and an 800 ms read timeout per attempt. The
simulator is a separate HTTP endpoint (in-process, flag-guarded) rather than a stubbed
bean, so the timeout path under test is the timeout path that runs in production.

**Bounded retries with jittered exponential backoff.** 3 attempts total, 50 ms base, ×2
multiplier, ±50 % jitter, capped at 400 ms — worst case ≈ 2.5 s, which is the number the
lease TTL (30 s) and the client-facing timeout budget are set against. Jitter is not
decoration: without it, N clients that failed together retry together and re-create the
spike that caused the failure.

**Retry only where a retry is both safe and useful:**

| Retry | Do not retry |
|---|---|
| connect timeout, connection refused | HTTP `400`, `401`, `422` from the acquirer |
| read timeout *(safe only because of the downstream idempotency key)* | an acquirer **decline** |
| HTTP `502`, `503`, `504` | `CallNotPermittedException` (breaker open) |
| HTTP `429` (honouring `Retry-After`) | anything a retry cannot change |

A decline is a final business answer. Retrying it is not resilience, it is a second
attempt to take someone's money.

**Circuit breaker (Resilience4j), count-based:** window 20, failure-rate threshold 50 %,
slow-call threshold 50 % at 1 s, 10 s in open, 3 permitted calls in half-open, automatic
transition. Slow calls count, because an acquirer answering in 5 s is down for our
purposes. When the breaker is open we return `503 ACQUIRER_UNAVAILABLE` with `Retry-After`
in ~microseconds and release the idempotency lease, so the same key can be retried later
(ADR-0001).

**Decorator order: `Retry(CircuitBreaker(call))`** — Resilience4j's default aspect order,
made explicit rather than inherited by accident. Each attempt is recorded by the breaker,
so sustained failure opens it after roughly ten calls rather than thirty; and because
`CallNotPermittedException` is excluded from the retry predicate, an open breaker fails
immediately instead of sleeping through three useless backoffs. The inverse order
(`CircuitBreaker(Retry(call))`) would record one aggregate failure per request and make
the breaker three times slower to notice an outage.

**Failure is not persisted as an authorization.** No `FAILED` status exists. Exhausted
retries release the lease and return `504 ACQUIRER_TIMEOUT` or `503 ACQUIRER_UNAVAILABLE`;
the authorization row is created only when the acquirer gave a definitive answer. What we
may have leaked — a hold created by a call we never heard back from — is tracked on the
idempotency record (`downstream_attempted`). Automatic compensation (a reversal sweeper)
is **out of scope**; instead the residual exposure is measured by the
`txnswitch_unresolved_attempts` gauge, which should be zero. An unmeasured gap is a lie; a
measured one is a backlog item.

## Consequences

**Good.** Worst-case client-visible latency is bounded and computable. An acquirer outage
costs ~10 wasted calls, then near-zero-cost failures until it recovers. Every behaviour is
independently testable through magic PANs and the simulator's control endpoint, without
`Thread.sleep` in test code.

**Bad.** The timeout constants are guesses calibrated against a loopback simulator, not
against a real acquirer; they are configuration, and the README says so. Retrying read
timeouts is only defensible while the downstream honours our key — a coupling that is
documented in three places because forgetting it is how this becomes a double-charge
incident. The breaker is per-process: five instances need five outages' worth of failed
calls to all open.

**Rejected.** Unbounded or long retry chains (they convert an acquirer problem into a
thread-pool problem); a bulkhead/`TimeLimiter` thread pool on top (the socket timeout
already bounds the call, and an extra pool is another thing to size wrongly); retrying
declines; in-process stubbing of the acquirer (does not exercise real timeouts).

## Verified by

`AcquirerRetryIT` (attempt counts for retryable vs non-retryable), `AcquirerTimeoutIT`,
`CircuitBreakerIT` (opens under sustained failure, fails fast while open, recovers through
half-open).
