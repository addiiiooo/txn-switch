/* SPDX-License-Identifier: MIT */
package com.txnswitch.application;

import com.txnswitch.application.port.AuthorizationRepository;
import com.txnswitch.application.port.IdempotencyStore;
import com.txnswitch.domain.authorization.Authorization;
import com.txnswitch.domain.authorization.AuthorizationEvent;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two write boundaries that have to be atomic.
 *
 * <p>Separated from the orchestration because the orchestration must not be transactional: it calls
 * the acquirer, and no database transaction may span that.
 */
@Service
public class AuthorizationWriter {

  private final AuthorizationRepository authorizations;
  private final IdempotencyStore idempotency;

  public AuthorizationWriter(AuthorizationRepository authorizations, IdempotencyStore idempotency) {
    this.authorizations = authorizations;
    this.idempotency = idempotency;
  }

  /**
   * Writes the authorization and completes its idempotency claim together.
   *
   * <p>Atomicity is the entire point: there must be no instant at which a charge exists without the
   * record that would stop it being made twice. If this transaction fails after the acquirer
   * approved, the claim's lease lapses, the next retry with the same key re-drives the same
   * downstream id, and the acquirer's own deduplication returns the original outcome — the system
   * heals rather than double-charging.
   */
  @Transactional
  public void recordAuthorization(
      Authorization authorization,
      AuthorizationEvent event,
      UUID idempotencyRecordId,
      int httpStatus,
      String responseBody,
      String correlationId) {
    authorizations.insert(authorization, event, correlationId);
    idempotency.complete(idempotencyRecordId, httpStatus, responseBody);
  }

  /**
   * Applies a capture or a void.
   *
   * @throws org.springframework.dao.OptimisticLockingFailureException when another transition won
   *     the race; the caller turns that into the truthful state-machine answer
   */
  @Transactional
  public void applyTransition(
      Authorization authorization, AuthorizationEvent event, String correlationId) {
    authorizations.update(authorization, event, correlationId);
  }
}
