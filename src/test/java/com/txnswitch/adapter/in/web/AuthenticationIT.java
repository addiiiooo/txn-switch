/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

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

/** The merchant identity comes from a credential, so there is a credential to get wrong. */
class AuthenticationIT extends IntegrationTest {

  @Autowired private ObjectMapper json;

  private ResponseEntity<String> authorizeWith(HttpHeaders headers) {
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", "key-auth-1");
    return rest.exchange(
        "/v1/authorizations",
        HttpMethod.POST,
        new HttpEntity<>(authorizeBody("4111111111111111", 1250, "USD", "order-1"), headers),
        String.class);
  }

  @Test
  void aRequestWithNoCredentialIsRefusedBeforeAnythingHappens() throws Exception {
    ResponseEntity<String> response = authorizeWith(new HttpHeaders());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(json.readTree(response.getBody()).get("code").asText())
        .isEqualTo("MISSING_CREDENTIALS");
    assertThat(response.getHeaders().getFirst("WWW-Authenticate")).isEqualTo("Bearer");
    assertThat(response.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records", Long.class))
        .as("an unauthenticated request claims nothing")
        .isZero();
    assertThat(simulator.requests()).isZero();
  }

  @Test
  void anUnknownKeyIsRefusedWithoutSayingAnythingUseful() throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth("sk_not_a_real_key");

    ResponseEntity<String> response = authorizeWith(headers);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(json.readTree(response.getBody()).get("code").asText())
        .isEqualTo("INVALID_CREDENTIALS");
    assertThat(response.getBody())
        .as("no hint about which keys exist, or how close this one was")
        .doesNotContain("sk_local_demo", "m_demo", "sk_not_a_real_key");
  }

  @Test
  void anUnparseableAuthorizationHeaderIsTreatedAsMissing() throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "Basic bm90OmJlYXJlcg==");

    ResponseEntity<String> response = authorizeWith(headers);

    assertThat(json.readTree(response.getBody()).get("code").asText())
        .isEqualTo("MISSING_CREDENTIALS");
  }

  @Test
  void everyFailureIsStillTraceable() throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Correlation-Id", "corr-unauthenticated");

    ResponseEntity<String> response = authorizeWith(headers);

    assertThat(json.readTree(response.getBody()).get("correlationId").asText())
        .as("authentication runs after the correlation filter so a 401 can still be traced")
        .isEqualTo("corr-unauthenticated");
    assertThat(response.getHeaders().getFirst("X-Correlation-Id"))
        .isEqualTo("corr-unauthenticated");
  }

  @Test
  void theMerchantComesFromTheCredentialAndNotFromAHeader() throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(API_KEY);
    headers.set("X-Merchant-Id", "m_someone_else");

    ResponseEntity<String> response = authorizeWith(headers);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(json.readTree(response.getBody()).get("merchantId").asText())
        .as("a header a caller can set is not a trust boundary")
        .isEqualTo("m_demo");
  }

  @Test
  void theProbesAndTheMetricsAreDeliberatelyOpen() {
    assertThat(rest.getForEntity("/actuator/health/liveness", String.class).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(rest.getForEntity("/actuator/prometheus", String.class).getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }
}
