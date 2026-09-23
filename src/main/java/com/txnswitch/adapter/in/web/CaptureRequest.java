/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter.in.web;

import jakarta.validation.constraints.Positive;

/**
 * @param amount optional. If given it must equal the authorized amount: partial capture is out of
 *     scope and is refused rather than silently widened to a full capture.
 */
public record CaptureRequest(@Positive Long amount) {}
