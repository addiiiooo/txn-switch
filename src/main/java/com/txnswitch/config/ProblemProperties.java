/* SPDX-License-Identifier: MIT */
package com.txnswitch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param typeBaseUri prefix for the {@code type} member of every problem document. Configuration
 *     rather than a constant, because pinning the contract to one repository's branch name means a
 *     move breaks every {@code type} ever emitted.
 */
@ConfigurationProperties("txnswitch.problem")
public record ProblemProperties(String typeBaseUri) {}
