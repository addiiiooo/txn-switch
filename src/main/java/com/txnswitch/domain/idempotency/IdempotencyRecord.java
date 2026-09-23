/* SPDX-License-Identifier: MIT */
package com.txnswitch.domain.idempotency;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The dedup ledger entry for one {@code Idempotency-Key} within one merchant.
 *
 * <p>It holds three things that together make a retry safe:
 *
 * <ul>
 *   <li>a fingerprint of the original request, so the same key with a different body is refused
 *       rather than silently answered with someone else's authorization;
 *   <li>an authorization id allocated <em>before</em> anything is sent downstream, which is also
 *       the acquirer's idempotency key — this is what makes a retry after a timeout safe;
 *   <li>a lease, so an attempt that crashed or timed out stops blocking the key without the record
 *       ever being deleted. Deleting it would release the pre-allocated id, which is the one thing
 *       that must survive a failure.
 * </ul>
 */
public final class IdempotencyRecord {

  private final UUID id;
  private final String merchantId;
  private final String idempotencyKey;
  private final String requestFingerprint;
  private final UUID authorizationId;
  private final Instant createdAt;
  private final Instant expiresAt;

  private IdempotencyState state;
  private Instant leaseExpiresAt;
  private int attempts;
  private boolean downstreamAttempted;
  private Integer responseStatus;
  private String responseBody;

  private IdempotencyRecord(
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
      Instant expiresAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.merchantId = Objects.requireNonNull(merchantId, "merchantId");
    this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    this.requestFingerprint = Objects.requireNonNull(requestFingerprint, "requestFingerprint");
    this.authorizationId = Objects.requireNonNull(authorizationId, "authorizationId");
    this.state = Objects.requireNonNull(state, "state");
    this.leaseExpiresAt = Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
    this.attempts = attempts;
    this.downstreamAttempted = downstreamAttempted;
    this.responseStatus = responseStatus;
    this.responseBody = responseBody;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
  }

  /** A fresh claim on a key, with the authorization id allocated up front. */
  public static IdempotencyRecord claim(
      String merchantId,
      String idempotencyKey,
      String requestFingerprint,
      UUID authorizationId,
      Instant now,
      Duration leaseTtl,
      Duration recordTtl) {
    return new IdempotencyRecord(
        UUID.randomUUID(),
        merchantId,
        idempotencyKey,
        requestFingerprint,
        authorizationId,
        IdempotencyState.IN_PROGRESS,
        now.plus(leaseTtl),
        1,
        false,
        null,
        null,
        now,
        now.plus(recordTtl));
  }

  public static IdempotencyRecord rehydrate(IdempotencySnapshot snapshot) {
    return new IdempotencyRecord(
        snapshot.id(),
        snapshot.merchantId(),
        snapshot.idempotencyKey(),
        snapshot.requestFingerprint(),
        snapshot.authorizationId(),
        snapshot.state(),
        snapshot.leaseExpiresAt(),
        snapshot.attempts(),
        snapshot.downstreamAttempted(),
        snapshot.responseStatus(),
        snapshot.responseBody(),
        snapshot.createdAt(),
        snapshot.expiresAt());
  }

  public IdempotencySnapshot snapshot() {
    return new IdempotencySnapshot(
        id,
        merchantId,
        idempotencyKey,
        requestFingerprint,
        authorizationId,
        state,
        leaseExpiresAt,
        attempts,
        downstreamAttempted,
        responseStatus,
        responseBody,
        createdAt,
        expiresAt);
  }

  /** Constant-time-ish comparison is unnecessary here: a fingerprint is not a secret. */
  public boolean matchesFingerprint(String candidate) {
    return requestFingerprint.equals(candidate);
  }

  public boolean isCompleted() {
    return state == IdempotencyState.COMPLETED;
  }

  public boolean isLeaseHeldAt(Instant now) {
    return state == IdempotencyState.IN_PROGRESS && now.isBefore(leaseExpiresAt);
  }

  /** Takes ownership of a claim whose holder is gone. */
  public void renewLease(Instant now, Duration leaseTtl) {
    if (state != IdempotencyState.IN_PROGRESS) {
      throw new IllegalStateException("cannot renew the lease on a completed record");
    }
    leaseExpiresAt = now.plus(leaseTtl);
    attempts++;
  }

  /**
   * Gives the key up without destroying the record, so the next attempt reuses the same downstream
   * identifier. This is the path taken after a timeout, an open breaker, or a failed commit.
   */
  public void releaseLease(Instant now) {
    if (state == IdempotencyState.IN_PROGRESS) {
      leaseExpiresAt = now;
    }
  }

  /** Records that bytes went to the acquirer, so an unresolved attempt can be counted later. */
  public void markDownstreamAttempted() {
    downstreamAttempted = true;
  }

  /** Stores the definitive response to replay. */
  public void complete(int httpStatus, String body) {
    if (state == IdempotencyState.COMPLETED) {
      throw new IllegalStateException("idempotency record " + id + " is already completed");
    }
    state = IdempotencyState.COMPLETED;
    responseStatus = httpStatus;
    responseBody = body;
  }

  public UUID id() {
    return id;
  }

  public String merchantId() {
    return merchantId;
  }

  public String idempotencyKey() {
    return idempotencyKey;
  }

  public String requestFingerprint() {
    return requestFingerprint;
  }

  public UUID authorizationId() {
    return authorizationId;
  }

  public IdempotencyState state() {
    return state;
  }

  public Instant leaseExpiresAt() {
    return leaseExpiresAt;
  }

  public int attempts() {
    return attempts;
  }

  public boolean downstreamAttempted() {
    return downstreamAttempted;
  }

  public Optional<Integer> responseStatus() {
    return Optional.ofNullable(responseStatus);
  }

  public Optional<String> responseBody() {
    return Optional.ofNullable(responseBody);
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant expiresAt() {
    return expiresAt;
  }
}
