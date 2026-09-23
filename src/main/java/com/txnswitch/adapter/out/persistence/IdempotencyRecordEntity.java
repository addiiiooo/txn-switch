/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter.out.persistence;

import com.txnswitch.domain.idempotency.IdempotencyState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Storage shape of an idempotency claim.
 *
 * <p>Claims are inserted with a native {@code ON CONFLICT DO NOTHING} rather than through this
 * entity, because losing the race must be an ordinary return value, not an exception that poisons
 * the transaction.
 */
@Entity
@Table(name = "idempotency_records")
class IdempotencyRecordEntity {

  @Id private UUID id;

  @Column(name = "merchant_id", nullable = false)
  private String merchantId;

  @Column(name = "idempotency_key", nullable = false)
  private String idempotencyKey;

  @Column(name = "request_fingerprint", nullable = false, length = 64)
  private String requestFingerprint;

  @Column(name = "authorization_id", nullable = false)
  private UUID authorizationId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private IdempotencyState state;

  @Column(name = "lease_expires_at", nullable = false)
  private Instant leaseExpiresAt;

  @Column(nullable = false)
  private int attempts;

  @Column(name = "downstream_attempted", nullable = false)
  private boolean downstreamAttempted;

  @Column(name = "response_status")
  private Integer responseStatus;

  @Column(name = "response_body")
  private String responseBody;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  protected IdempotencyRecordEntity() {
    // for JPA
  }

  UUID getId() {
    return id;
  }

  String getMerchantId() {
    return merchantId;
  }

  String getIdempotencyKey() {
    return idempotencyKey;
  }

  String getRequestFingerprint() {
    return requestFingerprint;
  }

  UUID getAuthorizationId() {
    return authorizationId;
  }

  IdempotencyState getState() {
    return state;
  }

  Instant getLeaseExpiresAt() {
    return leaseExpiresAt;
  }

  int getAttempts() {
    return attempts;
  }

  boolean isDownstreamAttempted() {
    return downstreamAttempted;
  }

  Integer getResponseStatus() {
    return responseStatus;
  }

  String getResponseBody() {
    return responseBody;
  }

  Instant getCreatedAt() {
    return createdAt;
  }

  Instant getExpiresAt() {
    return expiresAt;
  }

  void complete(int httpStatus, String body) {
    this.state = IdempotencyState.COMPLETED;
    this.responseStatus = httpStatus;
    this.responseBody = body;
  }
}
