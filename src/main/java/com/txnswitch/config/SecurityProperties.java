/* SPDX-License-Identifier: MIT */
package com.txnswitch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param apiKeys {@code key:merchantId} pairs, comma separated. The local default is a development
 *     credential that the application warns about at startup outside the local profile.
 * @param cardFingerprintKey HMAC key for the card fingerprint. A keyed digest, not a bare SHA-256:
 *     a sixteen-digit number has far too little entropy to survive an unkeyed hash.
 */
@ConfigurationProperties("txnswitch.security")
public record SecurityProperties(String apiKeys, String cardFingerprintKey) {

  public static final String DEVELOPMENT_API_KEY = "sk_local_demo";
  public static final String DEVELOPMENT_FINGERPRINT_KEY = "local-development-key-not-a-secret";
}
