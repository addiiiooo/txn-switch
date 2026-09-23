# ADR-0003: RFC 9457 problem documents with a stable machine-readable code

* Status: Accepted
* Date: 2026-09-23

## Context

The consumer of this API is a merchant's server, not a human. It must decide, from the
response alone and without parsing prose: *did this work, should I retry, and may I reuse
my idempotency key?* Free-form `{"error": "something went wrong"}` forces string matching
and breaks the day someone improves the wording.

## Decision

**Every failure response is `application/problem+json` per RFC 9457**, produced by one
`@RestControllerAdvice`, with these members:

| member | notes |
|---|---|
| `type` | `https://txn-switch.dev/problems/{code}` — a stable identifier, documented as not necessarily dereferenceable |
| `title`, `status`, `detail` | human-readable; `detail` never contains a PAN, a key, or a stack trace |
| `code` | **`UPPER_SNAKE_CASE`, the contract.** The catalogue in `docs/errors.md` is the normative list |
| `correlationId` | always present; the same id is in the logs and in `authorization_event` |
| `errors[]` | field-level validation failures: `{ field, code, message }` |
| `currentStatus` | on state-machine rejections, so the client learns the truth without a follow-up `GET` |
| `retryAfter` | seconds, mirrored in the `Retry-After` header |

Supporting decisions:

* **`code` is stable; `title`/`detail` are not.** Reworded prose is not a breaking change;
  a renamed `code` is. The catalogue states this explicitly.
* **A decline is not a problem document.** An acquirer decline is a business outcome, so it
  returns `201 Created` with `"status": "DECLINED"` and a `decline` object. The resource
  exists, has a `Location`, is fetchable, and replays under its idempotency key. Stripe
  returns `402 card_declined`; that is a defensible alternative, but it conflates "the
  issuer said no" with "I could not process your request", and it makes the idempotent
  replay of a decline awkward. Recorded here because it is the first thing a reviewer will
  challenge.
* **Retryability is documented per code**, together with whether retrying with the *same*
  idempotency key is safe. For `503`/`504` it is safe and is the recommended action, since
  the lease and the pre-allocated downstream key (ADR-0001) exist precisely for that.
* **One catch-all.** Any unmapped exception becomes `500 INTERNAL_ERROR` with the
  correlation id and nothing else. No message, no class name, no stack trace — an
  exception message is where PANs and connection strings leak.
* **Spring's own failures are mapped too** — malformed JSON, wrong content type,
  unsupported method, missing header — so there is no route through the framework that
  returns a non-problem body. A test walks the catalogue and asserts the content type.

## Consequences

**Good.** Clients branch on one field. The catalogue is the API contract for errors and can
be reviewed on its own. Correlation ids make a production report ("order 1234 failed at
10:15") a single log query.

**Bad.** Every new failure mode costs a catalogue entry and a mapping — deliberate
friction, to stop the catalogue drifting from the code. The `type` URIs point at a domain
that is not served; a reviewer may prefer them to resolve to the published docs, which is
a one-line change once the repository URL is fixed.

## Verified by

`ProblemDetailIT` (content type and required members for every catalogued code),
`PanRedactionTest` (no PAN in any problem body), `ErrorCatalogueTest` (every `ErrorCode`
enum constant appears in `docs/errors.md`, and vice versa — the doc cannot rot silently).
