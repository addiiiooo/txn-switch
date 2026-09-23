/* SPDX-License-Identifier: MIT */
package com.txnswitch.domain.card;

/**
 * A primary account number, held only for the life of a single request.
 *
 * <p>This type exists to make the full value hard to leak by accident: {@link #toString()} is
 * masked, the class is never serialised, and the one accessor that returns the real digits is named
 * {@link #exposeForAcquirer()} so that every use of it is greppable and obvious in review.
 */
public final class Pan {

  private static final int MIN_LENGTH = 12;
  private static final int MAX_LENGTH = 19;

  private final String value;

  private Pan(String value) {
    this.value = value;
  }

  public static Pan of(String raw) {
    if (raw == null) {
      throw new InvalidCardException("card number is required");
    }
    String trimmed = raw.trim();
    if (trimmed.length() < MIN_LENGTH || trimmed.length() > MAX_LENGTH) {
      throw new InvalidCardException("card number must be between 12 and 19 digits");
    }
    for (int i = 0; i < trimmed.length(); i++) {
      if (!Character.isDigit(trimmed.charAt(i))) {
        throw new InvalidCardException("card number must contain digits only");
      }
    }
    if (!passesLuhn(trimmed)) {
      throw new InvalidCardException("card number failed the Luhn check");
    }
    return new Pan(trimmed);
  }

  static boolean passesLuhn(String digits) {
    int sum = 0;
    boolean doubling = false;
    for (int i = digits.length() - 1; i >= 0; i--) {
      int digit = digits.charAt(i) - '0';
      if (doubling) {
        digit *= 2;
        if (digit > 9) {
          digit -= 9;
        }
      }
      sum += digit;
      doubling = !doubling;
    }
    return sum % 10 == 0;
  }

  /** First six digits. Retaining the BIN and the last four is the truncation PCI DSS permits. */
  public String bin() {
    return value.substring(0, 6);
  }

  public String last4() {
    return value.substring(value.length() - 4);
  }

  public CardBrand brand() {
    return CardBrand.ofPan(value);
  }

  /**
   * The only way to read the full number. Named so that a reviewer grepping for it finds every
   * place the PAN leaves this object; there is exactly one, in the acquirer adapter.
   */
  public String exposeForAcquirer() {
    return value;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof Pan other && value.equals(other.value);
  }

  @Override
  public int hashCode() {
    return value.hashCode();
  }

  /** Masked. A PAN must never reach a log line, and {@code toString} is how it would. */
  @Override
  public String toString() {
    return bin() + "*".repeat(value.length() - 10) + last4();
  }
}
