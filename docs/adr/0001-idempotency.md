# ADR-0001: Idempotency via a claimed key, a request fingerprint and a lease

* Status: Accepted
* Date: 2026-09-23
* Deciders: Aditya Sumanth

## Context

`POST /v1/authorizations` moves money. Clients retry — on connection resets, on gateway
timeouts, on a user double-clicking. A retry must never produce a second hold on the
cardholder's funds, and it must give the client the *same* answer as the first attempt,
because the client may have never seen that answer.

Two things make this harder than a `INSERT ... ON CONFLICT`:

1. The outcome is decided by a third party (the acquirer) over a network that can fail
   *after* it has done the work.
2. We can die halfway through. A dedup marker written before the acquirer call is a
   liability if we never come back to finish it.

## Decision

**Store an idempotency record, keyed `UNIQUE (merchant_id, idempotency_key)`, that holds
the request fingerprint, a pre-allocated authorization id, a lease, and — once known — the
verbatim response bytes.**

* **Scope.** The key is namespaced by merchant. A global key space lets one tenant's key
  collide with (or probe for) another's.
* **Fingerprint.** SHA-256 over `method + path + canonical JSON body`, where canonical
  means sorted keys and no insignificant whitespace. Key order and formatting must not
  change the hash; any value change must. A mismatch means the client reused a key for a
  different request: **`422 IDEMPOTENCY_KEY_REUSE`**. 422 rather than 409 because the key
  itself is well-formed and unambiguous — it is the *semantics* of the pairing we refuse
  (RFC 9110 §15.5.21). The original request is left completely untouched.
* **Pre-allocated id.** The authorization's UUIDv7 is allocated when the key is claimed,
  before anything is sent downstream, and is used as the acquirer's own idempotency key.
  This is what makes retries and takeovers safe rather than merely hopeful.
* **Lease, not a lock.** The claim carries `lease_expires_at` (default 30 s, comfortably
  longer than the ~2.5 s downstream budget). A live lease means "someone is working on
  this" → `409 IDEMPOTENCY_REQUEST_IN_PROGRESS` + `Retry-After`. An expired lease can be
  taken over with a conditional `UPDATE ... WHERE lease_expires_at < now()`; one row
  updated means you own it. The same mechanism handles a crashed process, a timed-out
  acquirer call and an open circuit breaker — we release the lease instead of deleting the
  record, so the pre-allocated id survives and the next attempt still deduplicates
  downstream.
* **Store bytes, replay bytes.** `response_body` is `text`, not `jsonb`, and is written by
  the same serialiser that produced the original response. A replay returns the original
  status code (`201`, not `200`) plus `Idempotency-Replayed: true`. `jsonb` would
  normalise the document and a replay would no longer be byte-identical.
* **Atomic completion.** The authorization row and the completed idempotency record are
  written in one transaction. There is no window in which a charge exists without its
  dedup record.
* **TTL.** Records expire after 24 h and are purged. After that, the same key is a new
  request. This is documented in `docs/errors.md` rather than hidden.

## Consequences

**Good.** One row, one unique index, and no distributed coordination gives exactly-once
*effects*. Crash recovery and downstream-failure recovery use one mechanism. The contract
is provable: replay, 422 on reuse, 409 while in progress, takeover after a lease expiry.

**Bad.** Every authorization costs an extra write and an extra round trip to Postgres. A
client that retries within the lease window gets a `409` it must handle — we have made the
client's life slightly harder in exchange for never holding a request thread hostage
(ADR-0002). The 24 h TTL is a real, if remote, edge: a client retrying after 24 h creates a
second authorization.

**Rejected.**

* *Dedup on a natural key* (`merchant_id + merchant_reference + amount`) — merchants reuse
  references, and two genuinely distinct charges for the same amount are legal.
* *Dedup in an in-memory cache* — lost on restart, wrong across instances. The database is
  already the arbiter of truth; adding Redis would add a second one.
* *Deleting the record on failure so the key is "free"* — releases the pre-allocated id and
  reopens the double-charge hole for the exact case (a timeout) that matters most.

## Verified by

`RequestFingerprintTest`, `IdempotencyReplayIT`, `IdempotencyLeaseIT`,
`ConcurrentAuthorizationIT`.
