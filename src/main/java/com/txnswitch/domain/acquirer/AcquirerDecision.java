/* SPDX-License-Identifier: MIT */
package com.txnswitch.domain.acquirer;

/**
 * A definitive answer from an acquirer.
 *
 * <p>Only two things can be decided: the issuer approved, or the issuer said no. Everything else —
 * a timeout, a gateway error, an open circuit — is a failure to obtain a decision, and is raised as
 * an exception by the gateway rather than modelled here. That keeps "we know the answer" and "we do
 * not know the answer" from sharing a type, and it is what lets Resilience4j's retry and circuit
 * breaker predicates work on exceptions in the usual way.
 */
public sealed interface AcquirerDecision {

  String acquirerName();

  String acquirerReference();

  record Approved(String acquirerName, String acquirerReference, String approvalCode)
      implements AcquirerDecision {}

  record Declined(
      String acquirerName, String acquirerReference, String declineCode, String declineMessage)
      implements AcquirerDecision {}
}
