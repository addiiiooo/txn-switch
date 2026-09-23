/* SPDX-License-Identifier: MIT */
package com.txnswitch.domain.authorization;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/**
 * Identity of an authorization, as a UUID version 7.
 *
 * <p>Version 7 is time-ordered in its most significant bits, so consecutive inserts land next to
 * each other in the primary-key index instead of scattering across it the way random v4 keys do.
 * The identifier is also sent to the acquirer as its idempotency key, so it is generated from a
 * cryptographic source rather than a predictable counter.
 */
public record AuthorizationId(UUID value) {

  private static final SecureRandom RANDOM = new SecureRandom();

  public AuthorizationId {
    if (value == null) {
      throw new IllegalArgumentException("authorization id is required");
    }
  }

  public static AuthorizationId generate(Instant now) {
    long millis = now.toEpochMilli();
    long randomA = RANDOM.nextLong() & 0x0FFFL;
    long mostSignificant = (millis << 16) | 0x7000L | randomA;
    long leastSignificant =
        (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L; // variant 0b10
    return new AuthorizationId(new UUID(mostSignificant, leastSignificant));
  }

  public static AuthorizationId of(String raw) {
    return new AuthorizationId(UUID.fromString(raw));
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
