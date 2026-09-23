# Error catalogue

Every failure response from `txn-switch` is an RFC 9457 problem document with the media
type `application/problem+json`. This file is the **normative list of error codes**: the
`code` member is the API contract, `title` and `detail` are not.

```json
{
  "type": "https://github.com/addiiiooo/txn-switch/blob/main/docs/errors.md#idempotency_key_reuse",
  "title": "Idempotency key reused with a different request",
  "status": 422,
  "detail": "Idempotency-Key 'k-2f81' was first used for a request with a different body.",
  "code": "IDEMPOTENCY_KEY_REUSE",
  "correlationId": "1f0c8b52-7b9a-4a1e-9a0e-2d0a3c7b4e11"
}
```

## Contract

* **`code` is stable.** It is `UPPER_SNAKE_CASE`, it never changes meaning, and it is what
  clients branch on. Renaming one is a breaking change.
* **`title` and `detail` are human-readable and may be reworded at any time.** Do not match
  on them.
* **`type`** dereferences to the matching section of this file:
  `https://github.com/addiiiooo/txn-switch/blob/main/docs/errors.md#{lowercased code}`.
  The base is configurable (`PROBLEM_TYPE_BASE_URI`) so a fork or a published docs site can
  point elsewhere without changing any code.
* **`correlationId`** is present on every problem document and on every log line for the
  same request. Quote it in a bug report.
* **`errors[]`** appears on `VALIDATION_FAILED`: `[{ "field": "amount", "code":
  "MUST_BE_POSITIVE", "message": "..." }]`.
* **`currentStatus`** appears on state-machine rejections, so a client does not need a
  follow-up `GET` to learn what happened.
* **`retryAfter`** (seconds) appears wherever the `Retry-After` header is set.
* Problem documents never contain a PAN, a CVV, an API key, an idempotency key belonging to
  another request, a stack trace or an exception class name.
* This file is checked against the `ErrorCode` enum by `ErrorCatalogueTest`: a code in the
  enum with no section here, or a section here with no enum constant, fails the build. The
  catalogue cannot rot.

## A decline is not an error

An acquirer decline is a **successful** API call. It returns `201 Created` with
`"status": "DECLINED"` and a `decline` object, and it replays under its idempotency key
like any other outcome. Problem documents are reserved for requests that could not be
processed. See [ADR-0003](adr/0003-error-contract.md) for why, and for the `402`
alternative that was rejected.

## Index

"Retry" = is retrying this request ever useful. "Same key" = whether the retry may reuse
the original `Idempotency-Key` (it always should, when the answer is yes).

