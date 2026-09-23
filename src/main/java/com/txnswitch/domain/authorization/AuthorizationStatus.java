/* SPDX-License-Identifier: MIT */
package com.txnswitch.domain.authorization;

/**
 * Lifecycle of an authorization.
 *
 * <p>There is no {@code FAILED} member on purpose: an {@code Authorization} exists only once the
 * acquirer has given a definitive answer. An attempt whose outcome is unknown is tracked on the
 * idempotency record, not here, so that nothing in this table can be mistaken for a hold that was
 * never placed.
 */
public enum AuthorizationStatus {
  AUTHORIZED,
  DECLINED,
  CAPTURED,
  VOIDED,
  EXPIRED;

  /** The whole state machine, in one exhaustive switch. */
  public boolean canTransitionTo(AuthorizationStatus target) {
    return switch (this) {
      case AUTHORIZED -> target == CAPTURED || target == VOIDED || target == EXPIRED;
      case DECLINED, CAPTURED, VOIDED, EXPIRED -> false;
    };
  }

  public boolean isTerminal() {
    return this != AUTHORIZED;
  }
}
