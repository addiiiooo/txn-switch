/* SPDX-License-Identifier: MIT */
package com.txnswitch.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ClockConfig {

  /**
   * One clock, injected everywhere, in UTC.
   *
   * <p>Nothing in this service calls {@code Instant.now()} directly: expiry and leases are decided
   * against this clock, and a service whose correctness depends on time should be able to say where
   * the time came from.
   */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
