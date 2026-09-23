/* SPDX-License-Identifier: MIT */
package com.txnswitch.domain.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthorizationIdTest {

  @Test
  void generatesAVersion7Uuid() {
    UUID id = AuthorizationId.generate(Instant.parse("2026-09-23T10:15:00Z")).value();

    assertThat(id.version()).isEqualTo(7);
    assertThat(id.variant()).isEqualTo(2);
  }

  @Test
  void encodesTheGenerationInstantInTheHighBits() {
    Instant now = Instant.parse("2026-09-23T10:15:00Z");

    long timestamp = AuthorizationId.generate(now).value().getMostSignificantBits() >>> 16;

    assertThat(timestamp).isEqualTo(now.toEpochMilli());
  }

  @Test
  void sortsInGenerationOrderAcrossMilliseconds() {
    Instant start = Instant.parse("2026-09-23T10:15:00Z");
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      ids.add(AuthorizationId.generate(start.plusMillis(i)).value());
    }

    assertThat(ids).isSortedAccordingTo(AuthorizationIdTest::compareUnsigned);
  }

  @Test
  void isDistinctWithinTheSameMillisecond() {
    Instant now = Instant.parse("2026-09-23T10:15:00Z");

    assertThat(AuthorizationId.generate(now)).isNotEqualTo(AuthorizationId.generate(now));
  }

  @Test
  void parsesAndPrintsItsTextualForm() {
    AuthorizationId id = AuthorizationId.generate(Instant.now());

    assertThat(AuthorizationId.of(id.toString())).isEqualTo(id);
    assertThat(id).hasToString(id.value().toString());
  }

  @Test
  void refusesToWrapNothing() {
    assertThatIllegalArgumentException().isThrownBy(() -> new AuthorizationId(null));
  }

  private static int compareUnsigned(UUID left, UUID right) {
    int high = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
    return high != 0
        ? high
        : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
  }
}
