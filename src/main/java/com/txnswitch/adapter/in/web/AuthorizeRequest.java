/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter.in.web;

import com.txnswitch.adapter.SensitivePan;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * @param amount minor units as an integer: 1250 with currency USD is USD 12.50. A fractional value
 *     is rejected at parse time rather than rounded (ADR-0005).
 */
public record AuthorizeRequest(
    @Size(max = 128) @Schema(example = "order-1234") String merchantReference,
    @NotNull @Positive @Schema(example = "1250", description = "Amount in minor units") Long amount,
    @NotNull @Size(min = 3, max = 3) @Schema(example = "USD") String currency,
    @NotNull @Valid Card card) {

  public record Card(
      @NotNull @Schema(example = "4111111111111111", type = "string") SensitivePan pan,
      @NotNull @Schema(example = "12") Integer expiryMonth,
      @NotNull @Schema(example = "2030") Integer expiryYear) {}
}
