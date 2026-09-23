/* SPDX-License-Identifier: MIT */
package com.txnswitch.application.port;

import com.txnswitch.domain.acquirer.AcquirerDecision;
import com.txnswitch.domain.card.Pan;
import java.util.UUID;

/**
 * The downstream acquirer.
 *
 * <p>Every command carries an {@code authorizationId} that was allocated before the first call and
 * is used as the acquirer's idempotency key. That is the contract this gateway depends on: a
 * repeated command must return the original outcome rather than act twice.
 */
public interface AcquirerGateway {

  AcquirerDecision authorize(AuthorizeCommand command);

  Acknowledgement capture(FollowUpCommand command);

  Acknowledgement voidHold(FollowUpCommand command);

  /**
   * @param pan discarded by the caller as soon as this call returns
   */
  record AuthorizeCommand(
      UUID authorizationId,
      String merchantId,
      String merchantReference,
      long amountMinorUnits,
      String currencyCode,
      Pan pan,
      int expiryMonth,
      int expiryYear,
      String correlationId) {}

  record FollowUpCommand(
      UUID authorizationId, long amountMinorUnits, String currencyCode, String correlationId) {}

  record Acknowledgement(String acquirerName, String acquirerReference) {}
}
