/* SPDX-License-Identifier: MIT */
package com.txnswitch.domain.authorization;

import java.time.Instant;

/**
 * An append-only record of one lifecycle change.
 *
 * <p>Carries no identifiers and no correlation id: those belong to the adapter that writes the row.
 * This is the domain's statement of what happened, not the storage format.
 *
 * @param fromStatus null when the authorization came into existence
 */
public record AuthorizationEvent(
    AuthorizationStatus fromStatus,
    AuthorizationStatus toStatus,
    Instant occurredAt,
    String detail) {

  public static AuthorizationEvent created(AuthorizationStatus status, Instant at, String detail) {
    return new AuthorizationEvent(null, status, at, detail);
  }
}
