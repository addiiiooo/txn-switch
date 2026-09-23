/* SPDX-License-Identifier: MIT */
package com.txnswitch.domain.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.txnswitch.testsupport.Fixtures;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The state machine is the requirement that matters most, so it is tested exhaustively rather than
 * by example: every status is tried against every operation.
 */
class AuthorizationTest {

  @Nested
  @DisplayName("creation")
  class Creation {

    @Test
    void anApprovedDecisionProducesAnAuthorizedHold() {
      Authorization authorization = Fixtures.approved();

      assertThat(authorization.status()).isEqualTo(AuthorizationStatus.AUTHORIZED);
      assertThat(authorization.approvalCode()).isEqualTo("A1B2C3");
      assertThat(authorization.declineCode()).isNull();
      assertThat(authorization.expiresAt()).isEqualTo(Fixtures.NOW.plus(Fixtures.HOLD_TTL));
    }

    @Test
    void aDeclinedDecisionProducesATerminalDeclinedRecord() {
      Authorization authorization = Fixtures.declined();

      assertThat(authorization.status()).isEqualTo(AuthorizationStatus.DECLINED);
      assertThat(authorization.status().isTerminal()).isTrue();
      assertThat(authorization.declineCode()).isEqualTo("DO_NOT_HONOR");
      assertThat(authorization.approvalCode()).isNull();
    }
  }

  @Nested
  @DisplayName("the transition table")
  class Transitions {

    @Test
    void authorizedIsTheOnlyNonTerminalStatus() {
      for (AuthorizationStatus status : AuthorizationStatus.values()) {
        assertThat(status.isTerminal()).isEqualTo(status != AuthorizationStatus.AUTHORIZED);
      }
    }

    @Test
    void onlyCaptureVoidAndExpireAreReachableAndOnlyFromAuthorized() {
      for (AuthorizationStatus from : AuthorizationStatus.values()) {
        for (AuthorizationStatus to : AuthorizationStatus.values()) {
          boolean legal =
              from == AuthorizationStatus.AUTHORIZED
                  && (to == AuthorizationStatus.CAPTURED
                      || to == AuthorizationStatus.VOIDED
                      || to == AuthorizationStatus.EXPIRED);
          assertThat(from.canTransitionTo(to)).describedAs("%s -> %s", from, to).isEqualTo(legal);
        }
      }
    }
  }

  @Nested
  @DisplayName("capture")
  class Capture {

    @Test
    void capturesAnAuthorizedHoldInFull() {
      Authorization authorization = Fixtures.approved();
      Instant at = Fixtures.NOW.plus(Duration.ofHours(1));

      AuthorizationEvent event = authorization.capture(at);

      assertThat(authorization.status()).isEqualTo(AuthorizationStatus.CAPTURED);
      assertThat(authorization.capturedAt()).isEqualTo(at);
      assertThat(authorization.updatedAt()).isEqualTo(at);
      assertThat(event.fromStatus()).isEqualTo(AuthorizationStatus.AUTHORIZED);
      assertThat(event.toStatus()).isEqualTo(AuthorizationStatus.CAPTURED);
      assertThat(event.occurredAt()).isEqualTo(at);
    }

    @ParameterizedTest(name = "capture is rejected from {0}")
    @EnumSource(
        value = AuthorizationStatus.class,
        names = {"AUTHORIZED"},
        mode = EnumSource.Mode.EXCLUDE)
    void isRejectedFromEveryOtherStatus(AuthorizationStatus status) {
      Authorization authorization = Fixtures.inStatus(status);

      assertThatExceptionOfType(IllegalTransitionException.class)
          .isThrownBy(() -> authorization.capture(Fixtures.NOW))
          .satisfies(
              e -> {
                assertThat(e.from()).isEqualTo(status);
                assertThat(e.to()).isEqualTo(AuthorizationStatus.CAPTURED);
              });
      assertThat(authorization.status()).isEqualTo(status);
    }

    @Test
    void isRejectedOnceTheHoldHasLapsedEvenIfTheStatusIsStillAuthorized() {
      Instant expiry = Fixtures.NOW.plus(Duration.ofDays(7));
      Authorization authorization = Fixtures.inStatus(AuthorizationStatus.AUTHORIZED, expiry);

      assertThatExceptionOfType(AuthorizationExpiredException.class)
          .isThrownBy(() -> authorization.capture(expiry))
          .satisfies(e -> assertThat(e.expiredAt()).isEqualTo(expiry));
      assertThat(authorization.status()).isEqualTo(AuthorizationStatus.AUTHORIZED);
    }
  }

