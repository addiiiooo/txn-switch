/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter.out.persistence;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface AuthorizationJpaRepository extends JpaRepository<AuthorizationEntity, UUID> {

  /** Merchant scoping happens in the query; there is no unscoped read of an authorization. */
  Optional<AuthorizationEntity> findByIdAndMerchantId(UUID id, String merchantId);

  /**
   * Claims lapsed holds for the expiry sweeper.
   *
   * <p>{@code jakarta.persistence.lock.timeout = -2} is Hibernate's spelling of {@code SKIP
   * LOCKED}: a second instance sweeping at the same time takes different rows instead of blocking,
   * which is what makes the sweeper safe to run everywhere without leader election.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query(
      """
      select a from AuthorizationEntity a
      where a.status = com.txnswitch.domain.authorization.AuthorizationStatus.AUTHORIZED
        and a.expiresAt <= :now
      order by a.expiresAt
      """)
  List<AuthorizationEntity> lockLapsedHolds(@Param("now") Instant now, Limit limit);
}
