/* SPDX-License-Identifier: MIT */
package com.txnswitch.application;

import com.txnswitch.config.SecurityProperties;
import com.txnswitch.domain.card.Pan;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Turns a card number into a stable, keyed fingerprint.
 *
 * <p>HMAC rather than a plain digest: there are only so many sixteen-digit numbers, and an unkeyed
 * SHA-256 of one is a lookup table away from being the card number again.
 */
@Component
public class CardFingerprinter {

  private static final Logger log = LoggerFactory.getLogger(CardFingerprinter.class);
  private static final String ALGORITHM = "HmacSHA256";

  private final SecretKeySpec key;

  public CardFingerprinter(SecurityProperties properties, Environment environment) {
    if (SecurityProperties.DEVELOPMENT_FINGERPRINT_KEY.equals(properties.cardFingerprintKey())
        && !environment.matchesProfiles("local", "test")) {
      log.warn(
          "CARD_FINGERPRINT_KEY is still the published development value. "
              + "Set it to a real secret before this instance sees a real card.");
    }
    this.key =
        new SecretKeySpec(
            properties.cardFingerprintKey().getBytes(StandardCharsets.UTF_8), ALGORITHM);
  }

  public String fingerprint(Pan pan) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(key);
      return HexFormat.of()
          .formatHex(mac.doFinal(pan.exposeForAcquirer().getBytes(StandardCharsets.US_ASCII)));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("cannot compute a card fingerprint", e);
    }
  }
}
