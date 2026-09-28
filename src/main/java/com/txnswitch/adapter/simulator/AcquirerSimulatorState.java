/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter.simulator;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Mutable state of the simulated acquirer: injected latency and failure rate, the deduplication
 * table, and call counters.
 *
 * <p>The deduplication table is the important part. A real acquirer that honours an idempotency key
 * is what makes retrying a read timeout safe, so the simulator honours one too — otherwise the
 * resilience tests would be proving something the production path does not rely on.
 */
@Component
public class AcquirerSimulatorState {

  private final Map<String, StoredOutcome> outcomesByKey = new ConcurrentHashMap<>();
  private final Map<String, AtomicInteger> attemptsByKey = new ConcurrentHashMap<>();
  private final AtomicLong requests = new AtomicLong();
  private final AtomicLong executions = new AtomicLong();
  private final AtomicLong deduplicated = new AtomicLong();
  private final AtomicLong generation = new AtomicLong();

  private volatile long latencyMillis;
  private volatile double failureRate;

  /**
   * Called between tests and by the control endpoint.
   *
   * <p>Starts a new generation. A call still in flight from before the reset, typically one
   * sleeping in injected latency after its client gave up, finishes into the old generation and is
   * neither stored nor counted, so it cannot leak into whatever runs next.
   */
  public synchronized void reset() {
    generation.incrementAndGet();
    outcomesByKey.clear();
    attemptsByKey.clear();
    requests.set(0);
    executions.set(0);
    deduplicated.set(0);
    latencyMillis = 0;
    failureRate = 0.0;
  }

  public long latencyMillis() {
    return latencyMillis;
  }

  public void latencyMillis(long value) {
    this.latencyMillis = value;
  }

  public double failureRate() {
    return failureRate;
  }

  public void failureRate(double value) {
    this.failureRate = value;
  }

  /** The generation a call belongs to, captured when it arrives and handed back to remember. */
  long generation() {
    return generation.get();
  }

  long countRequest() {
    return requests.incrementAndGet();
  }

  int nextAttempt(String idempotencyKey) {
    return attemptsByKey
        .computeIfAbsent(idempotencyKey, key -> new AtomicInteger())
        .incrementAndGet();
  }

  Optional<StoredOutcome> replay(String idempotencyKey) {
    StoredOutcome stored = outcomesByKey.get(idempotencyKey);
    if (stored != null) {
      deduplicated.incrementAndGet();
    }
    return Optional.ofNullable(stored);
  }

  /**
   * Stores an outcome for a key, first write wins, unless a reset has ended the call's generation.
   *
   * <p>First-write-wins matters: a slow call that finishes after its own retry has already been
   * answered must not overwrite what the caller was told, and {@code executions} must count
   * distinct keys rather than round trips, because that is the number a duplicate request must
   * never increase.
   *
   * <p>Synchronized with {@link #reset()} because the generation check and the write must be one
   * step: a call that passed the check just before a reset would otherwise still land in the table
   * the reset had just cleared.
   *
   * @return the outcome now associated with the key, which may be one an earlier call stored; for a
   *     call from an earlier generation, the outcome it brought, stored nowhere
   */
  synchronized StoredOutcome remember(
      long callGeneration, String idempotencyKey, StoredOutcome outcome) {
    if (callGeneration != generation.get()) {
      return outcome;
    }
    StoredOutcome existing = outcomesByKey.putIfAbsent(idempotencyKey, outcome);
    if (existing != null) {
      return existing;
    }
    executions.incrementAndGet();
    return outcome;
  }

  /** Total calls received, including those answered from the deduplication table. */
  public long requests() {
    return requests.get();
  }

  /** Calls that actually produced a new outcome. The number a duplicate must never increase. */
  public long executions() {
    return executions.get();
  }

  public long deduplicated() {
    return deduplicated.get();
  }

  record StoredOutcome(String outcome, String reference, String code, String message) {}
}
