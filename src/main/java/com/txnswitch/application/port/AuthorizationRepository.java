/* SPDX-License-Identifier: MIT */
package com.txnswitch.application.port;

import com.txnswitch.domain.authorization.Authorization;
import com.txnswitch.domain.authorization.AuthorizationEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage for authorizations and their audit trail.
 *
 * <p>Exists so the application layer never sees a JPA type, which is what lets the domain stay free
 * of persistence annotations. Reads are scoped by merchant at the query, not filtered afterwards.
 */
public interface AuthorizationRepository {

  /** Writes a new authorization and its creation event. Fails if the id is already taken. */
  void insert(Authorization authorization, AuthorizationEvent event, String correlationId);

  /**
   * Applies a state change with an optimistic version check.
   *
   * @throws org.springframework.dao.OptimisticLockingFailureException if the row moved on since it
   *     was read, which happens when a capture and a void race
   */
  void update(Authorization authorization, AuthorizationEvent event, String correlationId);

  Optional<Authorization> find(UUID id, String merchantId);

  /**
   * Claims a batch of lapsed holds for the expiry sweeper, skipping rows another instance is
   * already working on. Must be called inside a transaction: the rows stay locked until it ends.
   */
  List<Authorization> lockLapsedHolds(Instant now, int limit);
}
