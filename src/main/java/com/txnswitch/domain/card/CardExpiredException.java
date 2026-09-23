/* SPDX-License-Identifier: MIT */
package com.txnswitch.domain.card;

import com.txnswitch.domain.DomainException;

public class CardExpiredException extends DomainException {

  public CardExpiredException(String message) {
    super(message);
  }
}
