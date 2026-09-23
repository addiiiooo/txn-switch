/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.txnswitch.application.port.AuthorizationRepository;
import com.txnswitch.domain.authorization.Authorization;
import com.txnswitch.domain.authorization.AuthorizationEvent;
import com.txnswitch.domain.authorization.AuthorizationStatus;
import com.txnswitch.testsupport.Fixtures;
import com.txnswitch.testsupport.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

class AuthorizationPersistenceIT extends IntegrationTest {

  @Autowired private AuthorizationRepository repository;

  @Test
  void aRoundTripThroughPostgresPreservesEveryField() {
    Authorization original = Fixtures.approved();

    inTransaction(
        () ->
            repository.insert(
                original,
                AuthorizationEvent.created(AuthorizationStatus.AUTHORIZED, Fixtures.NOW, "created"),
                "corr-1"));

    Authorization loaded =
        repository.find(original.id().value(), Fixtures.MERCHANT_ID).orElseThrow();
    assertThat(loaded.snapshot()).isEqualTo(original.snapshot());
  }

  @Test
  void readsAreScopedByMerchant() {
    Authorization authorization = Fixtures.approved();
    inTransaction(
        () ->
            repository.insert(
                authorization,
                AuthorizationEvent.created(AuthorizationStatus.AUTHORIZED, Fixtures.NOW, "created"),
                "corr-1"));

    assertThat(repository.find(authorization.id().value(), "m_someone_else")).isEmpty();
    assertThat(repository.find(authorization.id().value(), Fixtures.MERCHANT_ID)).isPresent();
  }

  @Test
  void everyTransitionLeavesAnAuditRow() {
    Authorization authorization = Fixtures.approved();
    inTransaction(
        () ->
            repository.insert(
                authorization,
                AuthorizationEvent.created(AuthorizationStatus.AUTHORIZED, Fixtures.NOW, "created"),
                "corr-1"));

    AuthorizationEvent captured = authorization.capture(Fixtures.NOW.plusSeconds(30));
    inTransaction(() -> repository.update(authorization, captured, "corr-2"));

    List<Map<String, Object>> events =
        jdbc.queryForList(
            "SELECT from_status, to_status, correlation_id FROM authorization_events"
                + " WHERE authorization_id = ? ORDER BY id",
            authorization.id().value());
    assertThat(events)
        .extracting(row -> row.get("to_status"), row -> row.get("correlation_id"))
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("AUTHORIZED", "corr-1"),
            org.assertj.core.groups.Tuple.tuple("CAPTURED", "corr-2"));
    assertThat(events.get(0).get("from_status")).isNull();
    assertThat(events.get(1).get("from_status")).isEqualTo("AUTHORIZED");
  }

  @Test
  void aStaleWriteLosesToTheVersionCheck() {
    Authorization authorization = Fixtures.approved();
    inTransaction(
        () ->
            repository.insert(
                authorization,
                AuthorizationEvent.created(AuthorizationStatus.AUTHORIZED, Fixtures.NOW, "created"),
                "corr-1"));

    // Two readers, as a capture and a void arriving at the same moment would be.
    Authorization first = repository.find(authorization.id().value(), Fixtures.MERCHANT_ID).get();
    Authorization second = repository.find(authorization.id().value(), Fixtures.MERCHANT_ID).get();

    AuthorizationEvent capture = first.capture(Fixtures.NOW.plusSeconds(10));
    inTransaction(() -> repository.update(first, capture, "corr-2"));

    AuthorizationEvent voided = second.voidHold(Fixtures.NOW.plusSeconds(10));
    assertThatExceptionOfType(OptimisticLockingFailureException.class)
        .isThrownBy(() -> inTransaction(() -> repository.update(second, voided, "corr-3")));

    Authorization survivor =
        repository.find(authorization.id().value(), Fixtures.MERCHANT_ID).get();
    assertThat(survivor.status()).isEqualTo(AuthorizationStatus.CAPTURED);
  }

  @Test
  void theSweeperClaimsOnlyLapsedHoldsThatAreStillLive() {
    Instant now = Fixtures.NOW;
    Authorization lapsed = Fixtures.inStatus(AuthorizationStatus.AUTHORIZED, now.minusSeconds(1));
    Authorization live =
        Fixtures.inStatus(AuthorizationStatus.AUTHORIZED, now.plus(Duration.ofDays(1)));
    Authorization alreadyCaptured =
        Fixtures.inStatus(AuthorizationStatus.CAPTURED, now.minusSeconds(1));
    for (Authorization authorization : List.of(lapsed, live, alreadyCaptured)) {
      inTransaction(
          () ->
              repository.insert(
                  authorization,
                  AuthorizationEvent.created(authorization.status(), now, "created"),
                  "corr"));
    }

    List<Authorization> claimed = inTransaction(() -> repository.lockLapsedHolds(now, 10));

    assertThat(claimed).extracting(a -> a.id().value()).containsExactly(lapsed.id().value());
  }

  @Test
  void theSweeperBatchIsBounded() {
    Instant now = Fixtures.NOW;
    for (int i = 0; i < 5; i++) {
      Authorization authorization =
          Fixtures.inStatus(AuthorizationStatus.AUTHORIZED, now.minusSeconds(i + 1L));
      inTransaction(
          () ->
              repository.insert(
                  authorization,
                  AuthorizationEvent.created(AuthorizationStatus.AUTHORIZED, now, "created"),
                  "corr"));
    }

    assertThat(inTransaction(() -> repository.lockLapsedHolds(now, 2))).hasSize(2);
  }

  @Test
  void theDatabaseRefusesANonPositiveAmountEvenIfTheApplicationForgets() {
    assertThatExceptionOfType(DataIntegrityViolationException.class)
        .isThrownBy(
            () ->
                jdbc.update(
                    """
                    INSERT INTO authorizations (id, merchant_id, amount_minor, currency, status,
                        card_bin, card_last4, card_brand, card_exp_month, card_exp_year,
                        card_fingerprint, created_at, updated_at, expires_at, version)
                    VALUES (gen_random_uuid(), 'm_demo', 0, 'USD', 'AUTHORIZED', '411111', '1111',
                        'VISA', 12, 2030, 'f', now(), now(), now(), 0)
                    """));
  }

  @Test
  void anUnknownAuthorizationIsSimplyAbsent() {
    assertThat(repository.find(Fixtures.approved().id().value(), Fixtures.MERCHANT_ID))
        .isEqualTo(Optional.empty());
  }
}
