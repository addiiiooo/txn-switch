/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter.out.acquirer;

import com.txnswitch.adapter.out.acquirer.AcquirerMessages.AcknowledgementMessage;
import com.txnswitch.adapter.out.acquirer.AcquirerMessages.AuthorizeMessage;
import com.txnswitch.adapter.out.acquirer.AcquirerMessages.DecisionMessage;
import com.txnswitch.adapter.out.acquirer.AcquirerMessages.FollowUpMessage;
import com.txnswitch.application.port.AcquirerException;
import com.txnswitch.application.port.AcquirerGateway;
import com.txnswitch.application.port.AcquirerProtocolException;
import com.txnswitch.application.port.AcquirerTimeoutException;
import com.txnswitch.application.port.AcquirerUnavailableException;
import com.txnswitch.domain.acquirer.AcquirerDecision;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * The acquirer, over HTTP, with the resilience policy from ADR-0004 attached.
 *
 * <p>The policy is applied in code rather than by annotation, in {@link #resiliently}, because a
 * call needs to remember what its earlier attempts may have done; see there.
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
  private static final String RESILIENCE_INSTANCE = "acquirer";

  private final RestClient restClient;
  private final AcquirerEndpoint endpoint;
  private final String acquirerName;
  private final MeterRegistry meters;
  private final Retry retry;
  private final CircuitBreaker breaker;

  public HttpAcquirerClient(
      RestClient acquirerRestClient,
      AcquirerEndpoint endpoint,
      AcquirerProperties properties,
      MeterRegistry meters,
      RetryRegistry retries,
      CircuitBreakerRegistry breakers) {
    this.restClient = acquirerRestClient;
    this.endpoint = endpoint;
    this.acquirerName = properties.name();
    this.meters = meters;
    this.retry = retries.retry(RESILIENCE_INSTANCE);
    this.breaker = breakers.circuitBreaker(RESILIENCE_INSTANCE);
  }

  @Override
  public AcquirerDecision authorize(AuthorizeCommand command) {
    return resiliently(
        () ->
            toDecision(
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
                    DecisionMessage.class)));
  }

  @Override
  public Acknowledgement capture(FollowUpCommand command) {
    return resiliently(() -> acknowledge("/capture", "capture", command));
  }

  @Override
  public Acknowledgement voidHold(FollowUpCommand command) {
    return resiliently(() -> acknowledge("/void", "void", command));
  }

  /**
   * One logical call: the retry, wrapping the breaker, wrapping a single attempt, plus a memory of
   * the first attempt whose outcome was unknown.
   *
   * <p>The order is load-bearing. With the breaker inside the retry, every attempt is recorded, so
   * a sustained outage opens it in about ten calls rather than thirty; and once it is open, its
   * refusal is outside the retry predicate, so the call fails in microseconds instead of sleeping
   * through the backoffs.
   *
   * <p>The memory is why this is not two annotations. A retry reports only its last failure, and
   * when the breaker opens mid-retry that failure is the breaker's refusal, which on its own says
   * nothing was sent. If an earlier attempt timed out, that is false: the acquirer may hold an
   * approval, and the caller must hear "outcome unknown" so the attempt stays counted until a retry
   * with the same key resolves it.
   */
  private <T> T resiliently(Supplier<T> attempt) {
    AtomicReference<AcquirerException> firstUnknown = new AtomicReference<>();
    Supplier<T> remembered =
        () -> {
          try {
            return attempt.get();
          } catch (AcquirerException e) {
            if (e.outcomeUnknown()) {
              firstUnknown.compareAndSet(null, e);
            }
            throw e;
          }
        };
    try {
      return Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(breaker, remembered))
          .get();
    } catch (RuntimeException failure) {
      throw translateFinal(failure, firstUnknown.get());
    }
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
   * Makes sure every failure leaves this class as an {@code AcquirerException}, including the
   * breaker's own, and that no failure reports a known outcome after an attempt whose outcome was
   * not.
   */
  private static RuntimeException translateFinal(
      RuntimeException failure, AcquirerException earlierUnknown) {
    boolean alreadyUnknown = failure instanceof AcquirerException e && e.outcomeUnknown();
    if (earlierUnknown != null && !alreadyUnknown) {
      String message =
          earlierUnknown.getMessage()
              + ", then "
              + (failure instanceof CallNotPermittedException
                  ? "the circuit opened"
                  : failure.getMessage())
              + "; the outcome is unknown";
      return earlierUnknown instanceof AcquirerTimeoutException
          ? new AcquirerTimeoutException(message, failure)
          : new AcquirerUnavailableException(message, true, failure);
    }
    if (failure instanceof CallNotPermittedException) {
      return new AcquirerUnavailableException(
          "the acquirer circuit is open; no call was made", false, failure);
    }
    return failure;
  }
}
