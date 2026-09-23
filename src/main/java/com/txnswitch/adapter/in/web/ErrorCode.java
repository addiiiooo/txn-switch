/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter.in.web;

import org.springframework.http.HttpStatus;

/**
 * The machine-readable error contract.
 *
 * <p>The constant name is the contract and never changes meaning; the title is prose and may be
 * reworded freely. {@code docs/errors.md} is the normative catalogue, and {@code
 * ErrorCatalogueTest} fails the build if this enum and that document disagree — a catalogue nobody
 * checks is a catalogue that is wrong within a month.
 */
public enum ErrorCode {
  MISSING_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Missing credentials"),
  INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid credentials"),

  MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Malformed request"),
  VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request validation failed"),
  MISSING_IDEMPOTENCY_KEY(HttpStatus.BAD_REQUEST, "Missing Idempotency-Key header"),
  METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),
  UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"),
  UNSUPPORTED_CURRENCY(HttpStatus.UNPROCESSABLE_ENTITY, "Unsupported currency"),
  AMOUNT_OUT_OF_RANGE(HttpStatus.UNPROCESSABLE_ENTITY, "Amount out of range"),
  CARD_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid card number"),
  CARD_EXPIRED(HttpStatus.UNPROCESSABLE_ENTITY, "Card expired"),
  PARTIAL_CAPTURE_NOT_SUPPORTED(HttpStatus.UNPROCESSABLE_ENTITY, "Partial capture not supported"),

  IDEMPOTENCY_KEY_REUSE(
      HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency key reused with a different request"),
  IDEMPOTENCY_REQUEST_IN_PROGRESS(HttpStatus.CONFLICT, "A request with this key is in progress"),

  RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
  AUTHORIZATION_NOT_FOUND(HttpStatus.NOT_FOUND, "Authorization not found"),
  INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "Invalid state transition"),
  AUTHORIZATION_EXPIRED(HttpStatus.CONFLICT, "Authorization expired"),

  ACQUIRER_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "The acquirer did not answer in time"),
  ACQUIRER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "The acquirer is unavailable"),
  ACQUIRER_PROTOCOL_ERROR(HttpStatus.BAD_GATEWAY, "The acquirer answered unexpectedly"),

  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");

  private final HttpStatus status;
  private final String title;

  ErrorCode(HttpStatus status, String title) {
    this.status = status;
    this.title = title;
  }

  public HttpStatus status() {
    return status;
  }

  public String title() {
    return title;
  }
}
