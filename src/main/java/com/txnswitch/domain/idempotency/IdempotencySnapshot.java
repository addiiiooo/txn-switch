/* SPDX-License-Identifier: MIT */
package com.txnswitch.domain.idempotency;

import java.time.Instant;
import java.util.UUID;

/** Flat projection of an {@link IdempotencyRecord} for persistence adapters. */
public record IdempotencySnapshot(
    UUID id,
    String merchantId,
    String idempotencyKey,
    String requestFingerprint,
    UUID authorizationId,
    IdempotencyState state,
    Instant leaseExpiresAt,
    int attempts,
    boolean downstreamAttempted,
    Integer responseStatus,
    String responseBody,
    Instant createdAt,
    Instant expiresAt) {}
