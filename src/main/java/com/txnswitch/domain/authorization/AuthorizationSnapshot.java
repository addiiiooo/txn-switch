/* SPDX-License-Identifier: MIT */
package com.txnswitch.domain.authorization;

import com.txnswitch.domain.card.CardBrand;
import java.time.Instant;
import java.util.UUID;

/**
 * Flat projection of an {@link Authorization}, for adapters that have to store one.
 *
 * <p>The aggregate refuses to expose a constructor that skips its invariants, but persistence has
 * to rebuild an object that was already valid when it was written. A snapshot makes that boundary
 * explicit and one shape wide, instead of adding a twenty-argument factory that anyone could call.
 */
public record AuthorizationSnapshot(
    UUID id,
    String merchantId,
    String merchantReference,
    long amountMinorUnits,
    String currencyCode,
    String cardBin,
    String cardLast4,
    CardBrand cardBrand,
    int cardExpiryMonth,
    int cardExpiryYear,
    String cardFingerprint,
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
    long version) {}
