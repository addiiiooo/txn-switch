/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter.out.acquirer;

import com.txnswitch.adapter.out.acquirer.AcquirerMessages.AcknowledgementMessage;
import com.txnswitch.adapter.out.acquirer.AcquirerMessages.AuthorizeMessage;
import com.txnswitch.adapter.out.acquirer.AcquirerMessages.DecisionMessage;
import com.txnswitch.adapter.out.acquirer.AcquirerMessages.FollowUpMessage;
import com.txnswitch.application.port.AcquirerGateway;
import com.txnswitch.application.port.AcquirerProtocolException;
import com.txnswitch.application.port.AcquirerTimeoutException;
import com.txnswitch.application.port.AcquirerUnavailableException;
import com.txnswitch.domain.acquirer.AcquirerDecision;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * The acquirer, over HTTP, with the resilience policy from ADR-0004 attached.
 *
 * <p>Decorator order is Resilience4j's default and is load-bearing: {@code Retry} wraps {@code
 * CircuitBreaker}, so every attempt is recorded by the breaker and a sustained outage opens it in
 * about ten calls rather than thirty. Once open, {@link CallNotPermittedException} is excluded from
 * the retry predicate, so the call fails in microseconds instead of sleeping through three useless
 * backoffs.
 *
 * <p>Every request carries the pre-allocated authorization id as the acquirer's idempotency key.
 * That is what makes retrying a read timeout safe rather than a double charge, and it is why the
 * retry predicate in {@code application.yml} may include timeouts at all.
 *
 * <p>This class holds the only call to {@link com.txnswitch.domain.card.Pan#exposeForAcquirer()} in
 * the codebase. The request body is never logged.
 */
@Component
public class HttpAcquirerClient implements AcquirerGateway {

  private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
  private static final String CORRELATION_ID = "X-Correlation-Id";

  private final RestClient restClient;
  private final AcquirerEndpoint endpoint;
  private final String acquirerName;
  private final MeterRegistry meters;

  public HttpAcquirerClient(
      RestClient acquirerRestClient,
      AcquirerEndpoint endpoint,
      AcquirerProperties properties,
      MeterRegistry meters) {
    this.restClient = acquirerRestClient;
    this.endpoint = endpoint;
    this.acquirerName = properties.name();
    this.meters = meters;
  }

  @Override
  @Retry(name = "acquirer", fallbackMethod = "authorizeFallback")
  @CircuitBreaker(name = "acquirer")
  public AcquirerDecision authorize(AuthorizeCommand command) {
    DecisionMessage decision =
        post(
            "/authorize",
            command.authorizationId().toString(),
            command.correlationId(),
            new AuthorizeMessage(
                command.merchantId(),
                command.merchantReference(),
                command.amountMinorUnits(),
                command.currencyCode(),
                com.txnswitch.adapter.SensitivePan.of(command.pan().exposeForAcquirer()),
                command.expiryMonth(),
                command.expiryYear()),
            DecisionMessage.class);
    return toDecision(decision);
  }

  @Override
  @Retry(name = "acquirer", fallbackMethod = "followUpFallback")
  @CircuitBreaker(name = "acquirer")
  public Acknowledgement capture(FollowUpCommand command) {
    return acknowledge("/capture", "capture", command);
  }

  @Override
  @Retry(name = "acquirer", fallbackMethod = "followUpFallback")
  @CircuitBreaker(name = "acquirer")
  public Acknowledgement voidHold(FollowUpCommand command) {
    return acknowledge("/void", "void", command);
  }

  private Acknowledgement acknowledge(String path, String operation, FollowUpCommand command) {
    AcknowledgementMessage message =
        post(
            path,
            // A follow-up is a different operation on the same authorization, so it needs its own
            // downstream key: reusing the authorization's key would make a capture look like a
            // duplicate authorization.
            command.authorizationId() + ":" + operation,
            command.correlationId(),
            new FollowUpMessage(
                command.authorizationId().toString(),
                command.amountMinorUnits(),
                command.currencyCode()),
            AcknowledgementMessage.class);
    if (message == null || message.reference() == null) {
      throw new AcquirerProtocolException("the acquirer returned no reference for a " + operation);
    }
    return new Acknowledgement(acquirerName, message.reference());
  }

  /** Times one attempt, which is the thing worth measuring: the retry budget sits above this. */
  private <T> T post(
      String path,
      String idempotencyKey,
      String correlationId,
      Object body,
      Class<T> responseType) {
    Timer.Sample sample = Timer.start(meters);
    String result = "success";
    try {
      return restClient
          .post()
          .uri(endpoint.url(path))
          .header(IDEMPOTENCY_KEY, idempotencyKey)
          .header(CORRELATION_ID, correlationId)
          .body(body)
          .retrieve()
          .body(responseType);
    } catch (RestClientResponseException e) {
      result = "http_" + e.getStatusCode().value();
      throw translateStatus(e);
    } catch (ResourceAccessException e) {
      result = "transport_failure";
      throw translateTransport(e);
    } finally {
      sample.stop(
          Timer.builder("txnswitch.acquirer.call.duration")
              .description("One attempt against the acquirer")
              .tag("operation", path.substring(1))
              .tag("result", result)
              .register(meters));
    }
  }

  private static RuntimeException translateStatus(RestClientResponseException e) {
    HttpStatusCode status = e.getStatusCode();
    if (status.is5xxServerError() || status.value() == 429) {
      return new AcquirerUnavailableException("the acquirer answered " + status.value(), true, e);
    }
    return new AcquirerProtocolException("the acquirer rejected the request: " + status.value(), e);
  }

  private static RuntimeException translateTransport(ResourceAccessException e) {
    Throwable cause = e.getCause();
    // A connect timeout means nothing was sent; a read timeout means it may have been. The
    // difference decides whether an unresolved hold is possible, so it is not collapsed.
    if (cause instanceof HttpConnectTimeoutException || cause instanceof ConnectException) {
      return new AcquirerUnavailableException("could not reach the acquirer", false, e);
    }
    if (cause instanceof HttpTimeoutException || cause instanceof java.net.SocketTimeoutException) {
      return new AcquirerTimeoutException("the acquirer did not answer in time", e);
    }
    return new AcquirerUnavailableException("the acquirer call failed", false, e);
  }

  private AcquirerDecision toDecision(DecisionMessage message) {
    if (message == null || message.outcome() == null) {
      throw new AcquirerProtocolException("the acquirer returned no outcome");
    }
    return switch (message.outcome()) {
      case "APPROVED" ->
          new AcquirerDecision.Approved(acquirerName, message.reference(), message.approvalCode());
      case "DECLINED" ->
          new AcquirerDecision.Declined(
              acquirerName, message.reference(), message.declineCode(), message.declineMessage());
      default ->
          throw new AcquirerProtocolException(
              "the acquirer returned an outcome we do not understand");
    };
  }

  /**
   * Runs when the retries are exhausted or an exception is not retryable. Its job is to make sure
   * every failure leaves this class as an {@code AcquirerException}, including the breaker's own.
   */
  @SuppressWarnings("unused")
  private AcquirerDecision authorizeFallback(AuthorizeCommand command, Throwable failure) {
    throw translateFallback(failure);
  }

  @SuppressWarnings("unused")
  private Acknowledgement followUpFallback(FollowUpCommand command, Throwable failure) {
    throw translateFallback(failure);
  }

  private static RuntimeException translateFallback(Throwable failure) {
    if (failure instanceof CallNotPermittedException) {
      return new AcquirerUnavailableException(
          "the acquirer circuit is open; no call was made", false, failure);
    }
    if (failure instanceof RuntimeException runtime) {
      return runtime;
    }
    return new AcquirerUnavailableException("the acquirer call failed", false, failure);
  }
}
