/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.txnswitch.testsupport.IntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The card number is accepted, forwarded to the acquirer, and then gone.
 *
 * <p>Stated as a property rather than a habit: after a real authorization, the full number appears
 * in no log line, no response body, and no column of any row. The database check casts whole rows
 * to text, so a column added next year is covered without anyone remembering to update this test.
 *
 * <p>The requests here go through a bare JDK HTTP client rather than {@code TestRestTemplate},
 * because the test's own client runs in this JVM and would log the request body it is sending —
 * which would be the test framing the application for a leak it did not commit.
 */
class PanLeakageIT extends IntegrationTest {

  private static final String PAN = "4111111111111111";
  private static final List<String> TABLES =
      List.of("authorizations", "idempotency_records", "authorization_events");

  private HttpResponse<String> send(String method, String path, String body) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", "Bearer " + API_KEY)
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", "key-pan-leak-1");
    request =
        body == null
            ? request.GET()
            : request.method(method, HttpRequest.BodyPublishers.ofString(body));
    return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void theFullCardNumberReachesNoLogLineNoResponseAndNoColumn() throws Exception {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    ch.qos.logback.classic.Logger root =
        context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
    ListAppender<ILoggingEvent> captured = new ListAppender<>();
    captured.setContext(context);
    captured.start();
    Level original = root.getLevel();
    // Everything turned up: the interesting failure is some framework logger at DEBUG
    // deciding to print a request body or the object it just bound.
    root.setLevel(Level.DEBUG);
    root.addAppender(captured);

    HttpResponse<String> created;
    HttpResponse<String> replayed;
    HttpResponse<String> fetched;
    try {
      String body = authorizeBody(PAN, 1250, "USD", "order-1");
      created = send("POST", "/v1/authorizations", body);
      replayed = send("POST", "/v1/authorizations", body);
      String id = created.body().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
      fetched = send("GET", "/v1/authorizations/" + id, null);
    } finally {
      root.detachAppender(captured);
      root.setLevel(original);
      captured.stop();
    }

    assertThat(created.statusCode()).isEqualTo(201);
    assertThat(created.body()).doesNotContain(PAN);
    assertThat(replayed.body()).doesNotContain(PAN);
    assertThat(fetched.body()).doesNotContain(PAN);

    List<String> leaking =
        captured.list.stream()
            .filter(
                event ->
                    event.getFormattedMessage().contains(PAN)
                        || String.valueOf(event.getThrowableProxy()).contains(PAN))
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
    assertThat(leaking).as("log lines containing the card number").isEmpty();
    assertThat(captured.list)
        .as("this assertion is worthless if nothing was logged at all")
        .hasSizeGreaterThan(10);

    for (String table : TABLES) {
      Long rows =
          jdbc.queryForObject(
              "SELECT count(*) FROM " + table + " t WHERE t::text LIKE ?",
              Long.class,
              "%" + PAN + "%");
      assertThat(rows).as("rows of %s containing the card number", table).isZero();
    }

    assertThat(
            jdbc.queryForObject(
                "SELECT card_bin || ' ' || card_last4 FROM authorizations LIMIT 1", String.class))
        .as("what is kept instead")
        .isEqualTo("411111 1111");
    assertThat(
            jdbc.queryForObject(
                "SELECT length(card_fingerprint) FROM authorizations LIMIT 1", Integer.class))
        .as("a keyed HMAC, not the number")
        .isEqualTo(64);
  }

  @Test
  void aRejectedCardIsNotEchoedInTheProblemDocument() {
    var response =
        post(
            "/v1/authorizations",
            "key-pan-leak-2",
            authorizeBody("4111111111111112", 1250, "USD", "order-1"));

    assertThat(response.getBody()).doesNotContain("4111111111111112");
  }

  @Test
  void aSensitivePanCannotBePrintedByAccident() {
    assertThat(com.txnswitch.adapter.SensitivePan.of(PAN))
        .hasToString("411111***1111")
        .extracting(com.txnswitch.adapter.SensitivePan::value)
        .isEqualTo(PAN);
    assertThat(com.txnswitch.adapter.SensitivePan.of(null)).hasToString("***");
    assertThat(com.txnswitch.adapter.SensitivePan.of("123").toString()).isEqualTo("***");
  }
}
