/* SPDX-License-Identifier: MIT */
package com.txnswitch.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.txnswitch.application.MaintenanceService;
import com.txnswitch.application.port.AuthorizationRepository;
import com.txnswitch.application.port.IdempotencyStore;
import com.txnswitch.domain.authorization.Authorization;
import com.txnswitch.domain.authorization.AuthorizationEvent;
import com.txnswitch.domain.authorization.AuthorizationStatus;
import com.txnswitch.domain.idempotency.IdempotencyRecord;
import com.txnswitch.testsupport.Fixtures;
import com.txnswitch.testsupport.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MaintenanceIT extends IntegrationTest {

  @Autowired private MaintenanceService maintenance;
  @Autowired private AuthorizationRepository authorizations;
  @Autowired private IdempotencyStore idempotency;

  private Authorization store(AuthorizationStatus status, Instant expiresAt) {
    Authorization authorization = Fixtures.inStatus(status, expiresAt);
    inTransaction(
        () ->
            authorizations.insert(
                authorization,
                AuthorizationEvent.created(status, Instant.now(), "created"),
                "test"));
    return authorization;
  }

  @Test
  void lapsedHoldsAreMarkedExpiredAndLeaveAnAuditRow() {
    Authorization lapsed = store(AuthorizationStatus.AUTHORIZED, Instant.now().minusSeconds(60));

    assertThat(maintenance.expireLapsedHolds()).isEqualTo(1);

    assertThat(
            authorizations.find(lapsed.id().value(), Fixtures.MERCHANT_ID).orElseThrow().status())
        .isEqualTo(AuthorizationStatus.EXPIRED);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM authorization_events WHERE authorization_id = ?"
                    + " AND to_status = 'EXPIRED'",
                Long.class,
                lapsed.id().value()))
        .isEqualTo(1);
  }

  @Test
  void liveHoldsAndTerminalRowsAreLeftAlone() {
    Authorization live = store(AuthorizationStatus.AUTHORIZED, Instant.now().plusSeconds(3600));
    store(AuthorizationStatus.CAPTURED, Instant.now().minusSeconds(60));

    assertThat(maintenance.expireLapsedHolds()).isZero();
    assertThat(authorizations.find(live.id().value(), Fixtures.MERCHANT_ID).orElseThrow().status())
        .isEqualTo(AuthorizationStatus.AUTHORIZED);
  }

  @Test
  void theSweepIsIdempotentBecauseItOnlyEverSeesLiveHolds() {
    store(AuthorizationStatus.AUTHORIZED, Instant.now().minusSeconds(60));

    assertThat(maintenance.expireLapsedHolds()).isEqualTo(1);
    assertThat(maintenance.expireLapsedHolds()).isZero();
  }

  @Test
  void idempotencyRecordsArePurgedOnceTheyArePastRetention() {
    inTransaction(
        () ->
            idempotency.claim(
                IdempotencyRecord.claim(
                    Fixtures.MERCHANT_ID,
                    "key-purge-1",
                    "fingerprint",
                    UUID.randomUUID(),
                    Instant.now().minus(Duration.ofHours(25)),
                    Duration.ofSeconds(30),
                    Duration.ofHours(24))));

    assertThat(maintenance.purgeExpiredIdempotencyRecords()).isEqualTo(1);
    assertThat(idempotency.find(Fixtures.MERCHANT_ID, "key-purge-1")).isEmpty();
  }

  @Test
  void anAttemptThatReachedTheAcquirerAndNeverCameBackIsCountedAndPublished() {
    IdempotencyRecord abandoned =
        inTransaction(
                () ->
                    idempotency.claim(
                        IdempotencyRecord.claim(
                            Fixtures.MERCHANT_ID,
                            "key-unresolved-1",
                            "fingerprint",
                            UUID.randomUUID(),
                            Instant.now(),
                            Duration.ofSeconds(30),
                            Duration.ofHours(24))))
            .orElseThrow();
    inTransaction(
        () ->
            idempotency.releaseLease(
                abandoned.id(), Instant.now().minus(Duration.ofMinutes(10)), true));

    assertThat(maintenance.reportUnresolvedAttempts()).isEqualTo(1);
    assertThat(rest.getForEntity("/actuator/prometheus", String.class).getBody())
        .containsPattern("txnswitch_unresolved_attempts\\{[^}]*\\} 1\\.0");
  }
}