  @Nested
  @DisplayName("void")
  class VoidHold {

    @Test
    void releasesAnAuthorizedHold() {
      Authorization authorization = Fixtures.approved();
      Instant at = Fixtures.NOW.plus(Duration.ofMinutes(5));

      AuthorizationEvent event = authorization.voidHold(at);

      assertThat(authorization.status()).isEqualTo(AuthorizationStatus.VOIDED);
      assertThat(authorization.voidedAt()).isEqualTo(at);
      assertThat(event.toStatus()).isEqualTo(AuthorizationStatus.VOIDED);
    }

    @ParameterizedTest(name = "void is rejected from {0}")
    @EnumSource(
        value = AuthorizationStatus.class,
        names = {"AUTHORIZED"},
        mode = EnumSource.Mode.EXCLUDE)
    void isRejectedFromEveryOtherStatus(AuthorizationStatus status) {
      Authorization authorization = Fixtures.inStatus(status);

      assertThatExceptionOfType(IllegalTransitionException.class)
          .isThrownBy(() -> authorization.voidHold(Fixtures.NOW));
    }

    @Test
    void isRejectedOnceTheHoldHasLapsed() {
      Instant expiry = Fixtures.NOW.plus(Duration.ofDays(7));
      Authorization authorization = Fixtures.inStatus(AuthorizationStatus.AUTHORIZED, expiry);

      assertThatExceptionOfType(AuthorizationExpiredException.class)
          .isThrownBy(() -> authorization.voidHold(expiry.plusSeconds(1)));
    }
  }

  @Nested
  @DisplayName("expiry")
  class Expiry {

    @Test
    void marksALapsedHoldExpired() {
      Instant expiry = Fixtures.NOW.plus(Duration.ofDays(7));
      Authorization authorization = Fixtures.inStatus(AuthorizationStatus.AUTHORIZED, expiry);

      AuthorizationEvent event = authorization.expire(expiry);

      assertThat(authorization.status()).isEqualTo(AuthorizationStatus.EXPIRED);
      assertThat(event.fromStatus()).isEqualTo(AuthorizationStatus.AUTHORIZED);
      assertThat(event.toStatus()).isEqualTo(AuthorizationStatus.EXPIRED);
    }

    @Test
    void refusesToExpireAHoldThatIsStillLive() {
      Authorization authorization = Fixtures.approved();

      assertThatIllegalStateException().isThrownBy(() -> authorization.expire(Fixtures.NOW));
      assertThat(authorization.status()).isEqualTo(AuthorizationStatus.AUTHORIZED);
    }

    @ParameterizedTest(name = "expiry is rejected from {0}")
    @EnumSource(
        value = AuthorizationStatus.class,
        names = {"AUTHORIZED"},
        mode = EnumSource.Mode.EXCLUDE)
    void isRejectedFromEveryOtherStatus(AuthorizationStatus status) {
      Authorization authorization = Fixtures.inStatus(status, Fixtures.NOW);

      assertThatExceptionOfType(IllegalTransitionException.class)
          .isThrownBy(() -> authorization.expire(Fixtures.NOW.plusSeconds(1)));
    }

    @Test
    void reportsALapsedHoldFromTheExpiryInstantOnwards() {
      Instant expiry = Fixtures.NOW.plus(Duration.ofDays(7));
      Authorization authorization = Fixtures.inStatus(AuthorizationStatus.AUTHORIZED, expiry);

      assertThat(authorization.hasLapsedAt(expiry.minusMillis(1))).isFalse();
      assertThat(authorization.hasLapsedAt(expiry)).isTrue();
    }
  }

  @Nested
  @DisplayName("persistence boundary")
  class Persistence {

    @Test
    void aSnapshotRoundTripPreservesEveryField() {
      Authorization original = Fixtures.approved();
      original.capture(Fixtures.NOW.plusSeconds(60));

      AuthorizationSnapshot snapshot = original.snapshot();
      Authorization restored = Authorization.rehydrate(snapshot);

      assertThat(restored.snapshot()).isEqualTo(snapshot);
      assertThat(restored.id()).isEqualTo(original.id());
      assertThat(restored.amount()).isEqualTo(original.amount());
      assertThat(restored.card()).isEqualTo(original.card());
      assertThat(restored.status()).isEqualTo(AuthorizationStatus.CAPTURED);
      assertThat(restored.capturedAt()).isEqualTo(original.capturedAt());
    }
  }
}
