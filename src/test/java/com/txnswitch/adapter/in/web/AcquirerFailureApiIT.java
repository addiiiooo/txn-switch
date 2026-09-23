/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.txnswitch.testsupport.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** How a downstream failure looks from the outside, and what the caller is told to do about it. */
class AcquirerFailureApiIT extends IntegrationTest {

  @Autowired private ObjectMapper json;

  private JsonNode problem(ResponseEntity<String> response) throws Exception {
    return json.readTree(response.getBody());
  }

  @Test
  void anAcquirerThatNeverAnswersIsAGatewayTimeoutWithARetryHint() throws Exception {
    simulator.latencyMillis(2_000);

    ResponseEntity<String> response = authorize("key-timeout-1", "4111111111111111");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
    assertThat(problem(response).get("code").asText()).isEqualTo("ACQUIRER_TIMEOUT");
    assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
    assertThat(response.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM authorizations", Long.class))
        .as("no authorization exists until the acquirer has given a definitive answer")
        .isZero();
  }

  @Test
  void aFailedAttemptReleasesTheKeyWithoutReleasingTheDownstreamIdentifier() throws Exception {
    simulator.latencyMillis(2_000);
    assertThat(authorize("key-recover-1", "4111111111111111").getStatusCode())
        .isEqualTo(HttpStatus.GATEWAY_TIMEOUT);

    String preAllocated =
        jdbc.queryForObject(
            "SELECT authorization_id::text FROM idempotency_records WHERE idempotency_key = ?",
            String.class,
            "key-recover-1");
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM idempotency_records WHERE idempotency_key = ?",
                String.class,
                "key-recover-1"))
        .as("the record survives the failure so the pre-allocated id survives with it")
        .isEqualTo("IN_PROGRESS");

    // The acquirer recovers and the client retries with the same key, as the catalogue says to.
    simulator.latencyMillis(0);
    ResponseEntity<String> retry = authorize("key-recover-1", "4111111111111111");

    assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(problem(retry).get("id").asText())
        .as("the retry re-drives the same downstream id rather than creating a second hold")
        .isEqualTo(preAllocated);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM authorizations", Long.class)).isEqualTo(1);
  }

  @Test
  void anUnavailableAcquirerIsAServiceUnavailableWithARetryHint() throws Exception {
    simulator.failureRate(1.0);

    ResponseEntity<String> response = authorize("key-unavailable-1", "4111111111111111");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(problem(response).get("code").asText()).isEqualTo("ACQUIRER_UNAVAILABLE");
    assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("5");
    assertThat(problem(response).get("retryAfter").asInt()).isEqualTo(5);
  }

  @Test
  void anOpenBreakerAnswersImmediatelyAndStopsCallingTheAcquirer() throws Exception {
    simulator.failureRate(1.0);
    for (int i = 0; i < 4; i++) {
      authorize("key-breaker-" + i, "4111111111111111");
    }
    long callsBefore = simulator.requests();

    long startedAt = System.nanoTime();
    ResponseEntity<String> response = authorize("key-breaker-open", "4111111111111111");
    long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(simulator.requests()).as("nothing left the process").isEqualTo(callsBefore);
    assertThat(elapsedMillis)
        .as("an open breaker fails fast rather than spending the retry budget")
        .isLessThan(500);
  }

  @Test
  void anAcquirerWeCannotUnderstandIsABadGatewayAndIsNotRetried() throws Exception {
    ResponseEntity<String> response = authorize("key-protocol-1", "4000000000000101");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    assertThat(problem(response).get("code").asText()).isEqualTo("ACQUIRER_PROTOCOL_ERROR");
    assertThat(simulator.requests()).isEqualTo(1);
    assertThat(response.getBody())
        .as("nothing about our internals leaks into the caller's problem document")
        .doesNotContain("Exception", "com.txnswitch");
  }

  @Test
  void theWrongMethodAndTheWrongMediaTypeAreBothProblemDocuments() throws Exception {
    ResponseEntity<String> wrongMethod =
        rest.exchange(
            "/v1/authorizations",
            HttpMethod.PUT,
            new HttpEntity<>("{}", headers("key-method-1")),
            String.class);
    assertThat(wrongMethod.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    assertThat(problem(wrongMethod).get("code").asText()).isEqualTo("METHOD_NOT_ALLOWED");

    HttpHeaders textHeaders = headers("key-media-1");
    textHeaders.setContentType(MediaType.TEXT_PLAIN);
    ResponseEntity<String> wrongMedia =
        rest.exchange(
            "/v1/authorizations",
            HttpMethod.POST,
            new HttpEntity<>("not json", textHeaders),
            String.class);
    assertThat(wrongMedia.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    assertThat(problem(wrongMedia).get("code").asText()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
  }

  @Test
  void aTooShortIdempotencyKeyFailsValidationRatherThanBeingAccepted() throws Exception {
    ResponseEntity<String> response = authorize("short", "4111111111111111");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(problem(response).get("code").asText()).isEqualTo("VALIDATION_FAILED");
  }

  @Test
  void aBodyThatIsNotJsonIsAMalformedRequest() throws Exception {
    ResponseEntity<String> response = post("/v1/authorizations", "key-garbage-1", "{not json");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(problem(response).get("code").asText()).isEqualTo("MALFORMED_REQUEST");
  }
}
