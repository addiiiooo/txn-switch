/* SPDX-License-Identifier: MIT */
package com.txnswitch.domain.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdempotencyRecordTest {

  private static final Instant NOW = Instant.parse("2026-09-23T10:15:00Z");
  private static final Duration LEASE = Duration.ofSeconds(30);
  private static final Duration TTL = Duration.ofHours(24);
  private static final UUID AUTHORIZATION_ID = UUID.randomUUID();

  private static IdempotencyRecord claim() {
    return IdempotencyRecord.claim(
        "m_demo", "key-1", "fingerprint", AUTHORIZATION_ID, NOW, LEASE, TTL);
  }

  @Test
  void aClaimAllocatesTheAuthorizationIdUpFront() {
    IdempotencyRecord record = claim();

    assertThat(record.authorizationId()).isEqualTo(AUTHORIZATION_ID);
    assertThat(record.state()).isEqualTo(IdempotencyState.IN_PROGRESS);
    assertThat(record.attempts()).isEqualTo(1);
    assertThat(record.downstreamAttempted()).isFalse();
    assertThat(record.leaseExpiresAt()).isEqualTo(NOW.plus(LEASE));
    assertThat(record.expiresAt()).isEqualTo(NOW.plus(TTL));
    assertThat(record.responseStatus()).isEmpty();
    assertThat(record.responseBody()).isEmpty();
  }

  @Test
  void theLeaseIsHeldUntilItLapses() {
    IdempotencyRecord record = claim();

    assertThat(record.isLeaseHeldAt(NOW.plusSeconds(29))).isTrue();
    assertThat(record.isLeaseHeldAt(NOW.plus(LEASE))).isFalse();
  }

  @Test
  void renewingTheLeaseCountsAnotherAttempt() {
    IdempotencyRecord record = claim();
    Instant later = NOW.plusSeconds(60);

    record.renewLease(later, LEASE);

    assertThat(record.attempts()).isEqualTo(2);
    assertThat(record.isLeaseHeldAt(later)).isTrue();
  }

  @Test
  void releasingTheLeaseKeepsTheRecordAndItsPreAllocatedId() {
    IdempotencyRecord record = claim();

    record.markDownstreamAttempted();
    record.releaseLease(NOW.plusSeconds(3));

    assertThat(record.state()).isEqualTo(IdempotencyState.IN_PROGRESS);
    assertThat(record.authorizationId()).isEqualTo(AUTHORIZATION_ID);
    assertThat(record.downstreamAttempted()).isTrue();
    assertThat(record.isLeaseHeldAt(NOW.plusSeconds(3))).isFalse();
  }

  @Test
  void completionStoresTheResponseToReplay() {
    IdempotencyRecord record = claim();

    record.complete(201, "{\"id\":\"x\"}");

    assertThat(record.isCompleted()).isTrue();
    assertThat(record.responseStatus()).contains(201);
    assertThat(record.responseBody()).contains("{\"id\":\"x\"}");
    assertThat(record.isLeaseHeldAt(NOW)).isFalse();
  }

  @Test
  void aCompletedRecordCannotBeCompletedAgainOrReleased() {
    IdempotencyRecord record = claim();
    record.complete(201, "{}");

    assertThatIllegalStateException().isThrownBy(() -> record.complete(201, "{}"));
    assertThatIllegalStateException().isThrownBy(() -> record.renewLease(NOW, LEASE));

    record.releaseLease(NOW.plusSeconds(1));
    assertThat(record.isCompleted()).isTrue();
  }

  @Test
  void theFingerprintDecidesWhetherARetryIsTheSameRequest() {
    IdempotencyRecord record = claim();

    assertThat(record.matchesFingerprint("fingerprint")).isTrue();
    assertThat(record.matchesFingerprint("something-else")).isFalse();
  }

  @Test
  void aSnapshotRoundTripPreservesEveryField() {
    IdempotencyRecord record = claim();
    record.markDownstreamAttempted();
    record.complete(201, "{}");

    IdempotencySnapshot snapshot = record.snapshot();

    assertThat(IdempotencyRecord.rehydrate(snapshot).snapshot()).isEqualTo(snapshot);
    assertThat(snapshot.merchantId()).isEqualTo("m_demo");
    assertThat(snapshot.idempotencyKey()).isEqualTo("key-1");
  }
}
