/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter.simulator;

import java.util.Map;

/**
 * Behaviour the simulated acquirer exhibits, selected by card number.
 *
 * <p>Sandboxes route on magic card numbers because it is deterministic, and determinism is the
 * whole point: a test that depends on a random failure rate is a test that fails on someone else's
 * machine at some other time. The percentage knobs still exist on the control endpoint for manual
 * demonstration, but the suite routes on these.
 */
enum SimulatorScenario {
  APPROVE,
  DECLINE_DO_NOT_HONOR,
  DECLINE_INSUFFICIENT_FUNDS,
  /** Two gateway errors, then an approval: proves a bounded retry recovers. */
  FAIL_TWICE_THEN_APPROVE,
  /** One call that sleeps past any sane read timeout, then instant approvals. */
  SLOW_ONCE_THEN_APPROVE,
  /** A malformed-request answer, which must not be retried. */
  REJECT_AS_MALFORMED;

  private static final Map<String, SimulatorScenario> BY_PAN =
      Map.of(
          "4111111111111111", APPROVE,
          "4000000000000002", DECLINE_DO_NOT_HONOR,
          "4000000000009995", DECLINE_INSUFFICIENT_FUNDS,
          "4000000000000119", FAIL_TWICE_THEN_APPROVE,
          "4000000000000259", SLOW_ONCE_THEN_APPROVE,
          "4000000000000101", REJECT_AS_MALFORMED);

  static SimulatorScenario forPan(String pan) {
    return BY_PAN.getOrDefault(pan, APPROVE);
  }
}
