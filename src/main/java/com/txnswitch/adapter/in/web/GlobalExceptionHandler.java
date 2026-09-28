/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter.in.web;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.txnswitch.application.exception.AuthorizationNotFoundException;
import com.txnswitch.application.exception.IdempotencyInProgressException;
import com.txnswitch.application.exception.IdempotencyKeyReuseException;
import com.txnswitch.application.exception.PartialCaptureNotSupportedException;
import com.txnswitch.application.port.AcquirerProtocolException;
import com.txnswitch.application.port.AcquirerTimeoutException;
import com.txnswitch.application.port.AcquirerUnavailableException;
import com.txnswitch.domain.authorization.AuthorizationExpiredException;
import com.txnswitch.domain.authorization.IllegalTransitionException;
import com.txnswitch.domain.card.CardExpiredException;
import com.txnswitch.domain.card.InvalidCardException;
import com.txnswitch.domain.money.InvalidAmountException;
import com.txnswitch.domain.money.InvalidCurrencyException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Every failure leaves through here as an RFC 9457 problem document.
 *
 * <p>Spring's own exceptions are mapped as well as ours, so there is no route through the framework
 * that returns a different shape. The catch-all deliberately reveals nothing but a code and a
 * correlation id: an exception message is exactly where a card number or a connection string
 * escapes.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  private final ProblemFactory problems;

  public GlobalExceptionHandler(ProblemFactory problems) {
    this.problems = problems;
  }

  @ExceptionHandler(InvalidAmountException.class)
  ResponseEntity<ProblemDetail> handleAmount(InvalidAmountException e) {
    return respond(ErrorCode.AMOUNT_OUT_OF_RANGE, e.getMessage());
  }

  @ExceptionHandler(InvalidCurrencyException.class)
  ResponseEntity<ProblemDetail> handleCurrency(InvalidCurrencyException e) {
    return respond(ErrorCode.UNSUPPORTED_CURRENCY, e.getMessage());
  }

  @ExceptionHandler(InvalidCardException.class)
  ResponseEntity<ProblemDetail> handleCard(InvalidCardException e) {
    return respond(ErrorCode.CARD_INVALID, e.getMessage());
  }

  @ExceptionHandler(CardExpiredException.class)
  ResponseEntity<ProblemDetail> handleExpiredCard(CardExpiredException e) {
    return respond(ErrorCode.CARD_EXPIRED, e.getMessage());
  }

  @ExceptionHandler(PartialCaptureNotSupportedException.class)
  ResponseEntity<ProblemDetail> handlePartialCapture(PartialCaptureNotSupportedException e) {
    return respond(ErrorCode.PARTIAL_CAPTURE_NOT_SUPPORTED, e.getMessage());
  }

  @ExceptionHandler(IdempotencyKeyReuseException.class)
  ResponseEntity<ProblemDetail> handleKeyReuse(IdempotencyKeyReuseException e) {
    return respond(ErrorCode.IDEMPOTENCY_KEY_REUSE, e.getMessage());
  }

  @ExceptionHandler(IdempotencyInProgressException.class)
  ResponseEntity<ProblemDetail> handleInProgress(IdempotencyInProgressException e) {
    ProblemDetail problem =
        problems.create(ErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS, e.getMessage());
    return withRetryAfter(
        ErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS, problem, e.retryAfterSeconds());
  }

  @ExceptionHandler(AuthorizationNotFoundException.class)
  ResponseEntity<ProblemDetail> handleNotFound(AuthorizationNotFoundException e) {
    return respond(ErrorCode.AUTHORIZATION_NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler(IllegalTransitionException.class)
  ResponseEntity<ProblemDetail> handleIllegalTransition(IllegalTransitionException e) {
    ProblemDetail problem = problems.create(ErrorCode.INVALID_STATE_TRANSITION, e.getMessage());
    // The truth about where the authorization actually is, so no follow-up GET is needed.
    problem.setProperty("currentStatus", e.from().name());
    problem.setProperty("requestedStatus", e.to().name());
    return ResponseEntity.status(ErrorCode.INVALID_STATE_TRANSITION.status()).body(problem);
  }

  @ExceptionHandler(AuthorizationExpiredException.class)
  ResponseEntity<ProblemDetail> handleExpired(AuthorizationExpiredException e) {
    ProblemDetail problem = problems.create(ErrorCode.AUTHORIZATION_EXPIRED, e.getMessage());
    problem.setProperty("expiredAt", e.expiredAt().toString());
    return ResponseEntity.status(ErrorCode.AUTHORIZATION_EXPIRED.status()).body(problem);
  }

  @ExceptionHandler(AcquirerTimeoutException.class)
  ResponseEntity<ProblemDetail> handleAcquirerTimeout(AcquirerTimeoutException e) {
    log.warn("Acquirer timed out: {}", e.getMessage());
    ProblemDetail problem =
        problems.create(
            ErrorCode.ACQUIRER_TIMEOUT,
            "The acquirer did not answer in time. Retry with the same Idempotency-Key.");
    return withRetryAfter(ErrorCode.ACQUIRER_TIMEOUT, problem, 1);
  }

  @ExceptionHandler(AcquirerUnavailableException.class)
  ResponseEntity<ProblemDetail> handleAcquirerUnavailable(AcquirerUnavailableException e) {
    log.warn("Acquirer unavailable: {}", e.getMessage());
    ProblemDetail problem =
        problems.create(
            ErrorCode.ACQUIRER_UNAVAILABLE,
            "The acquirer is unavailable. Retry with the same Idempotency-Key.");
    return withRetryAfter(ErrorCode.ACQUIRER_UNAVAILABLE, problem, 5);
  }

  @ExceptionHandler(AcquirerProtocolException.class)
  ResponseEntity<ProblemDetail> handleAcquirerProtocol(AcquirerProtocolException e) {
    log.error("Acquirer protocol failure: {}", e.getMessage());
    return respond(
        ErrorCode.ACQUIRER_PROTOCOL_ERROR, "The acquirer answered with something unusable.");
  }

  /**
   * The database is unreachable: a condition, not a bug. Readiness reports it too, so a load
   * balancer should already be routing elsewhere; this answers the callers still arriving here.
   */
  @ExceptionHandler({
    DataAccessResourceFailureException.class,
    TransientDataAccessResourceException.class,
    CannotCreateTransactionException.class
  })
  ResponseEntity<ProblemDetail> handleDatabaseUnavailable(Exception e) {
    log.warn("Database unavailable: {}", e.getMessage());
    ProblemDetail problem =
        problems.create(
            ErrorCode.SERVICE_UNAVAILABLE,
            "The service is temporarily unavailable. Retry shortly; an authorization is safe to"
                + " retry with the same Idempotency-Key.");
    return withRetryAfter(ErrorCode.SERVICE_UNAVAILABLE, problem, 5);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> handleAnythingElse(Exception e) {
    // The correlation id is the only thing the caller gets; the detail stays in the logs.
    log.error("Unhandled failure", e);
    return respond(ErrorCode.INTERNAL_ERROR, "The request could not be completed.");
  }

  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException e,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    // Still MALFORMED_REQUEST, as the catalogue promises; but when one field's type is the
    // problem, the caller is told which, in the shape VALIDATION_FAILED already uses.
    if (e.getCause() instanceof MismatchedInputException mismatch
        && !mismatch.getPath().isEmpty()) {
      ProblemDetail problem =
          problems.create(ErrorCode.MALFORMED_REQUEST, "A field has the wrong JSON type.");
      problem.setProperty(
          "errors",
          List.of(
              Map.of(
                  "field", jsonPath(mismatch.getPath()),
                  "code", "TYPE_MISMATCH",
                  "message", expectedType(mismatch.getTargetType()))));
      return asObject(ResponseEntity.status(ErrorCode.MALFORMED_REQUEST.status()).body(problem));
    }
    return asObject(
        respond(
            ErrorCode.MALFORMED_REQUEST,
            "The request body is not valid JSON, or a field has the wrong type."));
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException e,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<Map<String, String>> errors = new ArrayList<>();
    e.getBindingResult()
        .getFieldErrors()
        .forEach(
            error ->
                errors.add(
                    Map.of(
                        "field", error.getField(),
                        "code", constraintCode(error.getCode()),
                        "message", String.valueOf(error.getDefaultMessage()))));
    ProblemDetail problem =
        problems.create(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.");
    problem.setProperty("errors", errors);
    return asObject(ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status()).body(problem));
  }

  @Override
  protected ResponseEntity<Object> handleHandlerMethodValidationException(
      HandlerMethodValidationException e,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    // A method with any constrained parameter routes ALL of its validation through here,
    // including @Valid @RequestBody. Unwrapping ParameterErrors is what keeps the response
    // naming the offending field rather than the name of the controller argument.
    List<Map<String, String>> errors = new ArrayList<>();
    for (ParameterValidationResult result : e.getAllValidationResults()) {
      if (result instanceof ParameterErrors parameterErrors) {
        parameterErrors
            .getFieldErrors()
            .forEach(
                error ->
                    errors.add(
                        Map.of(
                            "field", error.getField(),
                            "code", constraintCode(error.getCode()),
                            "message", String.valueOf(error.getDefaultMessage()))));
      } else {
        String parameter = String.valueOf(result.getMethodParameter().getParameterName());
        result
            .getResolvableErrors()
            .forEach(
                error ->
                    errors.add(
                        Map.of(
                            "field", parameter,
                            "code", constraintCode(firstCode(error.getCodes())),
                            "message", String.valueOf(error.getDefaultMessage()))));
      }
    }
    ProblemDetail problem =
        problems.create(ErrorCode.VALIDATION_FAILED, "One or more parameters are invalid.");
    problem.setProperty("errors", errors);
    return asObject(ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status()).body(problem));
  }

  @Override
  protected ResponseEntity<Object> handleServletRequestBindingException(
      ServletRequestBindingException e,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    if (e instanceof MissingRequestHeaderException missing) {
      ErrorCode code =
          "Idempotency-Key".equalsIgnoreCase(missing.getHeaderName())
              ? ErrorCode.MISSING_IDEMPOTENCY_KEY
              : ErrorCode.VALIDATION_FAILED;
      return asObject(respond(code, "The " + missing.getHeaderName() + " header is required."));
    }
    return asObject(respond(ErrorCode.VALIDATION_FAILED, "The request could not be bound."));
  }

  @Override
  protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(
      HttpRequestMethodNotSupportedException e,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    return asObject(respond(ErrorCode.METHOD_NOT_ALLOWED, e.getMessage()));
  }

  @Override
  protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(
      HttpMediaTypeNotSupportedException e,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    return asObject(
        respond(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "Send Content-Type: application/json."));
  }

  @Override
  protected ResponseEntity<Object> handleNoResourceFoundException(
      NoResourceFoundException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    return asObject(respond(ErrorCode.RESOURCE_NOT_FOUND, "No such resource."));
  }

  @Override
  protected ResponseEntity<Object> handleTypeMismatch(
      TypeMismatchException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    ProblemDetail problem =
        problems.create(ErrorCode.VALIDATION_FAILED, "A path or query value has the wrong type.");
    problem.setProperty(
        "errors",
        List.of(
            Map.of(
                "field",
                String.valueOf(e.getPropertyName()),
                "code",
                "TYPE_MISMATCH",
                "message",
                expectedType(e.getRequiredType()))));
    return asObject(ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status()).body(problem));
  }

  /** A field's position in the request body, the way a client would write it: card.expiryMonth. */
  private static String jsonPath(List<JsonMappingException.Reference> path) {
    StringBuilder out = new StringBuilder();
    for (JsonMappingException.Reference reference : path) {
      if (reference.getFieldName() != null) {
        out.append(out.isEmpty() ? "" : ".").append(reference.getFieldName());
      } else if (reference.getIndex() >= 0) {
        out.append('[').append(reference.getIndex()).append(']');
      }
    }
    return out.toString();
  }

  /** What a value should have been, in the API's terms rather than the implementation's. */
  private static String expectedType(Class<?> type) {
    if (type == null) {
      return "has the wrong type";
    }
    if (type == UUID.class) {
      return "must be a UUID";
    }
    if (type == Long.class || type == long.class || type == Integer.class || type == int.class) {
      return "must be a whole number";
    }
    if (Number.class.isAssignableFrom(type) || type == double.class || type == float.class) {
      return "must be a number";
    }
    if (type == Boolean.class || type == boolean.class) {
      return "must be true or false";
    }
    if (CharSequence.class.isAssignableFrom(type)) {
      return "must be a string";
    }
    return "has the wrong type";
  }

  private ResponseEntity<ProblemDetail> respond(ErrorCode code, String detail) {
    return ResponseEntity.status(code.status()).body(problems.create(code, detail));
  }

  private ResponseEntity<ProblemDetail> withRetryAfter(
      ErrorCode code, ProblemDetail problem, int seconds) {
    problem.setProperty("retryAfter", seconds);
    return ResponseEntity.status(code.status())
        .header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds))
        .body(problem);
  }

  @SuppressWarnings("unchecked")
  private static ResponseEntity<Object> asObject(ResponseEntity<ProblemDetail> response) {
    return (ResponseEntity<Object>) (ResponseEntity<?>) response;
  }

  private static String firstCode(String[] codes) {
    return codes == null || codes.length == 0 ? null : codes[codes.length - 1];
  }

  /** {@code NotNull} becomes {@code NOT_NULL}: a code a client can branch on. */
  private static String constraintCode(String constraint) {
    if (constraint == null) {
      return "INVALID";
    }
    return constraint
        .replaceAll("([a-z])([A-Z])", "$1_$2")
        .toUpperCase(Locale.ROOT)
        .replace("_CONSTRAINT", "");
  }
}
