/* SPDX-License-Identifier: MIT */
package com.txnswitch.domain.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PanTest {

  @Test
  void keepsOnlyTheBinAndTheLastFourInItsPublicSurface() {
    Pan pan = Pan.of("4111111111111111");

    assertThat(pan.bin()).isEqualTo("411111");
    assertThat(pan.last4()).isEqualTo("1111");
  }

  @Test
  void masksTheMiddleDigitsInToString() {
    Pan pan = Pan.of("4111111111111111");

    assertThat(pan).hasToString("411111******1111");
    assertThat(pan.toString()).doesNotContain("4111111111111111");
  }

  @Test
  void exposesTheFullNumberOnlyThroughTheExplicitlyNamedAccessor() {
    assertThat(Pan.of("4111111111111111").exposeForAcquirer()).isEqualTo("4111111111111111");
  }

  @ParameterizedTest
  @ValueSource(strings = {"4111111111111112", "1234567890123", "4000000000000003"})
  void rejectsNumbersThatFailLuhn(String candidate) {
    assertThatExceptionOfType(InvalidCardException.class).isThrownBy(() -> Pan.of(candidate));
  }

  @ParameterizedTest
  @ValueSource(strings = {"41111111111", "41111111111111111111", "4111-1111-1111-1111", "411a"})
  void rejectsNumbersOfTheWrongShape(String candidate) {
    assertThatExceptionOfType(InvalidCardException.class).isThrownBy(() -> Pan.of(candidate));
  }

  @Test
  void rejectsAMissingNumber() {
    assertThatExceptionOfType(InvalidCardException.class).isThrownBy(() -> Pan.of(null));
  }

  @Test
  void ignoresSurroundingWhitespace() {
    assertThat(Pan.of("  4111111111111111  ").last4()).isEqualTo("1111");
  }

  /**
   * The simulator routes on these numbers. A typo would be rejected as an invalid card long before
   * it reached the simulator, and the resilience tests would then pass for the wrong reason.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "4111111111111111",
        "4000000000000002",
        "4000000000009995",
        "4000000000000119",
        "4000000000000259",
        "4000000000000101"
      })
  void everyScenarioCardIsAValidCardNumber(String scenarioPan) {
    assertThat(Pan.of(scenarioPan).last4()).hasSize(4);
  }

  @ParameterizedTest
  @CsvSource({
    "4111111111111111,VISA",
    "5555555555554444,MASTERCARD",
    "2221000000000009,MASTERCARD",
    "378282246310005,AMEX",
    "6011111111111117,DISCOVER",
    "3530111333300000,UNKNOWN"
  })
  void derivesTheBrandFromTheIssuerIdentificationNumber(String pan, CardBrand expected) {
    assertThat(Pan.of(pan).brand()).isEqualTo(expected);
  }

  @Test
  void treatsAnUnusableInputAsAnUnknownBrand() {
    assertThat(CardBrand.ofPan(null)).isEqualTo(CardBrand.UNKNOWN);
    assertThat(CardBrand.ofPan("41")).isEqualTo(CardBrand.UNKNOWN);
    assertThat(CardBrand.ofPan("6500000000000002")).isEqualTo(CardBrand.DISCOVER);
  }

  @Test
  void equalityIsOnTheUnderlyingNumber() {
    assertThat(Pan.of("4111111111111111"))
        .isEqualTo(Pan.of("4111111111111111"))
        .hasSameHashCodeAs(Pan.of("4111111111111111"))
        .isNotEqualTo(Pan.of("5555555555554444"))
        .isNotEqualTo("4111111111111111");
  }
}
