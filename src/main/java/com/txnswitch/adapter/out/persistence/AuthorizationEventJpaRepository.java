/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthorizationEventJpaRepository
    extends JpaRepository<AuthorizationEventEntity, Long> {}
