/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter.in.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.txnswitch.application.AuthorizationService;
import com.txnswitch.application.AuthorizeCommand;
import com.txnswitch.application.AuthorizeResult;
import com.txnswitch.domain.authorization.Authorization;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(AuthorizationController.PATH)
// Deliberately not @Validated: on Spring 6.1+ that switches on the older AOP method
// validation, which throws ConstraintViolationException and bypasses the problem-document
// handler entirely — a too-short Idempotency-Key came back as a 500. Built-in method
// validation raises HandlerMethodValidationException, which the handler maps properly.
@Tag(name = "Authorizations", description = "Card authorizations and their lifecycle")
public class AuthorizationController {

  static final String PATH = "/v1/authorizations";

  private final AuthorizationService service;
  private final RequestFingerprinter fingerprinter;
  private final ObjectMapper objectMapper;

  public AuthorizationController(
      AuthorizationService service, RequestFingerprinter fingerprinter, ObjectMapper objectMapper) {
    this.service = service;
    this.fingerprinter = fingerprinter;
    this.objectMapper = objectMapper;
  }

  /**
   * Returns the stored bytes on a replay, with the status code the first caller received — a replay
   * is byte-identical, not merely equivalent, which is why the body travels as a string rather than
   * being serialised again here.
   */
  @Operation(
      summary = "Authorize a payment",
      description =
          "Requires an Idempotency-Key. A repeat of the same key with the same body returns the"
              + " original response; with a different body it is refused with 422.")
  @PostMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> authorize(
      MerchantPrincipal principal,
      @Parameter(description = "One per logical request, reused for every retry of it")
          @RequestHeader("Idempotency-Key")
          @Size(min = 8, max = 255) String idempotencyKey,
      @Valid @RequestBody AuthorizeRequest request) {
    AuthorizeCommand command =
        new AuthorizeCommand(
            principal.merchantId(),
            idempotencyKey,
            fingerprinter.fingerprint("POST", PATH, request),
            request.merchantReference(),
            request.amount(),
            request.currency(),
            request.card().pan().value(),
            request.card().expiryMonth(),
            request.card().expiryYear(),
            CorrelationIdFilter.current());

    return switch (service.authorize(command, this::render)) {
      case AuthorizeResult.Created created ->
          ResponseEntity.created(URI.create(PATH + "/" + created.authorization().id()))
              .contentType(MediaType.APPLICATION_JSON)
              .body(created.responseBody());
      case AuthorizeResult.Replayed replayed ->
          ResponseEntity.status(replayed.httpStatus())
              .header("Idempotency-Replayed", "true")
              .contentType(MediaType.APPLICATION_JSON)
              .body(replayed.responseBody());
    };
  }

  @Operation(summary = "Fetch an authorization")
  @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  public AuthorizationResponse get(MerchantPrincipal principal, @PathVariable UUID id) {
    return AuthorizationResponse.from(service.get(id, principal.merchantId()));
  }

  @Operation(
      summary = "Capture an authorization",
      description = "Full amount only; a different amount is refused rather than adjusted.")
  @PostMapping(value = "/{id}/capture", produces = MediaType.APPLICATION_JSON_VALUE)
  public AuthorizationResponse capture(
      MerchantPrincipal principal,
      @PathVariable UUID id,
      @RequestBody(required = false) @Valid CaptureRequest request) {
    Long amount = request == null ? null : request.amount();
    return AuthorizationResponse.from(
        service.capture(id, principal.merchantId(), amount, CorrelationIdFilter.current()));
  }

  @Operation(summary = "Void an authorization")
  @PostMapping(value = "/{id}/void", produces = MediaType.APPLICATION_JSON_VALUE)
  public AuthorizationResponse voidHold(MerchantPrincipal principal, @PathVariable UUID id) {
    return AuthorizationResponse.from(
        service.voidHold(id, principal.merchantId(), CorrelationIdFilter.current()));
  }

  private String render(Authorization authorization) {
    try {
      return objectMapper.writeValueAsString(AuthorizationResponse.from(authorization));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("cannot render an authorization", e);
    }
  }
}
