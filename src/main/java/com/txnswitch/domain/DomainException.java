/* SPDX-License-Identifier: MIT */
package com.txnswitch.domain;

/**
 * Base type for every rule the domain enforces. Adapters map subclasses onto transport-specific
 * error codes; the domain itself knows nothing about HTTP.
 */
public abstract class DomainException extends RuntimeException {

  protected DomainException(String message) {
    super(message);
  }
}
