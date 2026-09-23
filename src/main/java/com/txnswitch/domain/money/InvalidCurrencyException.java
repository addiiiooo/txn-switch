/* SPDX-License-Identifier: MIT */
package com.txnswitch.domain.money;

import com.txnswitch.domain.DomainException;

public class InvalidCurrencyException extends DomainException {

  public InvalidCurrencyException(String message) {
    super(message);
  }
}
