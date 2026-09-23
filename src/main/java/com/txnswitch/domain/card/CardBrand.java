/* SPDX-License-Identifier: MIT */
package com.txnswitch.domain.card;

/**
 * Card brand, derived from the issuer identification number.
 *
 * <p>Deliberately a coarse approximation: brand ranges change, and a switch that routes on them
 * would consult a maintained BIN table rather than a prefix match in source code. It is here
 * because the brand is useful on a receipt, not because anything routes on it.
 */
public enum CardBrand {
  VISA,
  MASTERCARD,
  AMEX,
  DISCOVER,
  UNKNOWN;

  public static CardBrand ofPan(String pan) {
    if (pan == null || pan.length() < 4) {
      return UNKNOWN;
    }
    int two = Integer.parseInt(pan.substring(0, 2));
    int four = Integer.parseInt(pan.substring(0, 4));
    if (pan.charAt(0) == '4') {
      return VISA;
    }
    if ((two >= 51 && two <= 55) || (four >= 2221 && four <= 2720)) {
      return MASTERCARD;
    }
    if (two == 34 || two == 37) {
      return AMEX;
    }
    if (four == 6011 || two == 65) {
      return DISCOVER;
    }
    return UNKNOWN;
  }
}
