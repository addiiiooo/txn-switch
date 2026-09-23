/* SPDX-License-Identifier: MIT */
package com.txnswitch.application.exception;

import java.util.UUID;

/**
 * No such authorization for the authenticated merchant.
 *
 * <p>Also raised when the authorization exists under a different merchant: confirming existence
 * across tenants is itself a leak, so there is no distinct "forbidden" answer.
 */
public class AuthorizationNotFoundException extends RuntimeException {

  public AuthorizationNotFoundException(UUID id) {
    super("no authorization " + id + " for this merchant");
  }
}
