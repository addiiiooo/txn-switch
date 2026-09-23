/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter.out.persistence;

import com.txnswitch.domain.authorization.AuthorizationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Append-only audit row. Never updated, never deleted. */
@Entity
@Table(name = "authorization_events")
class AuthorizationEventEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "authorization_id", nullable = false)
  private UUID authorizationId;

  @Enumerated(EnumType.STRING)
  @Column(name = "from_status", length = 16)
  private AuthorizationStatus fromStatus;

  @Enumerated(EnumType.STRING)
  @Column(name = "to_status", nullable = false, length = 16)
  private AuthorizationStatus toStatus;

  @Column private String detail;

  @Column(name = "correlation_id")
  private String correlationId;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  protected AuthorizationEventEntity() {
    // for JPA
  }

  AuthorizationEventEntity(
      UUID authorizationId,
      AuthorizationStatus fromStatus,
      AuthorizationStatus toStatus,
      String detail,
      String correlationId,
      Instant occurredAt) {
    this.authorizationId = authorizationId;
    this.fromStatus = fromStatus;
    this.toStatus = toStatus;
    this.detail = detail;
    this.correlationId = correlationId;
    this.occurredAt = occurredAt;
  }

  Long getId() {
    return id;
  }

  UUID getAuthorizationId() {
    return authorizationId;
  }

  AuthorizationStatus getFromStatus() {
    return fromStatus;
  }

  AuthorizationStatus getToStatus() {
    return toStatus;
  }

  String getDetail() {
    return detail;
  }

  String getCorrelationId() {
    return correlationId;
  }

  Instant getOccurredAt() {
    return occurredAt;
  }
}
