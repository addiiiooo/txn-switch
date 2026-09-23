/* SPDX-License-Identifier: MIT */
package com.txnswitch.config;

import java.time.Duration;
import java.util.Currency;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param holdTtl how long an authorization hold survives before it expires
 * @param supportedCurrencies a real switch is certified per currency; this mirrors that, and is not
 *     the same question as "is this a valid ISO-4217 code"
 */
@ConfigurationProperties("txnswitch.authorization")
public record AuthorizationProperties(
    Duration holdTtl, List<String> supportedCurrencies, int expirySweepBatchSize) {

  public Set<Currency> currencies() {
    Set<Currency> currencies = new LinkedHashSet<>();
    for (String code : supportedCurrencies) {
      currencies.add(Currency.getInstance(code.trim().toUpperCase(java.util.Locale.ROOT)));
    }
    return currencies;
  }
}
