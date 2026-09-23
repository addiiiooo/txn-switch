/* SPDX-License-Identifier: MIT */
package com.txnswitch.application;

import com.txnswitch.application.exception.AuthorizationNotFoundException;
import com.txnswitch.application.exception.IdempotencyInProgressException;
import com.txnswitch.application.exception.IdempotencyKeyReuseException;
import com.txnswitch.application.exception.PartialCaptureNotSupportedException;
import com.txnswitch.application.port.AcquirerException;
import com.txnswitch.application.port.AcquirerGateway;
import com.txnswitch.application.port.AuthorizationRepository;
import com.txnswitch.config.AuthorizationProperties;
import com.txnswitch.domain.acquirer.AcquirerDecision;
import com.txnswitch.domain.authorization.Authorization;
import com.txnswitch.domain.authorization.AuthorizationEvent;
import com.txnswitch.domain.authorization.AuthorizationId;
import com.txnswitch.domain.authorization.AuthorizationStatus;
import com.txnswitch.domain.authorization.IllegalTransitionException;
import com.txnswitch.domain.card.CardDetails;
import com.txnswitch.domain.card.Pan;
import com.txnswitch.domain.idempotency.IdempotencyRecord;
import com.txnswitch.domain.money.InvalidCurrencyException;
import com.txnswitch.domain.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Orchestrates an authorization and its follow-ups.
 *
 * <p>Deliberately <strong>not</strong> transactional. Each authorization is three short
 * transactions with one HTTP call between the first and the last; a transaction spanning that call
 * would pin a pooled connection for the length of a network round trip, and with fifty concurrent
 * duplicates the connection pool would fail before the acquirer did.
 */
@Service
public class AuthorizationService {

  private final IdempotencyService idempotency;
  private final AuthorizationWriter writer;
  private final AuthorizationRepository authorizations;
  private final AcquirerGateway acquirer;
  private final CardFingerprinter fingerprinter;
  private final AuthorizationProperties properties;
  private final Set<Currency> supportedCurrencies;
  private final Clock clock;

  public AuthorizationService(
      IdempotencyService idempotency,
      AuthorizationWriter writer,
      AuthorizationRepository authorizations,
      AcquirerGateway acquirer,
      CardFingerprinter fingerprinter,
      AuthorizationProperties properties,
      Clock clock) {
    this.idempotency = idempotency;
    this.writer = writer;
    this.authorizations = authorizations;
    this.acquirer = acquirer;
    this.fingerprinter = fingerprinter;
    this.properties = properties;
    this.supportedCurrencies = properties.currencies();
    this.clock = clock;
  }

  /**
   * @param renderResponse turns the finished authorization into the response body. The application
   *     stores exactly the bytes the first caller received, so a replay is byte-identical rather
   *     than merely equivalent; that is why the web layer's renderer is handed in here instead of
   *     serialising afterwards.
   */
  public AuthorizeResult authorize(
      AuthorizeCommand command, Function<Authorization, String> renderResponse) {
    // Validate before claiming anything: a malformed request should not consume a key.
    Money amount = Money.of(command.amountMinorUnits(), command.currencyCode());
    requireSupportedCurrency(amount);
    Pan pan = Pan.of(command.pan());
    Instant now = clock.instant();
    CardDetails card =
        CardDetails.from(
            pan,
            command.expiryMonth(),
            command.expiryYear(),
            fingerprinter.fingerprint(pan),
            YearMonth.from(now.atZone(ZoneOffset.UTC)));

    // T1: claim the key, with the authorization id allocated before anything leaves the process.
    AuthorizationId authorizationId = AuthorizationId.generate(now);
    ClaimOutcome outcome =
        idempotency.claim(
            command.merchantId(),
            command.idempotencyKey(),
            command.requestFingerprint(),
            authorizationId.value());

    final IdempotencyRecord claim;
    switch (outcome) {
      case ClaimOutcome.Claimed claimed -> claim = claimed.record();
      case ClaimOutcome.Replay replay -> {
        return new AuthorizeResult.Replayed(replay.httpStatus(), replay.body());
      }
      case ClaimOutcome.FingerprintMismatch ignored ->
          throw new IdempotencyKeyReuseException(command.idempotencyKey());
      case ClaimOutcome.InProgress ignored ->
          throw new IdempotencyInProgressException(
              command.idempotencyKey(), idempotency.retryAfterSeconds());
    }

    // A takeover reuses the claim's id, which may differ from the one just generated.
    AuthorizationId effectiveId = new AuthorizationId(claim.authorizationId());

    // No transaction is open here, on purpose.
    AcquirerDecision decision;
    try {
      decision =
          acquirer.authorize(
              new AcquirerGateway.AuthorizeCommand(
                  effectiveId.value(),
                  command.merchantId(),
                  command.merchantReference(),
                  amount.minorUnits(),
                  amount.currencyCode(),
                  pan,
                  command.expiryMonth(),
                  command.expiryYear(),
                  command.correlationId()));
    } catch (AcquirerException e) {
      // Release rather than delete: the pre-allocated id must survive so the client's retry
      // with the same key deduplicates at the acquirer instead of creating a second hold.
      idempotency.release(claim.id(), e.outcomeUnknown());
      throw e;
    }

    Instant decidedAt = clock.instant();
    Authorization authorization =
        switch (decision) {
          case AcquirerDecision.Approved approved ->
              Authorization.approved(
                  effectiveId,
                  command.merchantId(),
                  command.merchantReference(),
                  amount,
                  card,
                  approved,
                  decidedAt,
                  properties.holdTtl());
          case AcquirerDecision.Declined declined ->
              Authorization.declined(
                  effectiveId,
                  command.merchantId(),
                  command.merchantReference(),
                  amount,
                  card,
                  declined,
                  decidedAt,
                  properties.holdTtl());
        };

    // T2: the authorization and the completed claim land together or not at all.
    String body = renderResponse.apply(authorization);
    writer.recordAuthorization(
        authorization,
        AuthorizationEvent.created(
            authorization.status(), decidedAt, "decision from " + authorization.acquirerName()),
        claim.id(),
        201,
        body,
        command.correlationId());
    return new AuthorizeResult.Created(authorization, body);
  }

