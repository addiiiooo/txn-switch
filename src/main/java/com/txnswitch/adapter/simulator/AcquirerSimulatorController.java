/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter.simulator;

import com.txnswitch.adapter.out.acquirer.AcquirerMessages.AcknowledgementMessage;
import com.txnswitch.adapter.out.acquirer.AcquirerMessages.AuthorizeMessage;
import com.txnswitch.adapter.out.acquirer.AcquirerMessages.DecisionMessage;
import com.txnswitch.adapter.out.acquirer.AcquirerMessages.ErrorMessage;
import com.txnswitch.adapter.out.acquirer.AcquirerMessages.FollowUpMessage;
import com.txnswitch.adapter.simulator.AcquirerSimulatorState.StoredOutcome;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A stand-in acquirer, reachable over real HTTP.
 *
 * <p>It runs in this process and answers on this port, but it is reached through the network stack
 * like any other dependency, so connect and read timeouts, connection reuse and the circuit breaker
 * all exercise the code path that runs in production. An in-process stub whose "timeout" is a sleep
 * and an interrupt would prove none of that.
 *
 * <p>Hidden from the OpenAPI document and switched off with {@code SIMULATOR_ENABLED=false}, at
 * which point {@code ACQUIRER_BASE_URL} points at something real.
 */
@Hidden
@RestController
@RequestMapping("/__simulator/acquirer")
@ConditionalOnProperty(name = "txnswitch.simulator.enabled", havingValue = "true")
public class AcquirerSimulatorController {

  /** Longer than any read timeout a caller could sensibly configure. */
  private static final long SLOW_CALL_MILLIS = 3_000;

  private final AcquirerSimulatorState state;

  public AcquirerSimulatorController(AcquirerSimulatorState state) {
    this.state = state;
  }

  @PostMapping("/authorize")
  public ResponseEntity<?> authorize(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody AuthorizeMessage message)
      throws InterruptedException {
    // Captured first: if a reset lands while this call sleeps, remember() will see it.
    long generation = state.generation();
    state.countRequest();

    Optional<StoredOutcome> replayed = state.replay(idempotencyKey);
    if (replayed.isPresent()) {
      return ResponseEntity.ok(toDecision(replayed.get()));
    }

    int attempt = state.nextAttempt(idempotencyKey);
    ResponseEntity<ErrorMessage> injected = applyInjectedBehaviour();
    if (injected != null) {
      return injected;
    }

    return switch (SimulatorScenario.forPan(message.pan().value())) {
      case APPROVE -> approve(generation, idempotencyKey);
      case DECLINE_DO_NOT_HONOR ->
          decline(generation, idempotencyKey, "DO_NOT_HONOR", "Do not honour");
      case DECLINE_INSUFFICIENT_FUNDS ->
          decline(generation, idempotencyKey, "INSUFFICIENT_FUNDS", "Insufficient funds");
      case FAIL_TWICE_THEN_APPROVE ->
          attempt <= 2
              ? ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                  .body(new ErrorMessage("PROCESSING_ERROR", "temporary processing error"))
              : approve(generation, idempotencyKey);
      case SLOW_ONCE_THEN_APPROVE -> {
        if (attempt == 1) {
          Thread.sleep(SLOW_CALL_MILLIS);
        }
        yield approve(generation, idempotencyKey);
      }
      case REJECT_AS_MALFORMED ->
          ResponseEntity.badRequest()
              .body(new ErrorMessage("INVALID_MESSAGE", "the acquirer rejected the message"));
    };
  }

  @PostMapping("/capture")
  public ResponseEntity<?> capture(
      @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody FollowUpMessage message)
      throws InterruptedException {
    return acknowledge(idempotencyKey, "CAP");
  }

  @PostMapping("/void")
  public ResponseEntity<?> voidHold(
      @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody FollowUpMessage message)
      throws InterruptedException {
    return acknowledge(idempotencyKey, "VOI");
  }

  /** Injects latency and failures at runtime, for demonstrations the magic cards do not cover. */
  @PostMapping("/config")
  public ResponseEntity<SimulatorConfig> configure(@RequestBody SimulatorConfig config) {
    state.latencyMillis(config.latencyMillis());
    state.failureRate(config.failureRate());
    return ResponseEntity.ok(config);
  }

  @PostMapping("/reset")
  public ResponseEntity<Void> reset() {
    state.reset();
    return ResponseEntity.noContent().build();
  }

  @org.springframework.web.bind.annotation.GetMapping("/stats")
  public SimulatorStats stats() {
    return new SimulatorStats(state.requests(), state.executions(), state.deduplicated());
  }

  private ResponseEntity<?> acknowledge(String idempotencyKey, String prefix)
      throws InterruptedException {
    long generation = state.generation();
    state.countRequest();
    Optional<StoredOutcome> replayed = state.replay(idempotencyKey);
    if (replayed.isPresent()) {
      return ResponseEntity.ok(new AcknowledgementMessage(replayed.get().reference()));
    }
    state.nextAttempt(idempotencyKey);
    ResponseEntity<ErrorMessage> injected = applyInjectedBehaviour();
    if (injected != null) {
      return injected;
    }
    StoredOutcome stored =
        state.remember(
            generation,
            idempotencyKey,
            new StoredOutcome("APPROVED", prefix + "-" + reference(), null, null));
    return ResponseEntity.ok(new AcknowledgementMessage(stored.reference()));
  }

  private ResponseEntity<ErrorMessage> applyInjectedBehaviour() throws InterruptedException {
    if (state.failureRate() > 0 && ThreadLocalRandom.current().nextDouble() < state.failureRate()) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body(new ErrorMessage("UNAVAILABLE", "the acquirer is unavailable"));
    }
    long latency = state.latencyMillis();
    if (latency > 0) {
      Thread.sleep(latency);
    }
    return null;
  }

  private ResponseEntity<DecisionMessage> approve(long generation, String idempotencyKey) {
    StoredOutcome outcome =
        state.remember(
            generation,
            idempotencyKey,
            new StoredOutcome("APPROVED", "ACQ-" + reference(), approvalCode(), null));
    return ResponseEntity.ok(toDecision(outcome));
  }

  private ResponseEntity<DecisionMessage> decline(
      long generation, String idempotencyKey, String code, String text) {
    StoredOutcome outcome =
        state.remember(
            generation,
            idempotencyKey,
            new StoredOutcome("DECLINED", "ACQ-" + reference(), code, text));
    return ResponseEntity.ok(toDecision(outcome));
  }

  private static DecisionMessage toDecision(StoredOutcome outcome) {
    boolean approved = "APPROVED".equals(outcome.outcome());
    return new DecisionMessage(
        outcome.outcome(),
        outcome.reference(),
        approved ? outcome.code() : null,
        approved ? null : outcome.code(),
        approved ? null : outcome.message());
  }

  private static String reference() {
    return Long.toHexString(ThreadLocalRandom.current().nextLong(0x100000L, 0xFFFFFFL))
        .toUpperCase(java.util.Locale.ROOT);
  }

  private static String approvalCode() {
    return Integer.toHexString(ThreadLocalRandom.current().nextInt(0x100000, 0xFFFFFF))
        .toUpperCase(java.util.Locale.ROOT);
  }

  public record SimulatorConfig(long latencyMillis, double failureRate) {}

  public record SimulatorStats(long requests, long executions, long deduplicated) {}
}
