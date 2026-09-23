/* SPDX-License-Identifier: MIT */
package com.txnswitch.application.exception;

/** The same key was presented with a different request body. The original is left untouched. */
public class IdempotencyKeyReuseException extends RuntimeException {

  public IdempotencyKeyReuseException(String idempotencyKey) {
    super("Idempotency-Key '" + idempotencyKey + "' was first used for a different request");
  }
}
