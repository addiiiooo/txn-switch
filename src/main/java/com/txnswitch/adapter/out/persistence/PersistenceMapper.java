/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter.out.persistence;

import com.txnswitch.domain.authorization.Authorization;
import com.txnswitch.domain.authorization.AuthorizationSnapshot;
import com.txnswitch.domain.idempotency.IdempotencyRecord;
import com.txnswitch.domain.idempotency.IdempotencySnapshot;

/**
 * Hand-written mapping between the aggregates and their storage shapes.
 *
 * <p>About a hundred lines of boring code, paid deliberately so the domain owes nothing to JPA
 * (ADR-0006). A round-trip test covers it, because boring code is exactly where a dropped field
 * hides.
 */
final class PersistenceMapper {

  private PersistenceMapper() {}

  /** Version is left null so Spring Data persists rather than merges. */
  static AuthorizationEntity toNewEntity(AuthorizationSnapshot s) {
    return toEntity(s, null);
  }

  static AuthorizationEntity toEntity(AuthorizationSnapshot s) {
    return toEntity(s, s.version());
  }

  private static AuthorizationEntity toEntity(AuthorizationSnapshot s, Long version) {
    return new AuthorizationEntity(
        s.id(),
        s.merchantId(),
        s.merchantReference(),
        s.amountMinorUnits(),
        s.currencyCode(),
        s.status(),
        s.cardBin(),
        s.cardLast4(),
        s.cardBrand(),
        (short) s.cardExpiryMonth(),
        (short) s.cardExpiryYear(),
        s.cardFingerprint(),
        s.acquirerName(),
        s.acquirerReference(),
        s.approvalCode(),
        s.declineCode(),
        s.declineMessage(),
        s.createdAt(),
        s.updatedAt(),
        s.expiresAt(),
        s.capturedAt(),
        s.voidedAt(),
        version);
  }

  static Authorization toDomain(AuthorizationEntity e) {
    return Authorization.rehydrate(
        new AuthorizationSnapshot(
            e.getId(),
            e.getMerchantId(),
            e.getMerchantReference(),
            e.getAmountMinor(),
            e.getCurrency(),
            e.getCardBin(),
            e.getCardLast4(),
            e.getCardBrand(),
            e.getCardExpMonth(),
            e.getCardExpYear(),
            e.getCardFingerprint(),
            e.getStatus(),
            e.getAcquirerName(),
            e.getAcquirerReference(),
            e.getApprovalCode(),
            e.getDeclineCode(),
            e.getDeclineMessage(),
            e.getCreatedAt(),
            e.getUpdatedAt(),
            e.getExpiresAt(),
            e.getCapturedAt(),
            e.getVoidedAt(),
            e.getVersion() == null ? 0L : e.getVersion()));
  }

  static IdempotencyRecord toDomain(IdempotencyRecordEntity e) {
    return IdempotencyRecord.rehydrate(
        new IdempotencySnapshot(
            e.getId(),
            e.getMerchantId(),
            e.getIdempotencyKey(),
            e.getRequestFingerprint(),
            e.getAuthorizationId(),
            e.getState(),
            e.getLeaseExpiresAt(),
            e.getAttempts(),
            e.isDownstreamAttempted(),
            e.getResponseStatus(),
            e.getResponseBody(),
            e.getCreatedAt(),
            e.getExpiresAt()));
  }
}
