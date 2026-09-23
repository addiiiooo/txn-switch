/* SPDX-License-Identifier: MIT */
package com.txnswitch.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param ttl how long a key is remembered; after this it is a new request, which is documented in
 *     the error catalogue rather than hidden
 * @param leaseTtl deliberately longer than the worst-case downstream budget, so a lease only lapses
 *     when the holder is genuinely gone
 * @param unresolvedGrace how long after a lapsed lease an attempt counts as unresolved
 */
@ConfigurationProperties("txnswitch.idempotency")
public record IdempotencyProperties(
    Duration ttl, Duration leaseTtl, Duration unresolvedGrace, int purgeBatchSize) {}
