/* SPDX-License-Identifier: MIT */
package com.txnswitch.application;

import com.txnswitch.domain.idempotency.IdempotencyRecord;
import java.util.UUID;

/**
 * What happened when a request tried to claim its idempotency key.
 *
 * <p>Sealed, so adding a fifth possibility is a compile error everywhere it matters rather than a
 * silent fall-through in the one place somebody forgot.
 */
public sealed interface ClaimOutcome {

  /** This caller owns the key and should proceed to the acquirer. */
  record Claimed(IdempotencyRecord record) implements ClaimOutcome {}

  /** The key is complete and the original response is returned verbatim. */
  record Replay(int httpStatus, String body, UUID authorizationId) implements ClaimOutcome {}

  /** The key was first used for a different request. */
  record FingerprintMismatch() implements ClaimOutcome {}

  /** Someone else is working on this key right now. */
  record InProgress() implements ClaimOutcome {}
}
