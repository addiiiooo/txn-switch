/* SPDX-License-Identifier: MIT */
package com.txnswitch.domain.card;

import com.txnswitch.domain.DomainException;

public class InvalidCardException extends DomainException {

  public InvalidCardException(String message) {
    super(message);
  }
}
