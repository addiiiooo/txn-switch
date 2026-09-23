/* SPDX-License-Identifier: MIT */
package com.txnswitch.domain.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

  @Test
  void holdsAnIntegralNumberOfMinorUnits() {
    Money money = Money.of(1250, "USD");

    assertThat(money.minorUnits()).isEqualTo(1250);
    assertThat(money.currencyCode()).isEqualTo("USD");
    assertThat(money).hasToString("1250 USD");
  }

  @Test
  void treatsAZeroDecimalCurrencyLikeAnyOther() {
    Money yen = Money.of(1250, "JPY");

    assertThat(yen.minorUnits()).isEqualTo(1250);
    assertThat(yen.currency().getDefaultFractionDigits()).isZero();
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1, -1250})
  void rejectsAmountsThatAreNotPositive(long minorUnits) {
    assertThatExceptionOfType(InvalidAmountException.class)
        .isThrownBy(() -> Money.of(minorUnits, "USD"));
  }

  @Test
  void rejectsAmountsBeyondTheBoundThatKeepsSumsInsideALong() {
    assertThat(Money.of(Money.MAX_MINOR_UNITS, "USD").minorUnits())
        .isEqualTo(Money.MAX_MINOR_UNITS);

    assertThatExceptionOfType(InvalidAmountException.class)
        .isThrownBy(() -> Money.of(Money.MAX_MINOR_UNITS + 1, "USD"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"ZZZ", "US", "USDD", "usd!"})
  void rejectsCodesThatAreNotIso4217(String code) {
    assertThatExceptionOfType(InvalidCurrencyException.class).isThrownBy(() -> Money.of(100, code));
  }

  @Test
  void rejectsANullCurrencyCode() {
    assertThatExceptionOfType(InvalidCurrencyException.class)
        .isThrownBy(() -> Money.of(100, (String) null));
  }

  @Test
  void acceptsALowercaseCode() {
    assertThat(Money.of(100, "usd").currencyCode()).isEqualTo("USD");
  }

  @ParameterizedTest
  @ValueSource(strings = {"XXX", "XAU", "XDR"})
  void rejectsPseudoCurrenciesThatHaveNoMinorUnit(String code) {
    assertThatExceptionOfType(InvalidCurrencyException.class).isThrownBy(() -> Money.of(100, code));
  }

  @Test
  void addsWithinOneCurrency() {
    assertThat(Money.of(1250, "USD").plus(Money.of(750, "USD"))).isEqualTo(Money.of(2000, "USD"));
  }

  @Test
  void refusesToAddAcrossCurrencies() {
    assertThatExceptionOfType(InvalidCurrencyException.class)
        .isThrownBy(() -> Money.of(1250, "USD").plus(Money.of(750, "EUR")));
  }

  @Test
  void refusesToCompareAcrossCurrencies() {
    assertThatExceptionOfType(InvalidCurrencyException.class)
        .isThrownBy(() -> Money.of(1250, "USD").compareTo(Money.of(750, "EUR")));
  }

  @Test
  void ordersWithinOneCurrency() {
    assertThat(Money.of(750, "USD")).isLessThan(Money.of(1250, "USD"));
  }

  @Test
  void equalsAndHashCodeCoverBothAmountAndCurrency() {
    assertThat(Money.of(1250, "USD"))
        .isEqualTo(Money.of(1250, Currency.getInstance("USD")))
        .hasSameHashCodeAs(Money.of(1250, "USD"))
        .isNotEqualTo(Money.of(1250, "EUR"))
        .isNotEqualTo(Money.of(1251, "USD"))
        .isNotEqualTo("1250 USD");
    assertThat(Money.of(1250, "USD").isSameCurrencyAs(Money.of(1, "USD"))).isTrue();
  }

  @Test
  void rejectsANullCurrency() {
    assertThatExceptionOfType(NullPointerException.class)
        .isThrownBy(() -> Money.of(100, (Currency) null));
  }
}
