/* SPDX-License-Identifier: MIT */
package com.txnswitch.domain.authorization;

import com.txnswitch.domain.DomainException;

/** Raised when an operation is attempted from a state that does not permit it. */
public class IllegalTransitionException extends DomainException {

  private final AuthorizationStatus from;
  private final AuthorizationStatus to;

  public IllegalTransitionException(AuthorizationStatus from, AuthorizationStatus to) {
    super("cannot move an authorization from " + from + " to " + to);
    this.from = from;
    this.to = to;
  }

  public AuthorizationStatus from() {
    return from;
  }

  public AuthorizationStatus to() {
    return to;
  }
}
