/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter.in.web;

/**
 * The authenticated merchant.
 *
 * <p>The seam that everything downstream reads. Replacing the API-key map with OAuth2 client
 * credentials or mTLS means producing this record from a different source and changing nothing else
 * — which is the reason for having a principal at all rather than trusting a header.
 */
public record MerchantPrincipal(String merchantId) {}
