/* SPDX-License-Identifier: MIT */
package com.txnswitch.application.exception;

/**
 * A capture asked for an amount other than the authorized one.
 *
 * <p>Refused explicitly rather than rounded up to a full capture: silently capturing a different
 * amount than the caller asked for is a money bug wearing a convenience costume.
 */
public class PartialCaptureNotSupportedException extends RuntimeException {

  public PartialCaptureNotSupportedException(long requested, long authorized) {
    super("requested capture of " + requested + " differs from the authorized " + authorized);
  }
}
