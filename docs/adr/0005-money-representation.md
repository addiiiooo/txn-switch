# ADR-0005: Money as minor units in a `long`, with an ISO-4217 currency

* Status: Accepted
* Date: 2026-09-23

## Context

Money bugs are silent. A rounding error does not throw; it produces a plausible number
that is wrong by a cent, and nobody notices until reconciliation. The representation has
to make the wrong thing hard to express, at the edge as well as in the database.

## Decision

**`Money = long minorUnits + java.util.Currency`**, as an immutable value object in the
domain.

* **Minor units, not `BigDecimal`.** A card authorization is an integral number of the
  currency's smallest unit; there is no fractional cent to represent. `BigDecimal` would
  be *correct* but invites `setScale`, comparison-by-`equals` (`2.50` ≠ `2.5`), and a
  scale-per-instance that must be normalised before every comparison. A `long` of minor
  units has one representation per value, compares with `==`, and maps to `BIGINT` without
  a conversion. `double` is not considered: `0.1 + 0.2` disqualifies it.
* **Currency from `java.util.Currency`**, so ISO-4217 validity is the JDK's problem, not a
  hand-maintained enum. Codes the JDK does not know, and pseudo-currencies with negative
  default fraction digits (`XXX`, metals), are rejected with `422 UNSUPPORTED_CURRENCY`.
  An additional configurable allow-list (`SUPPORTED_CURRENCIES`, default
  `USD,EUR,GBP,JPY,INR`) reflects that a real switch is certified for specific currencies.
  `JPY` is in the default set on purpose: a zero-decimal currency is where "just divide by
  100" implementations break.
* **The API speaks minor units as a JSON integer.** `{"amount": 1250, "currency": "USD"}`
  is USD 12.50. A fractional JSON number (`12.50`) is **rejected at deserialisation**, not
  rounded — a client sending `12.50` believes something different from what we would
  store, and silently agreeing with a rounding is how a 100× error ships. The field name
  and the convention are spelled out in the OpenAPI description and the README.
* **Arithmetic is closed and checked.** `Money` refuses to operate across currencies, and
  addition uses `Math.addExact` so an overflow throws instead of wrapping. Amounts are
  bounded at 10^13 minor units at the edge, well inside `long`, so sums over a merchant's
  day cannot overflow either.
* **Persistence: `amount_minor BIGINT NOT NULL CHECK (amount_minor > 0)` + `currency
  CHAR(3)`.** The check constraint is the last line of defence if a future code path
  forgets. Currency is stored beside every amount; there is no implicit "system currency".
* **Formatting is a presentation concern.** The domain never renders money as a string;
  the API returns the integer and the code, and the client formats. Anything else bakes a
  locale into a payment record.

## Consequences

**Good.** One canonical representation from HTTP to disk. Equality, comparison and
serialisation are trivial and total. Zero-decimal and three-decimal currencies need no
special casing. The database refuses a negative amount independently of the application.

**Bad.** Minor units surprise developers who expect `12.50`; this is mitigated by
documentation and by rejecting decimals loudly rather than accepting them. `long` minor
units are wrong for instruments that need sub-minor precision (FX rates, interchange
fees) — out of scope here, and the day they arrive they need a different type, not a
widened one.

**Rejected.** `BigDecimal` with an enforced scale (correct, more ceremony, easier to
misuse at the boundaries); Joda-Money/JavaMoney (a dependency and a dialect for one value
object with three operations); storing a decimal string (unsortable, unsummable in SQL).

## Verified by

`MoneyTest` (construction, currency validation, zero-decimal handling, overflow,
cross-currency rejection), `AuthorizationRequestDeserialisationTest` (a fractional
`amount` is a `400`, not a rounding), and the `CHECK` constraint exercised in
`AuthorizationPersistenceIT`.
