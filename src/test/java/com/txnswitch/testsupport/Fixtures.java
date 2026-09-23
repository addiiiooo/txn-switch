/* SPDX-License-Identifier: MIT */
package com.txnswitch.testsupport;

import com.txnswitch.domain.acquirer.AcquirerDecision;
import com.txnswitch.domain.authorization.Authorization;
import com.txnswitch.domain.authorization.AuthorizationId;
import com.txnswitch.domain.authorization.AuthorizationSnapshot;
import com.txnswitch.domain.authorization.AuthorizationStatus;
import com.txnswitch.domain.card.CardBrand;
import com.txnswitch.domain.card.CardDetails;
import com.txnswitch.domain.money.Money;
import java.time.Duration;
import java.time.Instant;

/** Shared test data. Kept deliberately small: fixtures that hide the interesting values lie. */
public final class Fixtures {

  public static final String MERCHANT_ID = "m_demo";
  public static final Instant NOW = Instant.parse("2026-09-23T10:15:00Z");
  public static final Duration HOLD_TTL = Duration.ofDays(7);
  public static final String APPROVE_PAN = "4111111111111111";
  public static final String DECLINE_PAN = "4000000000000002";

  private Fixtures() {}

  public static CardDetails card() {
    return new CardDetails("411111", "1111", CardBrand.VISA, 12, 2030, "f".repeat(64));
  }

  public static Money amount() {
    return Money.of(1250, "USD");
  }

  public static Authorization approved() {
    return Authorization.approved(
        AuthorizationId.generate(NOW),
        MERCHANT_ID,
        "order-1234",
        amount(),
        card(),
        new AcquirerDecision.Approved("sim-acquirer", "ACQ-7F3C21", "A1B2C3"),
        NOW,
        HOLD_TTL);
  }

  public static Authorization declined() {
    return Authorization.declined(
        AuthorizationId.generate(NOW),
        MERCHANT_ID,
        "order-1234",
        amount(),
        card(),
        new AcquirerDecision.Declined("sim-acquirer", "ACQ-7F3C22", "DO_NOT_HONOR", "declined"),
        NOW,
        HOLD_TTL);
  }

  /** An authorization sitting in an arbitrary status, as if loaded from the database. */
  public static Authorization inStatus(AuthorizationStatus status) {
    return inStatus(status, NOW.plus(HOLD_TTL));
  }

  public static Authorization inStatus(AuthorizationStatus status, Instant expiresAt) {
    AuthorizationSnapshot snapshot = approved().snapshot();
    return Authorization.rehydrate(
        new AuthorizationSnapshot(
            snapshot.id(),
            snapshot.merchantId(),
            snapshot.merchantReference(),
            snapshot.amountMinorUnits(),
            snapshot.currencyCode(),
            snapshot.cardBin(),
            snapshot.cardLast4(),
            snapshot.cardBrand(),
            snapshot.cardExpiryMonth(),
            snapshot.cardExpiryYear(),
            snapshot.cardFingerprint(),
            status,
            snapshot.acquirerName(),
            snapshot.acquirerReference(),
            snapshot.approvalCode(),
            snapshot.declineCode(),
            snapshot.declineMessage(),
            snapshot.createdAt(),
            snapshot.updatedAt(),
            expiresAt,
            snapshot.capturedAt(),
            snapshot.voidedAt(),
            snapshot.version()));
  }
}
