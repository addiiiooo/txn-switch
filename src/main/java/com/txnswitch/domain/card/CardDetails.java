/* SPDX-License-Identifier: MIT */
package com.txnswitch.domain.card;

import java.time.YearMonth;

/**
 * What survives an authorization: the BIN, the last four digits, the brand, the expiry, and a keyed
 * fingerprint of the full number. The middle digits are never stored in any form.
 *
 * @param fingerprint HMAC-SHA256 of the PAN, computed by an adapter that owns the key; opaque here.
 */
public record CardDetails(
    String bin,
    String last4,
    CardBrand brand,
    int expiryMonth,
    int expiryYear,
    String fingerprint) {

  public CardDetails {
    if (bin == null || bin.length() != 6) {
      throw new InvalidCardException("bin must be six digits");
    }
    if (last4 == null || last4.length() != 4) {
      throw new InvalidCardException("last4 must be four digits");
    }
    if (expiryMonth < 1 || expiryMonth > 12) {
      throw new InvalidCardException("expiry month must be between 1 and 12");
    }
    if (expiryYear < 1000 || expiryYear > 9999) {
      throw new InvalidCardException("expiry year must be a four-digit year");
    }
    if (fingerprint == null || fingerprint.isBlank()) {
      throw new InvalidCardException("card fingerprint is required");
    }
  }

  /**
   * Builds the retained details from a PAN, rejecting a card that has already expired.
   *
   * @param currentMonth the current year-month, passed in rather than read from a clock so the
   *     domain stays deterministic and testable
   */
  public static CardDetails from(
      Pan pan, int expiryMonth, int expiryYear, String fingerprint, YearMonth currentMonth) {
    CardDetails details =
        new CardDetails(pan.bin(), pan.last4(), pan.brand(), expiryMonth, expiryYear, fingerprint);
    if (details.expiry().isBefore(currentMonth)) {
      throw new CardExpiredException("card expired " + details.expiry());
    }
    return details;
  }

  public YearMonth expiry() {
    return YearMonth.of(expiryYear, expiryMonth);
  }
}
