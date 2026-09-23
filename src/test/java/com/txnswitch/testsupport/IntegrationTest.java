/* SPDX-License-Identifier: MIT */
package com.txnswitch.testsupport;

import com.txnswitch.adapter.simulator.AcquirerSimulatorState;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for every integration test.
 *
 * <p>One container and one Spring context for the whole suite. The container is a singleton started
 * in a static initialiser and never stopped, so that {@code withReuse} can hand the same database
 * back on the next run; a JUnit-managed container would be torn down and the reuse would buy
 * nothing. The price of reuse is that the database is dirty on arrival, so every test truncates
 * first rather than assuming an empty schema.
 *
 * <p>Subclasses must not add {@code @MockBean} or class-level property overrides: each variation
 * forks a second application context and costs several seconds.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Spring Boot switches metrics exporters off under @SpringBootTest. Opting back in here, on
// the shared base, keeps the whole suite on one application context: putting this annotation
// on the one class that asserts on /actuator/prometheus would fork a second context and cost
// several seconds for nothing.
@AutoConfigureObservability
@ActiveProfiles("test")
public abstract class IntegrationTest {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("txnswitch")
          .withUsername("txnswitch")
          .withPassword("txnswitch")
          .withReuse(true);

  static {
    POSTGRES.start();
  }

  @Autowired protected JdbcTemplate jdbc;

  @Autowired protected TransactionTemplate transactionTemplate;

  @Autowired protected AcquirerSimulatorState simulator;

  @Autowired protected CircuitBreakerRegistry circuitBreakers;

  @Autowired protected TestRestTemplate rest;

  @org.springframework.boot.test.web.server.LocalServerPort protected int port;

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @BeforeEach
  void truncateEveryTable() {
    jdbc.execute(
        "TRUNCATE authorization_events, authorizations, idempotency_records RESTART IDENTITY CASCADE");
  }

  /**
   * The simulator and the breaker are process-wide mutable state shared by one application context.
   * Resetting them between tests is what keeps a suite that reuses a context from becoming
   * order-dependent.
   */
  @BeforeEach
  void resetDownstreamState() {
    simulator.reset();
    circuitBreakers.circuitBreaker("acquirer").reset();
  }

  /** The development credential from the default configuration. */
  public static final String API_KEY = "sk_local_demo";

  public static final String MERCHANT_ID = "m_demo";

  protected HttpHeaders headers(String idempotencyKey) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(API_KEY);
    if (idempotencyKey != null) {
      headers.set("Idempotency-Key", idempotencyKey);
    }
    return headers;
  }

  protected ResponseEntity<String> post(String path, String idempotencyKey, String body) {
    return rest.exchange(
        path, HttpMethod.POST, new HttpEntity<>(body, headers(idempotencyKey)), String.class);
  }

  protected ResponseEntity<String> get(String path) {
    return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(null)), String.class);
  }

  protected ResponseEntity<String> authorize(String idempotencyKey, String pan) {
    return post("/v1/authorizations", idempotencyKey, authorizeBody(pan, 1250, "USD", "order-1"));
  }

  protected static String authorizeBody(
      String pan, long amount, String currency, String reference) {
    return """
        {
          "merchantReference": "%s",
          "amount": %d,
          "currency": "%s",
          "card": { "pan": "%s", "expiryMonth": 12, "expiryYear": 2030 }
        }
        """
        .formatted(reference, amount, currency, pan);
  }

  /** Runs a block in its own transaction, the way an application service would. */
  protected <T> T inTransaction(Supplier<T> work) {
    return transactionTemplate.execute(status -> work.get());
  }

  protected void inTransaction(Runnable work) {
    transactionTemplate.executeWithoutResult(
        status -> {
          work.run();
        });
  }
}
