/* SPDX-License-Identifier: MIT */
package com.txnswitch.domain.idempotency;

public enum IdempotencyState {
  /** Claimed by some attempt. Whether that attempt is still alive is the lease's business. */
  IN_PROGRESS,
  /** A definitive response has been stored and will be replayed verbatim. */
  COMPLETED
}
