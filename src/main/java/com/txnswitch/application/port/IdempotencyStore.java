/* SPDX-License-Identifier: MIT */
package com.txnswitch.application.port;

import com.txnswitch.domain.idempotency.IdempotencyRecord;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The dedup ledger.
 *
 * <p>Every method here is a short, self-contained transaction. Nothing in this interface may be
 * held open across the acquirer call: that is the constraint the whole concurrency design is built
 * on (ADR-0002).
 */
public interface IdempotencyStore {

  /**
   * Attempts to claim a key.
   *
   * @return the stored claim if this caller won, or empty if the key already existed — in which
   *     case the caller reads the existing record and decides whether to replay, refuse or take
   *     over
   */
  Optional<IdempotencyRecord> claim(IdempotencyRecord candidate);

  Optional<IdempotencyRecord> find(String merchantId, String idempotencyKey);

  /**
   * Takes ownership of a claim whose lease has lapsed, conditionally.
   *
   * @return true if this caller now owns the claim; false if someone else got there first
   */
  boolean takeOverLease(UUID recordId, Instant now, Duration leaseTtl);

  /**
   * Gives up the claim without deleting the record, so the pre-allocated authorization id survives
   * for the next attempt.
   *
   * @param downstreamAttempted false only when we are certain nothing was sent, which resets the
   *     conservative flag set when this claim was taken, but never a possible hold left by an
   *     earlier attempt on the same key
   */
  void releaseLease(UUID recordId, Instant now, boolean downstreamAttempted);

  /** Stores the definitive response for replay. Called in the same transaction as the insert. */
  void complete(UUID recordId, int httpStatus, String responseBody);

  /** Removes records past their retention window. */
  int purgeExpired(Instant now, int limit);

  /**
   * Counts claims that reached the acquirer and were never resolved: attempts whose outcome we do
   * not know, and which may correspond to a hold nobody will ever capture or void.
   */
  long countUnresolved(Instant leaseLapsedBefore);
}
