/* SPDX-License-Identifier: MIT */
package com.txnswitch.domain.money;

import java.util.Currency;
import java.util.Objects;

/**
 * An amount of money, held as an integral number of the currency's minor units.
 *
 * <p>There is no fractional cent in a card authorization, so there is no reason to carry a scale
 * around. One representation per value means equality and ordering are trivial and a rounding mode
 * never has to be chosen. See ADR-0005.
 */
public final class Money implements Comparable<Money> {

  /** Upper bound at the edge, well inside {@code long}, so sums cannot realistically overflow. */
  public static final long MAX_MINOR_UNITS = 10_000_000_000_000L;

  private final long minorUnits;
  private final Currency currency;

  private Money(long minorUnits, Currency currency) {
    this.minorUnits = minorUnits;
    this.currency = currency;
  }

  public static Money of(long minorUnits, String currencyCode) {
    return of(minorUnits, parseCurrency(currencyCode));
  }

  public static Money of(long minorUnits, Currency currency) {
    Objects.requireNonNull(currency, "currency");
    if (minorUnits <= 0) {
      throw new InvalidAmountException("amount must be a positive number of minor units");
    }
    if (minorUnits > MAX_MINOR_UNITS) {
      throw new InvalidAmountException(
          "amount must not exceed " + MAX_MINOR_UNITS + " minor units");
    }
    if (currency.getDefaultFractionDigits() < 0) {
      throw new InvalidCurrencyException(
          currency.getCurrencyCode() + " is not a currency with minor units");
    }
    return new Money(minorUnits, currency);
  }

  private static Currency parseCurrency(String currencyCode) {
    if (currencyCode == null || currencyCode.length() != 3) {
      throw new InvalidCurrencyException("currency must be a three-letter ISO-4217 code");
    }
    try {
      return Currency.getInstance(currencyCode.toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new InvalidCurrencyException(currencyCode + " is not a known ISO-4217 currency");
    }
  }

  public long minorUnits() {
    return minorUnits;
  }

  public Currency currency() {
    return currency;
  }

  public String currencyCode() {
    return currency.getCurrencyCode();
  }

  /** Addition is closed over a single currency; mixing currencies is a bug, not a conversion. */
  public Money plus(Money other) {
    requireSameCurrency(other);
    return of(Math.addExact(minorUnits, other.minorUnits), currency);
  }

  public boolean isSameCurrencyAs(Money other) {
    return currency.equals(other.currency);
  }

  private void requireSameCurrency(Money other) {
    if (!isSameCurrencyAs(other)) {
      throw new InvalidCurrencyException(
          "cannot combine " + currencyCode() + " with " + other.currencyCode());
    }
  }

  @Override
  public int compareTo(Money other) {
    requireSameCurrency(other);
    return Long.compare(minorUnits, other.minorUnits);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    return o instanceof Money other
        && minorUnits == other.minorUnits
        && currency.equals(other.currency);
  }

  @Override
  public int hashCode() {
    return Objects.hash(minorUnits, currency);
  }

  @Override
  public String toString() {
    return minorUnits + " " + currency.getCurrencyCode();
  }
}
