/* SPDX-License-Identifier: MIT */
package com.txnswitch.application.port;

/**
 * The acquirer did not answer in time.
 *
 * <p>Retryable, but only because every call carries the pre-allocated authorization id as the
 * acquirer's idempotency key. Without that, retrying this exception would be a double charge.
 */
public class AcquirerTimeoutException extends AcquirerException {

  public AcquirerTimeoutException(String message, Throwable cause) {
    super(message, cause);
  }

  @Override
  public boolean outcomeUnknown() {
    return true;
  }
}
