/* SPDX-License-Identifier: MIT */
package com.txnswitch.domain.authorization;

import com.txnswitch.domain.DomainException;
import java.time.Instant;

/**
 * Raised when an authorization is operated on after its hold has lapsed.
 *
 * <p>Thrown by the aggregate on the strength of the clock alone, so the answer does not depend on
 * whether the background expiry sweeper has run yet.
 */
public class AuthorizationExpiredException extends DomainException {

  private final Instant expiredAt;

  public AuthorizationExpiredException(Instant expiredAt) {
    super("the authorization hold expired at " + expiredAt);
    this.expiredAt = expiredAt;
  }

  public Instant expiredAt() {
    return expiredAt;
  }
}
