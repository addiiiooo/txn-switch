/* SPDX-License-Identifier: MIT */
package com.txnswitch.application;

import com.txnswitch.domain.authorization.Authorization;
import java.util.UUID;

/** Either this request produced an authorization, or it replayed one that already had. */
public sealed interface AuthorizeResult {

  record Created(Authorization authorization, String responseBody) implements AuthorizeResult {}

  /** The stored bytes of the original response, returned with its original status code. */
  /**
   * @param authorizationId the claim's pre-allocated id, which is the id in the stored body
   */
  record Replayed(int httpStatus, String responseBody, UUID authorizationId)
      implements AuthorizeResult {}
}
