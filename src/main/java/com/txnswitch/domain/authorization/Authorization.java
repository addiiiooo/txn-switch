/* SPDX-License-Identifier: MIT */
package com.txnswitch.domain.authorization;

import com.txnswitch.domain.acquirer.AcquirerDecision;
import com.txnswitch.domain.card.CardDetails;
import com.txnswitch.domain.money.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The aggregate: one authorization and its lifecycle.
 *
 * <p>Two rules are enforced here rather than anywhere else, because they are the two that must hold
 * however the object is reached:
 *
 * <ol>
 *   <li>An {@code Authorization} cannot be constructed without an acquirer decision. There is no
 *       public constructor, and the factories take one.
 *   <li>Every state change goes through {@link #applyTransition}, which consults {@link
 *       AuthorizationStatus#canTransitionTo} and refuses anything the state machine does not allow.
 * </ol>
 *
 * <p>Expiry is evaluated against a clock supplied by the caller, so a lapsed hold is rejected
 * whether or not the background sweeper has caught up with it.
 */
public final class Authorization {

  private final AuthorizationId id;
  private final String merchantId;
  private final String merchantReference;
  private final Money amount;
  private final CardDetails card;
  private final String acquirerName;
  private final String acquirerReference;
  private final String approvalCode;
  private final String declineCode;
  private final String declineMessage;
  private final Instant createdAt;
  private final Instant expiresAt;

  private AuthorizationStatus status;
  private Instant updatedAt;
  private Instant capturedAt;
  private Instant voidedAt;
  private long version;

  private Authorization(
      AuthorizationId id,
      String merchantId,
      String merchantReference,
      Money amount,
      CardDetails card,
      AuthorizationStatus status,
      String acquirerName,
      String acquirerReference,
      String approvalCode,
      String declineCode,
      String declineMessage,
      Instant createdAt,
      Instant updatedAt,
      Instant expiresAt,
      Instant capturedAt,
      Instant voidedAt,
      long version) {
    this.id = Objects.requireNonNull(id, "id");
    this.merchantId = requireText(merchantId, "merchantId");
    this.merchantReference = merchantReference;
    this.amount = Objects.requireNonNull(amount, "amount");
    this.card = Objects.requireNonNull(card, "card");
    this.status = Objects.requireNonNull(status, "status");
    this.acquirerName = acquirerName;
    this.acquirerReference = acquirerReference;
    this.approvalCode = approvalCode;
    this.declineCode = declineCode;
    this.declineMessage = declineMessage;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    this.capturedAt = capturedAt;
    this.voidedAt = voidedAt;
    this.version = version;
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }

  /** An approved authorization: the only path to {@link AuthorizationStatus#AUTHORIZED}. */
  public static Authorization approved(
      AuthorizationId id,
      String merchantId,
      String merchantReference,
      Money amount,
      CardDetails card,
      AcquirerDecision.Approved decision,
      Instant now,
      Duration holdTtl) {
    Objects.requireNonNull(decision, "decision");
    return new Authorization(
        id,
        merchantId,
        merchantReference,
        amount,
        card,
        AuthorizationStatus.AUTHORIZED,
        decision.acquirerName(),
        decision.acquirerReference(),
        decision.approvalCode(),
        null,
        null,
        now,
        now,
        now.plus(holdTtl),
        null,
        null,
        0L);
  }

  /** A declined attempt: a real outcome, stored and addressable, but terminal on arrival. */
  public static Authorization declined(
      AuthorizationId id,
      String merchantId,
      String merchantReference,
      Money amount,
      CardDetails card,
      AcquirerDecision.Declined decision,
      Instant now,
      Duration holdTtl) {
    Objects.requireNonNull(decision, "decision");
    return new Authorization(
        id,
        merchantId,
        merchantReference,
        amount,
        card,
        AuthorizationStatus.DECLINED,
        decision.acquirerName(),
        decision.acquirerReference(),
        null,
        decision.declineCode(),
        decision.declineMessage(),
        now,
        now,
        now.plus(holdTtl),
        null,
        null,
        0L);
  }

  /** Rebuilds an authorization that was already valid when it was persisted. */
  public static Authorization rehydrate(AuthorizationSnapshot snapshot) {
    return new Authorization(
        new AuthorizationId(snapshot.id()),
        snapshot.merchantId(),
        snapshot.merchantReference(),
        Money.of(snapshot.amountMinorUnits(), snapshot.currencyCode()),
        new CardDetails(
            snapshot.cardBin(),
            snapshot.cardLast4(),
            snapshot.cardBrand(),
            snapshot.cardExpiryMonth(),
            snapshot.cardExpiryYear(),
            snapshot.cardFingerprint()),
        snapshot.status(),
        snapshot.acquirerName(),
        snapshot.acquirerReference(),
        snapshot.approvalCode(),
        snapshot.declineCode(),
        snapshot.declineMessage(),
        snapshot.createdAt(),
        snapshot.updatedAt(),
        snapshot.expiresAt(),
        snapshot.capturedAt(),
        snapshot.voidedAt(),
        snapshot.version());
  }

  public AuthorizationSnapshot snapshot() {
    return new AuthorizationSnapshot(
        id.value(),
        merchantId,
        merchantReference,
        amount.minorUnits(),
        amount.currencyCode(),
        card.bin(),
        card.last4(),
        card.brand(),
        card.expiryMonth(),
        card.expiryYear(),
        card.fingerprint(),
        status,
        acquirerName,
        acquirerReference,
        approvalCode,
        declineCode,
        declineMessage,
        createdAt,
        updatedAt,
        expiresAt,
        capturedAt,
        voidedAt,
        version);
  }

  /**
   * Captures the full authorized amount.
   *
   * @throws IllegalTransitionException if the current state does not permit a capture
   * @throws AuthorizationExpiredException if the hold has lapsed
   */
  public AuthorizationEvent capture(Instant now) {
    requireTransitionTo(AuthorizationStatus.CAPTURED);
    requireNotExpired(now);
    capturedAt = now;
    return applyTransition(AuthorizationStatus.CAPTURED, now, "captured in full");
  }

  /**
   * Releases the hold.
   *
   * @throws IllegalTransitionException if the current state does not permit a void
   * @throws AuthorizationExpiredException if the hold has lapsed
   */
  public AuthorizationEvent voidHold(Instant now) {
    requireTransitionTo(AuthorizationStatus.VOIDED);
    requireNotExpired(now);
    voidedAt = now;
    return applyTransition(AuthorizationStatus.VOIDED, now, "voided by the merchant");
  }

  /**
   * Marks a lapsed hold as expired. Called by the sweeper; the due check is a guard against a
   * mis-written query, not a client-facing rule.
   */
  public AuthorizationEvent expire(Instant now) {
    requireTransitionTo(AuthorizationStatus.EXPIRED);
    if (!hasLapsedAt(now)) {
      throw new IllegalStateException("authorization " + id + " is not due for expiry");
    }
    return applyTransition(AuthorizationStatus.EXPIRED, now, "hold lapsed at " + expiresAt);
  }

  private void requireTransitionTo(AuthorizationStatus target) {
    if (!status.canTransitionTo(target)) {
      throw new IllegalTransitionException(status, target);
    }
  }

  private void requireNotExpired(Instant now) {
    if (hasLapsedAt(now)) {
      throw new AuthorizationExpiredException(expiresAt);
    }
  }

  private AuthorizationEvent applyTransition(
      AuthorizationStatus target, Instant now, String detail) {
    AuthorizationStatus previous = status;
    status = target;
    updatedAt = now;
    return new AuthorizationEvent(previous, target, now, detail);
  }

  /** True once the hold is past its expiry instant, regardless of what the status column says. */
  public boolean hasLapsedAt(Instant now) {
    return !now.isBefore(expiresAt);
  }

  public AuthorizationId id() {
    return id;
  }

  public String merchantId() {
    return merchantId;
  }

  public String merchantReference() {
    return merchantReference;
  }

  public Money amount() {
    return amount;
  }

  public CardDetails card() {
    return card;
  }

  public AuthorizationStatus status() {
    return status;
  }

  public String acquirerName() {
    return acquirerName;
  }

  public String acquirerReference() {
    return acquirerReference;
  }

  public String approvalCode() {
    return approvalCode;
  }

  public String declineCode() {
    return declineCode;
  }

  public String declineMessage() {
    return declineMessage;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  public Instant expiresAt() {
    return expiresAt;
  }

  public Instant capturedAt() {
    return capturedAt;
  }

  public Instant voidedAt() {
    return voidedAt;
  }

  public long version() {
    return version;
  }

  @Override
  public String toString() {
    return "Authorization[" + id + " " + status + " " + amount + "]";
  }
}
