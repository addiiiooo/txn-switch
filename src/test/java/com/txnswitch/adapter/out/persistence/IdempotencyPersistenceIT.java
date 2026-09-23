/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.txnswitch.application.port.IdempotencyStore;
import com.txnswitch.domain.idempotency.IdempotencyRecord;
import com.txnswitch.domain.idempotency.IdempotencyState;
import com.txnswitch.testsupport.Fixtures;
import com.txnswitch.testsupport.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class IdempotencyPersistenceIT extends IntegrationTest {

  private static final Duration LEASE = Duration.ofSeconds(30);
  private static final Duration TTL = Duration.ofHours(24);

  @Autowired private IdempotencyStore store;

  private IdempotencyRecord candidate(String key) {
    return IdempotencyRecord.claim(
        Fixtures.MERCHANT_ID, key, "fingerprint-a", UUID.randomUUID(), Fixtures.NOW, LEASE, TTL);
  }

  @Test
  void theFirstClaimOnAKeyWinsAndTheSecondIsTurnedAway() {
    IdempotencyRecord first = candidate("key-1");
    IdempotencyRecord second = candidate("key-1");

    Optional<IdempotencyRecord> won = inTransaction(() -> store.claim(first));
    Optional<IdempotencyRecord> lost = inTransaction(() -> store.claim(second));

    assertThat(won).isPresent();
    assertThat(lost).as("the unique index is the arbiter").isEmpty();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records", Long.class))
        .isEqualTo(1);
  }

  @Test
  void theSameKeyUnderADifferentMerchantIsADifferentClaim() {
    inTransaction(() -> store.claim(candidate("key-1")));

    IdempotencyRecord other =
        IdempotencyRecord.claim(
            "m_other", "key-1", "fingerprint-a", UUID.randomUUID(), Fixtures.NOW, LEASE, TTL);

    assertThat(inTransaction(() -> store.claim(other))).isPresent();
  }

  @Test
  void aClaimIsReadBackWithEveryFieldIntact() {
    IdempotencyRecord claimed = inTransaction(() -> store.claim(candidate("key-1"))).orElseThrow();

    IdempotencyRecord loaded = store.find(Fixtures.MERCHANT_ID, "key-1").orElseThrow();

    assertThat(loaded.snapshot()).isEqualTo(claimed.snapshot());
    assertThat(loaded.state()).isEqualTo(IdempotencyState.IN_PROGRESS);
    assertThat(loaded.downstreamAttempted())
        .as("a claim is only ever taken in order to send")
        .isTrue();
  }

  @Test
  void aLiveLeaseCannotBeTakenOverAndALapsedOneCan() {
    IdempotencyRecord claimed = inTransaction(() -> store.claim(candidate("key-1"))).orElseThrow();
    UUID id = claimed.id();

    assertThat(inTransaction(() -> store.takeOverLease(id, Fixtures.NOW.plusSeconds(5), LEASE)))
        .as("the original holder is still working")
        .isFalse();

    Instant afterLease = Fixtures.NOW.plus(LEASE);
    assertThat(inTransaction(() -> store.takeOverLease(id, afterLease, LEASE))).isTrue();

    IdempotencyRecord loaded = store.find(Fixtures.MERCHANT_ID, "key-1").orElseThrow();
    assertThat(loaded.attempts()).isEqualTo(2);
    assertThat(loaded.authorizationId())
        .as("a takeover must reuse the downstream identifier, not allocate a new one")
        .isEqualTo(claimed.authorizationId());
  }

  @Test
  void onlyOneOfTwoSimultaneousTakeoversWins() {
    IdempotencyRecord claimed = inTransaction(() -> store.claim(candidate("key-1"))).orElseThrow();
    Instant afterLease = Fixtures.NOW.plus(LEASE);

    boolean first = inTransaction(() -> store.takeOverLease(claimed.id(), afterLease, LEASE));
    boolean second = inTransaction(() -> store.takeOverLease(claimed.id(), afterLease, LEASE));

    assertThat(first).isTrue();
    assertThat(second).as("the conditional update is the test").isFalse();
  }

  @Test
  void releasingALeaseKeepsTheRecordAndItsPreAllocatedId() {
    IdempotencyRecord claimed = inTransaction(() -> store.claim(candidate("key-1"))).orElseThrow();

    inTransaction(() -> store.releaseLease(claimed.id(), Fixtures.NOW.plusSeconds(2), true));

    IdempotencyRecord loaded = store.find(Fixtures.MERCHANT_ID, "key-1").orElseThrow();
    assertThat(loaded.state()).isEqualTo(IdempotencyState.IN_PROGRESS);
    assertThat(loaded.authorizationId()).isEqualTo(claimed.authorizationId());
    assertThat(loaded.isLeaseHeldAt(Fixtures.NOW.plusSeconds(2))).isFalse();
  }

  @Test
  void releasingCanClearTheDownstreamFlagWhenNothingWasEverSent() {
    IdempotencyRecord claimed = inTransaction(() -> store.claim(candidate("key-1"))).orElseThrow();

    inTransaction(() -> store.releaseLease(claimed.id(), Fixtures.NOW.plusSeconds(2), false));

    assertThat(store.find(Fixtures.MERCHANT_ID, "key-1").orElseThrow().downstreamAttempted())
        .isFalse();
  }

  @Test
  void completingStoresTheResponseBytesToReplay() {
    IdempotencyRecord claimed = inTransaction(() -> store.claim(candidate("key-1"))).orElseThrow();
    String body = "{\"id\":\"x\",\"status\":\"AUTHORIZED\"}";

    inTransaction(() -> store.complete(claimed.id(), 201, body));

    IdempotencyRecord loaded = store.find(Fixtures.MERCHANT_ID, "key-1").orElseThrow();
    assertThat(loaded.isCompleted()).isTrue();
    assertThat(loaded.responseStatus()).contains(201);
    assertThat(loaded.responseBody()).contains(body);
  }

  @Test
  void aCompletedClaimIsImmuneToTakeover() {
    IdempotencyRecord claimed = inTransaction(() -> store.claim(candidate("key-1"))).orElseThrow();
    inTransaction(() -> store.complete(claimed.id(), 201, "{}"));

    assertThat(
            inTransaction(
                () ->
                    store.takeOverLease(
                        claimed.id(), Fixtures.NOW.plus(Duration.ofDays(1)), LEASE)))
        .isFalse();
  }

  @Test
  void purgingRemovesOnlyRecordsPastTheirRetentionWindow() {
    inTransaction(() -> store.claim(candidate("key-old")));
    IdempotencyRecord fresh =
        IdempotencyRecord.claim(
            Fixtures.MERCHANT_ID,
            "key-new",
            "fingerprint-a",
            UUID.randomUUID(),
            Fixtures.NOW.plus(Duration.ofHours(23)),
            LEASE,
            TTL);
    inTransaction(() -> store.claim(fresh));

    int purged = inTransaction(() -> store.purgeExpired(Fixtures.NOW.plus(TTL), 100));

    assertThat(purged).isEqualTo(1);
    assertThat(store.find(Fixtures.MERCHANT_ID, "key-old")).isEmpty();
    assertThat(store.find(Fixtures.MERCHANT_ID, "key-new")).isPresent();
  }

  @Test
  void unresolvedAttemptsAreTheClaimsThatReachedTheAcquirerAndNeverCameBack() {
    IdempotencyRecord abandoned =
        inTransaction(() -> store.claim(candidate("key-abandoned"))).orElseThrow();
    inTransaction(() -> store.releaseLease(abandoned.id(), Fixtures.NOW.plusSeconds(1), true));

    IdempotencyRecord neverSent =
        inTransaction(() -> store.claim(candidate("key-never-sent"))).orElseThrow();
    inTransaction(() -> store.releaseLease(neverSent.id(), Fixtures.NOW.plusSeconds(1), false));

    IdempotencyRecord finished =
        inTransaction(() -> store.claim(candidate("key-finished"))).orElseThrow();
    inTransaction(() -> store.complete(finished.id(), 201, "{}"));

    inTransaction(() -> store.claim(candidate("key-in-flight")));

    assertThat(store.countUnresolved(Fixtures.NOW.plusSeconds(5))).isEqualTo(1);
  }
}
