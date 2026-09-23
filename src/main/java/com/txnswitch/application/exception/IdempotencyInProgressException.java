/* SPDX-License-Identifier: MIT */
package com.txnswitch.application.exception;

/**
 * An identical request with this key is being worked on right now.
 *
 * <p>The expected answer for concurrent duplicates. The caller retries with the same key and gets
 * the original response; the alternative — holding this request's thread until the winner finishes
 * — would make every duplicate cost a thread for the length of an acquirer call (ADR-0002).
 */
public class IdempotencyInProgressException extends RuntimeException {

  private final int retryAfterSeconds;

  public IdempotencyInProgressException(String idempotencyKey, int retryAfterSeconds) {
    super("a request with Idempotency-Key '" + idempotencyKey + "' is already in progress");
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public int retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
