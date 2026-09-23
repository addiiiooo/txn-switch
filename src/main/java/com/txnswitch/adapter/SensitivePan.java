/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A card number as it appears in a message, on its way in or out.
 *
 * <p>This type exists for one reason: {@code toString()}. Spring's message converters log the
 * object they are about to write at DEBUG level, and a record whose components include a bare
 * {@code String pan} prints the card number into the log the moment anyone raises the log level.
 * That is not a hypothetical — it is what the first run of {@code PanLeakageIT} found.
 *
 * <p>Wrapping the value makes the safe behaviour structural rather than a habit: there is no way to
 * print one of these by accident. Jackson still reads and writes it as a plain JSON string.
 */
public record SensitivePan(String value) {

  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public static SensitivePan of(String value) {
    return new SensitivePan(value);
  }

  @JsonValue
  @Override
  public String value() {
    return value;
  }

  @Override
  public String toString() {
    if (value == null || value.length() < 10) {
      return "***";
    }
    return value.substring(0, 6) + "***" + value.substring(value.length() - 4);
  }
}