| Code | HTTP | Retry | Same key |
|---|---|---|---|
| [`MISSING_CREDENTIALS`](#missing_credentials) | 401 | no | — |
| [`INVALID_CREDENTIALS`](#invalid_credentials) | 401 | no | — |
| [`MALFORMED_REQUEST`](#malformed_request) | 400 | no | — |
| [`VALIDATION_FAILED`](#validation_failed) | 400 | no | — |
| [`MISSING_IDEMPOTENCY_KEY`](#missing_idempotency_key) | 400 | no | — |
| [`METHOD_NOT_ALLOWED`](#method_not_allowed) | 405 | no | — |
| [`UNSUPPORTED_MEDIA_TYPE`](#unsupported_media_type) | 415 | no | — |
| [`UNSUPPORTED_CURRENCY`](#unsupported_currency) | 422 | no | — |
| [`AMOUNT_OUT_OF_RANGE`](#amount_out_of_range) | 422 | no | — |
| [`CARD_INVALID`](#card_invalid) | 422 | no | — |
| [`CARD_EXPIRED`](#card_expired) | 422 | no | — |
| [`PARTIAL_CAPTURE_NOT_SUPPORTED`](#partial_capture_not_supported) | 422 | no | — |
| [`IDEMPOTENCY_KEY_REUSE`](#idempotency_key_reuse) | 422 | no | no |
| [`IDEMPOTENCY_REQUEST_IN_PROGRESS`](#idempotency_request_in_progress) | 409 | yes | **yes** |
| [`AUTHORIZATION_NOT_FOUND`](#authorization_not_found) | 404 | no | — |
| [`INVALID_STATE_TRANSITION`](#invalid_state_transition) | 409 | no | — |
| [`AUTHORIZATION_EXPIRED`](#authorization_expired) | 409 | no | — |
| [`ACQUIRER_TIMEOUT`](#acquirer_timeout) | 504 | yes | **yes** |
| [`ACQUIRER_UNAVAILABLE`](#acquirer_unavailable) | 503 | yes | **yes** |
| [`ACQUIRER_PROTOCOL_ERROR`](#acquirer_protocol_error) | 502 | no | — |
| [`INTERNAL_ERROR`](#internal_error) | 500 | yes | **yes** |

---

## Authentication

### `MISSING_CREDENTIALS`

**401** · not retryable

No `Authorization: Bearer <api-key>` header was supplied. Every `/v1` endpoint requires
one; the merchant identity is derived from the credential and from nowhere else.
`/actuator/**` and `/__simulator/**` are deliberately unauthenticated.

### `INVALID_CREDENTIALS`

**401** · not retryable

The API key is not recognised. The response says nothing about which keys exist, how many
there are, or how close the presented one was. Retrying will not help; fix the credential.

---

## Request validation

### `MALFORMED_REQUEST`

**400** · not retryable

The body is not valid JSON, or a field has the wrong JSON type. This includes a fractional
`amount` such as `12.50`, which is **rejected rather than rounded** — see
[ADR-0005](adr/0005-money-representation.md). Fix the request.

### `VALIDATION_FAILED`

**400** · not retryable

One or more fields failed validation. The `errors[]` array names each offending field with
its own code, so a client can map failures onto its own form or queue.

### `MISSING_IDEMPOTENCY_KEY`

**400** · not retryable

`POST /v1/authorizations` requires an `Idempotency-Key` header of 8–255 characters.
Generate one per **logical request** (a UUID is ideal) and reuse it for every retry of that
request — that is what makes the retry safe.

### `METHOD_NOT_ALLOWED`

**405** · not retryable

Wrong HTTP method for this path. The `Allow` header lists what is accepted.

### `UNSUPPORTED_MEDIA_TYPE`

**415** · not retryable

Send `Content-Type: application/json`.

### `UNSUPPORTED_CURRENCY`

**422** · not retryable

The `currency` is not a valid ISO-4217 code, is a pseudo-currency with no minor unit
(`XXX`, metals), or is not in this deployment's `SUPPORTED_CURRENCIES` allow-list. A real
switch is certified per currency; this mirrors that.

### `AMOUNT_OUT_OF_RANGE`

**422** · not retryable

`amount` must be a positive integer in **minor units**, at most 10^13. `1250` with
`"currency": "USD"` means USD 12.50. Zero and negative amounts are rejected.

### `CARD_INVALID`

**422** · not retryable

The PAN failed the Luhn check or is not 12–19 digits. The PAN is never echoed back in the
problem document.

### `CARD_EXPIRED`

**422** · not retryable

The card's expiry date is in the past.

### `PARTIAL_CAPTURE_NOT_SUPPORTED`

**422** · not retryable

A capture was requested with an `amount` different from the authorized amount. Partial and
multiple capture are out of scope; capture the full amount or void the authorization. This
is an explicit refusal rather than a silent full capture, because silently capturing a
different amount than the client asked for is a money bug.

---

## Idempotency

### `IDEMPOTENCY_KEY_REUSE`

**422** · not retryable · do **not** reuse this key

This `Idempotency-Key` was already used for a request with a **different** body. The
original authorization is untouched and remains retrievable. Use a new key for a new
request. 422 rather than 409 because the key is well-formed and unambiguous — it is the
pairing of key and payload we refuse.

### `IDEMPOTENCY_REQUEST_IN_PROGRESS`

**409** · retryable · **reuse the same key**

An identical request with this key is being processed right now. Wait `Retry-After`
seconds and send the same request with the same key; you will receive the original
response. **This is the expected answer for concurrent duplicates** — when N identical
requests race, one creates the authorization and the rest get this.

---

## Resource and state

### `AUTHORIZATION_NOT_FOUND`

**404** · not retryable

No authorization with that id exists for the authenticated merchant. This is also the
answer when the id exists under a *different* merchant: confirming existence across tenants
would itself be a leak, so there is no `403`.

### `INVALID_STATE_TRANSITION`

**409** · not retryable

The operation is illegal from the current state — capturing a captured authorization,
voiding a declined one, and so on. `currentStatus` carries the truth, so no follow-up `GET`
is needed. This is also the answer given to the loser of a concurrent capture/void race,
which is why a duplicate capture cannot take money twice.

### `AUTHORIZATION_EXPIRED`

**409** · not retryable

The hold passed its `expiresAt` and can no longer be captured or voided. Returned even if
the background expiry sweeper has not yet written `EXPIRED` — the domain checks the clock,
so the sweeper is never the source of truth.

---

## Downstream acquirer

These are the retryable ones. All three release the idempotency lease, so retrying with the
**same** key is safe and correct: the retry reuses the same pre-allocated downstream
identifier, which the acquirer deduplicates. See
[ADR-0004](adr/0004-resilience-policy.md).

### `ACQUIRER_TIMEOUT`

**504** · retryable · **reuse the same key**

The acquirer did not answer within the per-attempt read timeout, on any of the bounded
attempts. The outcome is genuinely unknown — it may have approved a hold we never heard
about. Retry with the same key; that is what resolves it.

### `ACQUIRER_UNAVAILABLE`

**503** · retryable · **reuse the same key**

Either the circuit breaker is open, or the acquirer returned `502`/`503`/`504` on every
attempt. Honour `Retry-After`; retrying sooner will be refused by the breaker anyway.

### `ACQUIRER_PROTOCOL_ERROR`

**502** · not retryable

The acquirer answered, but with something we could not interpret — or it rejected our
request as malformed. A retry would produce the same answer, so we do not retry. This is a
bug on one side or the other; quote the `correlationId`.

---

## Server

### `INTERNAL_ERROR`

**500** · retryable · **reuse the same key**

An unhandled failure. The response deliberately carries nothing but the code and the
`correlationId`: an exception message is exactly where a PAN or a connection string leaks.
If the request was an authorization, retrying with the same key is safe.

---

## Idempotency key lifetime

Idempotency records are kept for **24 hours** and then purged. A key reused after that
window is treated as a brand-new request and **will create a second authorization**. This
is a deliberate trade — bounded retention against unbounded storage — and not an oversight;
clients that retry days later must use a new key and reconcile.
