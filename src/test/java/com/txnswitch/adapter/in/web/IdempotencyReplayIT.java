/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.txnswitch.application.port.IdempotencyStore;
import com.txnswitch.domain.idempotency.IdempotencyRecord;
import com.txnswitch.testsupport.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** The replay contract, and the two ways a repeated key can be something other than a replay. */
class IdempotencyReplayIT extends IntegrationTest {

  @Autowired private ObjectMapper json;
  @Autowired private RequestFingerprinter fingerprinter;
  @Autowired private IdempotencyStore store;

  private String fingerprintOf(String reference, long amount, String currency, String pan) {
    return fingerprinter.fingerprint(
        "POST",
        "/v1/authorizations",
        new AuthorizeRequest(
            reference,
            amount,
            currency,
            new AuthorizeRequest.Card(com.txnswitch.adapter.SensitivePan.of(pan), 12, 2030)));
  }

  @Test
  void aRepeatOfTheSameRequestReturnsTheOriginalResponseByteForByte() {
    ResponseEntity<String> first = authorize("key-replay-1", "4111111111111111");

    ResponseEntity<String> replay = authorize("key-replay-1", "4111111111111111");

    assertThat(replay.getStatusCode())
        .as("the original status code, not a 200 consolation prize")
        .isEqualTo(HttpStatus.CREATED);
    assertThat(replay.getBody()).isEqualTo(first.getBody());
    assertThat(replay.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
    assertThat(first.getHeaders().getFirst("Idempotency-Replayed")).isNull();
    assertThat(replay.getHeaders().getLocation())
        .as("the client whose first response was lost needs this more than anyone")
        .isNotNull()
        .isEqualTo(first.getHeaders().getLocation());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM authorizations", Long.class)).isEqualTo(1);
    assertThat(simulator.executions()).as("no second hold").isEqualTo(1);
  }

  @Test
  void aRepeatIsStillAReplayWhenTheFieldsArriveInADifferentOrder() {
    ResponseEntity<String> first = authorize("key-reorder-1", "4111111111111111");

    ResponseEntity<String> reordered =
        post(
            "/v1/authorizations",
            "key-reorder-1",
            """
            {"card": {"expiryYear": 2030, "pan": "4111111111111111", "expiryMonth": 12},
             "currency": "USD", "amount": 1250, "merchantReference": "order-1"}
            """);

    assertThat(reordered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(reordered.getBody())
        .as("field order is not part of the request's identity")
        .isEqualTo(first.getBody());
  }

  @Test
  void aDeclineReplaysLikeAnyOtherOutcome() {
    ResponseEntity<String> first = authorize("key-replay-decline", "4000000000000002");

    ResponseEntity<String> replay = authorize("key-replay-decline", "4000000000000002");

    assertThat(replay.getBody()).isEqualTo(first.getBody());
    assertThat(replay.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
    assertThat(replay.getHeaders().getLocation())
        .as("a decline is a created resource too")
        .isNotNull()
        .isEqualTo(first.getHeaders().getLocation());
  }

  @Test
  void theSameKeyWithADifferentBodyIsRefusedAndTheOriginalIsUntouched() throws Exception {
    ResponseEntity<String> first = authorize("key-mismatch-1", "4111111111111111");
    String originalId = json.readTree(first.getBody()).get("id").asText();

    ResponseEntity<String> different =
        post(
            "/v1/authorizations",
            "key-mismatch-1",
            authorizeBody("4111111111111111", 9999, "USD", "order-1"));

    assertThat(different.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(json.readTree(different.getBody()).get("code").asText())
        .isEqualTo("IDEMPOTENCY_KEY_REUSE");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM authorizations", Long.class)).isEqualTo(1);
    assertThat(
            json.readTree(get("/v1/authorizations/" + originalId).getBody()).get("amount").asLong())
        .isEqualTo(1250);
  }

  @Test
  void aKeyThatIsBeingWorkedOnRightNowIsRefusedWithARetryHint() throws Exception {
    // A live claim, exactly as a concurrent request would have left it.
    inTransaction(
        () ->
            store.claim(
                IdempotencyRecord.claim(
                    MERCHANT_ID,
                    "key-inflight-1",
                    fingerprintOf("order-1", 1250, "USD", "4111111111111111"),
                    UUID.randomUUID(),
                    Instant.now(),
                    Duration.ofSeconds(30),
                    Duration.ofHours(24))));

    ResponseEntity<String> response = authorize("key-inflight-1", "4111111111111111");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(json.readTree(response.getBody()).get("code").asText())
        .isEqualTo("IDEMPOTENCY_REQUEST_IN_PROGRESS");
    assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
    assertThat(simulator.requests()).as("nothing reached the acquirer").isZero();
  }

  @Test
  void aDifferentBodyIsRefusedEvenWhileTheKeyIsStillInProgress() throws Exception {
    inTransaction(
        () ->
            store.claim(
                IdempotencyRecord.claim(
                    MERCHANT_ID,
                    "key-inflight-2",
                    fingerprintOf("order-1", 1250, "USD", "4111111111111111"),
                    UUID.randomUUID(),
                    Instant.now(),
                    Duration.ofSeconds(30),
                    Duration.ofHours(24))));

    ResponseEntity<String> response =
        post(
            "/v1/authorizations",
            "key-inflight-2",
            authorizeBody("4111111111111111", 7777, "USD", "order-1"));

    assertThat(json.readTree(response.getBody()).get("code").asText())
        .isEqualTo("IDEMPOTENCY_KEY_REUSE");
  }

  @Test
  void anAbandonedClaimIsTakenOverAndReusesThePreAllocatedDownstreamIdentifier() throws Exception {
    UUID preAllocated = UUID.randomUUID();
    inTransaction(
        () ->
            store.claim(
                IdempotencyRecord.claim(
                    MERCHANT_ID,
                    "key-abandoned-1",
                    fingerprintOf("order-1", 1250, "USD", "4111111111111111"),
                    preAllocated,
                    Instant.now(),
                    Duration.ofSeconds(30),
                    Duration.ofHours(24))));
    // The holder died: no authorization was ever written, and the lease has since lapsed.
    jdbc.update(
        "UPDATE idempotency_records SET lease_expires_at = now() - interval '1 minute'"
            + " WHERE idempotency_key = 'key-abandoned-1'");

    ResponseEntity<String> response = authorize("key-abandoned-1", "4111111111111111");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(json.readTree(response.getBody()).get("id").asText())
        .as("a takeover must re-drive the same downstream id, not allocate a new one")
        .isEqualTo(preAllocated.toString());
    assertThat(
            jdbc.queryForObject(
                "SELECT attempts FROM idempotency_records WHERE idempotency_key = 'key-abandoned-1'",
                Integer.class))
        .isEqualTo(2);
  }

  @Test
  void aKeyIsScopedToItsMerchant() throws Exception {
    ResponseEntity<String> mine = authorize("shared-key-1", "4111111111111111");

    HttpHeaders otherMerchant = new HttpHeaders();
    otherMerchant.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
    otherMerchant.setBearerAuth("sk_local_other");
    otherMerchant.set("Idempotency-Key", "shared-key-1");
    ResponseEntity<String> theirs =
        rest.exchange(
            "/v1/authorizations",
            HttpMethod.POST,
            new HttpEntity<>(
                authorizeBody("4111111111111111", 1250, "USD", "order-1"), otherMerchant),
            String.class);

    assertThat(theirs.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    JsonNode theirsBody = json.readTree(theirs.getBody());
    assertThat(theirsBody.get("id").asText())
        .isNotEqualTo(json.readTree(mine.getBody()).get("id").asText());
    assertThat(theirsBody.get("merchantId").asText()).isEqualTo("m_other");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM authorizations", Long.class)).isEqualTo(2);
  }

  @Test
  void anotherMerchantCannotSeeMyAuthorization() throws Exception {
    String id =
        json.readTree(authorize("key-tenant-1", "4111111111111111").getBody()).get("id").asText();

    HttpHeaders otherMerchant = new HttpHeaders();
    otherMerchant.setBearerAuth("sk_local_other");
    ResponseEntity<String> response =
        rest.exchange(
            "/v1/authorizations/" + id,
            HttpMethod.GET,
            new HttpEntity<>(otherMerchant),
            String.class);

    assertThat(response.getStatusCode())
        .as("404, not 403: confirming existence across tenants is itself a leak")
        .isEqualTo(HttpStatus.NOT_FOUND);
  }
}
