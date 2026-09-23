/* SPDX-License-Identifier: MIT */
package com.txnswitch.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * The numbers worth watching.
 *
 * <p>Tags are deliberately low cardinality: no merchant id, no idempotency key, no card data. A tag
 * whose value comes from the request is a cardinality explosion waiting for a busy day.
 */
@Component
public class AuthorizationMetrics {

  private final MeterRegistry registry;
  private final AtomicLong unresolvedAttempts = new AtomicLong();

  public AuthorizationMetrics(MeterRegistry registry) {
    this.registry = registry;
    Gauge.builder("txnswitch.unresolved.attempts", unresolvedAttempts, AtomicLong::doubleValue)
        .description(
            "Claims that reached the acquirer and were never resolved. Should be zero; if it is"
                + " not, a hold may exist that this service has no record of.")
        .register(registry);
  }

  public Timer.Sample startAuthorization() {
    return Timer.start(registry);
  }

  /**
   * @param outcome the resulting status, or {@code REPLAYED} for a request answered from the
   *     idempotency ledger, or {@code FAILED} when no decision was obtained
   */
  public void record(Timer.Sample sample, String outcome, boolean replay) {
    sample.stop(
        Timer.builder("txnswitch.authorization.duration")
            .description("End-to-end time to answer an authorization request")
            .tag("outcome", outcome)
            .register(registry));
    Counter.builder("txnswitch.authorization.requests")
        .description("Authorization requests by outcome")
        .tag("outcome", outcome)
        .tag("replay", Boolean.toString(replay))
        .register(registry)
        .increment();
  }

  public void recordFailure(Timer.Sample sample, String reason) {
    record(sample, "FAILED", false);
    Counter.builder("txnswitch.authorization.failures")
        .description("Authorizations that could not be completed, by reason")
        .tag("reason", reason)
        .register(registry)
        .increment();
  }

  /**
   * @param kind why a repeated key was not a plain replay
   */
  public void recordIdempotencyConflict(String kind) {
    Counter.builder("txnswitch.idempotency.conflicts")
        .description("Repeated idempotency keys that were not plain replays")
        .tag("kind", kind)
        .register(registry)
        .increment();
  }

  public void reportUnresolvedAttempts(long count) {
    unresolvedAttempts.set(count);
  }
}
