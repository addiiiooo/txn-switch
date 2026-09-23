/* SPDX-License-Identifier: MIT */
package com.txnswitch.application.port;

/** The acquirer refused the call, or the circuit breaker refused it on the acquirer's behalf. */
public class AcquirerUnavailableException extends AcquirerException {

  private final boolean reachedAcquirer;

  public AcquirerUnavailableException(String message, boolean reachedAcquirer) {
    super(message);
    this.reachedAcquirer = reachedAcquirer;
  }

  public AcquirerUnavailableException(String message, boolean reachedAcquirer, Throwable cause) {
    super(message, cause);
    this.reachedAcquirer = reachedAcquirer;
  }

  /**
   * False when the call never left this process — an open breaker, or a connection that was
   * refused. That distinction is what keeps the unresolved-attempt gauge honest.
   */
  @Override
  public boolean outcomeUnknown() {
    return reachedAcquirer;
  }
}
