/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter.out.persistence;

import com.txnswitch.application.port.AuthorizationRepository;
import com.txnswitch.domain.authorization.Authorization;
import com.txnswitch.domain.authorization.AuthorizationEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

@Component
public class AuthorizationRepositoryAdapter implements AuthorizationRepository {

  private final AuthorizationJpaRepository authorizations;
  private final AuthorizationEventJpaRepository events;

  public AuthorizationRepositoryAdapter(
      AuthorizationJpaRepository authorizations, AuthorizationEventJpaRepository events) {
    this.authorizations = authorizations;
    this.events = events;
  }

  @Override
  public void insert(Authorization authorization, AuthorizationEvent event, String correlationId) {
    authorizations.save(PersistenceMapper.toNewEntity(authorization.snapshot()));
    recordEvent(authorization.id().value(), event, correlationId);
  }

  @Override
  public void update(Authorization authorization, AuthorizationEvent event, String correlationId) {
    // saveAndFlush, not save: the version conflict must surface here, inside the call that
    // caused it, rather than at an arbitrary point when the transaction commits.
    authorizations.saveAndFlush(PersistenceMapper.toEntity(authorization.snapshot()));
    recordEvent(authorization.id().value(), event, correlationId);
  }

  @Override
  public Optional<Authorization> find(UUID id, String merchantId) {
    return authorizations.findByIdAndMerchantId(id, merchantId).map(PersistenceMapper::toDomain);
  }

  @Override
  public List<Authorization> lockLapsedHolds(Instant now, int limit) {
    return authorizations.lockLapsedHolds(now, Limit.of(limit)).stream()
        .map(PersistenceMapper::toDomain)
        .toList();
  }

  private void recordEvent(UUID authorizationId, AuthorizationEvent event, String correlationId) {
    events.save(
        new AuthorizationEventEntity(
            authorizationId,
            event.fromStatus(),
            event.toStatus(),
            event.detail(),
            correlationId,
            event.occurredAt()));
  }
}
