/* SPDX-License-Identifier: MIT */
package com.txnswitch.application;

import com.txnswitch.domain.authorization.Authorization;

/** Either this request produced an authorization, or it replayed one that already had. */
public sealed interface AuthorizeResult {

  record Created(Authorization authorization, String responseBody) implements AuthorizeResult {}

  /** The stored bytes of the original response, returned with its original status code. */
  record Replayed(int httpStatus, String responseBody) implements AuthorizeResult {}
}
