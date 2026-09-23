/* SPDX-License-Identifier: MIT */
package com.txnswitch.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.txnswitch.testsupport.IntegrationTest;
import org.junit.jupiter.api.Test;

/** What an operator gets to see when this service is running. */
class ObservabilityIT extends IntegrationTest {

  private String scrape() {
    return rest.getForEntity("/actuator/prometheus", String.class).getBody();
  }

  @Test
  void authorizationsAreCountedAndTimedByOutcome() {
    authorize("key-metrics-1", "4111111111111111");
    authorize("key-metrics-1", "4111111111111111");
    authorize("key-metrics-2", "4000000000000002");

    String scrape = scrape();

    assertThat(scrape)
        .contains("txnswitch_authorization_requests_total")
        .contains("outcome=\"AUTHORIZED\"")
        .contains("outcome=\"DECLINED\"")
        .contains("outcome=\"REPLAYED\"")
        .contains("replay=\"true\"")
        .contains("txnswitch_authorization_duration_seconds_bucket")
        .contains("txnswitch_acquirer_call_duration_seconds");
  }

  @Test
  void repeatedKeysThatAreNotReplaysAreCountedSeparately() {
    authorize("key-metrics-3", "4111111111111111");
    post(
        "/v1/authorizations",
        "key-metrics-3",
        authorizeBody("4111111111111111", 9999, "USD", "order-1"));

    assertThat(scrape())
        .contains("txnswitch_idempotency_conflicts_total")
        .contains("kind=\"fingerprint_mismatch\"");
  }

  @Test
  void theCircuitBreakerStateIsVisibleWithoutReadingTheLogs() {
    assertThat(scrape())
        .contains("resilience4j_circuitbreaker_state")
        .contains("name=\"acquirer\"");
  }

  @Test
  void theUnresolvedAttemptGaugeIsPublished() {
    assertThat(scrape()).contains("txnswitch_unresolved_attempts");
  }

  @Test
  void livenessAndReadinessAnswerDifferentQuestions() {
    String liveness = rest.getForEntity("/actuator/health/liveness", String.class).getBody();
    String readiness = rest.getForEntity("/actuator/health/readiness", String.class).getBody();

    assertThat(liveness)
        .as("a process that is fine should not be restarted because a dependency is down")
        .doesNotContain("\"db\"");
    assertThat(readiness).contains("\"db\"").contains("\"status\":\"UP\"");
  }
}
