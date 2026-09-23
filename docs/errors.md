# Error catalogue

Every failure response from `txn-switch` is an RFC 9457 problem document with the media
type `application/problem+json`. This file is the **normative list of error codes**: the
`code` member is the API contract, `title` and `detail` are not.

```json
{
  "type": "https://txn-switch.dev/problems/IDEMPOTENCY_KEY_REUSE",
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
* **`type`** is `https://txn-switch.dev/problems/{code}` — a stable identifier. It is not
  guaranteed to be dereferenceable; this document is the resolution.
* **`correlationId`** is present on every problem document and on every log line for the
  same request. Quote it in a bug report.
* **`errors[]`** appears on `VALIDATION_FAILED`: `[{ "field": "amount", "code":
  "MUST_BE_POSITIVE", "message": "..." }]`.
* **`currentStatus`** appears on state-machine rejections, so a client does not need a
  follow-up `GET` to learn what happened.
* **`retryAfter`** (seconds) appears wherever the `Retry-After` header is set.
* Problem documents never contain a PAN, a CVV, an idempotency key belonging to another
  request, a stack trace or an exception class name.
* This table is checked against the `ErrorCode` enum by `ErrorCatalogueTest`: a code in the
  enum but not in this table, or the reverse, fails the build. The catalogue cannot rot.

## A decline is not an error

An acquirer decline is a **successful** API call. It returns `201 Created` with
`"status": "DECLINED"` and a `decline` object, and it replays under its idempotency key
like any other outcome. Problem documents are reserved for requests that could not be
processed. See [ADR-0003](adr/0003-error-contract.md) for why, and for the `402`
alternative that was rejected.

## Catalogue

"Retry" = is retrying this request ever useful. "Same key" = whether the retry may reuse
the original `Idempotency-Key` (it always should, when the answer is yes).

### Request validation

| Code | HTTP | Retry | Same key | Meaning and what to do |
|---|---|---|---|---|
| `MALFORMED_REQUEST` | 400 | no | — | Body is not valid JSON, or a field has the wrong JSON type — including a fractional `amount`, which is rejected rather than rounded. Fix the request. |
| `VALIDATION_FAILED` | 400 | no | — | One or more fields failed validation; see `errors[]`. |
| `MISSING_IDEMPOTENCY_KEY` | 400 | no | — | `POST /v1/authorizations` requires an `Idempotency-Key` header, 8–255 characters. Generate one per logical request (a UUID), not per attempt. |
| `MISSING_MERCHANT_ID` | 400 | no | — | `X-Merchant-Id` is required on every `/v1` request. |
| `METHOD_NOT_ALLOWED` | 405 | no | — | Wrong HTTP method for this path. |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | no | — | Send `Content-Type: application/json`. |
| `UNSUPPORTED_CURRENCY` | 422 | no | — | Not a valid ISO-4217 code, a pseudo-currency, or not in this deployment's `SUPPORTED_CURRENCIES` allow-list. |
| `AMOUNT_OUT_OF_RANGE` | 422 | no | — | `amount` must be a positive integer in **minor units**, at most 10^13. `1250` means USD 12.50. |
| `CARD_INVALID` | 422 | no | — | PAN failed the Luhn check or is not 12–19 digits. The PAN is never echoed back. |
| `CARD_EXPIRED` | 422 | no | — | The card expiry date is in the past. |
| `PARTIAL_CAPTURE_NOT_SUPPORTED` | 422 | no | — | A capture `amount` was supplied that differs from the authorized amount. Partial capture is out of scope; capture the full amount or void. |

### Idempotency

| Code | HTTP | Retry | Same key | Meaning and what to do |
|---|---|---|---|---|
| `IDEMPOTENCY_KEY_REUSE` | 422 | no | no | This key was already used for a **different** request body. The original authorization is untouched. Use a new key for a new request. |
| `IDEMPOTENCY_REQUEST_IN_PROGRESS` | 409 | yes | **yes** | An identical request with this key is being processed right now. Wait `Retry-After` seconds and send the same request with the same key; you will get the original response. This is the expected answer for concurrent duplicates. |
| `IDEMPOTENT_REQUEST_ABANDONED` | 409 | no | no | A previous attempt with this key reached the acquirer and never completed; it has since been reversed. Start a new request with a **new** key. |

### Resource and state

| Code | HTTP | Retry | Same key | Meaning and what to do |
|---|---|---|---|---|
| `AUTHORIZATION_NOT_FOUND` | 404 | no | — | No authorization with that id for this merchant. Also returned when the id exists under a different merchant — we do not confirm existence across tenants. |
| `INVALID_STATE_TRANSITION` | 409 | no | — | The operation is illegal from the current state (capturing a captured authorization, voiding a declined one, …). `currentStatus` tells you where it actually is. Also the answer to the loser of a concurrent capture/void race. |
| `AUTHORIZATION_EXPIRED` | 409 | no | — | The hold passed `expiresAt` and can no longer be captured or voided. Returned even if the expiry sweeper has not yet written `EXPIRED`. |

### Downstream acquirer

These are the retryable ones. All three release the idempotency lease, so retrying with the
**same** key is safe and correct: the retry reuses the same pre-allocated downstream
identifier, and the acquirer deduplicates it.

| Code | HTTP | Retry | Same key | Meaning and what to do |
|---|---|---|---|---|
| `ACQUIRER_TIMEOUT` | 504 | yes | **yes** | The acquirer did not answer within the per-attempt read timeout on any of the bounded attempts. The outcome is genuinely unknown; retry with the same key. |
| `ACQUIRER_UNAVAILABLE` | 503 | yes | **yes** | The circuit breaker is open, or the acquirer returned `502/503/504` on every attempt. Honour `Retry-After`. |
| `ACQUIRER_PROTOCOL_ERROR` | 502 | no | — | The acquirer answered, but with something we could not interpret (or rejected our request as malformed). Not retried — a retry would produce the same answer. This is our bug or theirs; quote the `correlationId`. |

### Server

| Code | HTTP | Retry | Same key | Meaning and what to do |
|---|---|---|---|---|
| `INTERNAL_ERROR` | 500 | yes | **yes** | An unhandled failure. The response carries nothing but the code and the `correlationId`, deliberately. If the request was an authorization, retrying with the same key is safe. |

## Idempotency key lifetime

Idempotency records are kept for **24 hours** and then purged. A key reused after that
window is treated as a brand-new request and **will create a second authorization**. This
is a deliberate trade (unbounded retention versus a bounded, documented window), not an
oversight — clients that retry days later must use a new key and reconcile.
