/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter.out.acquirer;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param baseUrl the acquirer's root URL, or the literal {@code self} to point at the bundled
 *     simulator on whatever port this instance actually bound to — which is what lets the same
 *     configuration work under test on a random port, in Compose, and against a real acquirer
 * @param connectTimeout a slow connect is a dead host, not a busy one
 * @param readTimeout per attempt, not per request; the retry budget multiplies it
 */
@ConfigurationProperties("txnswitch.acquirer")
public record AcquirerProperties(
    String name, String baseUrl, Duration connectTimeout, Duration readTimeout) {}
