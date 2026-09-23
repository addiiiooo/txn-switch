/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter.out.persistence;

import com.txnswitch.domain.authorization.AuthorizationStatus;
import com.txnswitch.domain.card.CardBrand;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * Storage shape of an authorization.
 *
 * <p>Deliberately separate from the aggregate: JPA would otherwise dictate a no-argument
 * constructor and setter-shaped mutation, and the aggregate would lose its guarantee that an
 * authorization cannot exist without an acquirer decision (ADR-0006).
 *
 * <p>{@code version} is boxed so that a null means "never persisted": Spring Data uses it to decide
 * between a persist and a merge, and a merge is what performs the optimistic check.
 */
@Entity
@Table(name = "authorizations")
class AuthorizationEntity {

  @Id private UUID id;

  @Column(name = "merchant_id", nullable = false)
  private String merchantId;

  @Column(name = "merchant_reference")
  private String merchantReference;

  @Column(name = "amount_minor", nullable = false)
  private long amountMinor;

  @Column(nullable = false, length = 3)
  private String currency;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private AuthorizationStatus status;

  @Column(name = "card_bin", nullable = false, length = 6)
  private String cardBin;

  @Column(name = "card_last4", nullable = false, length = 4)
  private String cardLast4;

  @Enumerated(EnumType.STRING)
  @Column(name = "card_brand", nullable = false, length = 16)
  private CardBrand cardBrand;

  @Column(name = "card_exp_month", nullable = false)
  private short cardExpMonth;

  @Column(name = "card_exp_year", nullable = false)
  private short cardExpYear;

  @Column(name = "card_fingerprint", nullable = false, length = 64)
  private String cardFingerprint;

  @Column(name = "acquirer_name")
  private String acquirerName;

  @Column(name = "acquirer_reference")
  private String acquirerReference;

  @Column(name = "approval_code")
  private String approvalCode;

  @Column(name = "decline_code")
  private String declineCode;

  @Column(name = "decline_message")
  private String declineMessage;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "captured_at")
  private Instant capturedAt;

  @Column(name = "voided_at")
  private Instant voidedAt;

  @Version
  @Column(nullable = false)
  private Long version;

  protected AuthorizationEntity() {
    // for JPA
  }

  AuthorizationEntity(
      UUID id,
      String merchantId,
      String merchantReference,
      long amountMinor,
      String currency,
      AuthorizationStatus status,
      String cardBin,
      String cardLast4,
      CardBrand cardBrand,
      short cardExpMonth,
      short cardExpYear,
      String cardFingerprint,
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
      Long version) {
    this.id = id;
    this.merchantId = merchantId;
    this.merchantReference = merchantReference;
    this.amountMinor = amountMinor;
    this.currency = currency;
    this.status = status;
    this.cardBin = cardBin;
    this.cardLast4 = cardLast4;
    this.cardBrand = cardBrand;
    this.cardExpMonth = cardExpMonth;
    this.cardExpYear = cardExpYear;
    this.cardFingerprint = cardFingerprint;
    this.acquirerName = acquirerName;
    this.acquirerReference = acquirerReference;
    this.approvalCode = approvalCode;
    this.declineCode = declineCode;
    this.declineMessage = declineMessage;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
    this.expiresAt = expiresAt;
    this.capturedAt = capturedAt;
    this.voidedAt = voidedAt;
    this.version = version;
  }

  UUID getId() {
    return id;
  }

  String getMerchantId() {
    return merchantId;
  }

  String getMerchantReference() {
    return merchantReference;
  }

  long getAmountMinor() {
    return amountMinor;
  }

  String getCurrency() {
    return currency;
  }

  AuthorizationStatus getStatus() {
    return status;
  }

  String getCardBin() {
    return cardBin;
  }

  String getCardLast4() {
    return cardLast4;
  }

  CardBrand getCardBrand() {
    return cardBrand;
  }

  short getCardExpMonth() {
    return cardExpMonth;
  }

  short getCardExpYear() {
    return cardExpYear;
  }

  String getCardFingerprint() {
    return cardFingerprint;
  }

  String getAcquirerName() {
    return acquirerName;
  }

  String getAcquirerReference() {
    return acquirerReference;
  }

  String getApprovalCode() {
    return approvalCode;
  }

  String getDeclineCode() {
    return declineCode;
  }

  String getDeclineMessage() {
    return declineMessage;
  }

  Instant getCreatedAt() {
    return createdAt;
  }

  Instant getUpdatedAt() {
    return updatedAt;
  }

  Instant getExpiresAt() {
    return expiresAt;
  }

  Instant getCapturedAt() {
    return capturedAt;
  }

  Instant getVoidedAt() {
    return voidedAt;
  }

  Long getVersion() {
    return version;
  }
}
