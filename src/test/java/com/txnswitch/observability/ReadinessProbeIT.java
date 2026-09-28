/* SPDX-License-Identifier: MIT */
package com.txnswitch.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Readiness has to mean something, so this test takes the database away.
 *
 * <p>One of the two classes in the suite that deliberately does not share the common context: it
 * needs a database it is allowed to destroy, and destroying the shared one would make every test
 * that ran afterwards depend on the order it ran in. The extra container and context cost a few
 * seconds, which is a fair price for the one probe an orchestrator acts on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ReadinessProbeIT {

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer<?> DISPOSABLE =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("txnswitch")
          .withUsername("txnswitch")
          .withPassword("txnswitch");

  static {
    DISPOSABLE.start();
  }

  @Autowired private TestRestTemplate rest;

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", DISPOSABLE::getJdbcUrl);
    registry.add("spring.datasource.username", DISPOSABLE::getUsername);
    registry.add("spring.datasource.password", DISPOSABLE::getPassword);
    // Fail fast rather than making the probe wait out a long pool timeout.
    registry.add("spring.datasource.hikari.connection-timeout", () -> "1000");
    registry.add("spring.datasource.hikari.initialization-fail-timeout", () -> "-1");
  }

  @Test
  void readinessFollowsTheDatabaseWhileLivenessDoesNot() {
    assertThat(rest.getForEntity("/actuator/health/readiness", String.class).getStatusCode())
        .isEqualTo(HttpStatus.OK);

    DISPOSABLE.stop();

    assertThat(rest.getForEntity("/actuator/health/readiness", String.class).getStatusCode())
        .as("an instance that cannot reach its database must leave the rotation")
        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(rest.getForEntity("/actuator/health/liveness", String.class).getStatusCode())
        .as("but the process is fine, and restarting it would not bring the database back")
        .isEqualTo(HttpStatus.OK);

    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth("sk_local_demo");
    ResponseEntity<String> call =
        rest.exchange(
            "/v1/authorizations/" + UUID.randomUUID(),
            HttpMethod.GET,
            new HttpEntity<>(headers),
            String.class);
    assertThat(call.getStatusCode())
        .as("a caller still routed here is told to come back, not that it found a bug")
        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(call.getBody()).contains("\"code\":\"SERVICE_UNAVAILABLE\"");
    assertThat(call.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
  }
}
