/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.txnswitch.domain.authorization.Authorization;
import com.txnswitch.domain.authorization.AuthorizationStatus;
import java.time.Instant;

/**
 * The representation of an authorization.
 *
 * <p>A decline is carried here, with {@code status} {@code DECLINED} and a {@code decline} object,
 * rather than as an error: the issuer saying no is an outcome, and the attempt is a resource that
 * exists and can be fetched and replayed (ADR-0003).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthorizationResponse(
    String id,
    AuthorizationStatus status,
    long amount,
    String currency,
    String merchantId,
    String merchantReference,
    Card card,
    Acquirer acquirer,
    Decline decline,
    Instant createdAt,
    Instant expiresAt,
    Instant capturedAt,
    Instant voidedAt) {

  public static AuthorizationResponse from(Authorization authorization) {
    return new AuthorizationResponse(
        authorization.id().toString(),
        authorization.status(),
        authorization.amount().minorUnits(),
        authorization.amount().currencyCode(),
        authorization.merchantId(),
        authorization.merchantReference(),
        new Card(
            authorization.card().brand().name(),
            authorization.card().bin(),
            authorization.card().last4(),
            authorization.card().expiryMonth(),
            authorization.card().expiryYear()),
        new Acquirer(
            authorization.acquirerName(),
            authorization.acquirerReference(),
            authorization.approvalCode()),
        authorization.declineCode() == null
            ? null
            : new Decline(authorization.declineCode(), authorization.declineMessage()),
        authorization.createdAt(),
        authorization.expiresAt(),
        authorization.capturedAt(),
        authorization.voidedAt());
  }

  /** The BIN and the last four digits: what is kept of a card, and all that is ever returned. */
  public record Card(String brand, String bin, String last4, int expiryMonth, int expiryYear) {}

  public record Acquirer(String name, String reference, String approvalCode) {}

  public record Decline(String code, String message) {}
}
