/* SPDX-License-Identifier: MIT */
package com.txnswitch.application.port;

/**
 * A failure to obtain a decision from the acquirer.
 *
 * <p>Distinct from a decline, which is a decision. Retry and circuit-breaker policy keys off the
 * subclasses, so adding a new one is a deliberate act with a documented retry stance (ADR-0004).
 */
public abstract class AcquirerException extends RuntimeException {

  protected AcquirerException(String message) {
    super(message);
  }

  protected AcquirerException(String message, Throwable cause) {
    super(message, cause);
  }

  /** Whether the acquirer may have acted on a request we never got an answer to. */
  public abstract boolean outcomeUnknown();
}
