/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.txnswitch.testsupport.IntegrationTest;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The requirement this whole design exists for: fifty identical requests arriving at once must
 * produce one authorization and one hold.
 *
 * <p>The assertions are deliberately about outcomes rather than about which response each thread
 * got. Exactly one request creates the authorization; the rest either learn that a request with
 * their key is already in flight (409) or arrive late enough to be handed the original response (a
 * replay). Asserting "one 201 and forty-nine 409s" would be asserting the thread scheduling of the
 * machine running the test, and would fail on a slower one for no good reason.
 */
class ConcurrentAuthorizationIT extends IntegrationTest {

  private static final int CONCURRENCY = 50;

  @Test
  void fiftyIdenticalRequestsCreateExactlyOneAuthorizationAndOneHold() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
    CountDownLatch startLine = new CountDownLatch(1);
    try {
      List<Future<ResponseEntity<String>>> inFlight =
          IntStream.range(0, CONCURRENCY)
              .mapToObj(
                  i ->
                      pool.submit(
                          () -> {
                            startLine.await();
                            return authorize("key-stampede-1", "4111111111111111");
                          }))
              .toList();

      startLine.countDown();
      List<ResponseEntity<String>> responses = new java.util.ArrayList<>();
      for (Future<ResponseEntity<String>> future : inFlight) {
        responses.add(future.get(30, TimeUnit.SECONDS));
      }

      assertThat(jdbc.queryForObject("SELECT count(*) FROM authorizations", Long.class))
          .as("exactly one authorization row")
          .isEqualTo(1);
      assertThat(simulator.executions()).as("exactly one hold at the acquirer").isEqualTo(1);
      assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records", Long.class))
          .isEqualTo(1);

      List<ResponseEntity<String>> created =
          responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED).toList();
      List<ResponseEntity<String>> refused =
          responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).toList();

      assertThat(created.size() + refused.size())
          .as("every request got a defined answer: created, replayed, or already in progress")
          .isEqualTo(CONCURRENCY);

      long fresh =
          created.stream()
              .filter(r -> r.getHeaders().getFirst("Idempotency-Replayed") == null)
              .count();
      assertThat(fresh).as("exactly one request created the authorization").isEqualTo(1);

      assertThat(created)
          .extracting(ResponseEntity::getBody)
          .containsOnly(created.get(0).getBody());
      assertThat(refused)
          .allSatisfy(r -> assertThat(r.getBody()).contains("IDEMPOTENCY_REQUEST_IN_PROGRESS"));

      // And afterwards, a sequential retry with the same key replays the original bytes.
      ResponseEntity<String> afterwards = authorize("key-stampede-1", "4111111111111111");
      assertThat(afterwards.getStatusCode()).isEqualTo(HttpStatus.CREATED);
      assertThat(afterwards.getBody()).isEqualTo(created.get(0).getBody());
      assertThat(afterwards.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
      assertThat(jdbc.queryForObject("SELECT count(*) FROM authorizations", Long.class))
          .isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void fiftyDistinctRequestsAllSucceed() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
    CountDownLatch startLine = new CountDownLatch(1);
    try {
      List<Future<ResponseEntity<String>>> inFlight =
          IntStream.range(0, CONCURRENCY)
              .mapToObj(
                  i ->
                      pool.submit(
                          () -> {
                            startLine.await();
                            return authorize("key-distinct-" + i, "4111111111111111");
                          }))
              .toList();
      startLine.countDown();
      for (Future<ResponseEntity<String>> future : inFlight) {
        assertThat(future.get(30, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.CREATED);
      }

      assertThat(jdbc.queryForObject("SELECT count(*) FROM authorizations", Long.class))
          .as("deduplication must not collapse genuinely different requests")
          .isEqualTo(CONCURRENCY);
      assertThat(simulator.executions()).isEqualTo(CONCURRENCY);
    } finally {
      pool.shutdownNow();
    }
  }
}
