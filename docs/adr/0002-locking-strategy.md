# ADR-0002: Unique-constraint arbitration for creation, optimistic locking for transitions

* Status: Accepted
* Date: 2026-09-23

## Context

Two different races, often conflated:

1. **Creation race** — N identical `POST /v1/authorizations` arrive at once. Exactly one
   authorization may be created.
2. **Transition race** — a capture and a void (or two captures) arrive for the same
   authorization at once. Exactly one may win, and the loser must not silently overwrite
   the winner.

The hard constraint over both: **the acquirer HTTP call must not run inside a database
transaction.** A `SELECT ... FOR UPDATE` held across a network round trip pins a pooled
connection for its duration. With a HikariCP pool of 10 and 50 concurrent duplicates, the
connection pool fails before the acquirer does, and the symptom is a `500` from a
connection-acquisition timeout rather than the correct, boring `409`.

## Decision

**Creation: let the unique index arbitrate.** `INSERT INTO idempotency_record ... ON
CONFLICT (merchant_id, idempotency_key) DO NOTHING`, in a transaction that touches nothing
else and commits immediately. The winner is whoever inserted a row. Postgres already
serialises concurrent inserts on a unique index; a second locking mechanism on top of it
would be ceremony. Losers read the existing row and either replay it, reject it (`422`), or
report it in progress (`409`).

**Transitions: JPA `@Version` optimistic locking.** Capture and void read the
authorization, call the acquirer *outside* any transaction, then apply the state change in
a short transaction guarded by the version. A conflict raises
`OptimisticLockingFailureException`; we re-read once and let the domain state machine
answer. The loser of a capture/void race therefore receives `409
INVALID_STATE_TRANSITION` with the current status — which is the truthful answer, not an
error we invented to paper over a lost update.

**Sweepers: `FOR UPDATE SKIP LOCKED LIMIT n`.** Background jobs claim work without
blocking each other, so running two instances needs no leader election.

## Why optimistic rather than pessimistic for transitions

* Contention is genuinely rare: two operators capturing the same authorization in the same
  millisecond is an anomaly, not a workload. Paying a lock on every capture to serialise an
  anomaly is the wrong trade.
* The alternative that *would* justify pessimistic locking — holding the row across the
  acquirer capture call — is exactly the thing we refuse to do.
* The failure mode is benign. Both racers may reach the acquirer, but both carry the same
  downstream idempotency key (`{authorizationId}:capture`), so the acquirer captures once.
  Locally, one commit wins and the other becomes a `409`. **This is load-bearing: without
  the downstream idempotency key, optimistic locking here would permit a duplicate capture
  at the acquirer.**
* Optimistic locking needs no lock timeout tuning, cannot deadlock, and cannot leak a lock
  if the process dies.

## Consequences

**Good.** No application-level locks anywhere on the hot path. No transaction spans a
network call. Connection-pool usage is bounded by DB work, not by acquirer latency.
Multi-instance safe with no coordination service.

**Bad.** Concurrent duplicates get a `409` and must retry, instead of transparently
receiving the winner's response. (Blocking the loser on `SELECT ... FOR UPDATE` until the
winner commits would give a nicer contract — 50 requests, 50 identical `201`s — but the
winner is not holding a transaction during the acquirer call, so there is nothing to block
on; implementing it would mean polling, and a polling request thread is a request thread
not serving anyone.) Optimistic-lock retries must be written carefully so a transient
conflict is not reported as a server error.

**Rejected.** `SERIALIZABLE` isolation (retry storms, no benefit once uniqueness is
enforced by an index); advisory locks (session-scoped, leak on crash, and duplicate what
the unique index already gives); application-level distributed locks (a second source of
truth, and Redis is out of scope by requirement).

## Verified by

`ConcurrentAuthorizationIT` (50 parallel duplicates → 1 row, 1 acquirer call, 1×201,
49×409), `ConcurrentTransitionIT` (parallel capture + void → one wins, one 409),
`OptimisticLockingIT`.
