/* SPDX-License-Identifier: MIT */
package com.txnswitch.application;

/**
 * Everything needed to authorize, already authenticated and fingerprinted.
 *
 * @param merchantId resolved from the API key, never from a header the caller can set
 * @param requestFingerprint decides whether a repeat of this key is the same request
 * @param pan discarded once the acquirer call returns; only the BIN, last four, brand, expiry and a
 *     keyed fingerprint are ever stored
 */
public record AuthorizeCommand(
    String merchantId,
    String idempotencyKey,
    String requestFingerprint,
    String merchantReference,
    long amountMinorUnits,
    String currencyCode,
    String pan,
    int expiryMonth,
    int expiryYear,
    String correlationId) {}
