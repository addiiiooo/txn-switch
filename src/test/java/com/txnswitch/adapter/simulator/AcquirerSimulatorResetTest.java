/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import com.txnswitch.adapter.SensitivePan;
import com.txnswitch.adapter.out.acquirer.AcquirerMessages.AuthorizeMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A reset must be a clean break, including for calls already in flight.
 *
 * <p>The integration tests share one simulator and reset it between tests. A call left sleeping in
 * injected latency after its client gave up outlives that reset; if it can still record its outcome
 * when it wakes, it bumps {@code executions()} in whichever test happens to be running, and that
 * test fails on a count it never caused. Which test depends on class order and machine speed, which
 * is why this surfaced on CI and not on a laptop.
 */
class AcquirerSimulatorResetTest {

  private static final long LATENCY_MILLIS = 500;

  private final AcquirerSimulatorState state = new AcquirerSimulatorState();
  private final AcquirerSimulatorController simulator = new AcquirerSimulatorController(state);
  private final ExecutorService caller = Executors.newSingleThreadExecutor();

  @AfterEach
  void stopCaller() {
    caller.shutdownNow();
  }

  @Test
  void aCallStillInFlightWhenTheSimulatorIsResetIsNotCountedAfterIt() throws Exception {
    state.latencyMillis(LATENCY_MILLIS);
    Future<?> slowCall = caller.submit(() -> simulator.authorize("stale-key", approvable()));
    awaitReceived();

    assertThat(slowCall).as("the call must still be sleeping when the reset lands").isNotDone();
    state.reset();
    slowCall.get(5, TimeUnit.SECONDS);

    assertThat(state.executions()).as("a call from before the reset").isZero();
    assertThat(state.replay("stale-key")).as("its outcome must not be replayable").isEmpty();
  }

  @Test
  void withoutAResetTheSameSlowCallIsCountedOnce() throws Exception {
    state.latencyMillis(LATENCY_MILLIS);

    simulator.authorize("fresh-key", approvable());

    assertThat(state.executions())
        .as("the control case: the call above does reach the point where it is counted")
        .isEqualTo(1);
  }

  private void awaitReceived() throws InterruptedException {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
    while (state.requests() == 0) {
      if (Instant.now().isAfter(deadline)) {
        throw new AssertionError("the simulator never received the call");
      }
      Thread.sleep(1);
    }
  }

  private static AuthorizeMessage approvable() {
    return new AuthorizeMessage(
        "m_test", "order-1", 1_000, "USD", SensitivePan.of("4111111111111111"), 12, 2030);
  }
}
