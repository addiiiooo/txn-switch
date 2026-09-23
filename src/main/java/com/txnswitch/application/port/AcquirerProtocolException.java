/* SPDX-License-Identifier: MIT */
package com.txnswitch.application.port;

/**
 * The acquirer answered with something we cannot act on, or rejected our request as malformed.
 *
 * <p>Never retried: a second identical request produces the same answer, and spending the retry
 * budget on it only delays the error the caller already has coming.
 */
public class AcquirerProtocolException extends AcquirerException {

  public AcquirerProtocolException(String message) {
    super(message);
  }

  public AcquirerProtocolException(String message, Throwable cause) {
    super(message, cause);
  }

  @Override
  public boolean outcomeUnknown() {
    return false;
  }
}
