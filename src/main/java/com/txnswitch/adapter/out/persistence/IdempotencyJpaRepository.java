/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter.out.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyJpaRepository extends JpaRepository<IdempotencyRecordEntity, UUID> {

  Optional<IdempotencyRecordEntity> findByMerchantIdAndIdempotencyKey(
      String merchantId, String idempotencyKey);

  /**
   * The single arbitration point for concurrent duplicates: the unique index decides the winner,
   * and the loser gets a zero back instead of an exception.
   *
   * <p>{@code downstream_attempted} is set here, on the reasoning that a claim is only ever taken
   * in order to send. It is cleared again if the call turns out never to have left the process. The
   * error is therefore towards over-reporting a possible unresolved hold, which is the right
   * direction for a number whose whole purpose is to be zero.
   *
   * @return 1 if this caller claimed the key, 0 if it was already taken
   */
  @Modifying
  @Query(
      value =
          """
          INSERT INTO idempotency_records (id, merchant_id, idempotency_key, request_fingerprint,
                                           authorization_id, state, lease_expires_at, attempts,
                                           downstream_attempted, created_at, expires_at)
          VALUES (:id, :merchantId, :idempotencyKey, :fingerprint, :authorizationId, 'IN_PROGRESS',
                  :leaseExpiresAt, 1, TRUE, :createdAt, :expiresAt)
          ON CONFLICT (merchant_id, idempotency_key) DO NOTHING
          """,
      nativeQuery = true)
  int insertIfAbsent(
      @Param("id") UUID id,
      @Param("merchantId") String merchantId,
      @Param("idempotencyKey") String idempotencyKey,
      @Param("fingerprint") String requestFingerprint,
      @Param("authorizationId") UUID authorizationId,
      @Param("leaseExpiresAt") Instant leaseExpiresAt,
      @Param("createdAt") Instant createdAt,
      @Param("expiresAt") Instant expiresAt);

  /**
   * Conditional takeover of an abandoned claim. Exactly one caller can win, because the update
   * itself is the test.
   *
   * <p>A takeover is taken in order to send, exactly like a first claim, so it sets {@code
   * downstream_attempted} pessimistically too. What the earlier claims may have done is kept in
   * {@code prior_downstream_attempted} first, because the release that follows must not be able to
   * erase it.
   *
   * @return 1 if this caller now owns the claim
   */
  @Modifying
  @Query(
      value =
          """
          UPDATE idempotency_records
             SET lease_expires_at = :newLeaseExpiresAt,
                 attempts = attempts + 1,
                 prior_downstream_attempted = downstream_attempted,
                 downstream_attempted = TRUE
           WHERE id = :id AND state = 'IN_PROGRESS' AND lease_expires_at <= :now
          """,
      nativeQuery = true)
  int takeOverLease(
      @Param("id") UUID id,
      @Param("now") Instant now,
      @Param("newLeaseExpiresAt") Instant newLeaseExpiresAt);

  /**
   * Gives up a claim. {@code downstream_attempted} becomes what this attempt knows OR'd with what
   * earlier attempts on the key may have done: a retry refused before sending proves nothing about
   * the attempt before it, which may have placed a hold.
   */
  @Modifying
  @Query(
      value =
          """
          UPDATE idempotency_records
             SET lease_expires_at = :now,
                 downstream_attempted = prior_downstream_attempted OR :downstreamAttempted
           WHERE id = :id AND state = 'IN_PROGRESS'
          """,
      nativeQuery = true)
  int releaseLease(
      @Param("id") UUID id,
      @Param("now") Instant now,
      @Param("downstreamAttempted") boolean downstreamAttempted);

  /** Batched and lock-skipping so two instances purging at once do not fight. */
  @Modifying
  @Query(
      value =
          """
          DELETE FROM idempotency_records
           WHERE id IN (SELECT id FROM idempotency_records
                         WHERE expires_at <= :now
                         LIMIT :batchSize
                           FOR UPDATE SKIP LOCKED)
          """,
      nativeQuery = true)
  int purgeExpired(@Param("now") Instant now, @Param("batchSize") int batchSize);

  @Query(
      value =
          """
          SELECT count(*) FROM idempotency_records
           WHERE state = 'IN_PROGRESS'
             AND downstream_attempted = TRUE
             AND lease_expires_at <= :leaseLapsedBefore
          """,
      nativeQuery = true)
  long countUnresolved(@Param("leaseLapsedBefore") Instant leaseLapsedBefore);
}
