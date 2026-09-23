/* SPDX-License-Identifier: MIT */
package com.txnswitch.domain.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class CardDetailsTest {

  private static final String FINGERPRINT = "a".repeat(64);
  private static final YearMonth SEPTEMBER_2026 = YearMonth.of(2026, 9);

  @Test
  void retainsTheBinTheLastFourAndAKeyedFingerprint() {
    CardDetails details =
        CardDetails.from(Pan.of("4111111111111111"), 12, 2030, FINGERPRINT, SEPTEMBER_2026);

    assertThat(details.bin()).isEqualTo("411111");
    assertThat(details.last4()).isEqualTo("1111");
    assertThat(details.brand()).isEqualTo(CardBrand.VISA);
    assertThat(details.fingerprint()).isEqualTo(FINGERPRINT);
    assertThat(details.expiry()).isEqualTo(YearMonth.of(2030, 12));
  }

  @Test
  void neverCarriesTheMiddleDigits() {
    CardDetails details =
        CardDetails.from(Pan.of("4111111111111111"), 12, 2030, FINGERPRINT, SEPTEMBER_2026);

    assertThat(details.toString()).doesNotContain("4111111111111111");
  }

  @Test
  void acceptsACardExpiringInTheCurrentMonth() {
    assertThat(CardDetails.from(Pan.of("4111111111111111"), 9, 2026, FINGERPRINT, SEPTEMBER_2026))
        .isNotNull();
  }

  @Test
  void rejectsACardThatHasAlreadyExpired() {
    assertThatExceptionOfType(CardExpiredException.class)
        .isThrownBy(
            () ->
                CardDetails.from(Pan.of("4111111111111111"), 8, 2026, FINGERPRINT, SEPTEMBER_2026));
  }

  @Test
  void rejectsAMalformedProjection() {
    assertThatExceptionOfType(InvalidCardException.class)
        .isThrownBy(() -> new CardDetails("4111", "1111", CardBrand.VISA, 12, 2030, FINGERPRINT));
    assertThatExceptionOfType(InvalidCardException.class)
        .isThrownBy(() -> new CardDetails("411111", "11", CardBrand.VISA, 12, 2030, FINGERPRINT));
    assertThatExceptionOfType(InvalidCardException.class)
        .isThrownBy(() -> new CardDetails("411111", "1111", CardBrand.VISA, 13, 2030, FINGERPRINT));
    assertThatExceptionOfType(InvalidCardException.class)
        .isThrownBy(() -> new CardDetails("411111", "1111", CardBrand.VISA, 12, 30, FINGERPRINT));
    assertThatExceptionOfType(InvalidCardException.class)
        .isThrownBy(() -> new CardDetails("411111", "1111", CardBrand.VISA, 12, 2030, " "));
  }
}
