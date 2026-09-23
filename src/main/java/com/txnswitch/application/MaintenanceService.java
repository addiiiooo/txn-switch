/* SPDX-License-Identifier: MIT */
package com.txnswitch.application;

import com.txnswitch.application.port.AuthorizationRepository;
import com.txnswitch.application.port.IdempotencyStore;
import com.txnswitch.config.AuthorizationProperties;
import com.txnswitch.config.IdempotencyProperties;
import com.txnswitch.domain.authorization.Authorization;
import com.txnswitch.domain.authorization.AuthorizationEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Housekeeping that has to happen but must never be on the request path.
 *
 * <p>Both sweeps claim their rows with {@code FOR UPDATE SKIP LOCKED}, so running more than one
 * instance needs no leader election: a second sweeper takes different rows rather than waiting.
 */
@Service
public class MaintenanceService {

  private final AuthorizationRepository authorizations;
  private final IdempotencyStore idempotency;
  private final AuthorizationMetrics metrics;
  private final AuthorizationProperties authorizationProperties;
  private final IdempotencyProperties idempotencyProperties;
  private final Clock clock;

  public MaintenanceService(
      AuthorizationRepository authorizations,
      IdempotencyStore idempotency,
      AuthorizationMetrics metrics,
      AuthorizationProperties authorizationProperties,
      IdempotencyProperties idempotencyProperties,
      Clock clock) {
    this.authorizations = authorizations;
    this.idempotency = idempotency;
    this.metrics = metrics;
    this.authorizationProperties = authorizationProperties;
    this.idempotencyProperties = idempotencyProperties;
    this.clock = clock;
  }

  /**
   * Writes {@code EXPIRED} onto holds whose time has passed.
   *
   * <p>Reporting only: the domain already refuses to capture or void a lapsed hold on the strength
   * of the clock, so this sweep being late is never a correctness problem.
   */
  @Transactional
  public int expireLapsedHolds() {
    Instant now = clock.instant();
    List<Authorization> lapsed =
        authorizations.lockLapsedHolds(now, authorizationProperties.expirySweepBatchSize());
    for (Authorization authorization : lapsed) {
      AuthorizationEvent event = authorization.expire(now);
      authorizations.update(authorization, event, "expiry-sweeper");
    }
    return lapsed.size();
  }

  @Transactional
  public int purgeExpiredIdempotencyRecords() {
    return idempotency.purgeExpired(clock.instant(), idempotencyProperties.purgeBatchSize());
  }

  /**
   * Publishes the number of claims that reached the acquirer and were never resolved.
   *
   * <p>Compensating for those automatically — sending a reversal for a hold we may have created and
   * never recorded — is out of scope for this project. Leaving the gap unmeasured as well would be
   * a different thing entirely, so the number is published and should be zero.
   */
  public long reportUnresolvedAttempts() {
    long unresolved =
        idempotency.countUnresolved(clock.instant().minus(idempotencyProperties.unresolvedGrace()));
    metrics.reportUnresolvedAttempts(unresolved);
    return unresolved;
  }
}
