/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter.out.acquirer;

/**
 * The wire contract between this service and an acquirer.
 *
 * <p>Shared with the bundled simulator, which implements the same contract. A real acquirer speaks
 * ISO 8583 or a scheme-specific API and would need its own adapter; this is a stand-in whose shape
 * is deliberately unremarkable.
 */
public final class AcquirerMessages {

  private AcquirerMessages() {}

  public record AuthorizeMessage(
      String merchantId,
      String merchantReference,
      long amount,
      String currency,
      String pan,
      int expiryMonth,
      int expiryYear) {}

  public record FollowUpMessage(String authorizationId, long amount, String currency) {}

  /**
   * @param outcome {@code APPROVED} or {@code DECLINED}
   */
  public record DecisionMessage(
      String outcome,
      String reference,
      String approvalCode,
      String declineCode,
      String declineMessage) {}

  public record AcknowledgementMessage(String reference) {}

  public record ErrorMessage(String code, String message) {}
}
