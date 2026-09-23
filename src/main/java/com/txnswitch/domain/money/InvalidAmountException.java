/* SPDX-License-Identifier: MIT */
package com.txnswitch.domain.money;

import com.txnswitch.domain.DomainException;

public class InvalidAmountException extends DomainException {

  public InvalidAmountException(String message) {
    super(message);
  }
}
