/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter.out.acquirer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.txnswitch.application.port.AcquirerGateway;
import com.txnswitch.application.port.AcquirerProtocolException;
import com.txnswitch.application.port.AcquirerTimeoutException;
import com.txnswitch.application.port.AcquirerUnavailableException;
import com.txnswitch.domain.acquirer.AcquirerDecision;
import com.txnswitch.domain.card.Pan;
import com.txnswitch.testsupport.IntegrationTest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Each behaviour in ADR-0004 gets its own test, driven through a real HTTP call to the simulator so
 * the timeouts under test are socket timeouts.
 */
class AcquirerResilienceIT extends IntegrationTest {

  @Autowired private AcquirerGateway acquirer;

  private AcquirerGateway.AuthorizeCommand command(String pan) {
    return new AcquirerGateway.AuthorizeCommand(
        UUID.randomUUID(), "m_demo", "order-1", 1250, "USD", Pan.of(pan), 12, 2030, "corr-1");
  }

  @Test
  void anApprovalTakesOneCall() {
    AcquirerDecision decision = acquirer.authorize(command("4111111111111111"));

    assertThat(decision).isInstanceOf(AcquirerDecision.Approved.class);
    assertThat(((AcquirerDecision.Approved) decision).approvalCode()).isNotBlank();
    assertThat(simulator.requests()).isEqualTo(1);
  }

  @Test
  void aDeclineIsAnAnswerAndIsNeverRetried() {
    AcquirerDecision decision = acquirer.authorize(command("4000000000000002"));

    assertThat(decision)
        .asInstanceOf(
            org.assertj.core.api.InstanceOfAssertFactories.type(AcquirerDecision.Declined.class))
        .extracting(AcquirerDecision.Declined::declineCode)
        .isEqualTo("DO_NOT_HONOR");
    assertThat(simulator.requests())
        .as("retrying a decline is a second attempt to take someone's money")
        .isEqualTo(1);
  }

  @Test
  void aRetryableGatewayErrorIsRetriedWithinTheBudget() {
    AcquirerDecision decision = acquirer.authorize(command("4000000000000119"));

    assertThat(decision).isInstanceOf(AcquirerDecision.Approved.class);
    assertThat(simulator.requests()).as("two failures then a success").isEqualTo(3);
  }

  @Test
  void aMalformedRequestAnswerIsNotRetried() {
    assertThatExceptionOfType(AcquirerProtocolException.class)
        .isThrownBy(() -> acquirer.authorize(command("4000000000000101")));

    assertThat(simulator.requests()).isEqualTo(1);
    assertThat(circuitBreakers.circuitBreaker("acquirer").getMetrics().getNumberOfFailedCalls())
        .as("our bug or theirs, but not an outage: it must not trip the breaker")
        .isZero();
  }

  @Test
  void aReadTimeoutIsRetriedAndTheAcquirerDeduplicatesTheRepeat() {
    AcquirerDecision decision = acquirer.authorize(command("4000000000000259"));

    assertThat(decision).isInstanceOf(AcquirerDecision.Approved.class);
    assertThat(simulator.requests()).isEqualTo(2);
    assertThat(simulator.executions()).as("the retry must not create a second hold").isEqualTo(1);
  }

  @Test
  void anAcquirerThatNeverAnswersExhaustsTheBudgetAndReportsAnUnknownOutcome() {
    simulator.latencyMillis(2_000);

    AcquirerTimeoutException thrown =
        org.junit.jupiter.api.Assertions.assertThrows(
            AcquirerTimeoutException.class, () -> acquirer.authorize(command("4111111111111111")));

    assertThat(thrown.outcomeUnknown()).isTrue();
    assertThat(simulator.requests()).as("bounded at three attempts").isEqualTo(3);
  }

  @Test
  void sustainedFailureOpensTheBreakerAndThenCallsStopLeavingTheProcess() {
    simulator.failureRate(1.0);
    CircuitBreaker breaker = circuitBreakers.circuitBreaker("acquirer");

    for (int i = 0; i < 4; i++) {
      assertThatExceptionOfType(AcquirerUnavailableException.class)
          .isThrownBy(() -> acquirer.authorize(command("4111111111111111")));
    }

    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

    long callsBefore = simulator.requests();
    AcquirerUnavailableException thrown =
        org.junit.jupiter.api.Assertions.assertThrows(
            AcquirerUnavailableException.class,
            () -> acquirer.authorize(command("4111111111111111")));

    assertThat(thrown.outcomeUnknown())
        .as("nothing was sent, so nothing can be pending at the acquirer")
        .isFalse();
    assertThat(simulator.requests()).isEqualTo(callsBefore);
  }

  @Test
  void aRecoveredAcquirerClosesTheBreakerThroughHalfOpen() {
    simulator.failureRate(1.0);
    CircuitBreaker breaker = circuitBreakers.circuitBreaker("acquirer");
    for (int i = 0; i < 4; i++) {
      assertThatExceptionOfType(AcquirerUnavailableException.class)
          .isThrownBy(() -> acquirer.authorize(command("4111111111111111")));
    }
    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

    // The ten-second open window is Resilience4j's timer, not ours; driving the transition
    // directly tests our configuration without spending ten seconds of the budget proving
    // that a library's clock works.
    simulator.failureRate(0.0);
    breaker.transitionToHalfOpenState();
    for (int i = 0; i < 3; i++) {
      assertThat(acquirer.authorize(command("4111111111111111")))
          .isInstanceOf(AcquirerDecision.Approved.class);
    }

    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
  }

  @Test
  void captureAndVoidAreAcknowledgedUnderTheirOwnDownstreamKeys() {
    UUID authorizationId = UUID.randomUUID();
    AcquirerGateway.FollowUpCommand followUp =
        new AcquirerGateway.FollowUpCommand(authorizationId, 1250, "USD", "corr-1");

    AcquirerGateway.Acknowledgement captured = acquirer.capture(followUp);
    AcquirerGateway.Acknowledgement voided = acquirer.voidHold(followUp);

    assertThat(captured.acquirerReference()).startsWith("CAP-");
    assertThat(voided.acquirerReference()).startsWith("VOI-");
    assertThat(simulator.executions())
        .as("a capture must not look like a duplicate of its authorization")
        .isEqualTo(2);
  }
}
