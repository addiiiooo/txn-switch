/* SPDX-License-Identifier: MIT */
package com.txnswitch.application;

import com.txnswitch.application.port.IdempotencyStore;
import com.txnswitch.config.IdempotencyProperties;
import com.txnswitch.domain.idempotency.IdempotencyRecord;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Claims, releases and inspects idempotency keys.
 *
 * <p>Every method here is its own short transaction, and none of them may be held open across the
 * acquirer call — that is the constraint the whole design rests on (ADR-0002). This is a separate
 * bean from {@link AuthorizationService} for exactly that reason: the orchestration must not be
 * transactional, and a self-invocation would not be proxied anyway.
 */
@Service
public class IdempotencyService {

  private final IdempotencyStore store;
  private final IdempotencyProperties properties;
  private final Clock clock;

  public IdempotencyService(IdempotencyStore store, IdempotencyProperties properties, Clock clock) {
    this.store = store;
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * Claims a key, or works out what the existing claim means for this request.
   *
   * <p>The insert is {@code ON CONFLICT DO NOTHING}, so losing the race is a return value rather
   * than an exception that would poison the transaction. Postgres makes the loser wait for the
   * winner to commit before reporting the conflict, so the follow-up read never sees a half-written
   * claim.
   *
   * @param authorizationId allocated by the caller before anything is sent downstream, and used as
   *     the acquirer's idempotency key
   */
  @Transactional
  public ClaimOutcome claim(
      String merchantId, String idempotencyKey, String requestFingerprint, UUID authorizationId) {
    Instant now = clock.instant();
    IdempotencyRecord candidate =
        IdempotencyRecord.claim(
            merchantId,
            idempotencyKey,
            requestFingerprint,
            authorizationId,
            now,
            properties.leaseTtl(),
            properties.ttl());

    Optional<IdempotencyRecord> claimed = store.claim(candidate);
    if (claimed.isPresent()) {
      return new ClaimOutcome.Claimed(claimed.get());
    }

    Optional<IdempotencyRecord> existing = store.find(merchantId, idempotencyKey);
    if (existing.isEmpty()) {
      // Purged between the failed insert and this read. Vanishingly rare, and asking the
      // caller to retry is safer than claiming a key we cannot see.
      return new ClaimOutcome.InProgress();
    }

    IdempotencyRecord record = existing.get();
    if (!record.matchesFingerprint(requestFingerprint)) {
      return new ClaimOutcome.FingerprintMismatch();
    }
    if (record.isCompleted()) {
      return new ClaimOutcome.Replay(
          record.responseStatus().orElseThrow(),
          record.responseBody().orElse(""),
          record.authorizationId());
    }
    if (record.isLeaseHeldAt(now)) {
      return new ClaimOutcome.InProgress();
    }
    // The lease has lapsed: the previous attempt crashed, timed out, or was refused by the
    // breaker. Taking it over reuses the same pre-allocated authorization id, which is what
    // makes re-driving the call safe.
    return store.takeOverLease(record.id(), now, properties.leaseTtl())
        ? new ClaimOutcome.Claimed(record)
        : new ClaimOutcome.InProgress();
  }

  /**
   * Gives up a claim without deleting it, so the pre-allocated authorization id survives for the
   * next attempt with the same key.
   *
   * @param downstreamAttempted false only when the call provably never left this process; it cannot
   *     clear a possible hold left by an earlier attempt on the same key
   */
  @Transactional
  public void release(UUID recordId, boolean downstreamAttempted) {
    store.releaseLease(recordId, clock.instant(), downstreamAttempted);
  }

  public int retryAfterSeconds() {
    return 1;
  }
}
