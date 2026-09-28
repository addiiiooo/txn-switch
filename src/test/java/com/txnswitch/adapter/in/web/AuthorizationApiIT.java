/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.txnswitch.testsupport.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class AuthorizationApiIT extends IntegrationTest {

  @Autowired private ObjectMapper json;

  private JsonNode body(ResponseEntity<String> response) throws Exception {
    return json.readTree(response.getBody());
  }

  @Test
  void anApprovedAuthorizationIsCreatedAndAddressable() throws Exception {
    ResponseEntity<String> response = authorize("key-approve-1", "4111111111111111");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    JsonNode created = body(response);
    assertThat(created.get("status").asText()).isEqualTo("AUTHORIZED");
    assertThat(created.get("amount").asLong()).isEqualTo(1250);
    assertThat(created.get("currency").asText()).isEqualTo("USD");
    assertThat(created.get("acquirer").get("approvalCode").asText()).isNotBlank();
    assertThat(created.has("decline")).isFalse();
    assertThat(response.getHeaders().getLocation())
        .hasToString("/v1/authorizations/" + created.get("id").asText());

    ResponseEntity<String> fetched = get("/v1/authorizations/" + created.get("id").asText());
    assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(body(fetched).get("id")).isEqualTo(created.get("id"));
  }

  @Test
  void onlyTheBinAndTheLastFourAreEverReturned() throws Exception {
    JsonNode card = body(authorize("key-card-1", "4111111111111111")).get("card");

    assertThat(card.get("bin").asText()).isEqualTo("411111");
    assertThat(card.get("last4").asText()).isEqualTo("1111");
    assertThat(card.get("brand").asText()).isEqualTo("VISA");
    assertThat(card.toString()).doesNotContain("4111111111111111");
  }

  @Test
  void aDeclineIsAnOutcomeAndNotAnError() throws Exception {
    ResponseEntity<String> response = authorize("key-decline-1", "4000000000000002");

    assertThat(response.getStatusCode())
        .as("the attempt is a resource that exists, is addressable, and replays")
        .isEqualTo(HttpStatus.CREATED);
    JsonNode declined = body(response);
    assertThat(declined.get("status").asText()).isEqualTo("DECLINED");
    assertThat(declined.get("decline").get("code").asText()).isEqualTo("DO_NOT_HONOR");
    assertThat(declined.has("capturedAt")).isFalse();

    ResponseEntity<String> fetched = get("/v1/authorizations/" + declined.get("id").asText());
    assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void anAuthorizedHoldCanBeCaptured() throws Exception {
    String id = body(authorize("key-capture-1", "4111111111111111")).get("id").asText();

    ResponseEntity<String> captured = post("/v1/authorizations/" + id + "/capture", null, "{}");

    assertThat(captured.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(body(captured).get("status").asText()).isEqualTo("CAPTURED");
    assertThat(body(captured).get("capturedAt").asText()).isNotBlank();
  }

  @Test
  void anAuthorizedHoldCanBeVoided() throws Exception {
    String id = body(authorize("key-void-1", "4111111111111111")).get("id").asText();

    ResponseEntity<String> voided = post("/v1/authorizations/" + id + "/void", null, "");

    assertThat(voided.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(body(voided).get("status").asText()).isEqualTo("VOIDED");
  }

  @Test
  void aSecondCaptureIsRefusedByTheStateMachineRatherThanTakingMoneyTwice() throws Exception {
    String id = body(authorize("key-capture-2", "4111111111111111")).get("id").asText();
    post("/v1/authorizations/" + id + "/capture", null, "{}");

    ResponseEntity<String> second = post("/v1/authorizations/" + id + "/capture", null, "{}");

    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    JsonNode problem = body(second);
    assertThat(problem.get("code").asText()).isEqualTo("INVALID_STATE_TRANSITION");
    assertThat(problem.get("currentStatus").asText()).isEqualTo("CAPTURED");
    assertThat(second.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
  }

  @Test
  void aDeclinedAuthorizationCannotBeCaptured() throws Exception {
    String id = body(authorize("key-decline-2", "4000000000000002")).get("id").asText();

    ResponseEntity<String> captured = post("/v1/authorizations/" + id + "/capture", null, "{}");

    assertThat(captured.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(body(captured).get("currentStatus").asText()).isEqualTo("DECLINED");
  }

  @Test
  void aPartialCaptureIsRefusedRatherThanQuietlyWidened() throws Exception {
    String id = body(authorize("key-partial-1", "4111111111111111")).get("id").asText();

    ResponseEntity<String> captured =
        post("/v1/authorizations/" + id + "/capture", null, "{\"amount\": 500}");

    assertThat(captured.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(body(captured).get("code").asText()).isEqualTo("PARTIAL_CAPTURE_NOT_SUPPORTED");
    assertThat(body(get("/v1/authorizations/" + id)).get("status").asText())
        .isEqualTo("AUTHORIZED");
  }

  @Test
  void aCaptureForTheAuthorizedAmountIsAccepted() throws Exception {
    String id = body(authorize("key-full-1", "4111111111111111")).get("id").asText();

    ResponseEntity<String> captured =
        post("/v1/authorizations/" + id + "/capture", null, "{\"amount\": 1250}");

    assertThat(captured.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void anUnknownAuthorizationIsNotFound() throws Exception {
    ResponseEntity<String> response = get("/v1/authorizations/" + UUID.randomUUID());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(body(response).get("code").asText()).isEqualTo("AUTHORIZATION_NOT_FOUND");
    assertThat(body(response).get("correlationId").asText()).isNotBlank();
  }

  @Test
  void aMalformedUuidIsARequestProblemNotAServerError() throws Exception {
    ResponseEntity<String> response = get("/v1/authorizations/not-a-uuid");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    JsonNode problem = body(response);
    assertThat(problem.get("code").asText()).isEqualTo("VALIDATION_FAILED");
    assertThat(problem.get("errors").get(0).get("field").asText()).isEqualTo("id");
    assertThat(problem.get("errors").get(0).get("message").asText())
        .as("described in the API's terms, not the implementation's")
        .isEqualTo("must be a UUID")
        .doesNotContain("java");
  }

  @Test
  void aMissingIdempotencyKeyIsItsOwnError() throws Exception {
    ResponseEntity<String> response =
        post("/v1/authorizations", null, authorizeBody("4111111111111111", 1250, "USD", "o"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(body(response).get("code").asText()).isEqualTo("MISSING_IDEMPOTENCY_KEY");
  }

  @Test
  void aFractionalAmountIsRejectedRatherThanRounded() throws Exception {
    ResponseEntity<String> response =
        post(
            "/v1/authorizations",
            "key-fractional-1",
            """
            {"amount": 12.50, "currency": "USD",
             "card": {"pan": "4111111111111111", "expiryMonth": 12, "expiryYear": 2030}}
            """);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    JsonNode problem = body(response);
    assertThat(problem.get("code").asText()).isEqualTo("MALFORMED_REQUEST");
    assertThat(problem.get("errors").get(0).get("field").asText())
        .as("a client should not have to guess which field was fractional")
        .isEqualTo("amount");
    assertThat(problem.get("errors").get(0).get("code").asText()).isEqualTo("TYPE_MISMATCH");
    assertThat(problem.get("errors").get(0).get("message").asText())
        .isEqualTo("must be a whole number");
  }

  @Test
  void aWrongTypeInANestedFieldIsNamedByItsFullPath() throws Exception {
    ResponseEntity<String> response =
        post(
            "/v1/authorizations",
            "key-nested-type-1",
            """
            {"amount": 1250, "currency": "USD",
             "card": {"pan": "4111111111111111", "expiryMonth": "twelve", "expiryYear": 2030}}
            """);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    JsonNode problem = body(response);
    assertThat(problem.get("code").asText()).isEqualTo("MALFORMED_REQUEST");
    assertThat(problem.get("errors").get(0).get("field").asText()).isEqualTo("card.expiryMonth");
  }

  @Test
  void aNonPositiveAmountFailsValidationWithTheOffendingField() throws Exception {
    ResponseEntity<String> response =
        post(
            "/v1/authorizations",
            "key-zero-1",
            authorizeBody("4111111111111111", 0, "USD", "order-1"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    JsonNode problem = body(response);
    assertThat(problem.get("code").asText()).isEqualTo("VALIDATION_FAILED");
    assertThat(problem.get("errors").get(0).get("field").asText()).isEqualTo("amount");
  }

  @Test
  void aNegativeCaptureAmountFailsValidationWithTheOffendingField() throws Exception {
    String id = body(authorize("key-negcapture-1", "4111111111111111")).get("id").asText();

    ResponseEntity<String> response =
        post("/v1/authorizations/" + id + "/capture", null, "{\"amount\": -5}");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    JsonNode problem = body(response);
    assertThat(problem.get("code").asText()).isEqualTo("VALIDATION_FAILED");
    assertThat(problem.get("errors").get(0).get("field").asText()).isEqualTo("amount");
    assertThat(problem.get("errors").get(0).get("code").asText()).isEqualTo("POSITIVE");
  }

  @Test
  void anUnsupportedCurrencyIsRefused() throws Exception {
    ResponseEntity<String> response =
        post(
            "/v1/authorizations",
            "key-currency-1",
            authorizeBody("4111111111111111", 1250, "CHF", "order-1"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(body(response).get("code").asText()).isEqualTo("UNSUPPORTED_CURRENCY");
  }

  @Test
  void anInvalidCardNumberIsRefusedAndNeverEchoed() throws Exception {
    ResponseEntity<String> response =
        post(
            "/v1/authorizations",
            "key-luhn-1",
            authorizeBody("4111111111111112", 1250, "USD", "order-1"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(body(response).get("code").asText()).isEqualTo("CARD_INVALID");
    assertThat(response.getBody()).doesNotContain("4111111111111112");
  }

  @Test
  void anExpiredCardIsRefused() throws Exception {
    ResponseEntity<String> response =
        post(
            "/v1/authorizations",
            "key-expired-1",
            """
            {"amount": 1250, "currency": "USD",
             "card": {"pan": "4111111111111111", "expiryMonth": 1, "expiryYear": 2020}}
            """);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(body(response).get("code").asText()).isEqualTo("CARD_EXPIRED");
  }

  @Test
  void anUnknownPathIsDistinguishableFromAnUnknownAuthorization() throws Exception {
    ResponseEntity<String> response = get("/v1/nothing-here");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(body(response).get("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
  }

  @Test
  void everyProblemCarriesTheContractMembers() throws Exception {
    JsonNode problem = body(get("/v1/authorizations/" + UUID.randomUUID()));

    assertThat(problem.get("type").asText())
        .isEqualTo(
            "https://github.com/addiiiooo/txn-switch/blob/main/docs/errors.md#authorization_not_found");
    assertThat(problem.get("title").asText()).isNotBlank();
    assertThat(problem.get("status").asInt()).isEqualTo(404);
    assertThat(problem.get("detail").asText()).isNotBlank();
    assertThat(problem.get("code").asText()).isEqualTo("AUTHORIZATION_NOT_FOUND");
    assertThat(problem.get("correlationId").asText()).isNotBlank();
  }

  @Test
  void theCorrelationIdIsEchoedAndReusedWhenSupplied() {
    org.springframework.http.HttpHeaders headers = headers("key-correlation-1");
    headers.set("X-Correlation-Id", "corr-supplied-1");

    ResponseEntity<String> response =
        rest.exchange(
            "/v1/authorizations",
            org.springframework.http.HttpMethod.POST,
            new org.springframework.http.HttpEntity<>(
                authorizeBody("4111111111111111", 1250, "USD", "order-1"), headers),
            String.class);

    assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo("corr-supplied-1");
    assertThat(
            jdbc.queryForObject(
                "SELECT correlation_id FROM authorization_events LIMIT 1", String.class))
        .isEqualTo("corr-supplied-1");
  }

  @Test
  void theApiDocumentsItselfAndDoesNotAdvertiseTheSimulator() {
    ResponseEntity<String> apiDocs = rest.getForEntity("/v3/api-docs", String.class);

    assertThat(apiDocs.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(apiDocs.getBody())
        .contains("/v1/authorizations")
        .contains("/v1/authorizations/{id}/capture")
        .as("the simulator is a demo affordance, not part of the API")
        .doesNotContain("__simulator");
    assertThat(rest.getForEntity("/swagger-ui.html", String.class).getStatusCode())
        .isIn(HttpStatus.OK, HttpStatus.FOUND, HttpStatus.MOVED_PERMANENTLY);
  }

  @Test
  void aGeneratedCorrelationIdIsReturnedWhenNoneIsSupplied() {
    ResponseEntity<String> response = authorize("key-correlation-2", "4111111111111111");

    assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isNotBlank();
  }
}