  public Authorization get(UUID id, String merchantId) {
    return authorizations
        .find(id, merchantId)
        .orElseThrow(() -> new AuthorizationNotFoundException(id));
  }

  public Authorization capture(
      UUID id, String merchantId, Long requestedAmount, String correlationId) {
    Authorization authorization = get(id, merchantId);
    if (requestedAmount != null && requestedAmount != authorization.amount().minorUnits()) {
      throw new PartialCaptureNotSupportedException(
          requestedAmount, authorization.amount().minorUnits());
    }
    // Refuse an illegal transition before spending a downstream round trip on it.
    authorization.ensureCapturable(clock.instant());

    acquirer.capture(followUp(authorization, correlationId));

    AuthorizationEvent event = authorization.capture(clock.instant());
    applyOrExplainTheRace(
        authorization, event, correlationId, AuthorizationStatus.CAPTURED, id, merchantId);
    return authorization;
  }

  public Authorization voidHold(UUID id, String merchantId, String correlationId) {
    Authorization authorization = get(id, merchantId);
    authorization.ensureVoidable(clock.instant());

    acquirer.voidHold(followUp(authorization, correlationId));

    AuthorizationEvent event = authorization.voidHold(clock.instant());
    applyOrExplainTheRace(
        authorization, event, correlationId, AuthorizationStatus.VOIDED, id, merchantId);
    return authorization;
  }

  private AcquirerGateway.FollowUpCommand followUp(
      Authorization authorization, String correlationId) {
    return new AcquirerGateway.FollowUpCommand(
        authorization.id().value(),
        authorization.amount().minorUnits(),
        authorization.amount().currencyCode(),
        correlationId);
  }

  /**
   * Turns a lost optimistic-lock race into the truthful answer.
   *
   * <p>The loser of a simultaneous capture and void has not hit a server error: it has asked for a
   * transition that is no longer legal, and the state machine says so. Both racers may reach the
   * acquirer, but both carry the same downstream key for their operation, so the acquirer acts
   * once.
   */
  private void applyOrExplainTheRace(
      Authorization authorization,
      AuthorizationEvent event,
      String correlationId,
      AuthorizationStatus attempted,
      UUID id,
      String merchantId) {
    try {
      writer.applyTransition(authorization, event, correlationId);
    } catch (OptimisticLockingFailureException e) {
      Authorization current = get(id, merchantId);
      throw new IllegalTransitionException(current.status(), attempted);
    }
  }

  private void requireSupportedCurrency(Money amount) {
    if (!supportedCurrencies.contains(amount.currency())) {
      throw new InvalidCurrencyException(
          amount.currencyCode() + " is not supported by this deployment");
    }
  }
}
